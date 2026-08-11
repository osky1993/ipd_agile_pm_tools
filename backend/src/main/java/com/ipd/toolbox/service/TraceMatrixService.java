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

    /**
     * 注入追溯矩阵所需仓储。矩阵仅按数据库事实组装：
     * - 需求池（WORK_ITEM + type=REQUIREMENT）
     * - 反向 verifies 链
     * - 每个用例的最近一次执行结果
     */
    public TraceMatrixService(WorkItemMapper workItemMapper, TraceLinkMapper traceLinkMapper,
                              TestCaseMapper testCaseMapper, TestRunMapper testRunMapper) {
        this.workItemMapper = workItemMapper;
        this.traceLinkMapper = traceLinkMapper;
        this.testCaseMapper = testCaseMapper;
        this.testRunMapper = testRunMapper;
    }

    /**
     * 覆盖单元：每条关联用例在矩阵中的展示行。
     * `latestResult` 为该用例在当前项目内最新一次执行结果。
     */
    public record Cell(String testCode, String testTitle, String latestResult) {
    }

    /**
     * 一行需求覆盖信息。
     * `covered` 表示是否存在至少一条测试用例 verifies 该需求。
     */
    public record Row(Long requirementId, String code, String title, String status, boolean covered, List<Cell> tests) {
    }

    /**
     * 生成需求-用例追溯矩阵。
     *
     * <p>更新/聚合口径：
     * <ul>
     *   <li>范围：仅扫描 {@code type=REQUIREMENT} 且属于当前项目的工作项。</li>
     *   <li>覆盖关系：只使用 `TEST_CASE -verifies-> WORK_ITEM` 关系；
     *       若需求未命中任何关系，返回 {@code covered=false}。</li>
     *   <li>执行结果：每个用例只取最新一次执行（`run_at` + `id` 降序）作为展示值；无执行则返回 null。</li>
     *   <li>副作用：纯读方法，不写数据库，不产生审计。</li>
     * </ul>
     * <p>边界与失败策略：`latestResult` 为空时表示“尚未执行”，不因单条 trace 数据缺失中断整次矩阵计算；不存在的用例引用会被跳过。</p>
     * 该方法用于“测试覆盖率看板”和“需求追溯卡片”，输出顺序保持 `需求ID` 升序，方便前端增量比对。
     *
     * @param projectId 项目 ID
     * @return 按需求维度聚合的追溯矩阵行
     */
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
