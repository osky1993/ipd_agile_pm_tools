package com.ipd.toolbox.service;

import com.ipd.toolbox.common.Labels;
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

    /**
     * 测试执行服务依赖：用例、项目、追溯、代码生成、审计与工作项服务；
     * 其职责是把一次执行行为落库并按规则自动创建缺陷与追溯关系。
     */
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

    /**
     * 列出指定用例的执行记录（按执行时间倒序）。
     *
     * <p>查询特性：
     * <ul>
     *   <li>仅按 {@code test_case_id} 过滤，不承担跨用例聚合。</li>
     *   <li>按 {@code run_at} 倒序返回，确保最新执行先展示。</li>
     * </ul>
     * <p>无副作用；异常由底层持久化层透传。</p>
     * <p>边界说明：不做分页或时间窗约束，返回全量执行历史；上层若需近 N 次记录需二次截断。</p>
     *
     * @param testCaseId 用例 ID
     * @return 执行记录列表（空列表表示无记录）
     */
    public List<TestRun> listByCase(Long testCaseId) {
        return mapper.selectList(new QueryWrapper<TestRun>()
                .eq("test_case_id", testCaseId).orderByDesc("run_at"));
    }

    /**
     * 记录一次测试执行（T306）。
     *
     * <p>更新粒度（先决条件 + 执行动作 + 副作用）：
     * <ul>
     *   <li>先决条件：用例存在且状态可执行（非草稿/停用），否则抛业务异常。</li>
     *   <li>执行动作：
     *     <ol>
     *       <li>基于用例计算项目、执行代码、执行人、执行时间并插入 {@code TEST_RUN}。</li>
     *       <li>固定写 {@code TEST_RUN CREATE} 审计事件。</li>
     *       <li>失败且允许自动建单时自动创建 {@code DEFECT} 工作项、回填 {@code defect_id}。</li>
     *     </ol>
     *   </li>
     *   <li>副作用（FAIL 分支）：
     *     <ul>
     *       <li>为新缺陷补齐追溯关系：该缺陷 -affects-> 验证需求、该用例。</li>
     *       <li>若有执行版本，补齐缺陷 -released_in-> 版本。</li>
     *     </ul>
     *   </li>
     * </ul>
     * <p>失败策略：方法标注 {@code @Transactional}；前置校验失败抛业务异常并回滚，未捕获的运行期异常也会回滚当前事务。</p>
     * <p>缺陷副作用边界：`autoCreateDefect=true` 且 `defectId == null` 时才自动建单；若 `defectId` 已存在则只保留现有缺陷引用，不进行重复建单。</p>
     *
     * @param run               待创建执行记录（包含用例 ID、执行结果、实际结果、版本等）
     * @param autoCreateDefect  失败时是否自动建单
     * @return 已落库并补齐上下文信息的执行实体
     */
    @Transactional
    public TestRun execute(TestRun run, boolean autoCreateDefect) {
        TestCase tc = testCaseMapper.selectById(run.getTestCaseId());
        if (tc == null) {
            throw new BusinessException("用例不存在");
        }
        if (tc.getStatus() != null && !"ACTIVE".equals(tc.getStatus())) {
            throw new BusinessException("用例 " + tc.getCode() + " 未启用（当前"
                    + ("DRAFT".equals(tc.getStatus()) ? "草稿" : "停用") + "），启用后才能执行");
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
                "执行用例 " + tc.getCode() + " 结果=" + Labels.testResult(run.getResult()), null, run);

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

    /**
     * 创建单条追溯关系（缺陷侧）。
     *
     * <p>该方法是 {@link #execute(TestRun, boolean)} 的内部封装，主要职责是保证
     * 同一执行上下文下缺陷与上下游对象的关系统一由 {@link TraceLinkService#create}
     * 落库。关系类型当前固定在 {@code affects}/{@code released_in} 两类，关系来源/目标在调用侧已确定。</p>
     * <p>更新行为：该方法不单独提交事务，依赖 {@link TestRunService#execute(TestRun, boolean)} 的事务边界；外层回滚时关系也会回滚。</p>
     *
     * @param projectId   项目 ID
     * @param defectId    缺陷工作项 ID（关系源）
     * @param targetType  目标实体类型
     * @param targetId    目标实体 ID
     * @param relation    关系类型
     */
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
