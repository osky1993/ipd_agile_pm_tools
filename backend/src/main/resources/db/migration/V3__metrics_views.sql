-- =====================================================================
-- 驾驶舱指标 SQL 视图（T701）。指标由业务数据自动计算，单一权威、不人工填报（规划§2.2/§15.5）。
-- =====================================================================

-- 项目级核心计数汇总
CREATE OR REPLACE VIEW v_project_metrics AS
SELECT
    p.id AS project_id,
    (SELECT COUNT(*) FROM work_item w WHERE w.project_id = p.id AND w.type = 'CAPABILITY' AND w.deleted = 0) AS capability_total,
    (SELECT COUNT(*) FROM work_item w WHERE w.project_id = p.id AND w.type = 'CAPABILITY' AND w.status = 'Accepted' AND w.deleted = 0) AS capability_accepted,
    (SELECT COUNT(*) FROM work_item w WHERE w.project_id = p.id AND w.type = 'REQUIREMENT' AND w.deleted = 0) AS requirement_total,
    (SELECT COUNT(*) FROM work_item w WHERE w.project_id = p.id AND w.type = 'REQUIREMENT' AND w.status = 'Accepted' AND w.deleted = 0) AS requirement_accepted,
    (SELECT COUNT(*) FROM work_item w WHERE w.project_id = p.id AND w.status = 'In Progress' AND w.deleted = 0) AS wip,
    (SELECT COUNT(*) FROM work_item w WHERE w.project_id = p.id AND w.status = 'Accepted' AND w.deleted = 0) AS accepted_count,
    (SELECT COUNT(*) FROM work_item w WHERE w.project_id = p.id AND w.type = 'DEFECT' AND w.status <> 'Closed' AND w.deleted = 0) AS open_defects,
    (SELECT COUNT(*) FROM work_item w WHERE w.project_id = p.id AND w.type = 'DEFECT' AND w.deleted = 0) AS defect_total,
    (SELECT COUNT(*) FROM work_item w WHERE w.project_id = p.id AND w.type = 'DEFECT' AND w.status = 'Closed' AND w.deleted = 0) AS defect_closed,
    (SELECT COUNT(*) FROM work_item w WHERE w.project_id = p.id AND w.type = 'CHANGE' AND w.status = 'Impact Analysed' AND w.deleted = 0) AS pending_changes,
    (SELECT COUNT(DISTINCT tl.target_id) FROM trace_link tl WHERE tl.project_id = p.id AND tl.relation = 'blocks') AS blocked_count
FROM project p
WHERE p.deleted = 0;

-- 需求测试覆盖：需求是否存在 verifies 关系
CREATE OR REPLACE VIEW v_requirement_coverage AS
SELECT
    w.project_id,
    w.id AS requirement_id,
    w.code,
    w.title,
    w.status,
    EXISTS(
        SELECT 1 FROM trace_link tl
        WHERE tl.relation = 'verifies' AND tl.target_type = 'WORK_ITEM' AND tl.target_id = w.id
    ) AS covered
FROM work_item w
WHERE w.type = 'REQUIREMENT' AND w.deleted = 0;

-- 用例最新执行结果（按用例去重取最近一次）
CREATE OR REPLACE VIEW v_testcase_latest AS
SELECT
    tc.project_id,
    tc.id AS test_case_id,
    tc.code,
    tc.title,
    (SELECT tr.result FROM test_run tr WHERE tr.test_case_id = tc.id ORDER BY tr.run_at DESC, tr.id DESC LIMIT 1) AS latest_result
FROM test_case tc
WHERE tc.deleted = 0;
