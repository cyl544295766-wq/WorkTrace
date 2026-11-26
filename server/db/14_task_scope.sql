-- ============================================================
-- 14_task_scope.sql  biz_task / biz_report 新增 task_scope 任务范围列（幂等）
-- ============================================================
-- 2026-11-26：任务管理拆分「公司任务 / 个人任务」。
--   * COMPANY  = 公司任务；存量任务迁移后全部归入 COMPANY；
--   * PERSONAL = 个人任务；迁移完成后由用户主动标记产生。
-- 11_biz_tables.sql 的 CREATE TABLE 已含此两列（全新库直接建出）；
-- 本文件给存量库一次性补齐，可重复执行。
--
-- 幂等要点（重复执行安全）：
--   1) ADD COLUMN 仅在列缺失时执行（PREPARE/EXECUTE 分支），重复执行不触碰已有行；
--   2) 绝不执行无条件 `UPDATE ... SET task_scope='COMPANY'`——那会把迁移后新建的
--      PERSONAL 任务改回 COMPANY。这里的 UPDATE 只修补空串（NOT NULL 列不会出现 NULL，
--      正常数据永远不命中），对 PERSONAL 行零影响；
--   3) 不删除、不重建、不 TRUNCATE 任何业务表，任务 id / 父子关系 / 重点标记 /
--      状态 / 日期与跟进记录关联全部原样保留。
--
-- 执行顺序：01 → … → 13 → 14（14 依赖 13 的 is_key 列做列定位）。
-- ============================================================

USE work_system;

-- ── 1) biz_task.task_scope ──
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = 'work_system'
       AND TABLE_NAME   = 'biz_task'
       AND COLUMN_NAME  = 'task_scope'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE biz_task ADD COLUMN task_scope VARCHAR(16) NOT NULL DEFAULT ''COMPANY'' COMMENT ''任务范围 COMPANY公司任务/PERSONAL个人任务'' AFTER is_key',
    'SELECT ''biz_task.task_scope already exists, skip'' AS notice');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 防御性修补：只修空串，永不触碰 PERSONAL（存量行由 DEFAULT 已落 COMPANY）
UPDATE biz_task SET task_scope = 'COMPANY' WHERE task_scope = '';

-- ── 2) biz_report.task_scope（报告记录保存生成范围；历史报告按 COMPANY 归类）──
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = 'work_system'
       AND TABLE_NAME   = 'biz_report'
       AND COLUMN_NAME  = 'task_scope'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE biz_report ADD COLUMN task_scope VARCHAR(16) NOT NULL DEFAULT ''COMPANY'' COMMENT ''生成范围 COMPANY公司任务/PERSONAL个人任务'' AFTER report_type',
    'SELECT ''biz_report.task_scope already exists, skip'' AS notice');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE biz_report SET task_scope = 'COMPANY' WHERE task_scope = '';

-- ── 迁移后核对（只读，人工执行）──
-- SELECT task_scope, COUNT(*) FROM biz_task GROUP BY task_scope;        -- 首次迁移后仅 COMPANY
-- SELECT task_scope, COUNT(*) FROM biz_report GROUP BY task_scope;      -- 历史报告仅 COMPANY
-- SELECT COUNT(*) FROM biz_task WHERE task_scope NOT IN ('COMPANY','PERSONAL');  -- 应为 0
