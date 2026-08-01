package com.ipd.toolbox.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 团队协作屏专用查询。 */
@Mapper
public interface TeamMapper {

    /** 每个被 verifies 的工作项的用例执行汇总（VERIFY_WAIT 规则 + 图节点 testBadge）。 */
    @Select("SELECT tl.target_id AS item_id, COUNT(*) AS cases_total, " +
            "COALESCE(SUM(CASE WHEN v.latest_result='PASS' THEN 1 ELSE 0 END),0) AS pass_cnt, " +
            "COALESCE(SUM(CASE WHEN v.latest_result='FAIL' THEN 1 ELSE 0 END),0) AS fail_cnt " +
            "FROM trace_link tl JOIN v_testcase_latest v ON v.test_case_id=tl.source_id " +
            "WHERE tl.project_id=#{projectId} AND tl.relation='verifies' AND tl.source_type='TEST_CASE' " +
            "AND tl.target_type='WORK_ITEM' GROUP BY tl.target_id")
    List<Map<String, Object>> verifyStats(Long projectId);

    /** 近 N 天状态流转（交接台推导）。 */
    @Select("SELECT l.work_item_id, l.from_status, l.to_status, l.at, w.code, w.type, w.title " +
            "FROM work_item_status_log l JOIN work_item w ON w.id=l.work_item_id " +
            "WHERE w.project_id=#{projectId} AND w.deleted=0 AND l.at>=#{since} " +
            "ORDER BY l.at DESC")
    List<Map<String, Object>> recentTransitions(@Param("projectId") Long projectId,
                                                @Param("since") LocalDateTime since);
}
