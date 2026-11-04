-- ============================================================
-- 07_export.sql  数据导出任务（I-01，r2 新增 P0）
-- ============================================================
USE work_system;

CREATE TABLE export_job (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id   BIGINT       NOT NULL,
    scope      VARCHAR(16)  NOT NULL DEFAULT 'ALL' COMMENT 'ALL / TASKS / NOTES / TIME / REPORTS',
    status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / DONE / FAILED',
    file_path  VARCHAR(512) DEFAULT NULL COMMENT '产物绝对路径，仅服务端使用，不出现在任何响应里',
    file_size  BIGINT       DEFAULT NULL,
    row_count  INT          DEFAULT NULL COMMENT '导出的记录总条数（给用户看"共 N 条"）',
    error_msg  VARCHAR(500) DEFAULT NULL COMMENT 'FAILED 时的简要原因（不含堆栈）',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at DATETIME(3)  DEFAULT NULL,
    finished_at DATETIME(3) DEFAULT NULL,
    expires_at DATETIME(3)  DEFAULT NULL COMMENT '产物过期时刻 = finished_at + 24h',
    PRIMARY KEY (id),
    KEY idx_export_owner_time (owner_id, created_at),
    KEY idx_export_status (status, created_at),
    KEY idx_export_expires (expires_at),
    CONSTRAINT ck_export_scope  CHECK (scope  IN ('ALL','TASKS','NOTES','TIME','REPORTS')),
    CONSTRAINT ck_export_status CHECK (status IN ('PENDING','RUNNING','DONE','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='异步导出任务；单用户同时最多 1 条 PENDING/RUNNING（由 Service 校验 + 唯一索引兜底见下）';

-- 同一用户同时只允许一个未完成任务（MySQL 无部分索引 → 生成列，同 §4.1 手法）
ALTER TABLE export_job
  ADD COLUMN active_owner BIGINT GENERATED ALWAYS AS
      (IF(status IN ('PENDING','RUNNING'), owner_id, NULL)) STORED,
  ADD UNIQUE KEY uk_export_active (active_owner);
