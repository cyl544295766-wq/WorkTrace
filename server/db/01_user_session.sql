-- ============================================================
-- 01_user_session.sql  WorkTrace v1.1 · MySQL 8.0+
-- 库、账号、邀请码
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

CREATE TABLE app_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    username        VARCHAR(64)  NOT NULL,
    password_hash   VARCHAR(100) NOT NULL COMMENT 'BCrypt strength=10，禁止出现在任何日志/响应',
    nickname        VARCHAR(64)  DEFAULT NULL,
    role            VARCHAR(16)  NOT NULL DEFAULT 'USER'   COMMENT 'v1.1: USER / ADMIN（已删除 MANAGER）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    must_change_pwd TINYINT(1)   NOT NULL DEFAULT 0        COMMENT '首登/重置后强制改密',
    timezone        VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'v1.1: 强制等于全局时区，仅用于展示',
    invite_code_id  BIGINT       DEFAULT NULL,
    pwd_updated_at  DATETIME(3)  DEFAULT NULL,
    last_login_at   DATETIME(3)  DEFAULT NULL,
    login_fail_cnt  SMALLINT     NOT NULL DEFAULT 0,
    locked_until    DATETIME(3)  DEFAULT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted         TINYINT      NOT NULL DEFAULT 0,
    -- ★ 软删除 + 唯一：MySQL 无部分索引，用存储生成列（唯一索引允许多 NULL）
    username_active VARCHAR(64)  GENERATED ALWAYS AS (IF(deleted = 0, username, NULL)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username_active (username_active),
    KEY idx_user_status (status, deleted),
    KEY idx_user_locked (locked_until),
    CONSTRAINT ck_user_role   CHECK (role   IN ('USER','ADMIN')),
    CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='账号；role 两级';

-- ------------------------------------------------------------ 邀请码（A-02）
CREATE TABLE invite_code (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    code       VARCHAR(32) NOT NULL,
    created_by BIGINT      NOT NULL,
    used_by    BIGINT      DEFAULT NULL,
    used_at    DATETIME(3) DEFAULT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_invite_code (code),
    KEY idx_invite_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='一次性；24h 过期；used_by 非空即已用';

