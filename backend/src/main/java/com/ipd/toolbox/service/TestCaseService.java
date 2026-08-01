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
import java.util.List;

@Service
public class TestCaseService {

    private final TestCaseMapper mapper;
    private final ProjectMapper projectMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;
    private final TraceLinkService traceLinkService;

    public TestCaseService(TestCaseMapper mapper, ProjectMapper projectMapper, CodeGenerator codeGenerator,
                           AuditService audit, TraceLinkService traceLinkService) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
        this.traceLinkService = traceLinkService;
    }

    public List<TestCase> list(Long projectId) {
        return mapper.selectList(new QueryWrapper<TestCase>()
                .eq("project_id", projectId).orderByDesc("id"));
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
}
