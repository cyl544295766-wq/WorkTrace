-- ============================================================
-- 13_task_is_key.sql  biz_task 新增 is_key 重点标记列（幂等）
-- ============================================================
-- 2026-11-06：任务管理「重点」按钮 → 首页「重点任务」卡。
-- 11_biz_tables.sql 的 CREATE TABLE 已含此列（全新库直接建出）；
-- 本文件给存量库一次性补齐，可重复执行（列已存在则跳过）。
-- ============================================================

USE work_system;

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = 'work_system'
       AND TABLE_NAME   = 'biz_task'
       AND COLUMN_NAME  = 'is_key'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE biz_task ADD COLUMN is_key TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''重点标记 1=重点（首页重点任务展示） 0=普通'' AFTER category',
    'SELECT ''is_key already exists, skip'' AS notice');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
