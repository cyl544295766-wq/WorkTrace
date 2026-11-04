-- ============================================================
-- 06_notify_audit.sql  站内通知（H-01）与审计
-- ============================================================
USE work_system;

CREATE TABLE notification (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    type         VARCHAR(32)  NOT NULL COMMENT 'DUE_TODAY / DAILY_DIGEST / WEEKLY_MISSING / SYSTEM / BACKUP_ALERT',
    biz_key      VARCHAR(128) NOT NULL COMMENT '幂等键：{TYPE}:{bizId}:{yyyy-MM-dd}',
    title        VARCHAR(255) NOT NULL,
    payload_json JSON         NOT NULL COMMENT '仅 id + 数字，禁止放笔记/描述',
    read_at      DATETIME(3)  DEFAULT NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- ★ 幂等：同一任务同类提醒每日最多 1 条（H-01 验收），由唯一键物理保证
    UNIQUE KEY uk_notification_biz (user_id, biz_key),
    KEY idx_notification_unread (user_id, read_at, created_at),
    KEY idx_notification_all (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 追加写；应用账号仅 GRANT SELECT, INSERT —— 物理不可改不可删
-- r2 收窄：只记管理操作（账号变更 / 改密 / 配置 / 备份 / 统计重算），不记业务读写与跨用户读取
CREATE TABLE audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    actor_id    BIGINT       DEFAULT NULL,
    actor_role  VARCHAR(16)  DEFAULT NULL,
    action      VARCHAR(32)  NOT NULL COMMENT 'ACCOUNT_CREATED / ACCOUNT_DISABLED / ACCOUNT_ROLE_CHANGED / PWD_RESET / PWD_CHANGED / CONFIG_CHANGED / BACKUP_OK / BACKUP_FAILED / STAT_REBUILD / EXPORT_CREATED',
    target_type VARCHAR(32)  DEFAULT NULL COMMENT 'USER / SYSTEM / CONFIG / EXPORT_JOB（r2：已删除 TASK/NOTE/TIME_ENTRY）',
    target_id   BIGINT       DEFAULT NULL,
    result      VARCHAR(16)  NOT NULL DEFAULT 'ALLOW' COMMENT 'ALLOW / DENY（DENY 仅用于账号类操作，如"不能停用自己"）',
    ip          VARCHAR(64)  DEFAULT NULL,
    ua          VARCHAR(255) DEFAULT NULL,
    detail_json JSON         NOT NULL COMMENT '禁止放笔记正文/描述/token',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_audit_actor (actor_id, created_at),
    KEY idx_audit_action_time (action, created_at),
    KEY idx_audit_target (target_type, target_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='管理操作审计；只追加；应用账号无 UPDATE/DELETE 权限。r2 删除 owner_id 列（无跨用户读取场景）';
