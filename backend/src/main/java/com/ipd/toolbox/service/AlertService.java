package com.ipd.toolbox.service;

import com.ipd.toolbox.common.Labels;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.domain.entity.GateCriterion;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.StageGate;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.mapper.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 站内预警聚合：把数据里"该办的事"主动推到驾驶舱。
 * 全部规则为项目/流程级，不含任何个人维度。
 */
@Service
public class AlertService {

    public record Alert(String severity, String type, String title, String detail,
                        String refType, Long refId, String refCode, LocalDate due) {
    }

    private static final Map<String, Integer> SEV_RANK = Map.of("HIGH", 0, "MED", 1, "LOW", 2);
    static final int COMMITMENT_WINDOW_DAYS = 14;
    static final int WAIVER_WINDOW_DAYS = 14;
    static final int WIP_STALE_DAYS = 7;
    static final int DEFECT_AGING_DAYS = 14;
    static final int DCP_WINDOW_DAYS = 14;

    private final ProjectMapper projectMapper;
    private final WorkItemMapper workItemMapper;
    private final DecisionMapper decisionMapper;
    private final GateCriterionMapper criterionMapper;
    private final StageGateMapper stageGateMapper;
    private final TraceLinkMapper traceLinkMapper;
    private final PerfMapper perfMapper;
    private final PerfService perfService;

    /**
     * 告警服务依赖注入。
     * 告警口径跨越 WorkItem / Decision / StageGate / TraceLink / PERF，
     * 所以依赖点按服务边界分别用于“规则计算 + 历史数据 + 追溯链”三部分拼接。
     *
     * <p>说明：该类属于纯计算层，未直接进行状态更新，所有副作用仅来自所依赖服务的读取行为。</p>
     */
    public AlertService(ProjectMapper projectMapper, WorkItemMapper workItemMapper,
                        DecisionMapper decisionMapper, GateCriterionMapper criterionMapper,
                        StageGateMapper stageGateMapper, TraceLinkMapper traceLinkMapper,
                        PerfMapper perfMapper, PerfService perfService) {
        this.projectMapper = projectMapper;
        this.workItemMapper = workItemMapper;
        this.decisionMapper = decisionMapper;
        this.criterionMapper = criterionMapper;
        this.stageGateMapper = stageGateMapper;
        this.traceLinkMapper = traceLinkMapper;
        this.perfMapper = perfMapper;
        this.perfService = perfService;
    }

    /**
     * 按项目汇总预警。
     *
     * <p>聚合 8 大类告警（风险超期、承诺临近、红线未满足、待审批变更、
     * 已满足无证据、豁免到期、WIP 停滞、缺陷老化、DCP 即将到期、追溯链断裂），
     * 并按严重度与到期日排序返回。</p>
     * <p>返回语义：</p>
     * <ul>
     *   <li>按 `severity` 排序：HIGH(0) -> MED(1) -> LOW(2) -> 未识别 9。</li>
     *   <li>同级别按到期日升序；`due == null` 放最后。</li>
     *   <li>封闭项目（lifecycleStatus=CLOSED）直接返回空列表。</li>
     * </ul>
     * <p>边界：8类规则分别可能返回重复告警（例如同一对象被多个规则命中），当前实现不做跨规则去重。</p>
     *
     * @param projectId 项目 ID
     * @return 告警列表，按严重度/到期日排序（仅失败时抛业务异常）
     */
    public List<Alert> list(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(4040, "项目不存在");
        }
        if ("CLOSED".equals(project.getLifecycleStatus())) {
            return List.of(); // 已关闭项目不再打扰
        }
        LocalDate today = LocalDate.now();
        List<Alert> out = new ArrayList<>();
        out.addAll(riskOverdue(projectId, today));
        out.addAll(commitmentDue(projectId, today));
        out.addAll(redlineUnmet(projectId));
        out.addAll(changePending(projectId));
        out.addAll(metNoEvidence(projectId));
        out.addAll(waiverDue(projectId, today));
        out.addAll(wipStale(projectId, today));
        out.addAll(defectAging(projectId, today));
        out.addAll(dcpApproaching(projectId, today));
        out.addAll(traceGaps(projectId));
        out.sort(Comparator
                .comparing((Alert a) -> SEV_RANK.getOrDefault(a.severity(), 9))
                .thenComparing(a -> a.due() == null ? LocalDate.MAX : a.due()));
        return out;
    }

    /**
     * 风险超期告警（HIGH）。
     *
     * <p>基于 PerfService.riskDueDate 计算截至日，
     * 仅当 today 超过 dueDate 时发出，并附带当前敞口与等级说明。</p>
     *
     * <p>更新粒度：纯读；输出每条未关闭风险一条告警；已关闭/已接纳风险自动排除。</p>
     */
    List<Alert> riskOverdue(Long projectId, LocalDate today) {
        List<Alert> out = new ArrayList<>();
        for (WorkItem r : workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).eq("type", WorkItemType.RISK.name())
                .notIn("status", "Closed", "Accepted"))) {
            LocalDate due = perfService.riskDueDate(r);
            if (due != null && due.isBefore(today)) {
                RiskService.RiskExt ext = RiskService.parseExt(r.getExtFields());
                Integer exposure = RiskService.exposure(ext);
                String expTxt = exposure == null ? ""
                        : "，敞口 " + ext.probability() + "×" + ext.impact() + "=" + exposure
                                + "（" + ("HIGH".equals(RiskService.exposureLevel(exposure)) ? "高"
                                        : "MED".equals(RiskService.exposureLevel(exposure)) ? "中" : "低") + "）";
                out.add(new Alert("HIGH", "RISK_OVERDUE", "风险超期未处置",
                        r.getCode() + " " + r.getTitle() + "，期限 " + due + " 已过 "
                                + ChronoUnit.DAYS.between(due, today) + " 天" + expTxt,
                        "WORK_ITEM", r.getId(), r.getCode(), due));
            }
        }
        return out;
    }

    /**
     * 承诺到期告警（CONDITIONAL 审批）。
     *
     * <p>对同一 subject 仅取最新决策；承诺已到期走 HIGH，
     * 即将到期（14 天窗口）走 MED；若绑定风险已闭环则跳过。</p>
     *
     * <p>口径说明：`latest` 按 id 正序累积，后写覆盖先写，等价取 subject 最新决策。</p>
     */
    List<Alert> commitmentDue(Long projectId, LocalDate today) {
        // 按 id 升序遍历，后写的覆盖先写的 → map 中留下每个 subject 的最新决策
        Map<String, Decision> latest = new LinkedHashMap<>();
        for (Decision d : decisionMapper.selectList(new QueryWrapper<Decision>()
                .eq("project_id", projectId).orderByAsc("id"))) {
            latest.put(d.getSubjectType() + "#" + d.getSubjectId(), d);
        }
        List<Alert> out = new ArrayList<>();
        for (Decision d : latest.values()) {
            if (!"CONDITIONAL".equals(d.getConclusion()) || d.getCommitmentDue() == null) {
                continue;
            }
            if (d.getLinkedRiskId() != null) {
                WorkItem risk = workItemMapper.selectById(d.getLinkedRiskId());
                if (risk != null && ("Closed".equals(risk.getStatus()) || "Accepted".equals(risk.getStatus()))) {
                    continue; // 承诺已兑现
                }
            }
            LocalDate due = d.getCommitmentDue();
            if (due.isBefore(today)) {
                out.add(new Alert("HIGH", "COMMITMENT_DUE", "有条件通过承诺已到期",
                        "决策 " + d.getCode() + " 绑定风险未闭环，承诺期限 " + due,
                        "DECISION", d.getId(), d.getCode(), due));
            } else if (!due.isAfter(today.plusDays(COMMITMENT_WINDOW_DAYS))) {
                out.add(new Alert("MED", "COMMITMENT_DUE", "有条件通过承诺临近到期",
                        "决策 " + d.getCode() + " 承诺期限 " + due + "（"
                                + ChronoUnit.DAYS.between(today, due) + " 天后）",
                        "DECISION", d.getId(), d.getCode(), due));
            }
        }
        return out;
    }

    /**
     * 红线未满足告警（is_redline=1 且未 MET/WAIVED）。
     * 红线被 WAIVED 的场景仍按满足处理，不纳入告警。
     *
     * <p>边界：只基于 `criterion.status` + `is_redline` 字段，不额外检查证据或关联上下文。</p>
     */
    List<Alert> redlineUnmet(Long projectId) {
        List<Alert> out = new ArrayList<>();
        for (GateCriterion c : criterionMapper.selectList(new QueryWrapper<GateCriterion>()
                .eq("project_id", projectId).eq("is_redline", 1)
                .notIn("status", "MET", "WAIVED"))) {
            out.add(new Alert("HIGH", "REDLINE_UNMET", "红线条件未满足",
                    c.getCode() + " [" + c.getDomain() + "] " + c.getCriterion(),
                    "GATE_CRITERION", c.getId(), c.getCode(), null));
        }
        return out;
    }

    /**
     * 待审批变更告警。
     * 命中 Impact Analysed 且尚未决策的变更，以 MED 提示给到项目看板。
     *
     * <p>边界：仅看 `status=Impact Analysed`，不检查关联附件、影响范围、优先级。</p>
     */
    List<Alert> changePending(Long projectId) {
        List<Alert> out = new ArrayList<>();
        for (WorkItem c : workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).eq("type", WorkItemType.CHANGE.name())
                .eq("status", "Impact Analysed"))) {
            out.add(new Alert("MED", "CHANGE_PENDING", "变更待审批",
                    c.getCode() + " " + c.getTitle() + " 已完成影响分析，等待决策",
                    "WORK_ITEM", c.getId(), c.getCode(), null));
        }
        return out;
    }

    /**
     * 已满足但缺证据告警（MET_NO_EVIDENCE）。
     * 通过 perfMapper 读取“状态已满足但证据链缺失”的 DCP 条目。
     *
     * <p>失败策略：若 perfMapper 查询异常，本方法抛异常终止整个告警聚合；当前实现无局部降级。</p>
     */
    List<Alert> metNoEvidence(Long projectId) {
        List<Alert> out = new ArrayList<>();
        for (Map<String, Object> r : perfMapper.metWithoutEvidence(projectId)) {
            out.add(new Alert("MED", "MET_NO_EVIDENCE", "已满足条件缺证据",
                    r.get("code") + " " + r.get("criterion") + "——状态 MET 但未挂证据",
                    "GATE_CRITERION", ((Number) r.get("id")).longValue(),
                    String.valueOf(r.get("code")), null));
        }
        return out;
    }

    /**
     * 豁免到期告警。
     * 仅检查 status=WAIVED 且 waiver_due 在窗口内（含过期）的对象。
     *
     * <p>窗口语义：`c.getWaiverDue()` 小于等于 `today + 14天` 时告警；包含过期与当日到期。</p>
     */
    List<Alert> waiverDue(Long projectId, LocalDate today) {
        List<Alert> out = new ArrayList<>();
        for (GateCriterion c : criterionMapper.selectList(new QueryWrapper<GateCriterion>()
                .eq("project_id", projectId).eq("status", "WAIVED").isNotNull("waiver_due"))) {
            if (!c.getWaiverDue().isAfter(today.plusDays(WAIVER_WINDOW_DAYS))) {
                out.add(new Alert("MED", "WAIVER_DUE", "豁免临近/已到期",
                        c.getCode() + " " + c.getCriterion() + " 豁免期限 " + c.getWaiverDue(),
                        "GATE_CRITERION", c.getId(), c.getCode(), c.getWaiverDue()));
            }
        }
        return out;
    }

    /**
     * WIP 停滞告警（In Progress 超过 7 天无状态变化）。
     * 通过 perfMapper.lastMoveOfOpenItems 计算最近一次状态变更时间窗口。
     *
     * <p>边界：`perfMapper.lastMoveOfOpenItems` 为空时跳过；`last_move` 异常解析失败时按 0 天处理。</p>
     */
    List<Alert> wipStale(Long projectId, LocalDate today) {
        List<Alert> out = new ArrayList<>();
        for (Map<String, Object> r : perfMapper.lastMoveOfOpenItems(projectId)) {
            if (!"In Progress".equals(String.valueOf(r.get("status")))) {
                continue;
            }
            LocalDateTime last = PerfService.toLdt(r.get("last_move"));
            long days = last == null ? 0 : ChronoUnit.DAYS.between(last.toLocalDate(), today);
            if (days > WIP_STALE_DAYS) {
                out.add(new Alert("LOW", "WIP_STALE", "WIP 停滞超" + WIP_STALE_DAYS + "天",
                        r.get("code") + " " + r.get("title") + " 已 " + days + " 天无状态变化",
                        "WORK_ITEM", ((Number) r.get("id")).longValue(),
                        String.valueOf(r.get("code")), null));
            }
        }
        return out;
    }

    /**
     * DCP 即将到期/逾期告警。
     * 若该 DCP 已有 PASS 或 CONDITIONAL 决策，不再重复提示；
     * 在窗口内（14 天）为 MED，逾期为 HIGH。
     *
     * <p>更新粒度：对每个 stage_gate 最多一条结果；有 PASS/CONDITIONAL 最新结论则跳过。</p>
     * <p>稳定性：计划日仅从 `plan_date` 读取，缺失项不参与该规则。</p>
     */
    List<Alert> dcpApproaching(Long projectId, LocalDate today) {
        // 已有通过类结论的 gate 不再提醒（按 subject 取最新一条决策）
        Map<Long, Decision> latestByGate = new LinkedHashMap<>();
        for (Decision d : decisionMapper.selectList(new QueryWrapper<Decision>()
                .eq("project_id", projectId).eq("subject_type", "STAGE_GATE").orderByAsc("id"))) {
            latestByGate.put(d.getSubjectId(), d);
        }
        List<Alert> out = new ArrayList<>();
        for (StageGate g : stageGateMapper.selectList(new QueryWrapper<StageGate>()
                .eq("project_id", projectId).isNotNull("plan_date"))) {
            Decision latest = latestByGate.get(g.getId());
            if (latest != null && ("PASS".equals(latest.getConclusion())
                    || "CONDITIONAL".equals(latest.getConclusion()))) {
                continue;
            }
            LocalDate plan = g.getPlanDate();
            String name = g.getCode() + " " + g.getStageName() + "/" + g.getGateName();
            if (plan.isBefore(today)) {
                out.add(new Alert("HIGH", "DCP_APPROACHING", "DCP 已逾期未评审",
                        name + " 计划评审日 " + plan + " 已过 "
                                + ChronoUnit.DAYS.between(plan, today) + " 天",
                        "STAGE_GATE", g.getId(), g.getCode(), plan));
            } else if (!plan.isAfter(today.plusDays(DCP_WINDOW_DAYS))) {
                out.add(new Alert("MED", "DCP_APPROACHING", "DCP 评审临近",
                        name + " 计划评审日 " + plan + "（"
                                + ChronoUnit.DAYS.between(today, plan) + " 天后）",
                        "STAGE_GATE", g.getId(), g.getCode(), plan));
            }
        }
        return out;
    }

    /**
     * 追溯完整性告警（LOW）：
     * 1) 非 Backlog 的需求无 verifies 入链；
     * 2) 进行中及以后的需求既无 implements 入链也无 parent_of 子项；
     * 3) 非 Backlog 能力无 parent_of 出链；
     * 目的在于提前暴露断链问题而非等待人工排查矩阵。
     *
     * <p>更新口径说明：</p>
     * <ul>
     *   <li>先聚合项目范围内 `verifies/implements/parent_of` 链接集合。</li>
     *   <li>对每个需求/能力分别按规则拼接低优先级告警。</li>
     *   <li>同一对象可能产生多类告警，当前实现不去重。</li>
     * </ul>
     */
    List<Alert> traceGaps(Long projectId) {
        Set<Long> verified = new HashSet<>();
        Set<Long> implemented = new HashSet<>();
        Set<Long> hasChildren = new HashSet<>();
        for (TraceLink l : traceLinkMapper.selectList(new QueryWrapper<TraceLink>()
                .eq("project_id", projectId)
                .in("relation", "verifies", "implements", "parent_of"))) {
            switch (l.getRelation()) {
                case "verifies" -> verified.add(l.getTargetId());
                case "implements" -> implemented.add(l.getTargetId());
                case "parent_of" -> hasChildren.add(l.getSourceId());
            }
        }
        Set<String> started = Set.of("In Progress", "Verification", "Accepted");
        List<Alert> out = new ArrayList<>();
        for (WorkItem w : workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId)
                .in("type", WorkItemType.REQUIREMENT.name(), WorkItemType.CAPABILITY.name())
                .ne("status", "Backlog"))) {
            if (WorkItemType.REQUIREMENT.name().equals(w.getType())) {
                if (!verified.contains(w.getId())) {
                    out.add(new Alert("LOW", "TRACE_NO_VERIFIES", "需求无测试覆盖",
                            w.getCode() + " " + w.getTitle() + " 尚无 verifies 链（测试用例未关联）",
                            "WORK_ITEM", w.getId(), w.getCode(), null));
                }
                if (started.contains(w.getStatus())
                        && !implemented.contains(w.getId()) && !hasChildren.contains(w.getId())) {
                    out.add(new Alert("LOW", "TRACE_NO_IMPLEMENTS", "需求无分解/实现链",
                            w.getCode() + " " + w.getTitle() + " 已开工但无 implements 链且无子项",
                            "WORK_ITEM", w.getId(), w.getCode(), null));
                }
            } else if (!hasChildren.contains(w.getId())) {
                out.add(new Alert("LOW", "TRACE_CAP_NO_CHILD", "能力未分解需求",
                        w.getCode() + " " + w.getTitle() + " 无 parent_of 子项",
                        "WORK_ITEM", w.getId(), w.getCode(), null));
            }
        }
        return out;
    }

    /**
     * 长期未关缺陷告警。
     * 超过 14 天仍未关闭的缺陷将触发 MED 告警，促进及时收口。
     *
     * <p>边界：以 `created_at` 到当前日期为年龄衡量；`created_at` 缺失时视为 0 天，不触发告警。</p>
     */
    List<Alert> defectAging(Long projectId, LocalDate today) {
        List<Alert> out = new ArrayList<>();
        for (WorkItem d : workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).eq("type", WorkItemType.DEFECT.name())
                .ne("status", "Closed"))) {
            long days = d.getCreatedAt() == null ? 0
                    : ChronoUnit.DAYS.between(d.getCreatedAt().toLocalDate(), today);
            if (days > DEFECT_AGING_DAYS) {
                out.add(new Alert("MED", "DEFECT_AGING", "缺陷超" + DEFECT_AGING_DAYS + "天未关闭",
                        d.getCode() + " " + d.getTitle() + " 已打开 " + days + " 天（当前 " + Labels.status(d.getStatus(), d.getType()) + "）",
                        "WORK_ITEM", d.getId(), d.getCode(), null));
            }
        }
        return out;
    }
}
