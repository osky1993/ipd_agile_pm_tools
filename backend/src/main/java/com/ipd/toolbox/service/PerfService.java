package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Iteration;
import com.ipd.toolbox.domain.entity.MetricTarget;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.mapper.*;
import com.ipd.toolbox.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 效能指标体系（数据监控 → 改进方案 → 跟踪落地）。
 * 5 组 L1 / 分层 L2、L3，全部由业务数据自动计算；注册表是"无个人绩效口径"的唯一出口（规划§13 红线）。
 */
@Service
public class PerfService {

    /** 指标定义。direction: higher=越高越好 / lower=越低越好。 */
    public record MetricDef(String key, String name, String group, int level, String parent,
                            String unit, String direction) {
    }

    public record Metric(String key, String name, int level, String parent, String unit,
                         String direction, Double value, Double target, String status) {
    }

    public record Group(String key, String name, List<Metric> metrics) {
    }

    public record WeekPoint(String weekStart, long count) {
    }

    public record TypeCount(String type, String label, long count) {
    }

    public record StageStay(String stage, Double avgDays, int samples) {
    }

    public record StaleItem(Long id, String code, String title, String status, long days) {
    }

    public record Charts(List<WeekPoint> weeklyThroughput, List<TypeCount> throughputByType,
                         List<StageStay> stageDurations, List<StaleItem> staleTop) {
    }

    public record PerfOverview(List<Group> groups, Charts charts) {
    }

    private static final Map<String, String> GROUP_NAMES = new LinkedHashMap<>();
    /** 指标注册表（顺序即展示顺序）。 */
    public static final List<MetricDef> REGISTRY = new ArrayList<>();

    static {
        GROUP_NAMES.put("delivery", "交付速率");
        GROUP_NAMES.put("cycle", "周期时间");
        GROUP_NAMES.put("quality", "质量合格率");
        GROUP_NAMES.put("flow", "资源与流动");
        GROUP_NAMES.put("gov", "流程治理");

        reg("delivery.throughput4w", "近4周吞吐", "delivery", 2, null, "件", "higher");
        reg("delivery.commitRateCount", "迭代承诺完成率(件)", "delivery", 2, null, "%", "higher");
        reg("delivery.commitRatePoints", "迭代承诺完成率(点)", "delivery", 3, "delivery.commitRateCount", "%", "higher");
        reg("cycle.leadP50", "端到端 Lead Time P50", "cycle", 2, null, "天", "lower");
        reg("cycle.leadP85", "Lead Time P85", "cycle", 3, "cycle.leadP50", "天", "lower");
        reg("cycle.cycleP50", "Cycle Time P50", "cycle", 2, null, "天", "lower");
        reg("cycle.cycleP85", "Cycle Time P85", "cycle", 3, "cycle.cycleP50", "天", "lower");
        reg("cycle.cycleP95", "Cycle Time P95", "cycle", 3, "cycle.cycleP50", "天", "lower");
        reg("cycle.defectFixAvg", "缺陷平均修复天数", "cycle", 2, null, "天", "lower");
        reg("quality.firstPassRate", "测试首次通过率", "quality", 2, null, "%", "higher");
        reg("quality.latestPassRate", "测试最新通过率", "quality", 3, "quality.firstPassRate", "%", "higher");
        reg("quality.defectDensity", "缺陷密度(缺陷/需求)", "quality", 2, null, "", "lower");
        reg("quality.reopenRate", "缺陷复开率", "quality", 2, null, "%", "lower");
        reg("quality.reqCoverage", "需求测试覆盖率", "quality", 2, null, "%", "higher");
        reg("flow.wip", "WIP 水位", "flow", 2, null, "件", "lower");
        reg("flow.capacityUtil", "迭代容量利用率(点)", "flow", 2, null, "%", "higher");
        reg("flow.readyCount", "Ready 就绪库存", "flow", 2, null, "件", "higher");
        reg("flow.staleCount", "停滞(>7天)工作项", "flow", 2, null, "件", "lower");
        reg("gov.changeCycleAvg", "变更平均处理周期", "gov", 2, null, "天", "lower");
        reg("gov.riskOnTimeRate", "风险按期处置率", "gov", 2, null, "%", "higher");
        reg("gov.redlineRate", "红线满足率", "gov", 2, null, "%", "higher");
        reg("gov.metEvidenceRate", "MET条件证据完备率", "gov", 2, null, "%", "higher");
    }

    private static void reg(String key, String name, String group, int level, String parent,
                            String unit, String direction) {
        REGISTRY.add(new MetricDef(key, name, group, level, parent, unit, direction));
    }

    public static Optional<MetricDef> def(String key) {
        return REGISTRY.stream().filter(d -> d.key().equals(key)).findFirst();
    }

    private final PerfMapper perfMapper;
    private final MetricsMapper metricsMapper;
    private final MetricTargetMapper targetMapper;
    private final IterationMapper iterationMapper;
    private final WorkItemMapper workItemMapper;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public PerfService(PerfMapper perfMapper, MetricsMapper metricsMapper, MetricTargetMapper targetMapper,
                       IterationMapper iterationMapper, WorkItemMapper workItemMapper,
                       AuditService audit, ObjectMapper objectMapper) {
        this.perfMapper = perfMapper;
        this.metricsMapper = metricsMapper;
        this.targetMapper = targetMapper;
        this.iterationMapper = iterationMapper;
        this.workItemMapper = workItemMapper;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public PerfOverview metrics(Long projectId) {
        Map<String, Object> pm = metricsMapper.projectMetrics(projectId);
        if (pm == null) {
            throw new BusinessException(4040, "项目不存在");
        }
        LocalDate today = LocalDate.now();
        Map<String, Double> values = computeValues(projectId, pm, today);
        Map<String, Double> targets = loadTargets(projectId);

        Map<String, List<Metric>> byGroup = new LinkedHashMap<>();
        GROUP_NAMES.keySet().forEach(g -> byGroup.put(g, new ArrayList<>()));
        for (MetricDef d : REGISTRY) {
            Double v = values.get(d.key());
            Double t = targets.get(d.key());
            byGroup.get(d.group()).add(new Metric(d.key(), d.name(), d.level(), d.parent(),
                    d.unit(), d.direction(), v, t, status(d.direction(), v, t)));
        }
        List<Group> groups = new ArrayList<>();
        byGroup.forEach((k, m) -> groups.add(new Group(k, GROUP_NAMES.get(k), m)));
        return new PerfOverview(groups, charts(projectId, today));
    }

    /** 单指标当前值（改进项基线/验证取数用）。 */
    public Double currentValue(Long projectId, String metricKey) {
        Map<String, Object> pm = metricsMapper.projectMetrics(projectId);
        if (pm == null) {
            throw new BusinessException(4040, "项目不存在");
        }
        return computeValues(projectId, pm, LocalDate.now()).get(metricKey);
    }

    /** 设定/清除目标值（null=清除）。返回该指标最新 Metric。 */
    @Transactional
    public Metric setTarget(Long projectId, String metricKey, Double targetValue) {
        UserContext.requireRole("PM");
        MetricDef d = def(metricKey).orElseThrow(() -> new BusinessException("未知指标: " + metricKey));
        MetricTarget row = targetMapper.selectOne(new QueryWrapper<MetricTarget>()
                .eq("project_id", projectId).eq("metric_key", metricKey));
        if (targetValue == null) {
            if (row != null) {
                targetMapper.deleteById(row.getId());
            }
            audit.record(projectId, "METRIC_TARGET", row == null ? null : row.getId(), "UPDATE",
                    "清除指标目标 " + d.name(), null, null);
        } else {
            if (row == null) {
                row = new MetricTarget();
                row.setProjectId(projectId);
                row.setMetricKey(metricKey);
            }
            row.setTargetValue(BigDecimal.valueOf(targetValue));
            row.setUpdatedBy(UserContext.currentUserId());
            row.setUpdatedAt(LocalDateTime.now());
            if (row.getId() == null) {
                targetMapper.insert(row);
            } else {
                targetMapper.updateById(row);
            }
            audit.record(projectId, "METRIC_TARGET", row.getId(), "UPDATE",
                    "设定指标目标 " + d.name() + " = " + targetValue, null, null);
        }
        Double v = currentValue(projectId, metricKey);
        return new Metric(d.key(), d.name(), d.level(), d.parent(), d.unit(), d.direction(),
                v, targetValue, status(d.direction(), v, targetValue));
    }

    // ---------- 指标计算 ----------

    private Map<String, Double> computeValues(Long projectId, Map<String, Object> pm, LocalDate today) {
        Map<String, Double> v = new HashMap<>();
        LocalDateTime since4w = today.minusDays(28).atStartOfDay();

        // 交付速率
        v.put("delivery.throughput4w", (double) perfMapper.acceptedSince(projectId, since4w));
        Iteration commitIt = pickCommitIteration(iterationMapper.selectList(new QueryWrapper<Iteration>()
                .eq("project_id", projectId)));
        if (commitIt != null) {
            List<WorkItem> items = workItemMapper.selectList(new QueryWrapper<WorkItem>()
                    .eq("iteration_id", commitIt.getId()));
            v.put("delivery.commitRateCount", rate(items.stream().filter(w -> "Accepted".equals(w.getStatus())).count(),
                    items.size()));
            double committedPts = items.stream().mapToDouble(w -> parsePoints(w.getEstimate())).sum();
            double donePts = items.stream().filter(w -> "Accepted".equals(w.getStatus()))
                    .mapToDouble(w -> parsePoints(w.getEstimate())).sum();
            v.put("delivery.commitRatePoints", committedPts == 0 ? null : round1(donePts * 100.0 / committedPts));
        }

        // 周期时间
        List<Integer> lead = sortedNonNull(perfMapper.leadDays(projectId));
        v.put("cycle.leadP50", percentile(lead, 50));
        v.put("cycle.leadP85", percentile(lead, 85));
        List<Integer> cycles = sortedNonNull(metricsMapper.cycleDays(projectId));
        v.put("cycle.cycleP50", percentile(cycles, 50));
        v.put("cycle.cycleP85", percentile(cycles, 85));
        v.put("cycle.cycleP95", percentile(cycles, 95));
        v.put("cycle.defectFixAvg", avg(perfMapper.defectFixDays(projectId)));

        // 质量
        Map<String, Object> fr = perfMapper.firstRunStats(projectId);
        v.put("quality.firstPassRate", rate(num(fr.get("first_pass")), num(fr.get("cases_with_runs"))));
        Map<String, Object> pass = metricsMapper.testPassStats(projectId);
        v.put("quality.latestPassRate", rate(num(pass.get("pass_cases")), num(pass.get("cases_with_runs"))));
        long reqTotal = num(pm.get("requirement_total"));
        v.put("quality.defectDensity", reqTotal == 0 ? null
                : round2(num(pm.get("defect_total")) * 1.0 / reqTotal));
        Map<String, Object> ro = perfMapper.defectReopenStats(projectId);
        v.put("quality.reopenRate", rate(num(ro.get("reopened")), num(ro.get("ever_closed"))));
        Map<String, Object> cov = metricsMapper.requirementCoverage(projectId);
        v.put("quality.reqCoverage", rate(num(cov.get("req_covered")), num(cov.get("req_total"))));

        // 资源与流动
        v.put("flow.wip", (double) num(pm.get("wip")));
        Iteration active = iterationMapper.selectOne(new QueryWrapper<Iteration>()
                .eq("project_id", projectId).eq("status", "ACTIVE").orderByDesc("id").last("LIMIT 1"));
        if (active != null) {
            List<WorkItem> items = workItemMapper.selectList(new QueryWrapper<WorkItem>()
                    .eq("iteration_id", active.getId()));
            double committed = items.stream().mapToDouble(w -> parsePoints(w.getEstimate())).sum();
            double done = items.stream().filter(w -> "Accepted".equals(w.getStatus()))
                    .mapToDouble(w -> parsePoints(w.getEstimate())).sum();
            v.put("flow.capacityUtil", committed == 0 ? null : round1(done * 100.0 / committed));
        }
        v.put("flow.readyCount", (double) perfMapper.readyCount(projectId));
        v.put("flow.staleCount", (double) staleItems(perfMapper.lastMoveOfOpenItems(projectId), today, 7).size());

        // 流程治理
        v.put("gov.changeCycleAvg", avg(perfMapper.changeCycleDays(projectId)));
        v.put("gov.riskOnTimeRate", riskOnTimeRate(
                workItemMapper.selectList(new QueryWrapper<WorkItem>()
                        .eq("project_id", projectId).eq("type", WorkItemType.RISK.name())), today));
        Map<String, Object> rl = perfMapper.redlineStats(projectId);
        v.put("gov.redlineRate", rate(num(rl.get("satisfied")), num(rl.get("total"))));
        Map<String, Object> ev = perfMapper.metEvidenceStats(projectId);
        v.put("gov.metEvidenceRate", rate(num(ev.get("with_evidence")), num(ev.get("total"))));
        return v;
    }

    private Charts charts(Long projectId, LocalDate today) {
        LocalDate week0 = today.with(DayOfWeek.MONDAY).minusWeeks(7);
        Map<LocalDate, Long> byDay = new HashMap<>();
        for (Map<String, Object> r : perfMapper.acceptedByDay(projectId, week0.atStartOfDay())) {
            Object d = r.get("d");
            if (d != null) {
                byDay.put(LocalDate.parse(String.valueOf(d).substring(0, 10)), num(r.get("cnt")));
            }
        }
        List<WeekPoint> weekly = bucketWeeks(byDay, today, 8);

        List<TypeCount> byType = new ArrayList<>();
        for (Map<String, Object> r : perfMapper.acceptedByType(projectId, today.minusDays(28).atStartOfDay())) {
            String t = String.valueOf(r.get("type"));
            String label;
            try {
                label = WorkItemType.of(t).label();
            } catch (Exception e) {
                label = t;
            }
            byType.add(new TypeCount(t, label, num(r.get("cnt"))));
        }

        List<StageStay> stages = stageDwell(perfMapper.genericStatusLogs(projectId));
        List<StaleItem> stale = staleItems(perfMapper.lastMoveOfOpenItems(projectId), today, 0).stream()
                .sorted(Comparator.comparingLong(StaleItem::days).reversed()).limit(5).toList();
        return new Charts(weekly, byType, stages, stale);
    }

    /** 承诺口径迭代：最近结束的（DONE/CLOSED，按结束日期）；无则 ACTIVE；再无为 null。 */
    static Iteration pickCommitIteration(List<Iteration> all) {
        return all.stream()
                .filter(i -> "DONE".equals(i.getStatus()) || "CLOSED".equals(i.getStatus()))
                .max(Comparator.comparing((Iteration i) -> i.getEndDate() == null ? LocalDate.MIN : i.getEndDate())
                        .thenComparing(Iteration::getId))
                .orElseGet(() -> all.stream().filter(i -> "ACTIVE".equals(i.getStatus()))
                        .max(Comparator.comparing(Iteration::getId)).orElse(null));
    }

    /** 估算点解析：数字字符串→数值，非数字/空按 0 计（容错，绝不抛错）。 */
    static double parsePoints(String estimate) {
        if (estimate == null || estimate.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(estimate.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 按 ISO 周（周一起）分桶，补零对齐最近 weeks 周。 */
    static List<WeekPoint> bucketWeeks(Map<LocalDate, Long> byDay, LocalDate today, int weeks) {
        Map<LocalDate, Long> byWeek = new HashMap<>();
        byDay.forEach((d, c) -> byWeek.merge(d.with(DayOfWeek.MONDAY), c, Long::sum));
        List<WeekPoint> out = new ArrayList<>();
        LocalDate start = today.with(DayOfWeek.MONDAY).minusWeeks(weeks - 1L);
        for (int i = 0; i < weeks; i++) {
            LocalDate w = start.plusWeeks(i);
            out.add(new WeekPoint(w.toString(), byWeek.getOrDefault(w, 0L)));
        }
        return out;
    }

    /**
     * 阶段停留回放：相邻两条状态事件之间的时长记到前一条的 to_status 名下；
     * 末段（仍停留中）不计。只输出通用链路四个非终态。
     */
    static List<StageStay> stageDwell(List<Map<String, Object>> logs) {
        Map<String, double[]> acc = new LinkedHashMap<>(); // stage -> [sumDays, count]
        for (String s : List.of("Backlog", "Ready", "In Progress", "Verification")) {
            acc.put(s, new double[2]);
        }
        Long prevItem = null;
        String prevStatus = null;
        LocalDateTime prevAt = null;
        for (Map<String, Object> r : logs) {
            Long item = ((Number) r.get("work_item_id")).longValue();
            String to = String.valueOf(r.get("to_status"));
            LocalDateTime at = toLdt(r.get("at"));
            if (item.equals(prevItem) && prevStatus != null && prevAt != null && acc.containsKey(prevStatus)) {
                double days = ChronoUnit.MINUTES.between(prevAt, at) / 1440.0;
                double[] a = acc.get(prevStatus);
                a[0] += days;
                a[1]++;
            }
            prevItem = item;
            prevStatus = to;
            prevAt = at;
        }
        List<StageStay> out = new ArrayList<>();
        acc.forEach((stage, a) -> out.add(new StageStay(stage,
                a[1] == 0 ? null : round1(a[0] / a[1]), (int) a[1])));
        return out;
    }

    /** 停滞项：最后一次状态变更距今超过 minDays 天。 */
    static List<StaleItem> staleItems(List<Map<String, Object>> rows, LocalDate today, int minDays) {
        List<StaleItem> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            LocalDateTime lastMove = toLdt(r.get("last_move"));
            if (lastMove == null) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(lastMove.toLocalDate(), today);
            if (days > minDays) {
                out.add(new StaleItem(((Number) r.get("id")).longValue(), String.valueOf(r.get("code")),
                        String.valueOf(r.get("title")), String.valueOf(r.get("status")), days));
            }
        }
        return out;
    }

    /** 风险按期处置率：已到期风险中当前已闭环（Closed/Accepted）的比例。ext JSON 坏/无期限则跳过。 */
    Double riskOnTimeRate(List<WorkItem> risks, LocalDate today) {
        int due = 0, closed = 0;
        for (WorkItem r : risks) {
            LocalDate d = riskDueDate(r);
            if (d == null || d.isAfter(today)) {
                continue;
            }
            due++;
            if ("Closed".equals(r.getStatus()) || "Accepted".equals(r.getStatus())) {
                closed++;
            }
        }
        return due == 0 ? null : round1(closed * 100.0 / due);
    }

    LocalDate riskDueDate(WorkItem risk) {
        try {
            if (risk.getExtFields() != null && !risk.getExtFields().isBlank()) {
                JsonNode n = objectMapper.readTree(risk.getExtFields()).path("dueDate");
                if (n.isTextual() && !n.asText().isBlank()) {
                    return LocalDate.parse(n.asText());
                }
            }
        } catch (Exception ignored) {
            // 解析失败按无期限处理
        }
        return null;
    }

    /** 达标判定：无值或无目标=none；higher 方向 value>=target 达标，lower 相反。 */
    static String status(String direction, Double value, Double target) {
        if (value == null || target == null) {
            return "none";
        }
        boolean good = "higher".equals(direction) ? value >= target : value <= target;
        return good ? "good" : "warn";
    }

    static Double percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return (double) sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static List<Integer> sortedNonNull(List<Integer> in) {
        return in.stream().filter(Objects::nonNull).sorted().toList();
    }

    private static Double avg(List<Integer> in) {
        List<Integer> ok = in.stream().filter(Objects::nonNull).toList();
        return ok.isEmpty() ? null : round1(ok.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    private static Double rate(long part, long total) {
        return total == 0 ? null : round1(part * 100.0 / total);
    }

    private static Double rate(double part, double total) {
        return total == 0 ? null : round1(part * 100.0 / total);
    }

    private static Double round1(double d) {
        return BigDecimal.valueOf(d).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double round2(double d) {
        return BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    static LocalDateTime toLdt(Object o) {
        if (o instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (o instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return o == null ? null : LocalDateTime.parse(String.valueOf(o).replace(' ', 'T'));
    }

    private Map<String, Double> loadTargets(Long projectId) {
        Map<String, Double> m = new HashMap<>();
        for (MetricTarget t : targetMapper.selectList(new QueryWrapper<MetricTarget>()
                .eq("project_id", projectId))) {
            m.put(t.getMetricKey(), t.getTargetValue() == null ? null : t.getTargetValue().doubleValue());
        }
        return m;
    }
}
