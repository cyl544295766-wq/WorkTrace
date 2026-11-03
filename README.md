# WorkTrace

个人工作留痕系统。本仓库当前只包含 **T01–T04 工程骨架**：后端骨架、数据库 DDL、前端壳层、统一响应与全局异常。

不包含登录认证、任何业务模块、业务接口、ECharts 报表实现与部署脚本。所有页面均为占位页，用于验证路由、布局与主题。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17、Spring Boot 3.3.6、MyBatis-Plus 3.5.9、Spring Session JDBC、Caffeine、Lombok、spring-security-crypto（仅 BCrypt） |
| 数据库 | MySQL 8.0.20+（验证使用 8.4.11 LTS）、InnoDB、utf8mb4 / utf8mb4_0900_ai_ci、DATETIME(3) |
| 前端 | Node.js 20+、Vue 3.5、Vite 6、TypeScript 5.7、Element Plus（按需）、Pinia、Vue Router 4、Axios、ECharts（懒加载） |

明确未引入：Spring Security 全家桶、Redis、消息队列、Elasticsearch、定时任务框架、Tailwind CSS。

## 目录结构

```
WorkTrace/
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
- MySQL 8.0.20+，且应用账号可连接

### 1. 初始化数据库

在 `server/db` 目录按顺序执行 8 个分片：

```bash
mysql --default-character-set=utf8mb4 -u root -p < 01_user_session.sql
mysql --default-character-set=utf8mb4 -u root -p < 02_task.sql
mysql --default-character-set=utf8mb4 -u root -p < 03_note_time.sql
mysql --default-character-set=utf8mb4 -u root -p < 04_stat.sql
mysql --default-character-set=utf8mb4 -u root -p < 05_report.sql
mysql --default-character-set=utf8mb4 -u root -p < 06_notify_audit.sql
mysql --default-character-set=utf8mb4 -u root -p < 07_export.sql
mysql --default-character-set=utf8mb4 -u root -p < 08_sys_seed.sql
```

`08_sys_seed.sql` 会创建开发账号 `wt_app`（密码 `WorkTrace_App_2026!`）和初始管理员 `admin`（密码 `Init@123456`，首次登录强制改密）。生产环境必须替换密码，并由 DBA 管理账号。

**时区表必须可用。** JDBC 使用 `connectionTimeZone=Asia/Shanghai`，MySQL 需要已加载时区表：

```bash
# Linux / WSL
mysql_tzinfo_to_sql /usr/share/zoneinfo | mysql -u root -p mysql
```

验证：

```sql
SELECT CONVERT_TZ('2026-11-03 00:00:00', 'UTC', 'Asia/Shanghai');
-- 预期：2026-11-03 08:00:00
```

更多说明（版本检查、字符集、权限、UPSERT 自检、设计差异）见 `server/db/README.md`。

### 2. 启动后端

开发环境默认使用 `dev` profile，数据库密码有本地默认值；也可显式注入：

```bash
cd server
export DB_USERNAME=wt_app
export DB_PASSWORD='WorkTrace_App_2026!'
mvn spring-boot:run
```

或打包后运行（JVM 时区必须固定）：

```bash
mvn clean package
java -Duser.timezone=Asia/Shanghai -jar target/worktrace-server-0.1.0-SNAPSHOT.jar
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
| `DB_USERNAME` | `wt_app` | 数据库账号 |
| `DB_PASSWORD` | dev: `WorkTrace_App_2026!`；prod: 无默认值 | 生产必须注入 |
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

- **DDL**：MySQL 8.4.11 上 8 个分片全部执行成功，18 张表；33 项结构检查通过（引擎/字符集、无 TIMESTAMP、唯一键、生成列软删除唯一性、4 组 UPSERT `ROW_COUNT()=2`、审计表只追加权限、Session 索引等）。
- **后端**：`mvn clean package` 成功，8 个测试全部通过；jar 启动后 ping、health、404 行为符合契约。
- **前端**：`npm run type-check` 与 `npm run build` 成功；13 条路由均能渲染，控制台无报错；360px 视口下文档无横向滚动（顶部导航自身可横向滑动）。

## 已知差异与后续事项

1. `report.sections_json` 列注释写四段，而接口契约只用 `{summary,nextPlan}`；以契约为准。
2. `notification.type` 注释使用 `DUE_TODAY`，固定枚举为 `DUE_REMIND`；前端已按 `DUE_REMIND` 定义，后续需统一。
3. `audit_log` 的“只追加”改为逐表授权实现；MySQL 不支持先库级授权再表级 `REVOKE`。
4. `task` 的自引用父任务约束无法用 MySQL CHECK 表达（不能引用 AUTO_INCREMENT 列），改由 Service 层校验。
5. `TINYINT(1)` 在 MySQL 8.4 触发 `Warning 1681`；这是本批明确要求的布尔列写法，不影响建表。
6. 前端 `ep` chunk 约 915 KB（gzip 295 KB），构建有体积提示但成功；后续按需拆分组件或接受该体积。
7. 登录、强制改密、角色校验、业务接口与所有页面逻辑均未实现，路由 `meta.roles` 已预留。
