-- 经验教训（组织资产）：跨项目沉淀与检索；可选关联来源（风险/决策/迭代）。
CREATE TABLE lesson (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    project_id  BIGINT       NOT NULL,
    category    VARCHAR(32)  NOT NULL COMMENT 'WELL/IMPROVE/PROCESS/TECH/SUPPLY/OTHER',
    title       VARCHAR(255) NOT NULL,
    detail      TEXT         NULL,
    source_type VARCHAR(32)  NULL COMMENT 'WORK_ITEM/DECISION/ITERATION',
    source_id   BIGINT       NULL,
    created_by  BIGINT       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_lesson_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='经验教训';
