-- ============================================================
-- 08_sys_seed.sql  系统锁 / 配置 / 初始数据（07 已被 export_job 占用）
-- ============================================================
USE worktrace;

CREATE TABLE sys_lock (
    lock_name    VARCHAR(64) NOT NULL,
    locked_until DATETIME(3) DEFAULT NULL,
    locked_by    VARCHAR(128) DEFAULT NULL COMMENT 'hostname:pid:thread',
    updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (lock_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='定时任务防重（PRD 7.5 要求 @Scheduled + 数据库行锁）';

CREATE TABLE sys_config (
    cfg_key    VARCHAR(64)   NOT NULL,
    cfg_value  VARCHAR(1024) NOT NULL,
    value_type VARCHAR(16)   NOT NULL DEFAULT 'STRING' COMMENT 'STRING / INT / BOOL / TIME',
    remark     VARCHAR(255)  DEFAULT NULL,
    updated_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (cfg_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- seed：系统参数
INSERT INTO sys_config (cfg_key, cfg_value, value_type, remark) VALUES
 ('week_start_dow',        '1',           'INT',    '周起始日：1=周一（ISO）'),
 ('remind_due_time',       '09:00',       'TIME',   '截止日当天提醒时刻'),
 ('digest_time',           '18:00',       'TIME',   '每日未完成小结时刻'),
 ('postpone_threshold',    '2',           'INT',    '改期 >= 此值标记反复拖延（P1 G-03）'),
 ('overdue_threshold_days','3',           'INT',    '逾期 >= 此天数标记反复拖延（P1 G-03）'),
 ('global_timezone',       'Asia/Shanghai','STRING','v1.1 全局时区，app_user.timezone 必须等于它'),
 ('account_source',        'BOTH',        'STRING', '建号方式：INVITE=仅邀请码 / ADMIN_ONLY=仅管理员建号 / BOTH=两者皆可。对应待确认 Q2，默认 BOTH，改配置即生效，无需改代码'),
 ('invite_code_ttl_hours', '24',          'INT',    '邀请码有效期（account_source=ADMIN_ONLY 时不生效）'),
 ('login_max_fail',        '5',           'INT',    '连续失败锁定阈值'),
 ('login_lock_minutes',    '10',          'INT',    '锁定时长'),
 ('stat_rebuild_days',     '60',          'INT',    '每日兜底重算回溯天数'),
 ('export_ttl_hours',      '24',          'INT',    '导出产物保留时长'),
 ('backup_keep_days',      '7',           'INT',    '本地备份保留份数');

-- ---------- seed：定时任务锁记录（预插入，保证条件 UPDATE 能命中）
INSERT INTO sys_lock (lock_name) VALUES
  ('stat_recalc_full'), ('reminder_due'), ('digest_daily'), ('weekly_missing'), ('backup_daily');

-- ---------- seed：首个管理员 admin / Init@123456（上线后首登强制改密）
-- 哈希由 BCrypt strength=10 实际生成；不是文档里的占位示例。
INSERT INTO app_user (username, password_hash, nickname, role, status, must_change_pwd)
VALUES ('admin',
        '$2b$10$RLkFBKwjoKs7U4nSt9uSX.aW0jbudD1Cl4jpr4M6dz.BovhbjkFWC',
        '系统管理员', 'ADMIN', 'ACTIVE', 1);

-- ------------------------------------------------------------ 权限收敛（MySQL 版）
-- 应用账号：业务表全 DML，审计表只追加。
--
-- 与 design.md §4.8 的差异说明（必须保留）：
-- MySQL 的权限是"加法模型"，不存在负授权。先 GRANT ON worktrace.* 再
-- REVOKE ON worktrace.audit_log 会报 ERROR 1147（没有这条表级授权可撤销）。
-- 因此这里改为逐表 GRANT：除 audit_log 外的表授 SELECT/INSERT/UPDATE/DELETE，
-- audit_log 只授 SELECT/INSERT，才能真实满足 T02 验收点 9。
CREATE USER 'wt_app'@'127.0.0.1' IDENTIFIED BY 'WorkTrace_App_2026!';

GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.app_user        TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.invite_code     TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.task            TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.tag             TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.task_tag        TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.note            TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.note_task_rel   TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.time_entry      TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.daily_stat      TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.weekly_stat     TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.report          TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.notification    TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.export_job      TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.sys_lock        TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.sys_config      TO 'wt_app'@'127.0.0.1';

-- ★ 审计表收回写权限：只 SELECT, INSERT
GRANT SELECT, INSERT ON worktrace.audit_log TO 'wt_app'@'127.0.0.1';

-- ★ 会话表需要 UPDATE（刷新 LAST_ACCESS_TIME）+ DELETE（过期清理）
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.SPRING_SESSION TO 'wt_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON worktrace.SPRING_SESSION_ATTRIBUTES TO 'wt_app'@'127.0.0.1';

FLUSH PRIVILEGES;
-- 迁移/备份账号另行创建，仅部署时使用（不做应用连接账号）
