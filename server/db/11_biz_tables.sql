-- ============================================================
-- 11_biz_tables.sql  业务表：biz_task / biz_report（应用实际读写的表）
-- ============================================================
-- 01~10 是契约分片（task/report 等为 PRD 全量设计表），而应用代码
-- @TableName 指向的是 biz_task / biz_report。此前这两张表只在开发机
-- 手工创建，全新环境会缺表——此文件把它们纳入可复现初始化。
-- 字典序在 09（账号改 '%'）、10（sys_user）之后，GRANT 目标写 'wt_app'@'%'。
-- ============================================================

USE work_system;

CREATE TABLE IF NOT EXISTS biz_task (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    title         VARCHAR(100) NOT NULL,
    content       TEXT,
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待办 1进行中 2已完成 3已延期',
    priority      TINYINT      NOT NULL DEFAULT 2 COMMENT '1低 2中 3高',
    plan_date     DATE         DEFAULT NULL,
    actual_hours  DECIMAL(8,2) DEFAULT NULL,
    create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_biz_task_user (user_id, status),
    KEY idx_biz_task_plan (user_id, plan_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='工作任务';

CREATE TABLE IF NOT EXISTS biz_report (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    report_type VARCHAR(16) NOT NULL COMMENT 'DAILY/WEEKLY/MONTHLY',
    start_date  DATE        NOT NULL,
    end_date    DATE        NOT NULL,
    content     LONGTEXT    COMMENT 'AI 生成的 Markdown',
    ai_model    VARCHAR(64) DEFAULT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_biz_report_user (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='AI 工作报告';

GRANT SELECT, INSERT, UPDATE, DELETE ON work_system.biz_task   TO 'wt_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON work_system.biz_report TO 'wt_app'@'%';

FLUSH PRIVILEGES;
