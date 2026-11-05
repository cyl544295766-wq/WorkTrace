-- ============================================================
-- 12_drop_unused.sql  裁剪 T02 契约分片中未实现的表（功能定稿后收敛）
-- ============================================================
-- 02~08 分片是 PRD 全量设计的表；当前应用实际只用 5 张：
--   sys_user(10)、biz_task / biz_report(11)、
--   SPRING_SESSION / SPRING_SESSION_ATTRIBUTES(01a)
-- 其余 14 张代码零引用（无 @TableName 实体、无 Mapper XML、无原生 SQL）：
--   task, tag, task_tag, note, note_task_rel, time_entry,
--   daily_stat, weekly_stat, report, notification, audit_log,
--   export_job, sys_lock, sys_config
-- 于本文件统一 DROP，使全新初始化与线上一致（线上已于 2026-11-05 同步删除，
-- 删前快照见服务器 /home/ubuntu/worktrace/db-backups/pre-drop-unused）。
-- 表级授权随 DROP 自动回收，wt_app 对剩余 5 张表的授权不受影响。
-- 若未来实现某表功能：从对应分片重建后再删除本文件中的表名即可。
-- ============================================================

USE work_system;

DROP TABLE IF EXISTS
    task, tag, task_tag,
    note, note_task_rel, time_entry,
    daily_stat, weekly_stat,
    report,
    notification, audit_log,
    export_job,
    sys_lock, sys_config;

-- ------------------------------------------------------------
-- 清理残留授权：MySQL 的 DROP **不会**自动回收表级授权，
-- 08 分片授出的 14 张表权限会继续挂在 SHOW GRANTS 里。
-- 直接条件 DELETE（幂等，可重复执行；REVOKE 对缺表/重复执行不友好）。
-- ------------------------------------------------------------
DELETE FROM mysql.tables_priv
 WHERE User = 'wt_app' AND Db = 'work_system' AND Table_name IN (
    'task', 'tag', 'task_tag', 'note', 'note_task_rel', 'time_entry',
    'daily_stat', 'weekly_stat', 'report', 'notification', 'audit_log',
    'export_job', 'sys_lock', 'sys_config');
DELETE FROM mysql.columns_priv
 WHERE User = 'wt_app' AND Db = 'work_system' AND Table_name IN (
    'task', 'tag', 'task_tag', 'note', 'note_task_rel', 'time_entry',
    'daily_stat', 'weekly_stat', 'report', 'notification', 'audit_log',
    'export_job', 'sys_lock', 'sys_config');
FLUSH PRIVILEGES;
