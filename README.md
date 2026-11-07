# WorkTrace

个人工作留痕系统。当前已实现：**登录会话、首页（设计图还原）、任务管理（多级树 / 进度 / 跟进记录 / 完成时间 / 重点标记）、AI 工作洞察、AI 工作报告（SSE 流式）、统一响应与全局异常、防重复提交注解**。

页面：首页 `/`、登录 `/login`、任务管理 `/tasks`、AI 报告生成 `/reports`；另有一个已实现但尚未挂路由的工作统计大屏 `Dashboard.vue`。数据库统一由 `docker-compose.yml` 管理，库内只保留 6 张在用表（设计稿中未实现的 14 张表已由 `12_drop_unused.sql` 裁剪）。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17、Spring Boot 3.3.6、MyBatis-Plus 3.5.9、Spring Session JDBC、Caffeine（防重复提交本地实现）、Lombok、spring-security-crypto（仅 BCrypt） |
| 数据库 | MySQL 8.4（Docker 容器运行，验证使用 8.4.13）、InnoDB、utf8mb4 / utf8mb4_0900_ai_ci、DATETIME(3) |
| 前端 | Node.js 20+、Vue 3.5、Vite 6、TypeScript 5.7、Element Plus（全量注册 + 中文 locale + 全量图标）、Pinia、Vue Router 4、Axios、ECharts（按需引入） |

明确未引入：Spring Security 全家桶、Redis、消息队列、Elasticsearch、定时任务框架、Tailwind CSS。

## 功能模块

### 登录会话（`auth`）

- `POST /api/auth/login`（演示账号 `admin` / `admin123`）、`GET /api/auth/info`
- 会话为 HttpOnly Cookie + Spring Session JDBC（默认 7 天），前端不能伪造登录态
- 前端 Pinia `user` store（localStorage 持久化，仅驱动路由守卫与 UI）；「退出登录」只清前端登录态，服务端会话到期自然失效

### 任务管理（`task`，`/tasks` → `TaskList.vue`）

- **层级**：`parent_id` 前端组树，**每级 64px 真缩进 + 自制展开箭头**（EP 原生缩进/箭头渲染在首列、深层箭头会被标题文字盖住，已弃用改由标题列自制接管），父/子/孙三层底色（父行深蓝分组条 / 子级白底 / 子孙级浅蓝底+浅蓝条）；删除任务级联删除全部后代（BFS 收集）与跟进记录
- **行内编辑**：标题、类型、状态、优先级、计划日期、实际工时直接点单元格修改，改动按快照 diff 即时回写；类型/状态/优先级以白底芯片标签呈现（状态/优先级药丸形带同色圆点、类型圆角芯片），点击即改
- **进度**：叶子任务行内滑块**用户直接拖**（0-100，步长 5）；父任务显示子任务进度**均值派生**条。拖满 ⇔ 勾选完成双向联动（完成时进度=100，拖离 100 移出完成）
- **完成时间**：后端派生——状态首次变为已完成打 `finish_time` 时间戳，移出已完成清空（`NULL` 显示「—」）
- **跟进记录**：`biz_task_log` 时间线（找领导核实 / 备注 / 开发 / 其他），抽屉内新增 / 按时间线展示 / 逐条删除
- **重点标记**：操作列「设重点 / 取消重点」+ 标题橙色星标（`biz_task.is_key`）；被标记的任务显示在首页「重点任务」卡（按计划日期升序取 3 条），类型选「其他」可自定义输入
- **小屏适配**：列宽整体瘦身（全列 ~1270px）；≤1440 自动收起「完成时间」列、≤1280 再收起「工时(h)」（matchMedia 断点驱动摘列，数据不丢），1280 级别屏幕无横向滚动，宽屏列自动回归
- **接口**：`GET /api/tasks/page`（一次拉全量，前端组树与筛选）、`POST /api/tasks`、`PUT /api/tasks`、`DELETE /api/tasks/{id}`；`GET /api/tasks/logs?taskId=`、`POST /api/tasks/logs`、`DELETE /api/tasks/logs/{id}`

### 首页（`/` → `Home.vue`）

- 问候横幅（小时问候 + ICU 中文农历）、KPI 四卡（全部 / 进行中 / 已完成 / 待处理，真数据派生）
- 任务走势（ECharts 折线，近 7/30/90 天按创建 / 完成时间按日聚合）、任务状态分布（环形）
- 重点任务卡：任务管理「设重点」的任务（`is_key=1`，计划日期升序取 3 条），勾选直接改完成状态
- AI 工作洞察卡（见下）；AI 报告中心入口（跳 `/reports?type=` 预选）

### AI 工作洞察（`insight`，`GET /api/insights/work`）

- 按当前用户真实任务统计（状态 / 逾期 / 到期 / 优先级 / 分类 / 重点数）调 DeepSeek 生成 `{headline, para, advice}` 三段洞察
- **降级在服务端**：AI key 缺失 / 超时 / 解析失败 / 无任务 → 返回规则兜底文案（source=RULES），接口恒 `Result.ok`；前端本地规则文案兜网络层失败
- Caffeine 按用户缓存 AI 结果 30 分钟（RULES 不缓存，下次自动重试）；配置复用 `ai.*`（与 AI 报告共用）

### AI 报告（`report`，`/reports` → `ReportGenerator.vue`）

- `POST /api/reports/generate` 一次性生成；`POST /api/reports/stream-generate` **SSE 流式推送**（前端逐字渲染）

### 公共能力（`common`）

- 统一响应 `Result{code,message,data}`，HTTP 语义业务码（见下文「统一响应」）
- `GlobalExceptionHandler`：`BizException` 按携带码透传、参数校验 → 400、未知路径 → 404、其余兜底 500，堆栈只进日志
- `@Idempotent` 防重复提交注解 + 切面（Caffeine 本地实现，防重窗口可配）；注解已就绪，业务接口暂未挂载

## 目录结构

```
WorkTrace/
├─ docker-compose.yml           # 三容器编排：mysql（初始化 00–12 脚本）、backend、frontend
├─ docker/mysql-init/           # 容器专用初始化：00 时区表、09 账号主机适配
├─ server/
│  ├─ db/                       # DDL 分片 01–13（11=业务表，12=裁剪未用表，13=is_key 幂等迁移）+ 执行/验收说明
│  ├─ src/main/java/com/worktrace/
│  │  ├─ auth/                  # 登录会话（AuthController、LoginInterceptor、SysUser 实体/映射）
│  │  ├─ common/
│  │  │  ├─ api/                # Result（HTTP 语义业务码）
│  │  │  ├─ config/             # MyBatis-Plus、WebMvc 配置
│  │  │  ├─ exception/          # BizException
│  │  │  ├─ idempotent/         # @Idempotent 注解 + 切面
│  │  │  └─ web/                # GlobalExceptionHandler
│  │  ├─ insight/               # AI 工作洞察（DeepSeek + 规则兜底）
│  │  ├─ report/                # AI 报告生成（含 SSE 流式）
│  │  ├─ task/                  # 任务 + 跟进记录（entity/mapper/dto/service/web）
│  │  └─ WorkTraceApplication.java
│  ├─ src/main/resources/       # application.yml、dev/prod、logback、banner
│  └─ pom.xml
├─ web/
│  ├─ src/api/request.ts        # Axios 封装：统一拆包、401 清登录态跳登录
│  ├─ src/components/NavBar.vue # 顶部导航（登录态、页面切换、退出）
│  ├─ src/router/               # / /login /tasks /reports + 登录守卫 + 标题同步
│  ├─ src/stores/user.ts        # 登录态（localStorage 持久化）
│  ├─ src/views/                # Home / Login / TaskList / ReportGenerator / Dashboard(未挂路由)
│  ├─ src/assets/home/          # 首页设计稿裁切素材（KPI / 报告图标）
│  ├─ vite.config.ts            # '@' 别名、/api 开发代理
│  └─ package.json
├─ deploy/                      # 本地一键发布（GitHub 无流水线，见 deploy/README.md）
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

仓库根目录的 `docker-compose.yml` 管理 `mysql` / `backend` / `frontend` 三个服务。本地开发通常只起数据库：

```bash
docker compose up -d mysql     # 只启动 MySQL（后端/前端在本机跑）
docker compose ps              # 查看状态，等 mysql 变为 healthy
docker compose logs -f mysql
```

`docker compose up -d` 则三容器全起（MySQL 初始化 + 后端 + 前端，适合快速体验整套系统）。

容器使用 `mysql:8.4` 镜像，参数与 `server/db/01_user_session.sql` 头部建议一致（utf8mb4 / `utf8mb4_0900_ai_ci` / `default-time-zone=+08:00`），数据保存在命名卷 `worktrace-mysql-data` 中。

常用操作：

```bash
docker compose down            # 停止，数据保留
docker compose down -v         # 停止并清空数据卷（下次 up 会重新执行 DDL）
docker compose restart mysql
```

初始化脚本由两部分组成，按文件名字典序执行：

| 顺序 | 来源 | 作用 |
|---|---|---|
| `00_load_tz.sh` | `docker/mysql-init/` | 加载 MySQL 时区表（JDBC 使用 `Asia/Shanghai` 必需） |
| `01`–`13` | `server/db/` | DDL 分片：`01–08` 设计稿建表、`10` 演示账号、`11` 业务表（`biz_task` 含 `is_key`/`finish_time`/`progress`、`biz_task_log`）、`12` 裁剪未实现表、`13` 幂等补 `is_key` |
| `09_docker_app_user.sql` | `docker/mysql-init/` | 把 `wt_app` 账号主机改为 `%`，适配宿主机经端口映射连接 |

> 本环境若无法访问 Docker Hub，可先从镜像站拉取再打标签：
> `docker pull docker.m.daocloud.io/library/mysql:8.4 && docker tag docker.m.daocloud.io/library/mysql:8.4 mysql:8.4`

**用本机 MySQL 替代 Docker（可选）**：在 `server/db` 目录按 01→13 顺序执行全部分片即可，此时 `wt_app` 按设计使用 `'wt_app'@'127.0.0.1'`，无需 `09` 适配脚本；并自行加载时区表：

```bash
mysql_tzinfo_to_sql /usr/share/zoneinfo | mysql -u root -p mysql
```

`08_sys_seed.sql` 会创建应用账号 `wt_app`（密码 `WorkTrace_App_2026!`）；`10_sys_user.sql` 创建演示账号 `admin`（密码 `admin123`，明文，仅供登录模块联调，生产必须改存 BCrypt 哈希并替换密码）。

**存量库升级**（已建过表的开发机/服务器）：`CREATE TABLE IF NOT EXISTS` 对已存在的表不生效，需执行 `server/db/11_biz_tables.sql` 头部注释里的 `ALTER`（补 `parent_id`/`category`/`finish_time`/`progress` 等历史列）并单独 `CREATE TABLE biz_task_log`；`is_key` 列直接跑幂等脚本 `server/db/13_task_is_key.sql`（重复执行安全）。

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
curl -i http://127.0.0.1:8080/actuator/health
curl -i http://127.0.0.1:8080/api/no-such-path
```

### 3. 启动前端

```bash
cd web
npm install
npm run dev
```

打开 `http://localhost:5173/`，登录后进入首页。开发服务器把 `/api` 代理到 `http://localhost:8080`。

```bash
npm run type-check
npm run build
npm run preview
```

## 部署

线上部署采用**本地一键发布**的模式（GitHub 仓库只存代码、无任何流水线）：

- 上线 → 本机执行 `./deploy/publish.sh`（构建后端+前端 → 备份 → scp 直传 → md5 校验 → 发前端 → 重启 + 健康检查，失败自动回滚）
- 自检 → `./deploy/publish.sh --build-only`（只编译打包）

完整说明（配置、服务器拓扑、排障速查、历史沿革）见 **[deploy/README.md](deploy/README.md)**。

## 统一响应

统一结构 `{ code, message, data }`，**code 使用 HTTP 语义**，前后端共用一套认知：

| code | 含义 |
|---|---|
| 200 | 成功 |
| 400 | 参数错误（校验失败时 `message` 拼接字段级错误） |
| 401 | 未登录 / 会话过期 |
| 403 | 无权限 |
| 404 | 资源 / 接口不存在 |
| 500 | 系统异常（堆栈只进日志，对外仅「系统繁忙」） |

成功：

```json
{
  "code": 200,
  "message": "ok",
  "data": {}
}
```

无论业务成败，**HTTP 状态码恒为 200**（拦截器直接返回 401 的场景除外），前端只看 `body.code`：`web/src/api/request.ts` 统一拆包，`code=200` 返回 `data`，`401` 自动清登录态并跳登录，其余以 `ApiError` 抛出、页面统一提示。异常映射集中在 `GlobalExceptionHandler`（`BizException` 码与消息原样透传 → 400 / 404 / 403 / 500）。

已实现的探针行为：

| 请求 | 结果 |
|---|---|
| `GET /actuator/health` | `200`，`{"status":"UP"}` |
| `GET /actuator/info` | `200`，应用信息 |
| 未知路径 | `200`，`code=404`，`"接口不存在: …"` 统一响应体 |

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
| `AI_API_KEY`（`ai.api-key`） | 空 | DeepSeek / OpenAI key，AI 报告与 AI 洞察共用；不配则报告报错、洞察降级为规则文案 |
| `ai.base-url` | `https://api.deepseek.com/v1` | OpenAI 兼容 Chat Completions 地址 |
| `ai.model` | `deepseek-chat` | OpenAI 可换 `gpt-4o-mini` |

时区三处对齐：MySQL `default-time-zone='+08:00'`、JDBC `connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true`、JVM `-Duser.timezone=Asia/Shanghai`。

## 主题

当前为 **蓝绿白品牌色（Element Plus 浅色主题）**：品牌蓝 `#3988E8`、主操作青绿 `#13B8A6`、页面底 `#F2F4F7`、卡片白、边框 `#E5EAF0`：

- 首页与任务管理共用色板（页面级 CSS 变量覆盖 `--el-color-primary` 系列，`color-mix` 派生浅/深变体）
- 状态/优先级/类型为**白底芯片标签**（同色细描边 + 圆点 + 加深文字；状态/优先级药丸形、类型圆角芯片），在父/子/孙分层底色上层次清晰；进度条青绿、满格绿
- 历史的深青 `tokens.css` 主题已随页面重构移除

## 已验证

- **数据库（Docker）**：`docker compose up -d mysql` 按 `00–12` 脚本完成初始化，容器 `worktrace-mysql`（MySQL 8.4.13）`healthy`；6 张表（`sys_user`、`biz_task`、`biz_task_log`、`biz_report`、`SPRING_SESSION` ×2）引擎/排序规则一致、时区表可用（`CONVERT_TZ` 返回 `+08:00`）、`wt_app`@`%` 权限正确。
- **后端**：`mvn compile` / `mvn clean package` 均通过（当前无单元测试，`src/test` 为空）；Docker `backend` 容器运行，任务 / 跟进记录 / 报告接口可用。
- **前端**：`npm run type-check`、`npm run build` 均通过（build 14s 级，仅体积提示无报错）。
- **任务模块端到端（本地浏览器实测）**：登录守卫、树形层级、行内编辑按 diff 回写、进度滑块拖动 / 满格联动完成、完成时间打戳与清空、父任务状态手动修改不被子任务派生覆盖、子任务全完成父任务自动完成、跟进记录时间线增删，均验证通过；树形三层缩进与自制展开箭头（含深层）、1280 小屏自动收列无横向滚动，线上实测通过。
- **首页 / AI 洞察 / 重点标记端到端（线上实测）**：KPI 与图表真数据、ICU 农历问候、AI 洞察生成与缓存命中（空 key 降级 RULES 实测）、设重点 → 首页重点任务卡可见 → 取消即消失（按计划日期取 3 条、空日期排后），均验证通过。

## 已知差异与后续事项

1. `Dashboard.vue`（工作统计大屏，ECharts）已实现但尚未挂路由，导航暂无入口。
2. `@Idempotent` 注解与切面已就绪，业务接口暂未挂载（当前提交场景手工防重足够，需要时在 Controller 方法上加注解即可）。
3. 「退出登录」只清前端登录态，服务端会话按 Cookie 7 天自然过期，无主动失效接口。
4. 数据库从设计稿 19 张裁剪至 6 张（`12_drop_unused.sql`，线上已于 2026-11-05 同步删除）；被裁表的 DDL 仍保留在 `02–08` 分片中，未来实现某功能时把该表从 `12` 的 `DROP` 列表移除即可。
5. `biz_task_log` 与 `biz_task` 的 `parent_id` / `category` / `finish_time` / `progress` / `is_key` 为后加能力：全新库由 `11_biz_tables.sql` 直接建全，**存量库需执行该文件头部注释中的 `ALTER`**；`is_key` 另有幂等脚本 `13_task_is_key.sql` 可直接跑。
6. Element Plus 全量注册，主包 `index` 约 1.2MB（gzip 约 390KB，含 EP 全量组件与样式），构建有「>500KB」体积提示但成功；在意体积可改按需引入或 `manualChunks` 分包。
7. 演示账号 `admin/admin123` 为明文比对（登录联调用），生产必须改存 BCrypt 哈希并替换密码。
