-- 范围基线：把"某时点承诺了什么"固化为可对比的快照（只增不删，与 decision 同哲学）。
-- 基线范围 = 需求域三类工作项（CAPABILITY/REQUIREMENT/STORY）；明细列全部冻结。
CREATE TABLE baseline (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    project_id    BIGINT       NOT NULL,
    name          VARCHAR(64)  NOT NULL COMMENT 'B1/B2 或 B-{gateCode}',
    source        VARCHAR(16)  NOT NULL COMMENT 'DCP=评审通过自动 / MANUAL=手动',
    stage_gate_id BIGINT       NULL,
    decision_id   BIGINT       NULL COMMENT 'DCP 来源时关联的决策',
    item_count    INT          NOT NULL DEFAULT 0,
    created_by    BIGINT       NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_bl_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='范围基线';

CREATE TABLE baseline_item (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    baseline_id  BIGINT       NOT NULL,
    work_item_id BIGINT       NOT NULL,
    code         VARCHAR(32)  NOT NULL,
    title        VARCHAR(255) NOT NULL,
    type         VARCHAR(24)  NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    estimate     VARCHAR(32)  NULL,
    planned_date DATE         NULL COMMENT '冻结当时的 forecast_date',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bli (baseline_id, work_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='基线明细（冻结列）';
