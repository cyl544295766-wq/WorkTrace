#!/usr/bin/env bash
# WorkTrace · 本地一键发布（参考 NewTrevl deploy/publish.sh 模式）
#
# 部署唯一入口（仓库无任何流水线，GitHub 只存代码）：构建（后端+前端）→
# 备份 → 传 jar → md5 校验 → 发布前端静态站 → 重启 + 健康检查（失败自动回滚）。
# 从本机执行：本机在国内、服务器也在国内，34MB jar 走同国链路通常几秒，不产生任何外购费用；
# 服务器凭据只在本地 deploy/.deploy.env / ~/.ssh，不进 GitHub。
#
# 用法：
#   ./deploy/publish.sh                # 完整发布（DEPLOY_HOST 缺省读 deploy/.deploy.env）
#   ./deploy/publish.sh --build-only   # 只构建不部署（自检）
#
# 配置（优先级：环境变量 > deploy/.deploy.env > 默认值）：
#   DEPLOY_HOST   服务器 IP（必填）
#   DEPLOY_PORT   SSH 端口（默认 22）
#   DEPLOY_USER   SSH 用户（默认 ubuntu）
#   SSH_KEY       部署私钥（默认 ~/.ssh/travel_os_deploy——与 NewTrevl 同一台服务器同一把钥匙）
#   MAVEN_BIN     mvn 路径（默认 workbuddy Maven 3.9.11；3.6.3 有 -source 8 内插 bug，勿用）
#   JAVA_HOME     JDK 17 路径（对齐 pom java.version=17）
set -euo pipefail

# ---- 载入本地配置（deploy/.deploy.env，gitignore 掉，勿提交） ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
if [ -f "$SCRIPT_DIR/.deploy.env" ]; then
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/.deploy.env"
fi

DEPLOY_USER="${DEPLOY_USER:-ubuntu}"
DEPLOY_PORT="${DEPLOY_PORT:-22}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/travel_os_deploy}"
MAVEN_BIN="${MAVEN_BIN:-C:/Users/15003/.workbuddy/binaries/java/maven/apache-maven-3.9.11/bin/mvn.cmd}"
JAVA_HOME="${JAVA_HOME:-C:/Users/15003/.workbuddy/binaries/java/jdk/jdk-17.0.20.1+1}"
export JAVA_HOME

# 服务器端布局（与 systemd worktrace.service 一致）
REMOTE_DIR=/home/ubuntu/worktrace
SERVICE=worktrace
HEALTH_URL=http://127.0.0.1:8083/actuator/health

BUILD_ONLY=0
if [ "${1:-}" = "--build-only" ]; then
  BUILD_ONLY=1
fi

log() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
die() { printf '\033[1;31m错误：%s\033[0m\n' "$*" >&2; exit 1; }

if [ "$BUILD_ONLY" = "0" ] && [ -z "${DEPLOY_HOST:-}" ]; then
  die "缺 DEPLOY_HOST（服务器 IP）。示例：DEPLOY_HOST=1.2.3.4 ./deploy/publish.sh；或写入 deploy/.deploy.env"
fi

# ---- 1. 构建（后端与 CI 同口径；前端 npm 构建。DB 单测需容器故跳过执行） ----
log "1/6 构建后端 jar + 前端 dist"
cd "$ROOT_DIR/server"
"$MAVEN_BIN" -B -DskipTests package
# 绝对路径：后面步骤会 cd 到 web/，相对路径会让 scp 找不到 jar
JAR="$(ls "$ROOT_DIR"/server/target/*.jar | head -1)"
[ -f "$JAR" ] || die "构建产物缺失"
LOCAL_MD5="$(md5sum "$JAR" | cut -d' ' -f1)"
echo "后端：$JAR（$(du -h "$JAR" | cut -f1)，md5 $LOCAL_MD5）"

command -v npm > /dev/null 2>&1 || die "npm 不在 PATH（前端构建需要 Node.js）"
cd "$ROOT_DIR/web"
npm run build
[ -f dist/index.html ] || die "前端构建产物 dist/index.html 缺失"
echo "前端：web/dist（$(du -sh dist | cut -f1)）"

if [ "$BUILD_ONLY" = "1" ]; then
  log "仅构建模式，结束"
  exit 0
fi

# ---- 部署连接参数（仅部署阶段需要） ----
[ -f "$SSH_KEY" ] || die "部署私钥不存在：$SSH_KEY"
SSH_CMD=(ssh -i "$SSH_KEY" -p "$DEPLOY_PORT" -o StrictHostKeyChecking=accept-new "$DEPLOY_USER@$DEPLOY_HOST")
SCP_CMD=(scp -i "$SSH_KEY" -P "$DEPLOY_PORT" -o StrictHostKeyChecking=accept-new)

# ---- 2. 备份服务器现有版本（保留最近 3 份） ----
log "2/6 备份服务器现有版本"
"${SSH_CMD[@]}" "
  mkdir -p $REMOTE_DIR/backups
  TS=\$(date +%Y%m%d%H%M%S)
  [ -f $REMOTE_DIR/app.jar ] && cp $REMOTE_DIR/app.jar \"$REMOTE_DIR/backups/app.jar.bak-\$TS\" || true
  ls -1t $REMOTE_DIR/backups/app.jar.bak-* 2>/dev/null | tail -n +4 | xargs -r rm -f
  ls -1t $REMOTE_DIR/backups/ | head -3"

# ---- 3. 直传 jar（先 .new 再 mv 原子替换；正在运行的旧进程持旧 inode 不受影响） ----
log "3/6 上传 jar（scp 直传 + 原子替换）"
"${SCP_CMD[@]}" "$JAR" "$DEPLOY_USER@$DEPLOY_HOST:$REMOTE_DIR/app.jar.new"
"${SSH_CMD[@]}" "mv $REMOTE_DIR/app.jar.new $REMOTE_DIR/app.jar"

# ---- 4. md5 校验（防“传了但没生效”） ----
log "4/6 校验落盘 jar"
REMOTE_MD5="$("${SSH_CMD[@]}" "md5sum $REMOTE_DIR/app.jar | cut -d' ' -f1")"
echo "本机: $LOCAL_MD5"
echo "服务器: $REMOTE_MD5"
[ "$LOCAL_MD5" = "$REMOTE_MD5" ] || die "md5 不一致，中止（jar 未就位？）"

# ---- 5. 发布前端静态站（tar + 服务器端 rsync --delete，nginx :8081 服务） ----
log "5/6 发布前端静态站点"
tar czf /tmp/worktrace-web.tar.gz -C "$ROOT_DIR/web/dist" .
"${SCP_CMD[@]}" /tmp/worktrace-web.tar.gz "$DEPLOY_USER@$DEPLOY_HOST:/tmp/"
"${SSH_CMD[@]}" 'set -e
  rm -rf /tmp/wt-web-new && mkdir -p /tmp/wt-web-new
  tar xzf /tmp/worktrace-web.tar.gz -C /tmp/wt-web-new
  sudo mkdir -p /var/www/worktrace && sudo chown ubuntu:ubuntu /var/www/worktrace
  rsync -a --delete /tmp/wt-web-new/ /var/www/worktrace/'
rm -f /tmp/worktrace-web.tar.gz

# ---- 6. 重启 + 健康检查（失败自动回滚） ----
log "6/6 重启服务 + 健康检查"
"${SSH_CMD[@]}" "
  sudo systemctl restart $SERVICE
  ok=0
  for i in \$(seq 1 12); do
    sleep 5
    if curl -sf -m 5 $HEALTH_URL > /dev/null; then ok=1; break; fi
  done
  if [ \"\$ok\" = \"1\" ]; then echo 'health check OK'; exit 0; fi
  echo '!! 健康检查失败，最近 50 行日志：'
  sudo journalctl -u $SERVICE --no-pager -n 50 || true
  echo '!! 自动回滚到最近备份 jar'
  latest=\$(ls -1t $REMOTE_DIR/backups/app.jar.bak-* | head -1)
  cp \"\$latest\" $REMOTE_DIR/app.jar
  sudo systemctl restart $SERVICE
  exit 1"

log "发布完成：后端已重启并通过健康检查；前端已发布。外网入口 http://${DEPLOY_HOST}:8081/ （需腾讯云安全组放行 TCP:8081）"
