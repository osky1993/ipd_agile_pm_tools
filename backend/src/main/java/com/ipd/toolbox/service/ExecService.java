package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.domain.entity.Improvement;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.StageGate;
import com.ipd.toolbox.mapper.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 多项目聚合大屏（面向领导）。纯读汇总与下钻（规划§9.3），
 * 全部复用现有指标/预警口径，不新增任何指标定义；无个人绩效口径。
 */
@Service
public class ExecService {

    public record Summary(int projectsActive, int projectsOnHold, int projectsClosed,
                          long reqTotal, long reqAccepted, long openDefects, long pendingChanges,
                          long activeSprints, long alertHigh, long alertMed,
                          long improvementsDoing, long improvementsVerified) {
    }

    public record ProjectCard(Long id, String code, String name, String lifecycleStatus,
                              String stageName, String gateName, String lastDecision, String lastDecisionAt,
                              long reqTotal, long reqAccepted, Integer testPassRate,
                              long openDefects, long pendingChanges, long redlineUnmet,
                              boolean ready, long alertHigh, long alertMed,
                              long throughput4w, Double leadP85, String health) {
    }

    public record WeekRow(String weekStart, Map<String, Long> byProject) {
    }

    public record ExecAlert(String severity, String type, String projectCode, String title, String detail) {
    }

    public record Overview(Summary summary, List<ProjectCard> projects,
                           List<WeekRow> weeklyThroughput, List<ExecAlert> alerts) {
    }

    private final ProjectMapper projectMapper;
    private final MetricsMapper metricsMapper;
    private final PerfMapper perfMapper;
    private final StageGateMapper stageGateMapper;
    private final DecisionMapper decisionMapper;
    private final IterationMapper iterationMapper;
    private final ImprovementMapper improvementMapper;
    private final GateCriterionMapper criterionMapper;
    private final AlertService alertService;
    private final ReadinessService readinessService;

    public ExecService(ProjectMapper projectMapper, MetricsMapper metricsMapper, PerfMapper perfMapper,
                       StageGateMapper stageGateMapper, DecisionMapper decisionMapper,
                       IterationMapper iterationMapper, ImprovementMapper improvementMapper,
                       GateCriterionMapper criterionMapper,
                       AlertService alertService, ReadinessService readinessService) {
        this.projectMapper = projectMapper;
        this.metricsMapper = metricsMapper;
        this.perfMapper = perfMapper;
        this.stageGateMapper = stageGateMapper;
        this.decisionMapper = decisionMapper;
        this.iterationMapper = iterationMapper;
        this.improvementMapper = improvementMapper;
        this.criterionMapper = criterionMapper;
        this.alertService = alertService;
        this.readinessService = readinessService;
    }

    public Overview overview() {
        List<Project> all = projectMapper.selectList(new QueryWrapper<Project>().orderByAsc("id"));
        LocalDate today = LocalDate.now();
        LocalDateTime since4w = today.minusDays(28).atStartOfDay();
        LocalDateTime since8w = today.minusWeeks(8).atStartOfDay();

        int active = 0, onHold = 0, closed = 0;
        long reqTotal = 0, reqAccepted = 0, openDefects = 0, pendingChanges = 0;
        long alertHighSum = 0, alertMedSum = 0;
        List<ProjectCard> cards = new ArrayList<>();
        Map<String, Map<LocalDate, Long>> weeklyByProject = new LinkedHashMap<>();
        List<ExecAlert> allAlerts = new ArrayList<>();

        for (Project p : all) {
            switch (p.getLifecycleStatus() == null ? "ACTIVE" : p.getLifecycleStatus()) {
                case "ON_HOLD" -> onHold++;
                case "CLOSED" -> closed++;
                default -> active++;
            }
            if ("CLOSED".equals(p.getLifecycleStatus())) {
                continue; // 已关闭项目不上卡片，只计数
            }
            Map<String, Object> pm = metricsMapper.projectMetrics(p.getId());
            if (pm == null) {
                pm = Map.of();
            }
            long rt = num(pm.get("requirement_total"));
            long ra = num(pm.get("requirement_accepted"));
            long od = num(pm.get("open_defects"));
            long pc = num(pm.get("pending_changes"));
            reqTotal += rt;
            reqAccepted += ra;
            openDefects += od;
            pendingChanges += pc;

            Map<String, Object> pass = metricsMapper.testPassStats(p.getId());
            long casesWithRuns = num(pass.get("cases_with_runs"));
            Integer passRate = casesWithRuns == 0 ? null
                    : (int) Math.round(num(pass.get("pass_cases")) * 100.0 / casesWithRuns);

            Map<String, Object> rl = perfMapper.redlineStats(p.getId());
            long redlineUnmet = num(rl.get("total")) - num(rl.get("satisfied"));
            // 红线是否已"进入评审"（所属 gate 有过决策）：未评审阶段的红线未满足属正常推进中，不判红
            boolean redlineInReviewedGate = redlineUnmet > 0 && hasReviewedUnmetRedline(p.getId());

            List<AlertService.Alert> alerts = alertService.list(p.getId());
            long high = alerts.stream().filter(a -> "HIGH".equals(a.severity())).count();
            long med = alerts.stream().filter(a -> "MED".equals(a.severity())).count();
            // 超期类 HIGH（风险超期/承诺过期）比"红线尚未满足"更接近失控信号
            boolean overdueHigh = alerts.stream().anyMatch(a -> "HIGH".equals(a.severity())
                    && ("RISK_OVERDUE".equals(a.type()) || "COMMITMENT_DUE".equals(a.type())));
            alertHighSum += high;
            alertMedSum += med;
            for (AlertService.Alert a : alerts) {
                allAlerts.add(new ExecAlert(a.severity(), a.type(), p.getCode(), a.title(), a.detail()));
            }

            boolean ready = readinessService.summary(p.getId()).overall().ready();

            StageGate gate = stageGateMapper.selectOne(new QueryWrapper<StageGate>()
                    .eq("project_id", p.getId()).orderByDesc("seq").orderByDesc("id").last("LIMIT 1"));
            Decision lastDcp = decisionMapper.selectOne(new QueryWrapper<Decision>()
                    .eq("project_id", p.getId()).eq("decision_type", "DCP").orderByDesc("id").last("LIMIT 1"));

            long throughput = perfMapper.acceptedSince(p.getId(), since4w);
            List<Integer> lead = perfMapper.leadDays(p.getId()).stream().filter(Objects::nonNull).sorted().toList();
            Double leadP85 = PerfService.percentile(lead, 85);

            cards.add(new ProjectCard(p.getId(), p.getCode(), p.getName(), p.getLifecycleStatus(),
                    gate == null ? null : gate.getStageName(), gate == null ? null : gate.getGateName(),
                    lastDcp == null ? null : lastDcp.getConclusion(),
                    lastDcp == null || lastDcp.getDecidedAt() == null ? null
                            : lastDcp.getDecidedAt().toLocalDate().toString(),
                    rt, ra, passRate, od, pc, redlineUnmet, ready, high, med,
                    throughput, leadP85,
                    health(overdueHigh || redlineInReviewedGate, high, med, ready)));

            // 每项目 8 周吞吐
            Map<LocalDate, Long> byDay = new HashMap<>();
            for (Map<String, Object> r : perfMapper.acceptedByDay(p.getId(), since8w)) {
                Object d = r.get("d");
                if (d != null) {
                    byDay.put(LocalDate.parse(String.valueOf(d).substring(0, 10)), num(r.get("cnt")));
                }
            }
            weeklyByProject.put(p.getCode(), byDay);
        }

        long activeSprints = iterationMapper.selectCount(new QueryWrapper<com.ipd.toolbox.domain.entity.Iteration>()
                .eq("status", "ACTIVE"));
        long doing = improvementMapper.selectCount(new QueryWrapper<Improvement>().eq("status", "DOING"));
        long verified = improvementMapper.selectCount(new QueryWrapper<Improvement>().eq("status", "VERIFIED"));

        // 周聚合：各项目分桶后按周对齐
        List<WeekRow> weeks = new ArrayList<>();
        List<String> codes = new ArrayList<>(weeklyByProject.keySet());
        Map<String, List<PerfService.WeekPoint>> bucketed = new LinkedHashMap<>();
        for (String c : codes) {
            bucketed.put(c, PerfService.bucketWeeks(weeklyByProject.get(c), today, 8));
        }
        for (int i = 0; i < 8; i++) {
            String weekStart = null;
            Map<String, Long> byProject = new LinkedHashMap<>();
            for (String c : codes) {
                PerfService.WeekPoint wp = bucketed.get(c).get(i);
                weekStart = wp.weekStart();
                if (wp.count() > 0) {
                    byProject.put(c, wp.count());
                }
            }
            weeks.add(new WeekRow(weekStart, byProject));
        }

        // 跨项目预警：HIGH > MED > LOW 取前 12
        Map<String, Integer> rank = Map.of("HIGH", 0, "MED", 1, "LOW", 2);
        allAlerts.sort(Comparator.comparing(a -> rank.getOrDefault(a.severity(), 9)));
        List<ExecAlert> topAlerts = allAlerts.stream().limit(12).toList();

        Summary summary = new Summary(active, onHold, closed, reqTotal, reqAccepted,
                openDefects, pendingChanges, activeSprints, alertHighSum, alertMedSum, doing, verified);
        return new Overview(summary, cards, weeks, topAlerts);
    }

    /**
     * 健康度：失控信号（超期类 HIGH / 已评审阶段仍红线未满足）→ DANGER；
     * 其余 HIGH/MED 预警或整机未就绪 → RISK；否则 GOOD。
     * 未进入评审的阶段红线未满足属正常推进中，不判红（避免早期项目误报）。
     */
    static String health(boolean dangerSignal, long alertHigh, long alertMed, boolean ready) {
        if (dangerSignal) {
            return "DANGER";
        }
        if (alertHigh > 0 || alertMed > 0 || !ready) {
            return "RISK";
        }
        return "GOOD";
    }

    /** 是否存在"所属 gate 已有决策记录"的未满足红线。 */
    private boolean hasReviewedUnmetRedline(Long projectId) {
        List<com.ipd.toolbox.domain.entity.GateCriterion> unmet = criterionMapperUnmetRedlines(projectId);
        if (unmet.isEmpty()) {
            return false;
        }
        Set<Long> reviewedGates = new HashSet<>();
        for (Decision d : decisionMapper.selectList(new QueryWrapper<Decision>()
                .eq("project_id", projectId).eq("subject_type", "STAGE_GATE"))) {
            reviewedGates.add(d.getSubjectId());
        }
        return unmet.stream().anyMatch(c -> c.getStageGateId() != null
                && reviewedGates.contains(c.getStageGateId()));
    }

    private List<com.ipd.toolbox.domain.entity.GateCriterion> criterionMapperUnmetRedlines(Long projectId) {
        return criterionMapper.selectList(new QueryWrapper<com.ipd.toolbox.domain.entity.GateCriterion>()
                .eq("project_id", projectId).eq("is_redline", 1).notIn("status", "MET", "WAIVED"));
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
