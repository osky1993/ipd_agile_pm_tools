package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.mapper.DecisionMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 决策服务（T404）。决策只增不改：修订必须通过新记录并 link prevDecisionId，不无痕覆盖历史（规划§7.3）。
 * 正式决策由授权角色作出（REVIEWER/ADMIN）——决策权不可被系统评分替代（规划§2.4）。
 */
@Service
public class DecisionService {

    private final DecisionMapper mapper;
    private final ProjectMapper projectMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;

    public DecisionService(DecisionMapper mapper, ProjectMapper projectMapper,
                           CodeGenerator codeGenerator, AuditService audit) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
    }

    public List<Decision> list(Long projectId) {
        return mapper.selectList(new QueryWrapper<Decision>()
                .eq("project_id", projectId).orderByDesc("decided_at"));
    }

    /** 记录一条正式决策（只增）。 */
    @Transactional
    public Decision record(Decision d) {
        UserContext.requireRole("REVIEWER");
        Project project = projectMapper.selectById(d.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (d.getConclusion() == null || d.getConclusion().isBlank()) {
            throw new BusinessException("决策结论不能为空");
        }
        d.setId(null);
        d.setCode(codeGenerator.next(project.getId(), project.getCode(), "DEC"));
        d.setDecidedBy(UserContext.currentUserId());
        d.setDecidedAt(LocalDateTime.now());
        mapper.insert(d);
        audit.record(d.getProjectId(), "DECISION", d.getId(), "DECISION",
                "决策 " + d.getCode() + " [" + d.getDecisionType() + "] 结论=" + d.getConclusion()
                        + (d.getPrevDecisionId() != null ? "（修订自 #" + d.getPrevDecisionId() + "）" : ""),
                null, d);
        return d;
    }
}
