package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.domain.entity.GateCriterion;
import com.ipd.toolbox.domain.entity.StageGate;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.DecisionMapper;
import com.ipd.toolbox.mapper.GateCriterionMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.StageGateMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 结项检查：项目流转 CLOSED 前的"未了事项"盘点（口径对齐 AlertService）。
 * 非强制拦截——单人工具守卫软化：计数写入审计与前端确认框，决定权留给人。
 */
@Service
public class ClosureService {

    public record CloseoutCheck(long openRisks, long unreviewedGates, long openDefects,
                                long pendingChanges, long unmetRedlines) {
        public boolean clean() {
            return openRisks == 0 && unreviewedGates == 0 && openDefects == 0
                    && pendingChanges == 0 && unmetRedlines == 0;
        }
    }

    private final ProjectMapper projectMapper;
    private final WorkItemMapper workItemMapper;
    private final StageGateMapper stageGateMapper;
    private final DecisionMapper decisionMapper;
    private final GateCriterionMapper criterionMapper;

    public ClosureService(ProjectMapper projectMapper, WorkItemMapper workItemMapper,
                          StageGateMapper stageGateMapper, DecisionMapper decisionMapper,
                          GateCriterionMapper criterionMapper) {
        this.projectMapper = projectMapper;
        this.workItemMapper = workItemMapper;
        this.stageGateMapper = stageGateMapper;
        this.decisionMapper = decisionMapper;
        this.criterionMapper = criterionMapper;
    }

    public CloseoutCheck check(Long projectId) {
        if (projectMapper.selectById(projectId) == null) {
            throw new BusinessException(4040, "项目不存在");
        }
        long openRisks = workItemMapper.selectCount(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).eq("type", "RISK")
                .notIn("status", "Closed", "Accepted"));
        long openDefects = workItemMapper.selectCount(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).eq("type", "DEFECT").ne("status", "Closed"));
        long pendingChanges = workItemMapper.selectCount(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).eq("type", "CHANGE")
                .notIn("status", "Verified", "Rejected", "Implemented"));
        long unmetRedlines = criterionMapper.selectCount(new QueryWrapper<GateCriterion>()
                .eq("project_id", projectId).eq("is_redline", 1)
                .notIn("status", "MET", "WAIVED"));
        // 未评审 gate：无 PASS/CONDITIONAL 最新决策的阶段（按 subject 最新一条判断）
        Set<Long> passed = new HashSet<>();
        java.util.Map<Long, Decision> latest = new java.util.LinkedHashMap<>();
        for (Decision d : decisionMapper.selectList(new QueryWrapper<Decision>()
                .eq("project_id", projectId).eq("subject_type", "STAGE_GATE").orderByAsc("id"))) {
            latest.put(d.getSubjectId(), d);
        }
        latest.forEach((gateId, d) -> {
            if ("PASS".equals(d.getConclusion()) || "CONDITIONAL".equals(d.getConclusion())) {
                passed.add(gateId);
            }
        });
        List<StageGate> gates = stageGateMapper.selectList(new QueryWrapper<StageGate>()
                .eq("project_id", projectId));
        long unreviewedGates = gates.stream().filter(g -> !passed.contains(g.getId())).count();

        return new CloseoutCheck(openRisks, unreviewedGates, openDefects, pendingChanges, unmetRedlines);
    }
}
