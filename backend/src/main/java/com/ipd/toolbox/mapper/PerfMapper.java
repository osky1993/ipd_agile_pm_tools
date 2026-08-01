package com.ipd.toolbox.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 效能指标专用查询（T-效能）。纯计数/日期差放 SQL；估算解析、JSON、日志回放、分位数在 PerfService。 */
@Mapper
public interface PerfMapper {

    /** 窗口内验收数（吞吐）。 */
    @Select("SELECT COUNT(*) FROM work_item WHERE project_id=#{projectId} AND status='Accepted' " +
            "AND accepted_at>=#{since} AND deleted=0")
    long acceptedSince(@Param("projectId") Long projectId, @Param("since") LocalDateTime since);

    /** 按日验收数（Java 里做 ISO 周分桶）。 */
    @Select("SELECT DATE(accepted_at) AS d, COUNT(*) AS cnt FROM work_item " +
            "WHERE project_id=#{projectId} AND status='Accepted' AND accepted_at>=#{since} AND deleted=0 " +
            "GROUP BY DATE(accepted_at)")
    List<Map<String, Object>> acceptedByDay(@Param("projectId") Long projectId, @Param("since") LocalDateTime since);

    /** 窗口内验收数按类型分解。 */
    @Select("SELECT type, COUNT(*) AS cnt FROM work_item WHERE project_id=#{projectId} " +
            "AND status='Accepted' AND accepted_at>=#{since} AND deleted=0 GROUP BY type")
    List<Map<String, Object>> acceptedByType(@Param("projectId") Long projectId, @Param("since") LocalDateTime since);

    /** 端到端 Lead Time 天数（created→Accepted），分位数在 Java 算。 */
    @Select("SELECT DATEDIFF(accepted_at, created_at) FROM work_item WHERE project_id=#{projectId} " +
            "AND status='Accepted' AND accepted_at IS NOT NULL AND deleted=0")
    List<Integer> leadDays(Long projectId);

    /** 通用类型全部状态事件（含创建时写入的初始状态行），Java 回放算阶段停留。 */
    @Select("SELECT l.work_item_id, l.to_status, l.at FROM work_item_status_log l " +
            "JOIN work_item w ON w.id=l.work_item_id " +
            "WHERE w.project_id=#{projectId} AND w.deleted=0 " +
            "AND w.type IN ('CAPABILITY','REQUIREMENT','STORY','TASK') " +
            "ORDER BY l.work_item_id, l.at, l.id")
    List<Map<String, Object>> genericStatusLogs(Long projectId);

    /** 非终态工作项 + 最后一次状态变更时间（停滞判定；无日志退回 created_at 兜底）。 */
    @Select("SELECT w.id, w.code, w.type, w.title, w.status, " +
            "COALESCE(MAX(l.at), w.created_at) AS last_move FROM work_item w " +
            "LEFT JOIN work_item_status_log l ON l.work_item_id=w.id " +
            "WHERE w.project_id=#{projectId} AND w.deleted=0 " +
            "AND w.status NOT IN ('Accepted','Closed','Rejected','Verified') " +
            "GROUP BY w.id, w.code, w.type, w.title, w.status, w.created_at")
    List<Map<String, Object>> lastMoveOfOpenItems(Long projectId);

    /** 缺陷修复天数（created→首次 Closed）。 */
    @Select("SELECT DATEDIFF(c.first_closed, w.created_at) FROM work_item w " +
            "JOIN (SELECT work_item_id, MIN(at) AS first_closed FROM work_item_status_log " +
            "      WHERE to_status='Closed' GROUP BY work_item_id) c ON c.work_item_id=w.id " +
            "WHERE w.project_id=#{projectId} AND w.type='DEFECT' AND w.deleted=0")
    List<Integer> defectFixDays(Long projectId);

    /** 测试首次执行统计（每用例第一次执行的结果）。 */
    @Select("SELECT COUNT(*) AS cases_with_runs, " +
            "COALESCE(SUM(CASE WHEN first_result='PASS' THEN 1 ELSE 0 END),0) AS first_pass FROM (" +
            "  SELECT tr.result AS first_result, " +
            "         ROW_NUMBER() OVER (PARTITION BY tr.test_case_id ORDER BY tr.run_at, tr.id) AS rn " +
            "  FROM test_run tr JOIN test_case tc ON tc.id=tr.test_case_id AND tc.deleted=0 " +
            "  WHERE tr.project_id=#{projectId}) t WHERE t.rn=1")
    Map<String, Object> firstRunStats(Long projectId);

    /** 缺陷复开统计：曾关闭数 / 关闭后又打回数。 */
    @Select("SELECT COUNT(DISTINCT CASE WHEN l.to_status='Closed' THEN l.work_item_id END) AS ever_closed, " +
            "COUNT(DISTINCT CASE WHEN l.from_status='Closed' THEN l.work_item_id END) AS reopened " +
            "FROM work_item_status_log l JOIN work_item w ON w.id=l.work_item_id " +
            "WHERE w.project_id=#{projectId} AND w.type='DEFECT' AND w.deleted=0")
    Map<String, Object> defectReopenStats(Long projectId);

    /** 变更处理周期天数（created→首次 Verified）。 */
    @Select("SELECT DATEDIFF(v.first_verified, w.created_at) FROM work_item w " +
            "JOIN (SELECT work_item_id, MIN(at) AS first_verified FROM work_item_status_log " +
            "      WHERE to_status='Verified' GROUP BY work_item_id) v ON v.work_item_id=w.id " +
            "WHERE w.project_id=#{projectId} AND w.type='CHANGE' AND w.deleted=0")
    List<Integer> changeCycleDays(Long projectId);

    /** 红线满足率（DCP+准备度合并口径；WAIVED 视为满足）。 */
    @Select("SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN status IN ('MET','WAIVED') THEN 1 ELSE 0 END),0) AS satisfied " +
            "FROM gate_criterion WHERE project_id=#{projectId} AND is_redline=1 AND deleted=0")
    Map<String, Object> redlineStats(Long projectId);

    /** MET 条件证据完备率。 */
    @Select("SELECT COUNT(*) AS total, COALESCE(SUM(EXISTS(" +
            "  SELECT 1 FROM trace_link tl WHERE tl.source_type='GATE_CRITERION' " +
            "  AND tl.source_id=gc.id AND tl.relation='evidences')),0) AS with_evidence " +
            "FROM gate_criterion gc WHERE gc.project_id=#{projectId} AND gc.status='MET' AND gc.deleted=0")
    Map<String, Object> metEvidenceStats(Long projectId);

    /** MET 但无证据的条件清单（告警用）。 */
    @Select("SELECT gc.id, gc.code, gc.criterion FROM gate_criterion gc " +
            "WHERE gc.project_id=#{projectId} AND gc.status='MET' AND gc.deleted=0 " +
            "AND NOT EXISTS(SELECT 1 FROM trace_link tl WHERE tl.source_type='GATE_CRITERION' " +
            "AND tl.source_id=gc.id AND tl.relation='evidences')")
    List<Map<String, Object>> metWithoutEvidence(Long projectId);

    /** Ready 就绪库存。 */
    @Select("SELECT COUNT(*) FROM work_item WHERE project_id=#{projectId} AND status='Ready' AND deleted=0")
    long readyCount(Long projectId);
}
