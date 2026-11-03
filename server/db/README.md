# WorkTrace 数据库脚本（MySQL 8）

本目录是 T02 的唯一交付：8 个 DDL 分片，按序号顺序在 **MySQL 8.0.20+** 实例上执行。
分片只负责建库建表、系统 seed 与最小权限，不包含任何业务数据。

> **Docker 部署**：仓库根目录的 `docker-compose.yml` 会把本目录 01–08 自动挂载到
> `/docker-entrypoint-initdb.d`，首次 `docker compose up -d` 时按序执行，无需手动运行。
> 另有两个容器专用脚本（不属于 T02 分片）：`docker/mysql-init/00_load_tz.sh`（加载时区表）
> 与 `09_docker_app_user.sql`（把 `wt_app` 主机从 `127.0.0.1` 改为 `%`，适配端口映射访问）。
> 本机直接执行时不需要这两个脚本。

## 1. 执行顺序

| 顺序 | 文件 | 内容 |
|---|---|---|
| 01 | `01_user_session.sql` | `CREATE DATABASE`、`app_user`、`invite_code`、Spring Session 两张官方表 |
| 02 | `02_task.sql` | `task`、`tag`、`task_tag` |
| 03 | `03_note_time.sql` | `note`、`note_task_rel`、`time_entry` |
| 04 | `04_stat.sql` | `daily_stat`、`weekly_stat`（双指标可空） |
| 05 | `05_report.sql` | `report` |
| 06 | `06_notify_audit.sql` | `notification`、`audit_log` |
| 07 | `07_export.sql` | `export_job` + `uk_export_active` |
| 08 | `08_sys_seed.sql` | `sys_lock`、`sys_config`、管理员 seed、`wt_app` 账号与授权 |

全新实例上依次执行：

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

也可以在 `mysql` 客户端内执行 `SOURCE 01_user_session.sql;`，依此类推。

> `08_sys_seed.sql` 使用 `CREATE USER 'wt_app'@'127.0.0.1'`。如果该账号已存在，先执行
> `DROP USER 'wt_app'@'127.0.0.1';` 再重跑 08（仅限开发/验证实例）。生产环境请由 DBA 单独管理账号。

## 2. 环境基线检查

执行 DDL 前先确认 MySQL 版本与实例参数：

```sql
SELECT @@version, @@sql_mode, @@lower_case_table_names,
       @@character_set_server, @@collation_server, @@global.time_zone, @@session.time_zone;
```

要求与说明：

- `@@version` ≥ `8.0.20`。推荐使用 MySQL 8.4 LTS；`utf8mb4_0900_ai_ci` 在 8.0 与 8.4 均可用。
- MySQL 没有 `@@default_time_zone` 系统变量；`default-time-zone` 是启动项，查看当前时区用
  `@@global.time_zone` / `@@session.time_zone`（任务卡中的原查询在 MySQL 8.4 会报 `ERROR 1193`）。
- `@@lower_case_table_names` 必须在所有环境（开发、测试、生产）保持一致。Linux 默认 `0`，
  Windows 默认 `1`；不一致会出现“本地正常、部署后表不存在”。本目录的表名、库名全部小写。
- `@@sql_mode` 建议保留 `ONLY_FULL_GROUP_BY`。按标签聚合时只 `SELECT tag_id, SUM(...)`，
  标签名称在 Java 层补入，不依赖非聚合列。
- `@@character_set_server` / `@@collation_server` 建议为 `utf8mb4` / `utf8mb4_0900_ai_ci`。
  每个建表语句仍显式声明字符集与排序规则，不依赖实例默认值。

时区三处对齐（设计文档 §3）：

1. MySQL：`default-time-zone='+08:00'`；
2. JDBC：`connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true`；
3. JVM / 启动参数：`-Duser.timezone=Asia/Shanghai`。

## 3. 字符集与时间类型

- 所有表统一 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`。
  Spring Session 的两张官方表原本未在官方 DDL 中声明字符集，本目录为满足统一基线显式补齐。
- 所有时间列使用 `DATETIME(3)`；**全库没有任何 `TIMESTAMP` 列**，避免隐式时区转换。
- 布尔语义使用 `TINYINT(1)`，主键统一 `BIGINT NOT NULL AUTO_INCREMENT`。
- 软删除唯一约束使用 `STORED` 生成列 + 唯一索引（MySQL 没有部分索引）：
  `app_user.username_active`、`tag.name_active`、`time_entry.running_owner`、`export_job.active_owner`。
- JSON 列统一 `JSON NOT NULL`，无默认值；Java 实体必须初始化为 `"[]"` / `"{}"` 后再写入。

## 4. 账号与权限

`08_sys_seed.sql` 创建应用账号：

| 项 | 值 |
|---|---|
| 用户 | `wt_app` |
| 主机 | `127.0.0.1` |
| 密码 | `WorkTrace_App_2026!`（仅开发默认值，生产必须替换） |
| 业务表 | `SELECT, INSERT, UPDATE, DELETE` |
| `audit_log` | `SELECT, INSERT` |
| Spring Session 两张表 | `SELECT, INSERT, UPDATE, DELETE` |

与设计文档 §4.8 的差异：MySQL 权限是加法模型，先 `GRANT ... ON worktrace.*` 再
`REVOKE ... ON worktrace.audit_log` 会报 `ERROR 1147`。因此脚本改为逐表授权，
`audit_log` 从不授予 `UPDATE/DELETE`，从而真实满足“审计表只追加”。

验证：

```sql
SHOW GRANTS FOR 'wt_app'@'127.0.0.1';
```

## 5. Seed 数据

- 管理员：`admin` / `Init@123456`，`must_change_pwd=1`，首次登录必须改密。
- 密码哈希为 BCrypt strength=10 实际生成值，不是占位符。
- `sys_config` 写入全局时区、提醒时刻、登录锁定阈值、导出保留时长等系统参数。
- `sys_lock` 预插入定时任务锁行，保证后续 `UPDATE ... WHERE locked_until ...` 能命中。

## 6. 验收自检 SQL

```sql
-- 1) 不允许出现 TIMESTAMP
SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE table_schema = 'worktrace' AND data_type = 'timestamp';

-- 2) 所有表字符集/引擎一致
SELECT table_name, engine, table_collation
FROM information_schema.tables
WHERE table_schema = 'worktrace'
ORDER BY table_name;

-- 3) 生成列唯一索引
SELECT table_name, index_name, column_name
FROM information_schema.statistics
WHERE table_schema = 'worktrace'
  AND index_name IN ('uk_user_username_active', 'uk_tag_owner_name_active',
                     'uk_te_running', 'uk_export_active');

-- 4) 双指标可空，原值列不可空
SELECT table_name, column_name, is_nullable, column_type
FROM information_schema.columns
WHERE table_schema = 'worktrace'
  AND table_name IN ('daily_stat', 'weekly_stat')
ORDER BY table_name, ordinal_position;

-- 5) UPSERT 第二次执行 ROW_COUNT() 必须为 2
INSERT INTO daily_stat (owner_id, stat_date, by_tag_json)
VALUES (1, '2026-11-02', '[]') AS new
ON DUPLICATE KEY UPDATE by_tag_json = new.by_tag_json, calc_ver = calc_ver + 1;
SELECT ROW_COUNT();
```

第 5 条要求 MySQL 8.0.20+ 的 `AS new` 别名语法。`ROW_COUNT()` 第二次为 `2` 表示走了
`ON DUPLICATE KEY UPDATE`，不是静默插入重复行。

## 7. 文档与实现差异

以下差异已在脚本内就地说明，最终汇报也会列出：

1. 设计文档 §4.8 的 `GRANT ... ON worktrace.*` + `REVOKE ... audit_log` 在 MySQL 无效，
   本目录改为逐表授权。
2. `report.sections_json` 的列注释写“四段 `{summary,nextPlan,risk,support}`”，
   而 §6.2 / r2 / r5 的契约只使用 `{summary,nextPlan}`；DDL 保留注释原文，业务实现以契约为准。
3. `notification.type` 列注释写 `DUE_TODAY`，而 §8.10 固定枚举为 `DUE_REMIND`；
   前端 `constants/enums.ts` 使用 `DUE_REMIND`，后续批次需统一。
4. Spring Session 官方 DDL 未声明字符集/排序规则，T02 要求所有表统一，本目录显式补齐。
5. 设计文档 `task` 表的 `ck_task_no_self CHECK (parent_id IS NULL OR parent_id <> id)` 在 MySQL 8
   不可执行（`ERROR 3818`：CHECK 不能引用 `AUTO_INCREMENT` 列）。本目录移除该约束，
   自引用父任务改由 Service 层校验，其余 CHECK 原样保留。
6. MySQL 8.4 对 `TINYINT(1)` 给出 `Warning 1681`（整数显示宽度已弃用）。T02 明确要求布尔列
   使用 `TINYINT(1)`，因此该警告无法同时消除；它不影响建表结果。
