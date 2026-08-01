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

    /** 用例 + 验证需求（verifies 链批量带出，供列表与执行窗口展示）。 */
    public record VerifiedReq(Long id, String code, String title) {
    }

    public record CaseView(Long id, String code, Long projectId, String title, String steps,
                           String expected, String status, List<VerifiedReq> verifies) {
    }

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
     * 创建用例；verifiesRequirementId 非空时建立 用例 -verifies-> 需求 追溯（用例↔需求关联，T305）。
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

    public TestCase get(Long id) {
        TestCase tc = mapper.selectById(id);
        if (tc == null) {
            throw new BusinessException(4040, "用例不存在");
        }
        return tc;
    }

    /** 编辑用例内容（标题/步骤/预期）。 */
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

    /** 状态变更：DRAFT 草稿 / ACTIVE 启用 / DISABLED 停用。 */
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

    /** 删除用例（逻辑删，执行记录保留）。 */
    @Transactional
    public void delete(Long id) {
        UserContext.requireRole("QA", "PM");
        TestCase old = get(id);
        mapper.deleteById(id); // @TableLogic 自动置 deleted=1，执行记录保留
        audit.record(old.getProjectId(), "TEST_CASE", id, "DELETE", "删除用例 " + old.getCode(), null, null);
    }
}
