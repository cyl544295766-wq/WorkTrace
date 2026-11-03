-- ============================================================
-- 03_note_time.sql  笔记（D-01）与时间记录（E-01）
-- ============================================================
USE worktrace;

CREATE TABLE note (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id   BIGINT       NOT NULL,
    title      VARCHAR(255) NOT NULL DEFAULT '',
    content_md MEDIUMTEXT   DEFAULT NULL,
    tag_id     BIGINT       DEFAULT NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_note_owner_updated (owner_id, deleted, updated_at),
    KEY idx_note_owner_tag     (owner_id, deleted, tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='正文用 MEDIUMTEXT（16MB），1 万条规模无需分表';

-- ------------------------------------------------------------ 笔记 ↔ 任务（D-01 关联）
CREATE TABLE note_task_rel (
    note_id    BIGINT      NOT NULL,
    task_id    BIGINT      NOT NULL,
    owner_id   BIGINT      NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (note_id, task_id),
    KEY idx_ntr_task (task_id, owner_id),
    KEY idx_ntr_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------ 时间记录
CREATE TABLE time_entry (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    owner_id     BIGINT      NOT NULL,
    task_id      BIGINT      DEFAULT NULL,
    tag_id       BIGINT      DEFAULT NULL,
    -- ★ r4：写入时快照标签名。让「一条时间记录」自描述，不依赖 tag 表是否还在/是否改名。
    --   展示优先级：单条记录只读本列；聚合（by_tag_json）读 tag.name 再退回本列。见 §5.4.2
    tag_name_snapshot VARCHAR(32) DEFAULT NULL COMMENT '写入时刻的标签名快照；tag_id 为 NULL 时也为 NULL',
    started_at   DATETIME(3) NOT NULL,
    ended_at     DATETIME(3) DEFAULT NULL COMMENT 'NULL = 正在计时',
    duration_min INT         DEFAULT NULL COMMENT '结算后写入；聚合直接 SUM 它',
    source       VARCHAR(16) NOT NULL COMMENT 'MANUAL / TIMER',
    memo         VARCHAR(500) DEFAULT NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted      TINYINT     NOT NULL DEFAULT 0,
    -- ★★ 生成列 + 唯一索引：一个用户同一时刻只能有 1 条进行中的计时
    --    这让"断网/刷新后恢复计时"（E-01）成为不可能出错的结构保证
    running_owner BIGINT GENERATED ALWAYS AS
        (IF(ended_at IS NULL AND deleted = 0, owner_id, NULL)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_te_running (running_owner),
    KEY idx_te_owner_started (owner_id, deleted, started_at),
    KEY idx_te_owner_task    (owner_id, deleted, task_id),
    KEY idx_te_owner_tag     (owner_id, deleted, tag_id),
    CONSTRAINT ck_te_range   CHECK (ended_at IS NULL OR ended_at > started_at),
    CONSTRAINT ck_te_source  CHECK (source IN ('MANUAL','TIMER')),
    CONSTRAINT ck_te_duration CHECK (duration_min IS NULL OR duration_min BETWEEN 1 AND 480),
    CONSTRAINT ck_te_manual_precision CHECK (
        source <> 'MANUAL' OR duration_min IS NULL OR duration_min % 15 = 0
    ),
    CONSTRAINT ck_te_ref CHECK (task_id IS NOT NULL OR tag_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='uk_te_running 由生成列实现（MySQL 无部分索引）';
