-- ============================================================
-- 10_sys_user.sql  登录模块：sys_user 表 + 演示数据 + 账号授权
-- ============================================================
-- 文件名字典序位于 09_docker_app_user.sql 之后，此时：
--   * wt_app 账号已被 08 创建、被 09 重命名为 'wt_app'@'%'；
--   * 因此 GRANT 目标只写 'wt_app'@'%'。
-- ============================================================

USE work_system;

CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    username    VARCHAR(64)  NOT NULL COMMENT '登录名',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希；演示数据为明文（代码端兼容直接比对）',
    nickname    VARCHAR(64)  DEFAULT NULL,
    create_time DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='登录会话演示用户';

GRANT SELECT, INSERT, UPDATE, DELETE ON work_system.sys_user TO 'wt_app'@'%';

-- 演示数据：admin / admin123（明文，代码端走"直接比对"分支；生产请改存 BCrypt 哈希）
INSERT INTO sys_user (username, password, nickname) VALUES
    ('admin', 'admin123', '管理员')
AS new ON DUPLICATE KEY UPDATE nickname = new.nickname;

FLUSH PRIVILEGES;
