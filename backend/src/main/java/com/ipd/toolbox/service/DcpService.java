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

    private final BaselineService baselineService;

    /**
     * DCP 决策服务依赖注入。
     * criterionMapper 提供指标定义与状态；stageGateMapper 读取当前门数据；
     * traceLinkMapper 统计证据；decisionService 负责决策落库；
     * readinessService 注入跨职能红线；baselineService 负责通过/有条件通过时固化修订基线。
     */
    public DcpService(GateCriterionMapper criterionMapper, StageGateMapper stageGateMapper,
                      TraceLinkMapper traceLinkMapper, DecisionService decisionService,
                      ReadinessService readinessService, ObjectMapper objectMapper,
                      BaselineService baselineService) {
        this.criterionMapper = criterionMapper;
        this.stageGateMapper = stageGateMapper;
        this.traceLinkMapper = traceLinkMapper;
        this.decisionService = decisionService;
        this.readinessService = readinessService;
        this.objectMapper = objectMapper;
        this.baselineService = baselineService;
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

    /**
     * DCP 页面总览（T505）：返回当前阶段门的清单与分类聚合快照。
     * 快照包含分领域状态、红线未满足、证据缺失、责任人缺失与待处理项数量，供前端状态判断与人工决策。
     *
     * <p>返回结构中的 snapshot 侧重可视化，不带分页和排序规则；overview 只读执行，无数据库写入。
     * readinessService 返回红线项在此会按“准备度红线”追加到同一清单。</p>
     */
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
     *
     * <p>副作用：
     * 1) 审批通过/条件通过会调用 DecisionService.record 落库；
     * 2) 对 PASS/CONDITIONAL 再创建并固化一条基线（可重复评审覆盖）；
     * 3) 决策结论校验与红线守卫在此方法内兜底，确保 UI 端不会绕过规则。</p>
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
        Decision saved = decisionService.record(d);

        // 通过类结论 = 承诺时刻：自动固化范围基线（同 gate 重复评审生成新基线，即修订语义）
        if ("PASS".equals(conclusion) || "CONDITIONAL".equals(conclusion)) {
            baselineService.create(gate.getProjectId(), "B-" + gate.getCode(), "DCP",
                    stageGateId, saved.getId());
        }
        return saved;
    }

    /**
     * 证据数量统计：按标准关系名 evidences 统计当前标准关联的 TraceLink 数量。
     * 未命中时返回 0，避免上游空指针并保持状态统计稳定。
     *
     * @param criterionId 标准 ID
     * @return 证据数量；null 场景按 0 兜底
     */
    private long evidenceCount(Long criterionId) {
        Long n = traceLinkMapper.selectCount(new QueryWrapper<TraceLink>()
                .eq("source_type", "GATE_CRITERION").eq("source_id", criterionId).eq("relation", "evidences"));
        return n == null ? 0 : n;
    }

    /**
     * 将快照对象序列化为 JSON。
     *
     * <p>序列化异常视为兼容退化：返回 null，由上游决策仍可继续落库。
     * 该策略牺牲快照可追溯性换取决策链路可用性，属于“不中断治理流程”选择。</p>
     *
     * @param o 待序列化对象
     */
    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
