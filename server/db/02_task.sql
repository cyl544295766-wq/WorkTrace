-- ============================================================
-- 02_task.sql  待办任务（C-01 ~ C-04）
-- ============================================================
USE work_system;

CREATE TABLE task (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id       BIGINT       NOT NULL,
    parent_id      BIGINT       DEFAULT NULL COMMENT '子任务；NULL = 顶层（层级最多 2）',
    title          VARCHAR(255) NOT NULL COMMENT '唯一必填字段',
    description    TEXT         DEFAULT NULL,
    priority       TINYINT      NOT NULL DEFAULT 2 COMMENT '0=P0 1=P1 2=P2 3=P3',
    status         VARCHAR(16)  NOT NULL DEFAULT 'TODO' COMMENT 'TODO / DOING / DONE',
    due_at         DATETIME(3)  DEFAULT NULL COMMENT '可空；仅选日期时取当日 23:59:59.000',
    completed_at   DATETIME(3)  DEFAULT NULL COMMENT '完成时刻（聚合依据）',
    started_at     DATETIME(3)  DEFAULT NULL COMMENT '最近一次进入 DOING 的时刻',
    postpone_count SMALLINT     NOT NULL DEFAULT 0 COMMENT '改期次数，供拖延识别',
    estimate_min   INT          DEFAULT NULL,
    sort_no        INT          NOT NULL DEFAULT 0,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_task_owner_status_due (owner_id, deleted, status, due_at),
    KEY idx_task_owner_completed  (owner_id, deleted, completed_at),
    KEY idx_task_owner_created    (owner_id, deleted, created_at),
    KEY idx_task_owner_started    (owner_id, deleted, started_at),
    KEY idx_task_owner_due_open   (owner_id, deleted, status, due_at, completed_at),
    KEY idx_task_owner_parent     (owner_id, deleted, parent_id),
    CONSTRAINT ck_task_priority CHECK (priority BETWEEN 0 AND 3),
    CONSTRAINT ck_task_status   CHECK (status IN ('TODO','DOING','DONE')),
    CONSTRAINT ck_task_done     CHECK ((status = 'DONE') = (completed_at IS NOT NULL))
    -- 设计文档的 ck_task_no_self 在 MySQL 8 不可执行：
    -- ERROR 3818，CHECK 约束不能引用 AUTO_INCREMENT 列。自引用父任务由 Service 层校验。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------ 标签（个人私有）
CREATE TABLE tag (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    owner_id   BIGINT      NOT NULL,
    name       VARCHAR(32) NOT NULL,
    color      VARCHAR(16) DEFAULT NULL COMMENT '前端色板 key，如 teal-2',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted    TINYINT     NOT NULL DEFAULT 0,
    name_active VARCHAR(32) GENERATED ALWAYS AS (IF(deleted = 0, name, NULL)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag_owner_name_active (owner_id, name_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------ 任务-标签
-- ★ 冗余 owner_id：让租户插件在 JOIN 时对两侧表分别注入条件
CREATE TABLE task_tag (
    task_id  BIGINT NOT NULL,
    tag_id   BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    PRIMARY KEY (task_id, tag_id),
    KEY idx_task_tag_tag_owner (tag_id, owner_id),
    KEY idx_task_tag_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
