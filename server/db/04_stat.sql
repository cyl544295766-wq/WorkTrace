-- ============================================================
-- 04_stat.sql  预聚合（F-01 / G-01 唯一数据源）
-- 写入方：只有 StatAggregatorService；读取方：报表 / 周报 / 时间汇总
-- ============================================================
USE worktrace;

CREATE TABLE daily_stat (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    owner_id      BIGINT      NOT NULL,
    stat_date     DATE        NOT NULL COMMENT 'Asia/Shanghai 自然日',
    timezone      VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    completed_cnt INT         NOT NULL DEFAULT 0,
    created_cnt   INT         NOT NULL DEFAULT 0,
    started_cnt   INT         NOT NULL DEFAULT 0,
    overdue_cnt   INT         NOT NULL DEFAULT 0 COMMENT '当日 24:00 时点积压数',
    total_minutes INT         NOT NULL DEFAULT 0,
    by_tag_json   JSON        NOT NULL COMMENT '结构见 §4.6 说明；写入方显式赋值，不设 DEFAULT',
    calc_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    calc_ver      INT         NOT NULL DEFAULT 1 COMMENT '每次重算 +1，排查漂移',
    PRIMARY KEY (id),
    -- ★ ON DUPLICATE KEY UPDATE 生效的唯一前提
    UNIQUE KEY uk_daily_stat (owner_id, stat_date),
    KEY idx_daily_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='日预聚合；幂等 UPSERT；可随时全量重算';

CREATE TABLE weekly_stat (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id        BIGINT       NOT NULL,
    week_start      DATE         NOT NULL COMMENT '周一（Java WeekFields.ISO 算出后传入）',
    week_end        DATE         NOT NULL COMMENT '周日',
    iso_year        SMALLINT     NOT NULL,
    iso_week        SMALLINT     NOT NULL,
    timezone        VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
    completed_cnt   INT          NOT NULL DEFAULT 0 COMMENT '= SUM(daily_stat.completed_cnt)',
    created_cnt     INT          NOT NULL DEFAULT 0 COMMENT '= SUM(daily_stat.created_cnt)',
    overdue_end_cnt INT          NOT NULL DEFAULT 0 COMMENT '= 周日那天的 daily_stat.overdue_cnt（期末积压）',
    total_minutes   INT          NOT NULL DEFAULT 0,
    active_days     SMALLINT     NOT NULL DEFAULT 0 COMMENT '有留痕的天数（G1 指标③）',
    -- ★ r3 双指标：可空是语义，不是疏忽。NULL = 分母为 0 = 不可计算，前端渲染「—」
    clearance_rate  DECIMAL(5,2) NULL COMMENT '消化率 = completed/(completed+overdue_end)*100；分母0→NULL',
    throughput_rate DECIMAL(5,2) NULL COMMENT '产出率 = completed/created*100；created=0→NULL；可>100',
    by_tag_json     JSON         NOT NULL,
    calc_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    calc_ver        INT          NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_weekly_stat (owner_id, week_start),
    KEY idx_weekly_stat_week (week_start),
    KEY idx_weekly_stat_owner_week (owner_id, week_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='周预聚合；只从 daily_stat 汇总，绝不扫 task 明细 —— 这是"同日数据差=0"的结构保证';
