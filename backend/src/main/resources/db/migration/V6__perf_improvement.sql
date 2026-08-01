-- 效能指标体系与改进闭环：指标目标值 + 改进项（数据监控 → 改进方案 → 跟踪落地）
CREATE TABLE metric_target (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    project_id   BIGINT        NOT NULL,
    metric_key   VARCHAR(64)   NOT NULL,
    target_value DECIMAL(10,2) NOT NULL,
    updated_by   BIGINT        NULL,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mt_proj_key (project_id, metric_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='效能指标目标值';

CREATE TABLE improvement (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    project_id     BIGINT        NOT NULL,
    code           VARCHAR(32)   NOT NULL,
    metric_key     VARCHAR(64)   NULL COMMENT '关联指标（可空=通用流程改进）',
    title          VARCHAR(256)  NOT NULL,
    measure        TEXT          NULL COMMENT '改进措施',
    baseline_value DECIMAL(10,2) NULL COMMENT '发起时指标基线值',
    target_value   DECIMAL(10,2) NULL COMMENT '改进目标值',
    result_value   DECIMAL(10,2) NULL COMMENT '验证时实际值',
    conclusion     VARCHAR(512)  NULL COMMENT '验证结论',
    due_date       DATE          NULL,
    status         VARCHAR(16)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/DOING/DONE/VERIFIED',
    created_by     BIGINT NULL, created_at DATETIME NULL,
    updated_by     BIGINT NULL, updated_at DATETIME NULL,
    deleted        TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_imp_proj (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程改进项（指标驱动，闭环跟踪）';
