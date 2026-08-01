-- =====================================================================
-- IPD 敏捷数字化工具箱 · 第一版核心数据模型（阶段1 · T201）
-- 对应 docs/01-对象模型与字段.md
-- =====================================================================

-- ---------- RBAC ----------
CREATE TABLE sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    enabled       TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户';

CREATE TABLE sys_role (
    id   BIGINT      NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统角色';

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联';

-- ---------- 项目 / 版本 / 阶段 ----------
CREATE TABLE project (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    code             VARCHAR(32)  NOT NULL,
    name             VARCHAR(128) NOT NULL,
    goal             TEXT         NULL,
    manager_id       BIGINT       NULL,
    lifecycle_status VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    seq_counter      INT          NOT NULL DEFAULT 0 COMMENT '编号序号计数器(按类型另建映射见 code_seq)',
    created_by       BIGINT       NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by       BIGINT       NULL,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目';

-- 编号序号表：按 项目+类型缩写 维护自增序号，保证 code 生成并发安全
CREATE TABLE code_seq (
    project_id BIGINT      NOT NULL,
    type_abbr  VARCHAR(8)  NOT NULL,
    next_val   INT         NOT NULL DEFAULT 1,
    PRIMARY KEY (project_id, type_abbr)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务编号序号';

CREATE TABLE product_version (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(32)  NOT NULL,
    project_id  BIGINT       NOT NULL,
    model       VARCHAR(128) NULL,
    version_no  VARCHAR(64)  NOT NULL,
    baseline    VARCHAR(128) NULL,
    created_by  BIGINT       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT       NULL,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_code (code),
    KEY idx_version_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品版本';

CREATE TABLE stage_gate (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(32)  NOT NULL,
    project_id  BIGINT       NOT NULL,
    stage_name  VARCHAR(128) NOT NULL,
    gate_name   VARCHAR(128) NOT NULL,
    seq         INT          NOT NULL DEFAULT 0,
    created_by  BIGINT       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT       NULL,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gate_code (code),
    KEY idx_gate_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IPD阶段与DCP';

-- DCP 准入条件（也复用于阶段4跨职能准备度检查项，domain 区分）
CREATE TABLE gate_criterion (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    code              VARCHAR(32)  NOT NULL,
    project_id        BIGINT       NOT NULL,
    stage_gate_id     BIGINT       NULL,
    domain            VARCHAR(32)  NOT NULL COMMENT '技术/质量/供应/制造/商业/上市',
    criterion         VARCHAR(512) NOT NULL,
    judge_type        VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/RULE',
    owner_id          BIGINT       NULL,
    status            VARCHAR(24)  NOT NULL DEFAULT 'NOT_READY' COMMENT 'NOT_READY/PARTIAL/MET/WAIVED',
    evidence_req      VARCHAR(512) NULL,
    is_redline        TINYINT      NOT NULL DEFAULT 0,
    linked_risk_id    BIGINT       NULL,
    review_conclusion VARCHAR(16)  NULL COMMENT 'PASS/CONDITIONAL/REJECT',
    waiver_reason     VARCHAR(512) NULL,
    waiver_by         BIGINT       NULL,
    waiver_due        DATE         NULL,
    plan_date         DATE         NULL,
    forecast_date     DATE         NULL,
    is_readiness      TINYINT      NOT NULL DEFAULT 0 COMMENT '1=跨职能准备度检查项',
    created_by        BIGINT       NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        BIGINT       NULL,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gc_code (code),
    KEY idx_gc_gate (stage_gate_id),
    KEY idx_gc_project_domain (project_id, domain)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DCP准入条件/跨职能准备度检查项';

-- ---------- 统一工作项 ----------
CREATE TABLE work_item (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    code                VARCHAR(32)  NOT NULL,
    project_id          BIGINT       NOT NULL,
    product_version_id  BIGINT       NULL,
    type                VARCHAR(24)  NOT NULL COMMENT 'CAPABILITY/REQUIREMENT/STORY/TASK/DEFECT/RISK/CHANGE',
    title               VARCHAR(255) NOT NULL,
    description         TEXT         NULL,
    status              VARCHAR(32)  NOT NULL,
    owner_id            BIGINT       NULL,
    priority            VARCHAR(16)  NULL,
    iteration_id        BIGINT       NULL,
    baseline_date       DATE         NULL,
    forecast_date       DATE         NULL,
    acceptance_criteria TEXT         NULL,
    estimate            VARCHAR(32)  NULL COMMENT '估算(用于Ready守卫)',
    accepted_by         BIGINT       NULL,
    accepted_at         DATETIME     NULL,
    ext_fields          JSON         NULL COMMENT '类型差异字段:缺陷severity/风险mitigation,due/变更impact等',
    created_by          BIGINT       NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          BIGINT       NULL,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wi_code (code),
    KEY idx_wi_project_type (project_id, type),
    KEY idx_wi_iteration (iteration_id),
    KEY idx_wi_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一工作项';

-- 工作项状态变更时间线（供 Cycle Time / Age 指标）
CREATE TABLE work_item_status_log (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    work_item_id BIGINT      NOT NULL,
    from_status  VARCHAR(32) NULL,
    to_status    VARCHAR(32) NOT NULL,
    actor_id     BIGINT      NULL,
    reason       VARCHAR(512) NULL,
    at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_wsl_item (work_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作项状态变更时间线';

-- ---------- 迭代 ----------
CREATE TABLE iteration (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(32)  NOT NULL,
    project_id  BIGINT       NOT NULL,
    name        VARCHAR(128) NOT NULL,
    goal        VARCHAR(512) NULL,
    start_date  DATE         NULL,
    end_date    DATE         NULL,
    status      VARCHAR(24)  NOT NULL DEFAULT 'PLANNING' COMMENT 'PLANNING/ACTIVE/CLOSED',
    created_by  BIGINT       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT       NULL,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_iter_code (code),
    KEY idx_iter_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='迭代/Sprint';

-- ---------- 测试 ----------
CREATE TABLE test_case (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(32)  NOT NULL,
    project_id  BIGINT       NOT NULL,
    title       VARCHAR(255) NOT NULL,
    steps       TEXT         NULL,
    expected    TEXT         NULL,
    created_by  BIGINT       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT       NULL,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tc_code (code),
    KEY idx_tc_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测试用例';

CREATE TABLE test_run (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    code           VARCHAR(32) NOT NULL,
    project_id     BIGINT      NOT NULL,
    test_case_id   BIGINT      NOT NULL,
    result         VARCHAR(16) NOT NULL COMMENT 'PASS/FAIL/BLOCKED',
    actual         TEXT        NULL,
    run_version_id BIGINT      NULL,
    run_by         BIGINT      NULL,
    run_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    defect_id      BIGINT      NULL COMMENT '失败生成的缺陷',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tr_code (code),
    KEY idx_tr_case (test_case_id),
    KEY idx_tr_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测试执行';

-- ---------- 证据 ----------
CREATE TABLE evidence (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(32)  NOT NULL,
    project_id   BIGINT       NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    file_path    VARCHAR(512) NOT NULL,
    sha256       CHAR(64)     NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    mime         VARCHAR(128) NULL,
    uploaded_by  BIGINT       NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ev_code (code),
    KEY idx_ev_project (project_id),
    KEY idx_ev_sha (sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证据文件';

-- ---------- 追溯 ----------
CREATE TABLE trace_link (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    project_id  BIGINT      NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id   BIGINT      NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id   BIGINT      NOT NULL,
    relation    VARCHAR(24) NOT NULL COMMENT 'contributes_to/parent_of/implements/verifies/blocks/depends_on/changes/evidences/affects/released_in',
    created_by  BIGINT      NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trace (source_type, source_id, relation, target_type, target_id),
    KEY idx_trace_source (source_type, source_id),
    KEY idx_trace_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='追溯关系';

-- ---------- 决策（只增不改） ----------
CREATE TABLE decision (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    code             VARCHAR(32)  NOT NULL,
    project_id       BIGINT       NOT NULL,
    decision_type    VARCHAR(24)  NOT NULL COMMENT 'DCP/TECH/QUALITY/CHANGE',
    subject_type     VARCHAR(32)  NULL COMMENT '决策对象类型',
    subject_id       BIGINT       NULL,
    conclusion       VARCHAR(24)  NOT NULL COMMENT 'PASS/CONDITIONAL/REJECT/APPROVED/REJECTED',
    reason           TEXT         NULL,
    decided_by       BIGINT       NOT NULL,
    decided_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    snapshot         JSON         NULL COMMENT '决策时固化的准备度快照',
    linked_risk_id   BIGINT       NULL COMMENT '有条件通过绑定的遗留风险',
    commitment_due   DATE         NULL,
    prev_decision_id BIGINT       NULL COMMENT '修订指向的旧决策',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dec_code (code),
    KEY idx_dec_project (project_id),
    KEY idx_dec_subject (subject_type, subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正式决策';

-- ---------- 审计 ----------
CREATE TABLE audit_event (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    project_id  BIGINT      NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id   BIGINT      NULL,
    action      VARCHAR(32) NOT NULL COMMENT 'CREATE/UPDATE/STATUS_CHANGE/OWNER_CHANGE/DECISION/DELETE',
    actor_id    BIGINT      NULL,
    summary     VARCHAR(512) NULL,
    before_json JSON        NULL,
    after_json  JSON        NULL,
    at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_audit_entity (entity_type, entity_id),
    KEY idx_audit_project (project_id),
    KEY idx_audit_at (at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计事件';
