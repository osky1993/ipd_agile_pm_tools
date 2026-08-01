package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.*;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.TestCaseMapper;
import com.ipd.toolbox.mapper.TestRunMapper;
import com.ipd.toolbox.mapper.TraceLinkMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TestRunService {

    private final TestRunMapper mapper;
    private final TestCaseMapper testCaseMapper;
    private final TraceLinkMapper traceLinkMapper;
    private final ProjectMapper projectMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;
    private final WorkItemService workItemService;
    private final TraceLinkService traceLinkService;

    public TestRunService(TestRunMapper mapper, TestCaseMapper testCaseMapper, TraceLinkMapper traceLinkMapper,
                          ProjectMapper projectMapper, CodeGenerator codeGenerator, AuditService audit,
                          WorkItemService workItemService, TraceLinkService traceLinkService) {
        this.mapper = mapper;
        this.testCaseMapper = testCaseMapper;
        this.traceLinkMapper = traceLinkMapper;
        this.projectMapper = projectMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
        this.workItemService = workItemService;
        this.traceLinkService = traceLinkService;
    }

    public List<TestRun> listByCase(Long testCaseId) {
        return mapper.selectList(new QueryWrapper<TestRun>()
                .eq("test_case_id", testCaseId).orderByDesc("run_at"));
    }

    /**
     * 记录一次测试执行（T306）。
     * - result=FAIL 且 autoCreateDefect：自动创建缺陷工作项，回填 defect_id，
     *   并建链 缺陷 -affects-> 该用例 verifies 的需求、缺陷 -released_in-> 执行版本。
     * - 复测场景：传入 defectId，使本次 PASS 的执行绑定既有缺陷（供缺陷关闭守卫#2 校验）。
     */
    @Transactional
    public TestRun execute(TestRun run, boolean autoCreateDefect) {
        TestCase tc = testCaseMapper.selectById(run.getTestCaseId());
        if (tc == null) {
            throw new BusinessException("用例不存在");
        }
        Project project = projectMapper.selectById(tc.getProjectId());
        Long uid = UserContext.currentUserId();
        run.setId(null);
        run.setProjectId(tc.getProjectId());
        run.setCode(codeGenerator.next(project.getId(), project.getCode(), "TR"));
        run.setRunBy(uid);
        run.setRunAt(LocalDateTime.now());
        mapper.insert(run);
        audit.record(run.getProjectId(), "TEST_RUN", run.getId(), "CREATE",
                "执行用例 " + tc.getCode() + " 结果=" + run.getResult(), null, run);

        if ("FAIL".equals(run.getResult()) && autoCreateDefect && run.getDefectId() == null) {
            WorkItem defect = new WorkItem();
            defect.setProjectId(tc.getProjectId());
            defect.setType(WorkItemType.DEFECT.name());
            defect.setTitle("测试失败：" + tc.getTitle());
            defect.setDescription("由用例 " + tc.getCode() + " 执行失败自动生成。实际结果：" + run.getActual());
            defect.setProductVersionId(run.getRunVersionId());
            WorkItem created = workItemService.create(defect);

            // 回填 test_run.defect_id
            run.setDefectId(created.getId());
            mapper.updateById(run);

            // 缺陷 -affects-> 该用例 verifies 的每个需求
            for (TraceLink v : traceLinkMapper.selectList(new QueryWrapper<TraceLink>()
                    .eq("source_type", "TEST_CASE").eq("source_id", tc.getId())
                    .eq("relation", "verifies").eq("target_type", "WORK_ITEM"))) {
                link(tc.getProjectId(), created.getId(), "WORK_ITEM", v.getTargetId(), "affects");
            }
            // 缺陷 -affects-> 用例
            link(tc.getProjectId(), created.getId(), "TEST_CASE", tc.getId(), "affects");
            // 缺陷 -released_in-> 执行版本
            if (run.getRunVersionId() != null) {
                link(tc.getProjectId(), created.getId(), "PRODUCT_VERSION", run.getRunVersionId(), "released_in");
            }
        }
        return run;
    }

    private void link(Long projectId, Long defectId, String targetType, Long targetId, String relation) {
        TraceLink l = new TraceLink();
        l.setProjectId(projectId);
        l.setSourceType("WORK_ITEM");
        l.setSourceId(defectId);
        l.setTargetType(targetType);
        l.setTargetId(targetId);
        l.setRelation(relation);
        traceLinkService.create(l);
    }
}
