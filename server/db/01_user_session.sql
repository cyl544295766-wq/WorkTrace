-- ============================================================
-- 01_user_session.sql  WorkTrace · MySQL 8.0+
-- 只负责建库。用户表由 10_sys_user.sql 创建（sys_user）。
-- ============================================================
-- 建议在 my.cnf 中固定（三处时区对齐的第 3 处）
-- [mysqld]
-- default-time-zone='+08:00'
-- character-set-server=utf8mb4
-- collation-server=utf8mb4_0900_ai_ci
-- innodb_buffer_pool_size=2G
-- max_connections=200

CREATE DATABASE IF NOT EXISTS work_system
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE work_system;
