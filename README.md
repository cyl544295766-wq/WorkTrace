# WorkTrace

个人工作留痕系统。本仓库当前只包含 **T01–T04 工程骨架**：后端骨架、数据库 DDL、前端壳层、统一响应与全局异常。

不包含登录认证、任何业务模块、业务接口与 ECharts 报表实现。所有页面均为占位页，用于验证路由、布局与主题。数据库统一由 `docker-compose.yml` 管理。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17、Spring Boot 3.3.6、MyBatis-Plus 3.5.9、Spring Session JDBC、Caffeine、Lombok、spring-security-crypto（仅 BCrypt） |
| 数据库 | MySQL 8.4（Docker 容器运行，验证使用 8.4.13）、InnoDB、utf8mb4 / utf8mb4_0900_ai_ci、DATETIME(3) |
| 前端 | Node.js 20+、Vue 3.5、Vite 6、TypeScript 5.7、Element Plus（按需）、Pinia、Vue Router 4、Axios、ECharts（懒加载） |

明确未引入：Spring Security 全家桶、Redis、消息队列、Elasticsearch、定时任务框架、Tailwind CSS。

## 目录结构

```
WorkTrace/
├─ docker-compose.yml           # MySQL 容器编排（启动、初始化、健康检查）
├─ docker/mysql-init/           # 容器专用初始化：时区表、Docker 账号适配
├─ server/
│  ├─ db/                         # T02：8 个 DDL 分片 + 执行/验收说明
│  ├─ src/main/java/com/worktrace/
│  │  ├─ common/api/              # Result、ResultCode、PageReq、PageResp
│  │  ├─ common/config/           # Jackson、MyBatis-Plus 自动填充
│  │  ├─ common/exception/        # BizException
│  │  ├─ common/util/             # TimeUtil、TraceIdUtil
│  │  ├─ common/web/              # TraceIdFilter、GlobalExceptionHandler
│  │  ├─ sys/                     # 系统探针接口
│  │  └─ WorkTraceApplication.java
│  ├─ src/main/resources/         # application.yml、dev/prod、logback、banner
│  ├─ src/test/java/              # 统一响应与异常映射测试
│  └─ pom.xml
├─ web/
│  ├─ src/api/                    # Axios 封装与统一响应类型
│  ├─ src/components/             # 占位组件
│  ├─ src/constants/              # 固定枚举（与 DDL CHECK 对齐）
│  ├─ src/layouts/                # AppLayout、AuthLayout、SideNav、TopBar
│  ├─ src/router/                 # 路由表、守卫（当前仅设置标题）
│  ├─ src/stores/                 # Pinia UI 状态
│  ├─ src/styles/                 # 深青主题 tokens 与全局样式
│  ├─ src/utils/                  # ECharts 按需注册入口
│  ├─ src/views/                  # 所有页面占位
│  ├─ vite.config.ts              # 按需引入、代理、manualChunks、gzip
│  └─ package.json
├─ .gitignore
└─ README.md
```

## 快速开始

### 0. 前置条件

- JDK 17
- Maven 3.9+
- Node.js 20+、npm 10+
- Docker + Docker Compose（推荐，用于统一管理数据库）；或本机 MySQL 8.0.20+

### 1. 启动数据库（Docker）

仓库根目录的 `docker-compose.yml` 把 MySQL 统一跑在容器里，首次启动会自动按序执行全部 DDL：

```bash
docker compose up -d        # 启动 MySQL 并初始化（首次约 30 秒）
docker compose ps           # 查看状态，等 health 变为 healthy
docker compose logs -f mysql
```

容器使用 `mysql:8.4` 镜像，实例参数与 `server/db/01_user_session.sql` 头部建议一致（utf8mb4 / `utf8mb4_0900_ai_ci` / `default-time-zone=+08:00`），并把数据保存在命名卷 `worktrace-mysql-data` 中。

常用操作：

```bash
docker compose down         # 停止，数据保留
docker compose down -v      # 停止并清空数据卷（下次 up 会重新执行 DDL）
docker compose restart mysql
```

初始化脚本由两部分组成，按文件名字典序执行：

| 顺序 | 来源 | 作用 |
|---|---|---|
| `00_load_tz.sh` | `docker/mysql-init/` | 加载 MySQL 时区表（JDBC 使用 `Asia/Shanghai` 必需） |
| `01`–`08` | `server/db/` | 项目 8 个 DDL 分片（T02 交付物） |
| `09_docker_app_user.sql` | `docker/mysql-init/` | 把 `wt_app` 账号主机改为 `%`，适配宿主机经端口映射连接 |

> 本环境若无法访问 Docker Hub，可先从镜像站拉取再打标签：
> `docker pull docker.m.daocloud.io/library/mysql:8.4 && docker tag docker.m.daocloud.io/library/mysql:8.4 mysql:8.4`

**用本机 MySQL 替代 Docker（可选）**：在 `server/db` 目录按 01→08 顺序执行 8 个分片即可，此时 `wt_app` 按设计使用 `'wt_app'@'127.0.0.1'`，无需 `09` 适配脚本；并自行加载时区表：

```bash
mysql_tzinfo_to_sql /usr/share/zoneinfo | mysql -u root -p mysql
```

`08_sys_seed.sql` 会创建应用账号 `wt_app`（密码 `WorkTrace_App_2026!`）；`10_sys_user.sql` 创建演示账号 `admin`（密码 `admin123`，明文，仅供登录模块联调，生产必须改存 BCrypt 哈希并替换密码）。

验证：

```bash
docker exec -it worktrace-mysql mysql -u root -p   # 容器内登录
```

```sql
SELECT CONVERT_TZ('2026-11-03 00:00:00', 'UTC', 'Asia/Shanghai');
-- 预期：2026-11-03 08:00:00
```

更多说明（版本检查、字符集、权限、UPSERT 自检、设计差异）见 `server/db/README.md`。

### 2. 启动后端

应用默认连接 `127.0.0.1:3306`（即上面的 Docker MySQL），开发环境使用 `dev` profile，数据库密码有本地默认值；也可显式注入：

```bash
cd server
export DB_USERNAME=wt_app
export DB_PASSWORD='WorkTrace_App_2026!'
mvn spring-boot:run
```

或打包后运行（JVM 时区必须固定；Windows PowerShell 下参数需要加引号）：

```bash
mvn clean package
java -Duser.timezone=Asia/Shanghai -jar target/worktrace-server-0.1.0-SNAPSHOT.jar
# PowerShell:
java '-Duser.timezone=Asia/Shanghai' -jar target\worktrace-server-0.1.0-SNAPSHOT.jar
```

服务默认监听 `127.0.0.1:8080`。验证：

```bash
curl -i http://127.0.0.1:8080/api/sys/ping
curl -i http://127.0.0.1:8080/actuator/health
curl -i http://127.0.0.1:8080/api/no-such-path
```

### 3. 启动前端

```bash
cd web
npm install
npm run dev
```

打开 `http://localhost:5173/`。开发服务器把 `/api` 代理到 `http://127.0.0.1:8080`。

```bash
npm run type-check
npm run build
npm run preview
```

`src/auto-imports.d.ts` 与 `src/components.d.ts` 由 Vite 插件自动生成，已加入 `.gitignore`；首次运行 `npm run dev` 或 `npm run build` 后会重新生成。

## 部署

线上部署采用**本地一键发布**的模式（与 NewTrevl 相同，GitHub 仓库只存代码、无任何流水线）：

- 上线 → 本机执行 `./deploy/publish.sh`（构建后端+前端 → 备份 → scp 直传 → md5 校验 → 发前端 → 重启 + 健康检查，失败自动回滚）
- 自检 → `./deploy/publish.sh --build-only`（只编译打包，替代已删除的 CI）

完整说明（配置、服务器拓扑、排障速查、历史沿革）见 **[deploy/README.md](deploy/README.md)**。

## 统一响应

成功：

```json
{
  "code": 0,
  "message": "ok",
  "data": {},
  "traceId": "b37b88d39544",
  "ts": 1793670876286
}
```

失败：`code` 为业务错误码，HTTP 状态由错误码决定；`data` 可为 `null`，校验失败时包含 `errors[{field,message}]`。响应头同时返回 `X-Trace-Id`，日志 MDC 使用同一 traceId。

错误码分段：`1xxxx` 通用/协议、`2xxxx` 认证/会话、`3xxxx` 授权、`4xxxx` 任务、`5xxxx` 时间记录、`6xxxx` 笔记/报告、`7xxxx` 导出、`9xxxx` 系统。完整定义见 `server/src/main/java/com/worktrace/common/api/ResultCode.java`。

已实现的三条探针行为：

| 请求 | 结果 |
|---|---|
| `GET /api/sys/ping` | `200`，`code=0`，返回服务器时间与时区 |
| `GET /actuator/health` | `200`，`{"status":"UP"}` |
| 未知路径 | `404`，`code=10003`，统一响应体 + `X-Trace-Id` |

## 关键配置

| 配置 | 默认值 | 说明 |
|---|---|---|
| `DB_USERNAME` | `wt_app` | 数据库账号（Docker 下主机为 `%`，见 `docker/mysql-init/09_docker_app_user.sql`） |
| `DB_PASSWORD` | dev: `WorkTrace_App_2026!`；prod: 无默认值 | 生产必须注入 |
| `MYSQL_ROOT_PASSWORD` | `WorkTrace_Root_2026!` | 容器 root 密码，可在根目录 `.env` 覆盖 |
| 数据库地址 | `127.0.0.1:3306` | Docker 端口映射，仅发布到本机回环 |
| `server.address` | `127.0.0.1` | 单机部署，由反向代理暴露 |
| `server.port` | `8080` | |
| `spring.profiles.default` | `dev` | |
| `spring.servlet.session.timeout` | `7d` | Session 落库 |
| `VITE_API_BASE` | `/api` | 前端请求前缀 |

时区三处对齐：MySQL `default-time-zone='+08:00'`、JDBC `connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true`、JVM `-Duser.timezone=Asia/Shanghai`。

## 主题

深青主题全部来自 `web/src/styles/tokens.css`：

- 页面底色 `#0E2A2E`
- 卡片/侧栏 `#12363B`
- 品牌色 `#1FB6A6`
- 正文 `#E6F1F0`

页面内不硬编码颜色；Element Plus 通过 `element-override.scss` 覆盖为同一套 CSS 变量。

## 已验证

- **数据库（Docker）**：`docker compose up -d` 自动完成初始化，容器 `worktrace-mysql`（MySQL 8.4.13）`healthy`；18 张表、0 个 `TIMESTAMP` 列、引擎/排序规则全部一致、时区表可用（`CONVERT_TZ` 返回 `+08:00`）、`wt_app`@`%` 权限正确且 `audit_log` 只有 `SELECT, INSERT`、管理员 seed 就位。
- **DDL**：8 个分片在 MySQL 8.4 上执行成功，33 项结构检查通过（引擎/字符集、唯一键、生成列软删除唯一性、4 组 UPSERT `ROW_COUNT()=2`、审计表只追加权限、Session 索引等）。
- **后端**：`mvn clean package` 成功，8 个测试全部通过；在 Windows 上连接容器 MySQL 启动后，ping、health（`UP` 说明数据库连通）、404 行为符合契约。
- **前端**：`npm run type-check` 与 `npm run build` 成功；13 条路由均能渲染，控制台无报错；360px 视口下文档无横向滚动（顶部导航自身可横向滑动）。

## 已知差异与后续事项

1. `report.sections_json` 列注释写四段，而接口契约只用 `{summary,nextPlan}`；以契约为准。
2. `notification.type` 注释使用 `DUE_TODAY`，固定枚举为 `DUE_REMIND`；前端已按 `DUE_REMIND` 定义，后续需统一。
3. `audit_log` 的“只追加”改为逐表授权实现；MySQL 不支持先库级授权再表级 `REVOKE`。
4. `task` 的自引用父任务约束无法用 MySQL CHECK 表达（不能引用 AUTO_INCREMENT 列），改由 Service 层校验。
5. `TINYINT(1)` 在 MySQL 8.4 触发 `Warning 1681`；这是本批明确要求的布尔列写法，不影响建表。
6. 前端 `ep` chunk 约 915 KB（gzip 295 KB），构建有体积提示但成功；后续按需拆分组件或接受该体积。
7. 登录、强制改密、角色校验、业务接口与所有页面逻辑均未实现，路由 `meta.roles` 已预留。
8. 数据库已迁移到 Docker（`docker compose`）。原先安装在 WSL 里的原生 MySQL 已彻底移除（包、配置、数据目录 `/var/lib/mysql` 均已删除），当前唯一数据库实例是容器 `worktrace-mysql`。
9. Docker 下应用账号为 `wt_app`@`%`（原 DDL 为 `'wt_app'@'127.0.0.1'`，宿主机经端口映射连接时源地址是网关），端口只发布在 `127.0.0.1`，生产请按来源网段收紧。
