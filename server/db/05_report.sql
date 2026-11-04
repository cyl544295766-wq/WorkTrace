-- ============================================================
-- 05_report.sql  日报/周报（F-01 / F-02）
-- ============================================================
USE work_system;

CREATE TABLE report (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    owner_id      BIGINT      NOT NULL,
    type          VARCHAR(16) NOT NULL COMMENT 'DAILY / WEEKLY',
    period_start  DATE        NOT NULL,
    period_end    DATE        NOT NULL,
    content_md    MEDIUMTEXT  NOT NULL COMMENT '渲染后的完整 Markdown',
    sections_json JSON        NOT NULL COMMENT '手写四段：{summary,nextPlan,risk,support}',
    auto_ratio    SMALLINT    NOT NULL DEFAULT 0 COMMENT '自动聚合字数占比 0-100（G2 指标①）',
    status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / FINAL',
    revision      SMALLINT    NOT NULL DEFAULT 1 COMMENT '定稿后修改 → 新 revision（只增不改）',
    submitted_at  DATETIME(3) DEFAULT NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report (owner_id, type, period_start, revision),
    KEY idx_report_owner (owner_id, deleted, type, period_start),
    KEY idx_report_final (type, period_start, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
