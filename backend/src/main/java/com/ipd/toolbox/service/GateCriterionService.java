package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.GateCriterion;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.mapper.GateCriterionMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DCP 准入条件 / 跨职能准备度检查项的基础 CRUD（T204）。
 * 完整 DCP 规则引擎与评审决策在 E5（T502/T505）实现。
 */
@Service
public class GateCriterionService {

    private final GateCriterionMapper mapper;
    private final ProjectMapper projectMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;

    public GateCriterionService(GateCriterionMapper mapper, ProjectMapper projectMapper,
                                CodeGenerator codeGenerator, AuditService audit) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
    }

    /** 按项目列出，可选按 stageGateId 或 isReadiness 过滤。 */
    public List<GateCriterion> list(Long projectId, Long stageGateId, Integer isReadiness) {
        QueryWrapper<GateCriterion> qw = new QueryWrapper<GateCriterion>().eq("project_id", projectId);
        if (stageGateId != null) {
            qw.eq("stage_gate_id", stageGateId);
        }
        if (isReadiness != null) {
            qw.eq("is_readiness", isReadiness);
        }
        qw.orderByAsc("domain").orderByAsc("id");
        return mapper.selectList(qw);
    }

    @Transactional
    public GateCriterion create(GateCriterion c) {
        UserContext.requireRole("PM", "QUALITY");
        Project project = projectMapper.selectById(c.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (c.getCriterion() == null || c.getCriterion().isBlank()) {
            throw new BusinessException("准入条件描述不能为空");
        }
        Long uid = UserContext.currentUserId();
        c.setId(null);
        c.setCode(codeGenerator.next(project.getId(), project.getCode(), "GC"));
        if (c.getStatus() == null) c.setStatus("NOT_READY");
        if (c.getJudgeType() == null) c.setJudgeType("MANUAL");
        if (c.getIsRedline() == null) c.setIsRedline(0);
        if (c.getIsReadiness() == null) c.setIsReadiness(0);
        c.setCreatedBy(uid);
        c.setUpdatedBy(uid);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        c.setDeleted(0);
        mapper.insert(c);
        audit.record(c.getProjectId(), "GATE_CRITERION", c.getId(), "CREATE",
                "创建准入条件 " + c.getCode() + " [" + c.getDomain() + "] " + c.getCriterion(), null, c);
        return c;
    }

    @Transactional
    public GateCriterion update(Long id, GateCriterion patch) {
        UserContext.requireRole("PM", "QUALITY");
        GateCriterion old = mapper.selectById(id);
        if (old == null) {
            throw new BusinessException(4040, "准入条件不存在");
        }
        String beforeStatus = old.getStatus();
        Long beforeOwner = old.getOwnerId();
        if (patch.getDomain() != null) old.setDomain(patch.getDomain());
        if (patch.getCriterion() != null) old.setCriterion(patch.getCriterion());
        if (patch.getJudgeType() != null) old.setJudgeType(patch.getJudgeType());
        if (patch.getOwnerId() != null) old.setOwnerId(patch.getOwnerId());
        if (patch.getStatus() != null) old.setStatus(patch.getStatus());
        if (patch.getEvidenceReq() != null) old.setEvidenceReq(patch.getEvidenceReq());
        if (patch.getIsRedline() != null) old.setIsRedline(patch.getIsRedline());
        if (patch.getLinkedRiskId() != null) old.setLinkedRiskId(patch.getLinkedRiskId());
        if (patch.getWaiverReason() != null) old.setWaiverReason(patch.getWaiverReason());
        if (patch.getWaiverDue() != null) old.setWaiverDue(patch.getWaiverDue());
        if (patch.getPlanDate() != null) old.setPlanDate(patch.getPlanDate());
        if (patch.getForecastDate() != null) old.setForecastDate(patch.getForecastDate());
        if (patch.getIsReadiness() != null) old.setIsReadiness(patch.getIsReadiness());
        // 豁免必须包含授权人、理由、期限（规划§7.3）；授权人自动记为当前用户
        if ("WAIVED".equals(old.getStatus())) {
            if (old.getWaiverReason() == null || old.getWaiverReason().isBlank() || old.getWaiverDue() == null) {
                throw new BusinessException("豁免必须填写理由与期限");
            }
            old.setWaiverBy(UserContext.currentUserId());
        }
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);

        String action = (patch.getStatus() != null && !patch.getStatus().equals(beforeStatus))
                ? "STATUS_CHANGE"
                : (patch.getOwnerId() != null && !patch.getOwnerId().equals(beforeOwner) ? "OWNER_CHANGE" : "UPDATE");
        audit.record(old.getProjectId(), "GATE_CRITERION", id, action, "更新准入条件 " + old.getCode(), null, old);
        return old;
    }

    @Transactional
    public void delete(Long id) {
        UserContext.requireRole("PM", "QUALITY");
        GateCriterion old = mapper.selectById(id);
        if (old == null) {
            return;
        }
        mapper.deleteById(id);
        audit.record(old.getProjectId(), "GATE_CRITERION", id, "DELETE", "删除准入条件 " + old.getCode(), old, null);
    }
}
