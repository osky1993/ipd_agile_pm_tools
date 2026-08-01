package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.domain.entity.TestCase;
import com.ipd.toolbox.domain.entity.TestRun;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.mapper.TestCaseMapper;
import com.ipd.toolbox.mapper.TestRunMapper;
import com.ipd.toolbox.mapper.TraceLinkMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 追溯矩阵（T703）：需求 × 测试覆盖。每个需求列出验证它的用例及最新执行结果，
 * 未被任何用例 verifies 的需求标记为未覆盖（规划§15.1）。
 */
@Service
public class TraceMatrixService {

    private final WorkItemMapper workItemMapper;
    private final TraceLinkMapper traceLinkMapper;
    private final TestCaseMapper testCaseMapper;
    private final TestRunMapper testRunMapper;

    public TraceMatrixService(WorkItemMapper workItemMapper, TraceLinkMapper traceLinkMapper,
                              TestCaseMapper testCaseMapper, TestRunMapper testRunMapper) {
        this.workItemMapper = workItemMapper;
        this.traceLinkMapper = traceLinkMapper;
        this.testCaseMapper = testCaseMapper;
        this.testRunMapper = testRunMapper;
    }

    public record Cell(String testCode, String testTitle, String latestResult) {
    }

    public record Row(Long requirementId, String code, String title, String status, boolean covered, List<Cell> tests) {
    }

    public List<Row> matrix(Long projectId) {
        List<WorkItem> reqs = workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).eq("type", WorkItemType.REQUIREMENT.name()).orderByAsc("id"));

        List<Row> rows = new ArrayList<>();
        for (WorkItem req : reqs) {
            // 找 verifies 该需求的用例（TEST_CASE -verifies-> 需求）
            List<TraceLink> links = traceLinkMapper.selectList(new QueryWrapper<TraceLink>()
                    .eq("relation", "verifies").eq("target_type", "WORK_ITEM").eq("target_id", req.getId())
                    .eq("source_type", "TEST_CASE"));
            List<Cell> cells = new ArrayList<>();
            for (TraceLink l : links) {
                TestCase tc = testCaseMapper.selectById(l.getSourceId());
                if (tc == null) {
                    continue;
                }
                TestRun latest = testRunMapper.selectList(new QueryWrapper<TestRun>()
                        .eq("test_case_id", tc.getId()).orderByDesc("run_at").orderByDesc("id").last("LIMIT 1"))
                        .stream().findFirst().orElse(null);
                cells.add(new Cell(tc.getCode(), tc.getTitle(), latest != null ? latest.getResult() : null));
            }
            rows.add(new Row(req.getId(), req.getCode(), req.getTitle(), req.getStatus(), !cells.isEmpty(), cells));
        }
        return rows;
    }
}
