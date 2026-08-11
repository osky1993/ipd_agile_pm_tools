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
 * 结项检查服务：项目流转 CLOSED 前的未了事项口径汇总。
 * 非强制拦截，结果用于界面提示与人工确认。
 */
@Service
public class ClosureService {

    public record CloseoutCheck(long openRisks, long unreviewedGates, long openDefects,
                                long pendingChanges, long unmetRedlines) {
        /**
         * 是否满足结项前置条件。
         *
         * 用途：
         * 检查五类口径（风险、门禁、缺陷、变更、红线）是否全部归零。
         *
         * 返回：
         * 全部为 0 时返回 true；任一口径有未清事项则返回 false。
         */
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

    /**
     * 结项检查依赖注入。
     * 覆盖风险、DCP、缺陷、变更、红线等五类口径来源，统一在 check() 返回摘要统计。
     * 仅查询与计数，不在此处产生流转副作用，结果用于上层是否继续结项提示。
     */
    public ClosureService(ProjectMapper projectMapper, WorkItemMapper workItemMapper,
                          StageGateMapper stageGateMapper, DecisionMapper decisionMapper,
                          GateCriterionMapper criterionMapper) {
        this.projectMapper = projectMapper;
        this.workItemMapper = workItemMapper;
        this.stageGateMapper = stageGateMapper;
        this.decisionMapper = decisionMapper;
        this.criterionMapper = criterionMapper;
    }

    /**
     * 生成结项前检查清单（只读）：
     * <ul>
     *   <li>统计五类未满足项：未闭合风险、未评审门禁、未关闭缺陷、未完成变更、未满足红线。</li>
     *   <li>门禁采用 Decision 最新结论判断 PASS/CONDITIONAL。</li>
     *   <li>无硬阻断副作用，只返回状态快照给上层决定。</li>
     * </ul>
     *
     * @param projectId 项目 ID
     * @return 结项检查计数结构体
     */
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
