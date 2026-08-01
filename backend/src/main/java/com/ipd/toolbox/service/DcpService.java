package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.domain.entity.GateCriterion;
import com.ipd.toolbox.domain.entity.StageGate;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.mapper.GateCriterionMapper;
import com.ipd.toolbox.mapper.StageGateMapper;
import com.ipd.toolbox.mapper.TraceLinkMapper;
import com.ipd.toolbox.security.UserContext;
import com.ipd.toolbox.statemachine.GuardException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * DCP 决策服务（T502/T503/T505）：准备度计算 + 规则引擎 + 评审决策。
 * 系统计算准备度，但不自动决定是否通过——最终决策由授权角色作出（规划§2.4）。
 */
@Service
public class DcpService {

    private final GateCriterionMapper criterionMapper;
    private final StageGateMapper stageGateMapper;
    private final TraceLinkMapper traceLinkMapper;
    private final DecisionService decisionService;
    private final ReadinessService readinessService;
    private final ObjectMapper objectMapper;

    public DcpService(GateCriterionMapper criterionMapper, StageGateMapper stageGateMapper,
                      TraceLinkMapper traceLinkMapper, DecisionService decisionService,
                      ReadinessService readinessService, ObjectMapper objectMapper) {
        this.criterionMapper = criterionMapper;
        this.stageGateMapper = stageGateMapper;
        this.traceLinkMapper = traceLinkMapper;
        this.decisionService = decisionService;
        this.readinessService = readinessService;
        this.objectMapper = objectMapper;
    }

    public record CriterionView(Long id, String code, String domain, String criterion, Long ownerId,
                                String status, Integer isRedline, String evidenceReq, int evidenceCount,
                                Long linkedRiskId) {
    }

    public record DomainStat(int total, int met, int partial, int notReady, int waived) {
    }

    /** 准备度快照（规划§7.4）：分领域状态 + 红线未满足/证据缺失/无责任人清单。 */
    public record Snapshot(Map<String, DomainStat> byDomain, List<String> redlineUnmet,
                           List<String> evidenceMissing, List<String> ownerMissing, int pending) {
    }

    public record Overview(List<CriterionView> criteria, Snapshot snapshot) {
    }

    public Overview overview(Long stageGateId) {
        StageGate gate = stageGateMapper.selectById(stageGateId);
        List<GateCriterion> criteria = criterionMapper.selectList(new QueryWrapper<GateCriterion>()
                .eq("stage_gate_id", stageGateId).eq("is_readiness", 0).orderByAsc("domain").orderByAsc("id"));

        List<CriterionView> views = new ArrayList<>();
        Map<String, int[]> domainCounts = new LinkedHashMap<>(); // [total,met,partial,notReady,waived]
        List<String> redlineUnmet = new ArrayList<>();
        List<String> evidenceMissing = new ArrayList<>();
        List<String> ownerMissing = new ArrayList<>();
        int pending = 0;

        for (GateCriterion c : criteria) {
            int evCount = (int) evidenceCount(c.getId());
            views.add(new CriterionView(c.getId(), c.getCode(), c.getDomain(), c.getCriterion(),
                    c.getOwnerId(), c.getStatus(), c.getIsRedline(), c.getEvidenceReq(), evCount, c.getLinkedRiskId()));

            int[] cnt = domainCounts.computeIfAbsent(c.getDomain(), k -> new int[5]);
            cnt[0]++;
            switch (c.getStatus()) {
                case "MET" -> cnt[1]++;
                case "PARTIAL" -> cnt[2]++;
                case "WAIVED" -> cnt[4]++;
                default -> cnt[3]++;
            }

            boolean met = "MET".equals(c.getStatus());
            boolean waived = "WAIVED".equals(c.getStatus());
            // 红线未满足：红线条件且未满足也未豁免
            if (c.getIsRedline() != null && c.getIsRedline() == 1 && !met && !waived) {
                redlineUnmet.add(c.getCode());
            }
            // 证据缺失：已满足但无证据
            if (met && evCount == 0) {
                evidenceMissing.add(c.getCode());
            }
            if (c.getOwnerId() == null) {
                ownerMissing.add(c.getCode());
            }
            if (c.getReviewConclusion() == null || c.getReviewConclusion().isBlank()) {
                pending++;
            }
        }

        // 纳入跨职能准备度红线（规划§8.4：某领域红线问题能影响 DCP 判断）
        if (gate != null) {
            for (String rl : readinessService.readinessRedlineUnmet(gate.getProjectId())) {
                redlineUnmet.add(rl + "(准备度)");
            }
        }

        Map<String, DomainStat> byDomain = new LinkedHashMap<>();
        domainCounts.forEach((d, a) -> byDomain.put(d, new DomainStat(a[0], a[1], a[2], a[3], a[4])));
        return new Overview(views, new Snapshot(byDomain, redlineUnmet, evidenceMissing, ownerMissing, pending));
    }

    /**
     * DCP 评审决策（T505）。规则（规划§7.3）：
     * - PASS 时若存在红线未满足，不允许判通过（守卫 GUARD_REDLINE_UNMET）；
     * - CONDITIONAL 必须绑定遗留风险 + 完成期限；
     * - 决策固化准备度快照，只增不改。
     */
    @Transactional
    public Decision review(Long stageGateId, String conclusion, String reason,
                           Long linkedRiskId, LocalDate commitmentDue) {
        UserContext.requireRole("REVIEWER");
        StageGate gate = stageGateMapper.selectById(stageGateId);
        if (gate == null) {
            throw new BusinessException("阶段/DCP 不存在");
        }
        if (!Set.of("PASS", "CONDITIONAL", "REJECT").contains(conclusion)) {
            throw new BusinessException("无效评审结论");
        }
        Snapshot snap = overview(stageGateId).snapshot();

        if ("PASS".equals(conclusion) && !snap.redlineUnmet().isEmpty()) {
            throw new GuardException("GUARD_REDLINE_UNMET",
                    "存在红线未满足项，不能判通过：" + String.join("、", snap.redlineUnmet()));
        }
        if ("CONDITIONAL".equals(conclusion) && (linkedRiskId == null || commitmentDue == null)) {
            throw new BusinessException("有条件通过必须绑定遗留风险与完成期限");
        }

        Decision d = new Decision();
        d.setProjectId(gate.getProjectId());
        d.setDecisionType("DCP");
        d.setSubjectType("STAGE_GATE");
        d.setSubjectId(stageGateId);
        d.setConclusion(conclusion);
        d.setReason(reason);
        d.setLinkedRiskId(linkedRiskId);
        d.setCommitmentDue(commitmentDue);
        d.setSnapshot(toJson(snap));
        return decisionService.record(d);
    }

    private long evidenceCount(Long criterionId) {
        Long n = traceLinkMapper.selectCount(new QueryWrapper<TraceLink>()
                .eq("source_type", "GATE_CRITERION").eq("source_id", criterionId).eq("relation", "evidences"));
        return n == null ? 0 : n;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
