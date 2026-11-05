# WorkTrace 部署手册

> 部署模式与 **NewTrevl** 相同（用户指定参考）：**GitHub CI 只做构建验证，部署唯一入口是本地 `deploy/publish.sh`**。两个项目跑在同一台腾讯云服务器（Ubuntu 24.04，内网 10.2.0.9，公网 140.143.166.155）。

## 模式总览

| 环节 | 在哪跑 | 做什么 | 触发方式 |
|---|---|---|---|
| **CI 构建验证** `.github/workflows/ci.yml` | GitHub 托管 runner | `mvn -B -DskipTests package` 编译打包，红了说明代码编不过 | `git push` / PR 自动，约 20s |
| **部署** `deploy/publish.sh` | **你的本机** | 构建 → 备份 → 传 jar → 校验 → 重启 + 健康检查 | 手动执行（部署不进 CI） |

设计要点：

- **同国链路**：本机在国内、服务器在国内，34MB jar scp 直传**几秒**完成，零外购费用（GitHub Actions 托管 runner 跨境传 jar 曾耗时 20 分钟，弃用）。
- **凭据不出本机**：服务器 IP / 密钥只在 `deploy/.deploy.env`（已 gitignore）与 `~/.ssh/travel_os_deploy`；**GitHub 不存任何服务器 secrets**（原 6 个已删除）。
- **安全落盘**：先传 `app.jar.new` 再 `mv` 原子替换（运行中的旧进程持旧 inode 不受影响）；重启前后有 md5 双端比对；健康检查失败**自动回滚**最近备份。

## 日常用法

```bash
git push                      # 推代码 → GitHub CI 自动构建验证
./deploy/publish.sh           # 想上线时：本机一键完整发布
./deploy/publish.sh --build-only   # 只构建不部署（自检）
SKIP_TESTS=1 已是默认口径；测试执行需 MySQL 容器，CI 与发布均跳过（编译仍会执行）
```

> Windows 下在 Git Bash 中执行。`publish.sh` 是 bash 脚本，`chmod +x` 已设置。

## publish.sh 五步流程

1. **构建** — `mvn -B -DskipTests package`（默认 workbuddy Maven 3.9.11 + JDK 17.0.20.1，对齐 pom `java.version=17`；可用环境变量 `MAVEN_BIN` / `JAVA_HOME` 覆盖）
2. **备份** — 服务器现存 jar → `backups/app.jar.bak-<时间戳>`，只留最近 3 份
3. **传输** — scp 直传 `app.jar.new` → `mv` 原子替换
4. **校验** — 本机与服务器 jar md5 比对，不一致立即中止（防“传了但没生效”）
5. **重启 + 健康检查** — `systemctl restart worktrace` → 12×5s 轮询 `actuator/health`；失败自动 `journalctl -u worktrace -n 50` 带出日志、**回滚到最近备份 jar** 并重启、退出码非 0

## 配置文件

### deploy/.deploy.env（gitignore，勿提交）

```bash
DEPLOY_HOST=140.143.166.155
DEPLOY_PORT=22
DEPLOY_USER=ubuntu
SSH_KEY=C:/Users/15003/.ssh/travel_os_deploy
```

优先级：**环境变量 > deploy/.deploy.env > 脚本默认值**。CI 服务器环境里也写过 `DEPLOY_HOST=... ./deploy/publish.sh` 用法。

### SSH 密钥

- `~/.ssh/travel_os_deploy` — **与 NewTrevl 共用**（同一台服务器、同一 ubuntu 用户），服务器 `authorized_keys` 已有其公钥，无需再配。
- `~/.ssh/worktrace_deploy` — 这是 **GitHub 仓库的 deploy key**（推代码用），不是服务器登录密钥，别混用。

## 服务器侧拓扑

```
systemd  worktrace.service        # /etc/systemd/system/worktrace.service，enable 开机自启
env      /home/ubuntu/worktrace/worktrace.env   # chmod 600：DB_PASSWORD / AI_API_KEY
jar      /home/ubuntu/worktrace/app.jar         # ExecStart 指向这里；backups/ 同目录
MySQL    127.0.0.1:3306  库 work_system  账号 wt_app@'%'
监听     127.0.0.1:8080（仅本机；外网入口未配置）
DDL      /home/ubuntu/worktrace/init-sql/（01→01a→02..08→09→10→11 顺序已执行）
```

**同机共存（重要）**：这台服务器还跑着 **NewTrevl（travel-os）**——`travel-os-api` systemd 服务、PostgreSQL 容器、**nginx :80 站点 `/var/www/travel-os`**。给 WorkTrace 配外网入口时注意：

- WorkTrace 后端目前无公网暴露，只能服务器本机访问；
- 若要开外网，走 nginx 反代（独立 `server_name` 或端口），**不要覆盖 travel-os 现有站点**；
- 健康检查 / E2E 验证均在服务器本机执行。

## 排障速查

| 症状 | 处理 |
|---|---|
| 发布在第 5 步失败 | 脚本已自动回滚备份并重启；服务器上 `sudo journalctl -u worktrace -n 50` |
| 健康检查过不去 | `curl -sm 3 http://127.0.0.1:8080/actuator/health`；确认 `systemctl status worktrace` |
| md5 不一致中止 | 网络传输问题，重跑 `./deploy/publish.sh` 即可（会重新构建+传输） |
| 登录/接口 500 | 查 DB：`sudo mysql -e "SELECT COUNT(*) FROM work_system.biz_task"`；时区表缺失会导致连接失败（见下） |
| 新机器初始化库 | 先 `mysql_tzinfo_to_sql /usr/share/zoneinfo \| mysql mysql` 加载时区表（JDBC `connectionTimeZone=Asia/Shanghai` 依赖），再按序跑 `init-sql/*.sql`（含 `11_biz_tables.sql`——`biz_task`/`biz_report` 不在 01-10 分片里） |
| CI 红 | 只代表编译/打包失败，与部署无关；本地 `./deploy/publish.sh --build-only` 复现 |

## 历史沿革（为什么是这个模式）

1. **GitHub Actions 托管 runner 直接部署**（已弃用）：跨境传 34MB jar 实测 3~20 分钟波动；scp-action 走 tar 保留相对路径导致 jar 落错目录（“部署成功却不生效”），补 `strip_components` + md5 校验才修好——链路太长，弃用。
2. **腾讯云 CODING**：注册已关闭（2026-11-05 确认），无法开通。
3. **GitHub 自托管 runner**（服务器本机构建）：服务器到 GitHub release 资产域下载仅 **15KB/s**，200MB runner 包不可行；gh-proxy 中转亦中断。已放弃，服务器上残留 `actions-runner/` 已清理（留下的 Maven 3.9.16 + 阿里云 `~/.m2` 镜像无害，后续如有需要可复用）。
4. **最终方案**：参考同服务器的 NewTrevl——**CI 只构建验证 + 本地 `publish.sh` 一键发布**。同国直传秒级、凭据零外泄、失败自动回滚。
