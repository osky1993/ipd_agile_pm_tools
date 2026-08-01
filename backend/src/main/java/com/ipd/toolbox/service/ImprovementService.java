package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Improvement;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.mapper.ImprovementMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 流程改进项闭环（指标驱动）：发现偏离 → 立改进项（记基线）→ 跟踪 → 验证实际效果。
 * 状态只前进：OPEN → DOING → DONE → VERIFIED；VERIFIED 必须回填实际值与基线对比。
 */
@Service
public class ImprovementService {

    private static final List<String> FLOW = List.of("OPEN", "DOING", "DONE", "VERIFIED");

    private final ImprovementMapper mapper;
    private final ProjectMapper projectMapper;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;
    private final PerfService perfService;

    public ImprovementService(ImprovementMapper mapper, ProjectMapper projectMapper,
                              CodeGenerator codeGenerator, AuditService audit, PerfService perfService) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
        this.perfService = perfService;
    }

    public List<Improvement> list(Long projectId, String status) {
        QueryWrapper<Improvement> qw = new QueryWrapper<Improvement>().eq("project_id", projectId);
        if (status != null && !status.isBlank()) {
            qw.eq("status", status);
        }
        return mapper.selectList(qw.orderByDesc("created_at").orderByDesc("id"));
    }

    @Transactional
    public Improvement create(Improvement in) {
        UserContext.requireRole("PM");
        Project project = projectMapper.selectById(in.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (in.getTitle() == null || in.getTitle().isBlank()) {
            throw new BusinessException("改进项标题不能为空");
        }
        if (in.getMetricKey() != null && !in.getMetricKey().isBlank()) {
            PerfService.def(in.getMetricKey())
                    .orElseThrow(() -> new BusinessException("未知指标: " + in.getMetricKey()));
            // 未显式给基线时，自动固化当前指标值为基线
            if (in.getBaselineValue() == null) {
                Double cur = perfService.currentValue(in.getProjectId(), in.getMetricKey());
                if (cur != null) {
                    in.setBaselineValue(BigDecimal.valueOf(cur));
                }
            }
        } else {
            in.setMetricKey(null);
        }
        Long uid = UserContext.currentUserId();
        in.setId(null);
        in.setCode(codeGenerator.next(project.getId(), project.getCode(), "IMP"));
        in.setStatus("OPEN");
        in.setResultValue(null);
        in.setConclusion(null);
        in.setCreatedBy(uid);
        in.setUpdatedBy(uid);
        in.setCreatedAt(LocalDateTime.now());
        in.setUpdatedAt(LocalDateTime.now());
        in.setDeleted(0);
        mapper.insert(in);
        audit.record(in.getProjectId(), "IMPROVEMENT", in.getId(), "CREATE",
                "发起改进 " + in.getCode() + " " + in.getTitle()
                        + (in.getMetricKey() != null ? "（指标 " + in.getMetricKey()
                        + " 基线 " + in.getBaselineValue() + "）" : ""), null, in);
        return in;
    }

    @Transactional
    public Improvement update(Long id, Improvement patch) {
        UserContext.requireRole("PM");
        Improvement old = get(id);
        if (!"OPEN".equals(old.getStatus()) && !"DOING".equals(old.getStatus())) {
            throw new BusinessException("仅 OPEN/DOING 状态可编辑");
        }
        if (patch.getTitle() != null) old.setTitle(patch.getTitle());
        if (patch.getMeasure() != null) old.setMeasure(patch.getMeasure());
        if (patch.getTargetValue() != null) old.setTargetValue(patch.getTargetValue());
        if (patch.getDueDate() != null) old.setDueDate(patch.getDueDate());
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        audit.record(old.getProjectId(), "IMPROVEMENT", id, "UPDATE", "更新改进 " + old.getCode(), null, old);
        return old;
    }

    /** 状态推进（只允许顺序前进一步）。VERIFIED 必填实际值（有关联指标时可自动取当前值）。 */
    @Transactional
    public Improvement transition(Long id, String toStatus, BigDecimal resultValue, String conclusion) {
        UserContext.requireRole("PM");
        Improvement old = get(id);
        int from = FLOW.indexOf(old.getStatus());
        int to = FLOW.indexOf(toStatus);
        if (to < 0 || to != from + 1) {
            throw new BusinessException("非法流转: " + old.getStatus() + " → " + toStatus + "（只允许顺序前进）");
        }
        if ("VERIFIED".equals(toStatus)) {
            if (resultValue == null && old.getMetricKey() != null) {
                Double cur = perfService.currentValue(old.getProjectId(), old.getMetricKey());
                if (cur != null) {
                    resultValue = BigDecimal.valueOf(cur);
                }
            }
            if (resultValue == null) {
                throw new BusinessException("验证改进效果必须填写实际值");
            }
            old.setResultValue(resultValue);
            if (conclusion != null && !conclusion.isBlank()) {
                old.setConclusion(conclusion);
            }
        }
        old.setStatus(toStatus);
        old.setUpdatedBy(UserContext.currentUserId());
        old.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(old);
        audit.record(old.getProjectId(), "IMPROVEMENT", id, "STATUS_CHANGE",
                "改进 " + old.getCode() + " → " + toStatus
                        + ("VERIFIED".equals(toStatus) ? "（基线 " + old.getBaselineValue()
                        + " → 实际 " + old.getResultValue() + "）" : ""), null, old);
        return old;
    }

    @Transactional
    public void delete(Long id) {
        UserContext.requireRole("PM");
        Improvement old = get(id);
        mapper.deleteById(id);
        audit.record(old.getProjectId(), "IMPROVEMENT", id, "DELETE", "删除改进 " + old.getCode(), null, null);
    }

    private Improvement get(Long id) {
        Improvement imp = mapper.selectById(id);
        if (imp == null) {
            throw new BusinessException(4040, "改进项不存在");
        }
        return imp;
    }
}
