package com.ipd.toolbox.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface MetricsMapper {

    @Select("SELECT * FROM v_project_metrics WHERE project_id = #{projectId}")
    Map<String, Object> projectMetrics(Long projectId);

    /** 需求测试覆盖计数。 */
    @Select("SELECT COUNT(*) AS req_total, COALESCE(SUM(covered), 0) AS req_covered " +
            "FROM v_requirement_coverage WHERE project_id = #{projectId}")
    Map<String, Object> requirementCoverage(Long projectId);

    /** 用例最新执行的通过率计数（仅统计有执行记录的用例）。 */
    @Select("SELECT COUNT(*) AS cases_with_runs, " +
            "COALESCE(SUM(CASE WHEN latest_result = 'PASS' THEN 1 ELSE 0 END), 0) AS pass_cases " +
            "FROM v_testcase_latest WHERE project_id = #{projectId} AND latest_result IS NOT NULL")
    Map<String, Object> testPassStats(Long projectId);

    /** DCP 条件按状态分布。 */
    @Select("SELECT status, COUNT(*) AS cnt FROM gate_criterion " +
            "WHERE project_id = #{projectId} AND is_readiness = 0 AND deleted = 0 GROUP BY status")
    List<Map<String, Object>> dcpStatusDist(Long projectId);

    /** 缺陷按创建日流入数（完整历史，直接来自时间戳）。 */
    @Select("SELECT DATE(created_at) AS d, COUNT(*) AS cnt FROM work_item " +
            "WHERE project_id = #{projectId} AND type = 'DEFECT' AND deleted = 0 GROUP BY DATE(created_at)")
    List<Map<String, Object>> defectInflowByDay(Long projectId);

    /** 缺陷按关闭日关闭数（取状态时间线进入 Closed 的事件）。 */
    @Select("SELECT DATE(l.at) AS d, COUNT(*) AS cnt FROM work_item_status_log l " +
            "JOIN work_item w ON w.id = l.work_item_id " +
            "WHERE w.project_id = #{projectId} AND w.type = 'DEFECT' AND w.deleted = 0 " +
            "AND l.to_status = 'Closed' GROUP BY DATE(l.at)")
    List<Map<String, Object>> defectClosedByDay(Long projectId);

    /** DCP 条件总数与满足数（当日快照用）。 */
    @Select("SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN status = 'MET' THEN 1 ELSE 0 END), 0) AS met " +
            "FROM gate_criterion WHERE project_id = #{projectId} AND is_readiness = 0 AND deleted = 0")
    Map<String, Object> criteriaStats(Long projectId);

    /** 已验收工作项的周期天数（In Progress 首次进入 → Accepted），供分位计算。 */
    @Select("SELECT DATEDIFF(w.accepted_at, sl.started_at) AS cycle_days FROM work_item w " +
            "JOIN (SELECT work_item_id, MIN(at) AS started_at FROM work_item_status_log " +
            "      WHERE to_status = 'In Progress' GROUP BY work_item_id) sl ON sl.work_item_id = w.id " +
            "WHERE w.project_id = #{projectId} AND w.status = 'Accepted' AND w.accepted_at IS NOT NULL AND w.deleted = 0")
    List<Integer> cycleDays(Long projectId);
}
