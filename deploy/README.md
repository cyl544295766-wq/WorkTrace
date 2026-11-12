# WorkTrace 部署手册

> 部署模式与 **NewTrevl** 相同（用户指定参考）：**部署唯一入口是本地 `deploy/publish.sh`**。GitHub 仓库**只存代码、无任何流水线**（Actions 全部删除于 `b493031`）。两个项目跑在同一台腾讯云服务器（Ubuntu 24.04，内网 10.2.0.9，公网 140.143.166.155）。

## 模式总览

| 环节 | 在哪跑 | 做什么 | 触发方式 |
|---|---|---|---|
| **代码托管** | GitHub | 只存代码；push 不触发任何构建/部署 | — |
| **部署** `deploy/publish.sh` | **你的本机** | 构建（后端+前端）→ 备份 → 传 jar → 校验 → 发前端 → 重启 + 健康检查 | 手动执行 |

设计要点：

- **同国链路**：本机在国内、服务器在国内，34MB jar scp 直传**几秒**完成，零外购费用（GitHub Actions 托管 runner 跨境传 jar 曾耗时 20 分钟，弃用）。
- **凭据不出本机**：服务器 IP / 密钥只在 `deploy/.deploy.env`（已 gitignore）与 `~/.ssh/travel_os_deploy`；**GitHub 不存任何服务器 secrets**（原 6 个已删除）。
- **安全落盘**：先传 `app.jar.new` 再 `mv` 原子替换（运行中的旧进程持旧 inode 不受影响）；重启前后有 md5 双端比对；健康检查失败**自动回滚**最近备份。
- **构建自检**：改动后想验证能否编过，本地跑 `./deploy/publish.sh --build-only`（仓库已无 CI 兜底，此为替代）。

## 日常用法

```bash
git push                      # 只是存代码（无 CI，不触发任何东西）
./deploy/publish.sh           # 想上线时：本机一键完整发布
./deploy/publish.sh --build-only   # 只构建不部署（自检，替代已删除的 CI）
SKIP_TESTS=1 已是默认口径；测试执行需 MySQL 容器，构建与发布均跳过（编译仍会执行）
```

> Windows 下在 Git Bash 中执行。`publish.sh` 是 bash 脚本，`chmod +x` 已设置。

## publish.sh 六步流程

1. **构建** — 后端 `mvn -B -DskipTests package`（默认 workbuddy Maven 3.9.11 + JDK 17.0.20.1，对齐 pom `java.version=17`，可用 `MAVEN_BIN` / `JAVA_HOME` 覆盖）+ 前端 `npm run build` → `web/dist`
2. **备份** — 服务器现存 jar → `backups/app.jar.bak-<时间戳>`，只留最近 3 份
3. **传输** — scp 直传 `app.jar.new` → `mv` 原子替换
4. **校验** — 本机与服务器 jar md5 比对，不一致立即中止（防“传了但没生效”）
5. **发布前端** — `web/dist` 打 tar → 服务器端 `rsync --delete` 到 `/var/www/worktrace/`
6. **重启 + 健康检查** — `systemctl restart worktrace` → 12×5s 轮询 `actuator/health`；失败自动 `journalctl -u worktrace -n 50` 带出日志、**回滚到最近备份 jar** 并重启、退出码非 0

## 外网入口（nginx :8081）

访问地址：**http://140.143.166.155:8081/** （前端 SPA + `/api/` 反代到本机 8083）

- 站点配置：[deploy/nginx/worktrace.conf](nginx/worktrace.conf)，与 Monevo Console（**:80**，monevo-nginx 容器）、adminer（:8001）完全独立，互不影响。安装：
  ```bash
  scp -i ~/.ssh/travel_os_deploy deploy/nginx/worktrace.conf ubuntu@<服务器>:/tmp/
  ssh -i ~/.ssh/travel_os_deploy ubuntu@<服务器> \
    'sudo mv /tmp/worktrace.conf /etc/nginx/sites-available/worktrace.conf && \
     sudo ln -sf /etc/nginx/sites-available/worktrace.conf /etc/nginx/sites-enabled/ && \
     sudo nginx -t && sudo systemctl reload nginx'
  ```
- **腾讯云安全组必须放行 TCP:8081 入站**（控制台 → 云服务器 → 安全组 → 入站规则；来源 0.0.0.0/0，协议端口 TCP:8081，动作允许），**服务器本机 UFW 也需放行**（`sudo ufw allow 8081/tcp`）。两层都放行前外网超时，服务器本机 `curl 127.0.0.1:8081` 依然正常。
  > 2026-11-05 已完成两层放行并外网端到端验证（首页/深链/静态/登录全 200）。
- 安全边界：Spring Boot 经 `worktrace.env` 监听 `0.0.0.0:8083`（供 Monevo 整合页内网直连；8083 未在 UFW/安全组放行，公网不可直连），公网**只能**经 nginx 到达 `/api/`；`actuator` 不经 nginx 暴露，健康检查只在服务器本机做。

## 配置文件

### deploy/.deploy.env（gitignore，勿提交）

```bash
DEPLOY_HOST=140.143.166.155
DEPLOY_PORT=22
DEPLOY_USER=ubuntu
SSH_KEY=C:/Users/15003/.ssh/travel_os_deploy
```

优先级：**环境变量 > deploy/.deploy.env > 脚本默认值**（例如临时切换目标：`DEPLOY_HOST=x.x.x.x ./deploy/publish.sh`）。

### SSH 密钥

- `~/.ssh/travel_os_deploy` — **与 NewTrevl 共用**（同一台服务器、同一 ubuntu 用户），服务器 `authorized_keys` 已有其公钥，无需再配。
- `~/.ssh/worktrace_deploy` — 这是 **GitHub 仓库的 deploy key**（推代码用），不是服务器登录密钥，别混用。

## 服务器侧拓扑

```
systemd  worktrace.service        # /etc/systemd/system/worktrace.service，enable 开机自启
env      /home/ubuntu/worktrace/worktrace.env   # chmod 600：DB_PASSWORD / AI_API_KEY
jar      /home/ubuntu/worktrace/app.jar         # ExecStart 指向这里；backups/ 同目录
MySQL    Docker 容器 worktrace-mysql（mysql:8.4，见下"数据库"小节），应用连 127.0.0.1:3306/work_system，账号 wt_app@'%'
后端     0.0.0.0:8083（worktrace.env 的 SERVER_PORT/SERVER_ADDRESS；内网与容器可直连，8083 未放行公网不可直连）
前端     /var/www/worktrace/（publish.sh 第 5 步 rsync --delete 发布）
nginx    :8081 = WorkTrace（deploy/nginx/worktrace.conf）｜:80 = Monevo Console（monevo-nginx 容器）｜:8001 = adminer
DDL      /home/ubuntu/worktrace/init-sql/（01→01a→02..08→09→10→11 顺序已执行）
```

### 数据库（2026-11-05 起 Docker 运行，与 NewTrevl 同为容器化）

服务器上 **PG 和 MySQL 现在都在 Docker**：

```
容器 worktrace-mysql     mysql:8.4      127.0.0.1:3306 → work_system（19 表）
容器 travel-os-postgres  postgres:16    （NewTrevl 的库）
原生 MySQL（apt 装的 8.0.46）→ 已 stop + disable，数据留在 /var/lib/mysql 作回滚底牌
```

- **root 密码**：随机生成，存 `/home/ubuntu/worktrace/.mysql-root-pw`（chmod 600）
- **进库**：`sudo docker exec -it worktrace-mysql mysql -uroot -p"$(cat /home/ubuntu/worktrace/.mysql-root-pw)" work_system`（`sudo mysql` 走 socket 的老命令**已失效**）
- **备份**：`sudo docker exec worktrace-mysql mysqldump -uroot -p"$(cat …/.mysql-root-pw)" --databases work_system | gzip > ~/wt_$(date +%F).sql.gz`
- **重启库**：`sudo docker restart worktrace-mysql`（`--restart unless-stopped`，开机自启）
- **回滚到底**（万一容器化有问题）：`sudo docker rm -f worktrace-mysql && sudo systemctl enable --now mysql` —— 原生数据原封未动
- 迁移用的全量备份与授权文件在 `/home/ubuntu/worktrace/db-backups/`（`work_system.pre-docker.*.sql.gz` + `wt_app-grants.fixed.sql`）

**同机共存（重要）**：这台服务器还跑着 **NewTrevl（travel-os）**——`travel-os-api` systemd 服务、PostgreSQL 容器、**nginx :80 站点 `/var/www/travel-os`**。给 WorkTrace 配外网入口时注意：

- WorkTrace 后端目前无公网暴露，只能服务器本机访问；
- 若要开外网，走 nginx 反代（独立 `server_name` 或端口），**不要覆盖 travel-os 现有站点**；
- 健康检查 / E2E 验证均在服务器本机执行。

## 排障速查

| 症状 | 处理 |
|---|---|
| 发布在第 5 步失败 | 脚本已自动回滚备份并重启；服务器上 `sudo journalctl -u worktrace -n 50` |
| 健康检查过不去 | `curl -sm 3 http://127.0.0.1:8083/actuator/health`；确认 `systemctl status worktrace` |
| md5 不一致中止 | 网络传输问题，重跑 `./deploy/publish.sh` 即可（会重新构建+传输） |
| 登录/接口 500 | 查 DB：`sudo docker exec worktrace-mysql mysql -uroot -p"$(cat /home/ubuntu/worktrace/.mysql-root-pw)" -e "SELECT COUNT(*) FROM work_system.biz_task"`；时区表缺失会导致连接失败（见下） |
| 新机器初始化库 | Docker MySQL 容器内先 `mysql_tzinfo_to_sql /usr/share/zoneinfo \| mysql -uroot -p$PW mysql` 加载时区表（JDBC `connectionTimeZone=Asia/Shanghai` 依赖，**就绪判断用 `mysql -e 'SELECT 1'` 校验密码，别信 mysqladmin ping**——临时 server 阶段 ping 也返回成功），再按序跑 `init-sql/*.sql`（含 `11_biz_tables.sql`——`biz_task`/`biz_report` 不在 01-10 分片里） |
| 构建失败 | 本地 `./deploy/publish.sh --build-only` 复现（编译错误在第 1 步就会中止，服务器不受影响） |
| 外网 `:8081` 打不开（本机 curl 正常） | 腾讯云安全组未放行 TCP:8081 → 控制台加入站规则；再查 `sudo nginx -t`、`ss -tlnp \| grep 8081` |
| 页面能开但登录/接口失败 | 看 nginx 错误日志 `sudo tail -20 /var/log/nginx/error.log`；确认后端 `systemctl status worktrace` |

## 历史沿革（为什么是这个模式）

1. **GitHub Actions 托管 runner 直接部署**（已弃用）：跨境传 34MB jar 实测 3~20 分钟波动；scp-action 走 tar 保留相对路径导致 jar 落错目录（“部署成功却不生效”），补 `strip_components` + md5 校验才修好——链路太长，弃用。
2. **腾讯云 CODING**：注册已关闭（2026-11-05 确认），无法开通。
3. **GitHub 自托管 runner**（服务器本机构建）：服务器到 GitHub release 资产域下载仅 **15KB/s**，200MB runner 包不可行；gh-proxy 中转亦中断。已放弃，服务器上残留 `actions-runner/` 已清理（留下的 Maven 3.9.16 + 阿里云 `~/.m2` 镜像无害，后续如有需要可复用）。
4. **GitHub CI 构建验证**（后曾短暂保留，`b493031` 已删）：用户最终要求仓库**零流水线**，纯存代码。
5. **最终方案**：参考同服务器的 NewTrevl——**本地 `publish.sh` 唯一入口**，构建验证也走 `--build-only` 在本机跑。同国直传秒级、凭据零外泄、失败自动回滚。
