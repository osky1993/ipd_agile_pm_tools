package com.ipd.toolbox.service;

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

    /** 超期风险（HIGH）。 */
    List<Alert> riskOverdue(Long projectId, LocalDate today) {
        List<Alert> out = new ArrayList<>();
        for (WorkItem r : workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).eq("type", WorkItemType.RISK.name())
                .notIn("status", "Closed", "Accepted"))) {
            LocalDate due = perfService.riskDueDate(r);
            if (due != null && due.isBefore(today)) {
                out.add(new Alert("HIGH", "RISK_OVERDUE", "风险超期未处置",
                        r.getCode() + " " + r.getTitle() + "，期限 " + due + " 已过 "
                                + ChronoUnit.DAYS.between(due, today) + " 天",
                        "WORK_ITEM", r.getId(), r.getCode(), due));
            }
        }
        return out;
    }

    /** 有条件通过承诺到期（每个 subject 只看最新决策；绑定风险已闭环则不告警）。 */
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

    /** 红线未满足（WAIVED 视为满足）。 */
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

    /** 待审批变更。 */
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

    /** MET 但无证据的条件。 */
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

    /** 豁免到期（含已过期）。 */
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

    /** WIP 停滞（In Progress 超 7 天无状态变化）。 */
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

    /** DCP 计划评审日临近/已逾期且尚无 PASS/CONDITIONAL 决策。 */
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
     * 追溯完整性（LOW，断链主动暴露而非等人查矩阵）：
     * ① 非 Backlog 需求无 verifies 入链（无测试覆盖）
     * ② 进行中及以后的需求既无 implements 入链也无 parent_of 子项（无分解/实现链）
     * ③ 非 Backlog 能力无 parent_of 出链（未分解出需求）
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

    /** 长期未关缺陷。 */
    List<Alert> defectAging(Long projectId, LocalDate today) {
        List<Alert> out = new ArrayList<>();
        for (WorkItem d : workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).eq("type", WorkItemType.DEFECT.name())
                .ne("status", "Closed"))) {
            long days = d.getCreatedAt() == null ? 0
                    : ChronoUnit.DAYS.between(d.getCreatedAt().toLocalDate(), today);
            if (days > DEFECT_AGING_DAYS) {
                out.add(new Alert("MED", "DEFECT_AGING", "缺陷超" + DEFECT_AGING_DAYS + "天未关闭",
                        d.getCode() + " " + d.getTitle() + " 已打开 " + days + " 天（当前 " + d.getStatus() + "）",
                        "WORK_ITEM", d.getId(), d.getCode(), null));
            }
        }
        return out;
    }
}
