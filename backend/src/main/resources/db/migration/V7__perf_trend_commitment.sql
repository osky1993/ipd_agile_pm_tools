-- 效能趋势化 + 迭代承诺快照
-- perf_snapshot：效能指标每日快照（长表，读取效能指标时对当天 upsert，历史随使用累积），
-- 支撑指标趋势曲线与改进项"改进前后趋势对比"。
CREATE TABLE perf_snapshot (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    project_id BIGINT        NOT NULL,
    snap_date  DATE          NOT NULL,
    metric_key VARCHAR(64)   NOT NULL,
    value      DECIMAL(10,2) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ps (project_id, snap_date, metric_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='效能指标每日快照（趋势）';

-- iteration_commitment：迭代承诺快照。拉入迭代即记一条承诺，之后移出也不删——
-- 承诺完成率以此为分母，修正"移出迭代即不计承诺"的乐观口径。
CREATE TABLE iteration_commitment (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    iteration_id   BIGINT        NOT NULL,
    work_item_id   BIGINT        NOT NULL,
    estimate_snap  VARCHAR(32)   NULL COMMENT '承诺时的估算',
    committed_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ic (iteration_id, work_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='迭代承诺快照（只增不删）';

-- 回填：把当前已在迭代中的工作项记为该迭代的承诺
INSERT INTO iteration_commitment (iteration_id, work_item_id, estimate_snap, committed_at)
SELECT w.iteration_id, w.id, w.estimate, COALESCE(w.updated_at, NOW())
FROM work_item w
WHERE w.iteration_id IS NOT NULL AND w.deleted = 0;
