# 任务范围（task_scope）生产迁移手册

> 变更日期：2026-11-26。对应代码提交见 `deploy/HANDOFF.md`。
> **生产迁移已于 2026-11-26 14:55（Asia/Shanghai）完成**：124 条存量任务全部 COMPANY，报告 0 条、跟进 6 条，原字段指纹及各用户任务数保持一致。备份：`/home/ubuntu/worktrace/backups/scope-release-20261126145501/`。应用发布结果见 HANDOFF。

## 一、变更内容

| 项 | 说明 |
|---|---|
| `biz_task.task_scope` | 新增列，`VARCHAR(16) NOT NULL DEFAULT 'COMPANY'`，取值 `COMPANY`/`PERSONAL` |
| `biz_report.task_scope` | 新增列，同上；记录报告的生成范围，历史报告统一归 `COMPANY` |
| 存量数据 | 首次执行后**全部任务归入 COMPANY**；id、用户、父子关系、重点标记、状态、进度、日期、跟进记录全部不变 |
| 破坏性操作 | **无**——脚本只做「缺列则加列」与「空串修补」，不 DELETE/TRUNCATE/重建任何表 |

执行账号需要 `work_system` 库的 ALTER 及备份所需权限。权限取决于实际环境：本地曾使用的 `wt_app` 只有 DML，执行 ALTER 报 1142；本次生产核对应用自身配置账号已具备库级 ALL PRIVILEGES，使用该账号完成备份和迁移。认证仅从 `/home/ubuntu/worktrace/worktrace.env` 的数据库配置读取，密码不写入命令参数、输出或仓库。

生产现通过宿主机 `mysql` / `mysqldump` 客户端连接 `127.0.0.1:3306/work_system`，实际容器为共享的 `monevo-mysql`。不要停止共享数据库容器或操作其他库；旧 `worktrace-mysql` 容器及 `.mysql-root-pw` 登录说明已失效。

## 二、执行顺序

```
1) 备份（见下节）
2) 停应用  —— systemctl stop worktrace   （避免迁移期间应用写入未带新列的语句）
3) 执行迁移：使用已确认权限的账号连接 work_system，执行 server/db/14_task_scope.sql（可重复执行）
4) 核对（见下节）
5) 发布应用：bash deploy/publish.sh      （新代码才认识 task_scope 字段）
6) 起应用并健康检查：systemctl start worktrace
```

**兼容关系：必须先迁移数据库，再发布新应用，顺序不可颠倒。**
- 先迁移后发布：旧应用不认识新列（SELECT/INSERT 按列名映射，多出的列用默认值），可正常运行；
- 先发布后迁移：新应用查询带 `task_scope = ?`，列不存在会报错——**因此必须先迁移再发布**（上表顺序）。
  若误操作先发布了，立即执行 `14` 即可恢复（幂等，可重复）。

## 三、备份与回退

**备份（执行前必做）**：
```bash
# 结构 + 数据（单文件，含 DROP/CREATE 语句，可整库恢复）
mysqldump -u <root> -p --databases work_system \
  | gzip > /backup/work_system-pre-scope-$(date +%Y%m%d%H%M).sql.gz
# 或仅业务表备份（包含这三张表的结构与数据）
mysqldump -u <root> -p work_system biz_task biz_report biz_task_log \
  | gzip > /backup/biz-data-pre-scope-$(date +%Y%m%d%H%M).sql.gz
```

**回退说明**：
- 应用回退时优先保留新增列，旧版本可以容忍新列；恢复同版前后端后检查健康与数据。若已新增 PERSONAL 任务，先评估旧版不识别范围对任务列表与报告的影响，避免两类数据混用。
- 仅在确认不再需要范围字段及已有取值后，才考虑人工移除新增列；此操作不属于自动回退步骤：
  ```sql
  ALTER TABLE biz_task DROP COLUMN task_scope;
  ALTER TABLE biz_report DROP COLUMN task_scope;
  ```
- 若已用新版本应用写入过 PERSONAL 任务，**删列会丢掉该字段值**——回退前先评估；
  数据级恢复用上面的备份整表覆盖。
- 应用回退：`deploy/publish.sh` 保留最近三份 jar，恢复对应版本后重启 `worktrace`（详见 HANDOFF「回退说明」）。
- 不要回退/删除任何其他表或业务行。

## 四、迁移后核对（全部只读）

```sql
-- 1) 列已存在
SELECT COLUMN_NAME, COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA='work_system' AND TABLE_NAME IN ('biz_task','biz_report')
   AND COLUMN_NAME='task_scope';                    -- 期望 2 行，默认 COMPANY

-- 2) 首次迁移后存量任务全部 COMPANY（PERSONAL 计数应为 0）
SELECT task_scope, COUNT(*) FROM biz_task GROUP BY task_scope;
SELECT task_scope, COUNT(*) FROM biz_report GROUP BY task_scope;

-- 3) 行数与迁移前备份一致（示例值按备份当天实际数替换）
SELECT (SELECT COUNT(*) FROM biz_task)      AS tasks,
       (SELECT COUNT(*) FROM biz_report)    AS reports,
       (SELECT COUNT(*) FROM biz_task_log)  AS logs;

-- 4) 非法取值应为 0
SELECT COUNT(*) FROM biz_task   WHERE task_scope NOT IN ('COMPANY','PERSONAL');
SELECT COUNT(*) FROM biz_report WHERE task_scope NOT IN ('COMPANY','PERSONAL');

-- 5) 父子关系完好（孤儿父引用应与迁移前一致，不新增）
SELECT COUNT(*) FROM biz_task c
 WHERE c.parent_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM biz_task p WHERE p.id = c.parent_id);
```

应用侧核对：登录后分别切换「公司任务 / 个人任务」查看任务页（存量应全在公司任务）、
首页 KPI 与 AI 洞察范围一致、生成一次日报确认报告记录的范围列。

## 五、重复执行（幂等）保证

- `14` 对两张表分别做「列存在则跳过」（PREPARE/EXECUTE 分支），重复执行无副作用；
- **绝不执行** `UPDATE ... SET task_scope='COMPANY'` 全表回写——迁移后新建的 PERSONAL
  任务不会被改回 COMPANY（本地已实测：插入 PERSONAL 行后重跑，值保持不变）；
- 唯一的 UPDATE 只修空串（`WHERE task_scope = ''`），正常数据永不命中。
