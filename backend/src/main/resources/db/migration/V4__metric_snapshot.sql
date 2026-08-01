-- 驾驶舱趋势（规划§7.4 / T708 反馈②）：每日指标快照。
-- 缺陷流入/关闭趋势可由 work_item / work_item_status_log 的时间戳精确回算，
-- 但 DCP 条件满足数、未关缺陷存量等属于"当日状态"，历史不可回算——
-- 由服务端在读取趋势时对当天做一次 upsert 快照，随日常使用自然累积。
CREATE TABLE metric_snapshot (
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    project_id     BIGINT   NOT NULL,
    snap_date      DATE     NOT NULL,
    criteria_total INT      NOT NULL DEFAULT 0,
    criteria_met   INT      NOT NULL DEFAULT 0,
    open_defects   INT      NOT NULL DEFAULT 0,
    req_total      INT      NOT NULL DEFAULT 0,
    req_accepted   INT      NOT NULL DEFAULT 0,
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ms_proj_date (project_id, snap_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='驾驶舱每日指标快照（趋势）';
