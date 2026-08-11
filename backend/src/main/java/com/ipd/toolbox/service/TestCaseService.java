package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.TestCase;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.TestCaseMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class TestCaseService {

    /** 用例状态集合。仅 ACTIVE 可执行。 */
    public static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "DISABLED");

    private final TestCaseMapper mapper;
    private final ProjectMapper projectMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;
    private final TraceLinkService traceLinkService;
    private final com.ipd.toolbox.mapper.TraceLinkMapper traceLinkMapper;
    private final com.ipd.toolbox.mapper.WorkItemMapper workItemMapper;

    /**
     * 用例服务依赖用例表仓储、项目表、代码生成、审计与追溯服务；
     * WorkItem/TraceLink 映射器用于构造和回填用例-需求验证关系。
     */
    public TestCaseService(TestCaseMapper mapper, ProjectMapper projectMapper, CodeGenerator codeGenerator,
                           AuditService audit, TraceLinkService traceLinkService,
                           com.ipd.toolbox.mapper.TraceLinkMapper traceLinkMapper,
                           com.ipd.toolbox.mapper.WorkItemMapper workItemMapper) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
        this.traceLinkService = traceLinkService;
        this.traceLinkMapper = traceLinkMapper;
        this.workItemMapper = workItemMapper;
    }

    /**
     * 用例和验证需求轻量视图。
     * CaseView 是列表/执行窗口的只读投影，避免上层再次拼装关联关系。
     */
    public record VerifiedReq(Long id, String code, String title) {
    }

    public record CaseView(Long id, String code, Long projectId, String title, String steps,
                           String expected, String status, List<VerifiedReq> verifies) {
    }

    /**
     * 列表查询指定项目的全部用例（只读）：
     * <ul>
     *   <li>按项目边界取 `TEST_CASE`，返回展示型视图 CaseView。</li>
     *   <li>一次性预取项目内工作项 + verifies 链，避免逐条 N+1。</li>
     *   <li>每条用例默认状态回填 ACTIVE，避免历史数据空值影响 UI。</li>
     * </ul>
     * <p>读取口径：仅使用 `project_id` 过滤，不分页；调用方可按需要再做二次分页。</p>
     */
    public List<CaseView> list(Long projectId) {
        List<TestCase> cases = mapper.selectList(new QueryWrapper<TestCase>()
                .eq("project_id", projectId).orderByDesc("id"));
        // 批量取 verifies 链与需求信息
        Map<Long, List<VerifiedReq>> byCase = new HashMap<>();
        Map<Long, com.ipd.toolbox.domain.entity.WorkItem> items = new HashMap<>();
        workItemMapper.selectList(new QueryWrapper<com.ipd.toolbox.domain.entity.WorkItem>()
                .eq("project_id", projectId)).forEach(w -> items.put(w.getId(), w));
        for (TraceLink l : traceLinkMapper.selectList(new QueryWrapper<TraceLink>()
                .eq("project_id", projectId).eq("relation", "verifies").eq("source_type", "TEST_CASE"))) {
            var w = items.get(l.getTargetId());
            if (w != null) {
                byCase.computeIfAbsent(l.getSourceId(), k -> new ArrayList<>())
                        .add(new VerifiedReq(w.getId(), w.getCode(), w.getTitle()));
            }
        }
        return cases.stream().map(c -> new CaseView(c.getId(), c.getCode(), c.getProjectId(),
                c.getTitle(), c.getSteps(), c.getExpected(),
                c.getStatus() == null ? "ACTIVE" : c.getStatus(),
                byCase.getOrDefault(c.getId(), List.of()))).toList();
    }

    /**
     * 创建用例（写链路）：
     * <ol>
     *   <li>校验项目存在、标题非空，状态非法则拒绝。</li>
     *   <li>生成 code、创建元数据并写库。</li>
     *   <li>写入 CREATE 审计。</li>
     *   <li>如 `verifiesRequirementId` 存在，补齐用例↔需求 verifies 关系。</li>
     * </ol>
     * <p>更新粒度：新增 TEST_CASE 主表，关系副作用可选。</p>
     * <p>失败回退：本方法声明事务，项目不存在/标题空/状态非法时立即终止且不落库；若主表写入后再建关系失败，关系创建与审计落库会被整体回滚（当前实现不启用重试）。</p>
     */
    @Transactional
    public TestCase create(TestCase tc, Long verifiesRequirementId) {
        Project project = projectMapper.selectById(tc.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (tc.getTitle() == null || tc.getTitle().isBlank()) {
            throw new BusinessException("用例标题不能为空");
        }
        Long uid = UserContext.currentUserId();
        tc.setId(null);
        // 未显式指定状态默认 ACTIVE（可执行）；页面可显式建为 DRAFT 草稿
        if (tc.getStatus() == null || tc.getStatus().isBlank()) {
            tc.setStatus("ACTIVE");
        } else if (!STATUSES.contains(tc.getStatus())) {
            throw new BusinessException("无效用例状态: " + tc.getStatus());
        }
        tc.setCode(codeGenerator.next(project.getId(), project.getCode(), "TC"));
        tc.setCreatedBy(uid);
        tc.setUpdatedBy(uid);
        tc.setCreatedAt(LocalDateTime.now());
        tc.setUpdatedAt(LocalDateTime.now());
        tc.setDeleted(0);
        mapper.insert(tc);
        audit.record(tc.getProjectId(), "TEST_CASE", tc.getId(), "CREATE",
                "创建用例 " + tc.getCode() + " " + tc.getTitle(), null, tc);

        if (verifiesRequirementId != null) {
            TraceLink link = new TraceLink();
            link.setProjectId(tc.getProjectId());
            link.setSourceType("TEST_CASE");
            link.setSourceId(tc.getId());
            link.setTargetType("WORK_ITEM");
            link.setTargetId(verifiesRequirementId);
            link.setRelation("verifies");
            traceLinkService.create(link);
        }
        return tc;
    }

    /**
     * 按主键读取用例（只读）：
     * <p>用途：作为 update/delete/execute 的前置一致性边界，统一约束“按 ID 必须能读到有效实体”。</p>
     * <p>不存在时抛 4040（非空实体边界）。</p>
     */
    public TestCase get(Long id) {
        TestCase tc = mapper.selectById(id);
        if (tc == null) {
            throw new BusinessException(4040, "用例不存在");
        }
        return tc;
    }

    /**
     * 编辑用例（写链路）：
     * <ul>
     *   <li>角色校验 QA/PM。</li>
     *   <li>按非空字段补丁更新：标题、步骤、预期。</li>
     *   <li>更新人和更新时间更新后落库，记录 UPDATE 审计。</li>
     *   <li>状态字段不可在该方法中修改，避免与独立状态变更入口冲突。</li>
     * </ul>
     * <p>更新粒度：仅更新 `title / steps / expected / updated_by / updated_at`，缺省字段保持不变。</p>
     */
    @Transactional
    public TestCase update(Long id, TestCase patch) {
        UserContext.requireRole("QA", "PM");
        TestCase old = get(id);
        if (patch.getTitle() != null) {
            if (patch.getTitle().isBlank()) {
                throw new BusinessException("用例标题不能为空");
            }
            old.setTitle(patch.getTitle());
        }
        if (patch.getSteps() != null) old.setSteps(patch.getSteps());
        if (patch.getExpected() != null) old.setExpected(patch.getExpected());
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        audit.record(old.getProjectId(), "TEST_CASE", id, "UPDATE", "编辑用例 " + old.getCode(), null, old);
        return old;
    }

    /**
     * 状态变更（写链路）：
     * <ul>
     *   <li>角色校验 QA/PM，状态仅限 DRAFT/ACTIVE/DISABLED。</li>
     *   <li>保存前置状态用于审计。</li>
     *   <li>记录 STATUS_CHANGE，触发更新人和更新时间。</li>
     * </ul>
     * <p>幂等语义：同一状态重复提交仍会触发一次 update+审计；如果需要严格幂等，应在上层过滤。</p>
     */
    @Transactional
    public TestCase changeStatus(Long id, String status) {
        UserContext.requireRole("QA", "PM");
        if (!STATUSES.contains(status)) {
            throw new BusinessException("无效用例状态: " + status);
        }
        TestCase old = get(id);
        String before = old.getStatus();
        old.setStatus(status);
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        audit.record(old.getProjectId(), "TEST_CASE", id, "STATUS_CHANGE",
                "用例 " + old.getCode() + " 状态 " + before + " → " + status, null, old);
        return old;
    }

    /**
     * 删除用例（逻辑删）：
     * <ul>
     *   <li>角色校验 QA/PM。</li>
     *   <li>通过逻辑删除保留历史记录。</li>
     *   <li>记录 DELETE 审计。</li>
     * </ul>
     * <p>副作用：`@TableLogic` 驱动软删除，不会清理关联 traces/执行记录，追溯链路可能保留历史。</p>
     */
    @Transactional
    public void delete(Long id) {
        UserContext.requireRole("QA", "PM");
        TestCase old = get(id);
        mapper.deleteById(id); // @TableLogic 自动置 deleted=1，执行记录保留
        audit.record(old.getProjectId(), "TEST_CASE", id, "DELETE", "删除用例 " + old.getCode(), null, null);
    }
}
