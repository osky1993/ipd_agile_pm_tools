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

    /** 全局汇总面板中的一级指标集合。*/
    public record Summary(int projectsActive, int projectsOnHold, int projectsClosed,
                          long reqTotal, long reqAccepted, long openDefects, long pendingChanges,
                          long activeSprints, long alertHigh, long alertMed,
                          long improvementsDoing, long improvementsVerified) {
    }

    /** 单个项目在多项目大屏中的卡片模型。*/
    public record ProjectCard(Long id, String code, String name, String lifecycleStatus,
                              String stageName, String gateName, String lastDecision, String lastDecisionAt,
                              long reqTotal, long reqAccepted, Integer testPassRate,
                              long openDefects, long pendingChanges, long redlineUnmet,
                              boolean ready, long alertHigh, long alertMed,
                              long throughput4w, Double leadP85, String health) {
    }

    /** 周吞吐聚合行，按项目输出每周计数（仅保留有值项目）。*/
    public record WeekRow(String weekStart, Map<String, Long> byProject) {
    }

    /** 全局告警条目，按 severity 降序用于告警 Top12。*/
    public record ExecAlert(String severity, String type, String projectCode, String title, String detail) {
    }

    /** 缺陷入/关趋势的周粒度数据。*/
    public record DefectWeek(String weekStart, long inflow, long closed) {
    }

    /** 近期动态事件（项目维度）供大屏“最近变更”列表展示。*/
    public record RecentEvent(String projectCode, String summary, String at) {
    }

    /** 进行中的改善项摘要卡片，默认按 ID 降序截断。*/
    public record ActiveImprovement(String projectCode, String code, String title, String metricName) {
    }

    /** 多项目大屏总返回对象。*/
    public record Overview(Summary summary, List<ProjectCard> projects,
                           List<WeekRow> weeklyThroughput, List<ExecAlert> alerts,
                           List<DefectWeek> combinedDefectTrend, List<RecentEvent> recentEvents,
                           List<ActiveImprovement> activeImprovements) {
    }

    private final ProjectMapper projectMapper;
    private final MetricsMapper metricsMapper;
    private final PerfMapper perfMapper;
    private final StageGateMapper stageGateMapper;
    private final DecisionMapper decisionMapper;
    private final IterationMapper iterationMapper;
    private final ImprovementMapper improvementMapper;
    private final GateCriterionMapper criterionMapper;
    private final AuditEventMapper auditEventMapper;
    private final AlertService alertService;
    private final ReadinessService readinessService;

    /**
     * 多项目大屏聚合服务依赖注入。
     * 组合 Metrics/Perf/Iteration/Gate/Decision/Improvement/Audit 等来源，
     * 形成“全局经营看板”而非单一项目视角。
     */
    public ExecService(ProjectMapper projectMapper, MetricsMapper metricsMapper, PerfMapper perfMapper,
                       StageGateMapper stageGateMapper, DecisionMapper decisionMapper,
                       IterationMapper iterationMapper, ImprovementMapper improvementMapper,
                       GateCriterionMapper criterionMapper, AuditEventMapper auditEventMapper,
                       AlertService alertService, ReadinessService readinessService) {
        this.projectMapper = projectMapper;
        this.metricsMapper = metricsMapper;
        this.perfMapper = perfMapper;
        this.stageGateMapper = stageGateMapper;
        this.decisionMapper = decisionMapper;
        this.iterationMapper = iterationMapper;
        this.improvementMapper = improvementMapper;
        this.criterionMapper = criterionMapper;
        this.auditEventMapper = auditEventMapper;
        this.alertService = alertService;
        this.readinessService = readinessService;
    }

    /**
     * 返回整体经营大屏聚合数据。
     *
     * <p>按“项目→指标→事件”顺序一次性组装：
     * 1) 统计项目生命周期状态并汇总关键总量；
     * 2) 从 Metrics/Perf/Iteration/Alert 读出各项目看板字段；
     * 3) 通过 Perf 口径重算 4 周吞吐与 8 周吞吐桶；
     * 4) 汇总跨项目告警、缺陷趋势、最近动态和进行中改善项。</p>
     *
     * @return 可直接用于 Exec 大屏展示的总览对象。
     */
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
        Map<LocalDate, Long> defectInflowByDay = new HashMap<>();
        Map<LocalDate, Long> defectClosedByDay = new HashMap<>();
        Map<Long, String> codeOf = new HashMap<>();
        all.forEach(p -> codeOf.put(p.getId(), p.getCode()));

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

            // 组合缺陷流入/关闭（按日累积，稍后分桶）
            accumulateByDay(defectInflowByDay, metricsMapper.defectInflowByDay(p.getId()));
            accumulateByDay(defectClosedByDay, metricsMapper.defectClosedByDay(p.getId()));
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
        return new Overview(summary, cards, weeks, topAlerts,
                combinedDefectWeeks(defectInflowByDay, defectClosedByDay, today),
                recentEvents(codeOf), activeImprovements(codeOf));
    }

    /**
     * 组合缺陷入/闭环趋势到 8 周（按周一对齐）。
     *
     * <p>将 daily 粒度的 inflow/closed 归并为统一的 8 周窗口；窗口数和窗口边界由 {@link PerfService#bucketWeeks(Map, LocalDate, int)} 控制，
     * 以保证与吞吐窗口口径一致，便于横向比较。</p>
     *
     * @param inflow 日粒度缺陷流入计数
     * @param closed 日粒度缺陷关闭计数
     * @param today 当前日期（用于计算滚动窗口）
     * @return 按周顺序返回 8 个窗口的入闭环统计
     */
    static List<DefectWeek> combinedDefectWeeks(Map<LocalDate, Long> inflow, Map<LocalDate, Long> closed,
                                                LocalDate today) {
        List<PerfService.WeekPoint> in = PerfService.bucketWeeks(inflow, today, 8);
        List<PerfService.WeekPoint> cl = PerfService.bucketWeeks(closed, today, 8);
        List<DefectWeek> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            out.add(new DefectWeek(in.get(i).weekStart(), in.get(i).count(), cl.get(i).count()));
        }
        return out;
    }

    /**
     * 累加按日指标到目标 map。
     *
     * <p>上游 SQL 使用列名 {@code d} 与 {@code cnt} 返回行；方法负责将字符串日期解析为 {@link LocalDate}，
     * 并按 key 合并计数（同日多行自动求和）。</p>
     *
     * @param acc 已有聚合桶，按天累加
     * @param rows mapper 返回的行列表
     */
    private static void accumulateByDay(Map<LocalDate, Long> acc, List<Map<String, Object>> rows) {
        for (Map<String, Object> r : rows) {
            Object d = r.get("d");
            if (d != null) {
                acc.merge(LocalDate.parse(String.valueOf(d).substring(0, 10)), num(r.get("cnt")), Long::sum);
            }
        }
    }

    /**
     * 最近动态（跨项目）列表。
     *
     * <p>从审计表读取 {@code CREATE}/{@code STATUS_CHANGE}/{@code DECISION} 的最近 15 条日志，
     * 直接保留原生 summary 文案，优先显示项目编码与原始时间字符串，适配“按时间倒序”展示。</p>
     *
     * @param codeOf 项目 ID 到项目编码的映射，用于在审计事件中补齐 projectCode
     * @return 最近动态事件列表
     */
    private List<RecentEvent> recentEvents(Map<Long, String> codeOf) {
        List<RecentEvent> out = new ArrayList<>();
        for (com.ipd.toolbox.domain.entity.AuditEvent e : auditEventMapper.selectList(
                new QueryWrapper<com.ipd.toolbox.domain.entity.AuditEvent>()
                        .in("action", "CREATE", "STATUS_CHANGE", "DECISION")
                        .orderByDesc("id").last("LIMIT 15"))) {
            out.add(new RecentEvent(codeOf.getOrDefault(e.getProjectId(), "?"),
                    e.getSummary(), e.getAt() == null ? null : e.getAt().toString()));
        }
        return out;
    }

    /**
     * 进行中的流程改进行动列表。
     *
     * <p>只读取状态为 {@code DOING} 的改进行项，按 ID 倒序返回最近 8 条。
     * {@code metricName} 使用 {@link PerfService#def(String)} 做二次解析，若未匹配则回退到原始 metricKey。</p>
     *
     * @param codeOf 项目 ID 到项目编码的映射
     * @return 进行中改进项摘要列表
     */
    private List<ActiveImprovement> activeImprovements(Map<Long, String> codeOf) {
        List<ActiveImprovement> out = new ArrayList<>();
        for (Improvement i : improvementMapper.selectList(new QueryWrapper<Improvement>()
                .eq("status", "DOING").orderByDesc("id").last("LIMIT 8"))) {
            out.add(new ActiveImprovement(codeOf.getOrDefault(i.getProjectId(), "?"),
                    i.getCode(), i.getTitle(),
                    i.getMetricKey() == null ? null
                            : PerfService.def(i.getMetricKey()).map(PerfService.MetricDef::name)
                            .orElse(i.getMetricKey())));
        }
        return out;
    }

    /**
     * 健康度评级。
     *
     * <p>评分规则：
     * <ul>
     *   <li>{@code dangerSignal=true}（超期高危告警或已评审阶段红线未满足）=> {@code DANGER}</li>
     *   <li>存在 {@code HIGH}/{@code MED} 告警，或整体就绪状态为 false => {@code RISK}</li>
     *   <li>其余 => {@code GOOD}</li>
     * </ul>
     * 该方法刻意将“未评审阶段红线未满足”视为正常推进，不直接判定危险。</p>
     *
     * @param dangerSignal 是否触发阻断型风险信号
     * @param alertHigh 全局高危告警数
     * @param alertMed 全局中等告警数
     * @param ready 项目/体系级就绪状态
     * @return 健康度枚举值文本
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

    /**
     * 判断项目是否存在“红线未满足且 gate 已进入评审”场景。
     *
     * <p>红线未满足本身不是立即阻断条件；只有对应的 {@code StageGate} 已产出决策记录时，
     * 才认定为评审态阻断风险（用于健康度 DANGER）。</p>
     *
     * <p>实现上分两步：
     * 1) 拉取未满足红线明细；
     * 2) 拉取项目下所有 STAGE_GATE 决策并求交集，命中任一门禁即返回 true。
     * 这一步不做分页，避免重复评估丢失边界。</p>
     *
     * @param projectId 项目 ID
     * @return {@code true} 表示存在“已评审且未满足红线”的情形
     */
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

    /**
     * 查询项目内未满足红线条件。
     *
     * <p>SQL 口径：is_redline=1 且 status 不在 {@code MET}/{@code WAIVED}。</p>
     *
     * <p>不分页、不聚合，按数据库默认排序返回。上游可按 domain / status 再次归类。</p>
     *
     * @param projectId 项目 ID
     * @return 该项目所有未满足红线明细
     */
    private List<com.ipd.toolbox.domain.entity.GateCriterion> criterionMapperUnmetRedlines(Long projectId) {
        return criterionMapper.selectList(new QueryWrapper<com.ipd.toolbox.domain.entity.GateCriterion>()
                .eq("project_id", projectId).eq("is_redline", 1).notIn("status", "MET", "WAIVED"));
    }

    /**
     * 安全的 {@link Number} 转 {@code long}。
     *
     * <p>用于聚合查询列值（通常是 Long/Integer/BigDecimal），统一处理 {@code null} 并避免 NPE。
     * 当前实现默认视输入为 Number；调用方保证 select 列返回可数值类型或 null。</p>
     *
     * @param o 聚合列值
     * @return 0（空值）或数值转换结果
     */
    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
