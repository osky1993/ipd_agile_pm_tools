package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Iteration;
import com.ipd.toolbox.domain.entity.IterationCommitment;
import com.ipd.toolbox.domain.entity.MetricTarget;
import com.ipd.toolbox.domain.entity.PerfSnapshot;
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
        reg("gov.riskExposure", "风险敞口总量", "gov", 2, null, "", "lower");
        reg("gov.redlineRate", "红线满足率", "gov", 2, null, "%", "higher");
        reg("gov.metEvidenceRate", "MET条件证据完备率", "gov", 2, null, "%", "higher");
    }

    /**
     * 注册单个指标元数据到内存 REGISTRY。
     *
     * @param key 指标 key
     * @param name 指标中文名
     * @param group 分组
     * @param level 层级（1~3）
     * @param parent 上级指标 key
     * @param unit 计量单位
     * @param direction 指标方向：higher 表示越高越好，lower 表示越低越好
     */
    private static void reg(String key, String name, String group, int level, String parent,
                            String unit, String direction) {
        REGISTRY.add(new MetricDef(key, name, group, level, parent, unit, direction));
    }

    /**
     * 按 metric key 获取静态指标定义（方向/单位/层级等）；
     * 为目标设置与状态计算提供统一元数据源。
     */
    public static Optional<MetricDef> def(String key) {
        return REGISTRY.stream().filter(d -> d.key().equals(key)).findFirst();
    }

    private final PerfMapper perfMapper;
    private final MetricsMapper metricsMapper;
    private final MetricTargetMapper targetMapper;
    private final IterationMapper iterationMapper;
    private final WorkItemMapper workItemMapper;
    private final PerfSnapshotMapper snapshotMapper;
    private final IterationCommitmentMapper commitmentMapper;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    /**
     * 效能服务依赖注入。
     * perfMapper/metricsMapper 提供计算源；target/快照映射用于指标目标与趋势持久化；
     * 与迭代相关的承诺率需 iteration/commitment/workItem；audit 用于目标值变更留痕。
     */
    public PerfService(PerfMapper perfMapper, MetricsMapper metricsMapper, MetricTargetMapper targetMapper,
                       IterationMapper iterationMapper, WorkItemMapper workItemMapper,
                       PerfSnapshotMapper snapshotMapper, IterationCommitmentMapper commitmentMapper,
                       AuditService audit, ObjectMapper objectMapper) {
        this.perfMapper = perfMapper;
        this.metricsMapper = metricsMapper;
        this.targetMapper = targetMapper;
        this.iterationMapper = iterationMapper;
        this.workItemMapper = workItemMapper;
        this.snapshotMapper = snapshotMapper;
        this.commitmentMapper = commitmentMapper;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询项目绩效总览。
     *
     * <p>执行流程：先基于 {@code metricsMapper.projectMetrics} 读取一次原始基表，
     * 再通过 computeValues 做全量派生，最后读取目标并组装分组与图表。</p>
     *
     * <p>更新粒度说明：方法会触发 {@link #snapshotToday(Long, LocalDate, Map)} 写入
     * 今日 {@code perf_snapshot}，采用 upsert 语义；即每次读取都会保证该日有一套快照，
     * 不新增重复行、仅更新变更值。该副作用用于趋势连续性，不影响原始明细表。</p>
     *
     * @param projectId 项目 ID
     * @return 指标分组 + 图表片段
     */
    @Transactional
    public PerfOverview metrics(Long projectId) {
        Map<String, Object> pm = metricsMapper.projectMetrics(projectId);
        if (pm == null) {
            throw new BusinessException(4040, "项目不存在");
        }
        LocalDate today = LocalDate.now();
        Map<String, Double> values = computeValues(projectId, pm, today);
        snapshotToday(projectId, today, values);
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

    /**
     * 查询单指标当前值（改进项基线与验证场景使用）。
     *
     * @param projectId 项目 ID
     * @param metricKey 指标 key
     */
    public Double currentValue(Long projectId, String metricKey) {
        Map<String, Object> pm = metricsMapper.projectMetrics(projectId);
        if (pm == null) {
            throw new BusinessException(4040, "项目不存在");
        }
        return computeValues(projectId, pm, LocalDate.now()).get(metricKey);
    }

    /**
     * 设定或清除指标目标。
     *
     * <p>PM-only。target 为 null 时视为清除目标；非 null 为新增/更新目标。
     * 先校验指标 key 有效性，再按目标行是否存在执行 INSERT/UPDATE/DELETE，并统一写入审计。</p>
     *
     * <p>更新副作用：每次调用都会写出一次 {@code METRIC_TARGET} 审计记录；
     * 成功后返回该指标当前值 + 新目标 + 达成态，便于前端即时回显。</p>
     *
     * @param projectId 项目 ID
     * @param metricKey 指标 key
     * @param targetValue 目标值；null 表示清除
     */
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

    /**
     * 全指标快照 upsert（同日重复只更新，不新增重复行）。
     *
     * <p>对 REGISTRY 全量指标执行持久化决策：
     * 当日无记录 INSERT；有记录且值变更 UPDATE；值一致不发起 SQL。
     * 以避免重复调度时的数据库抖动。</p>
     *
     * <p>副作用：更新仅限 {@code perf_snapshot}（projectId/date/metricKey/value），
     * 不改写原始明细与业务状态。</p>
     */
    private void snapshotToday(Long projectId, LocalDate today, Map<String, Double> values) {
        Map<String, PerfSnapshot> existing = new HashMap<>();
        for (PerfSnapshot s : snapshotMapper.selectList(new QueryWrapper<PerfSnapshot>()
                .eq("project_id", projectId).eq("snap_date", today))) {
            existing.put(s.getMetricKey(), s);
        }
        for (MetricDef d : REGISTRY) {
            Double v = values.get(d.key());
            PerfSnapshot s = existing.get(d.key());
            BigDecimal val = v == null ? null : BigDecimal.valueOf(v);
            if (s == null) {
                s = new PerfSnapshot();
                s.setProjectId(projectId);
                s.setSnapDate(today);
                s.setMetricKey(d.key());
                s.setValue(val);
                snapshotMapper.insert(s);
            } else if (!Objects.equals(s.getValue() == null ? null : s.getValue().doubleValue(), v)) {
                s.setValue(val);
                snapshotMapper.updateById(s);
            }
        }
    }

    public record TrendPoint(String date, Double value) {
    }

    /**
     * 指标趋势序列（按 key 分组），来源为每日快照。
     *
     * <p>days 自动钳制到 1~365。起点定义为 {@code today-(days-1)}，按天返回可见的快照；
     * 快照缺失的天直接不产生点位，而不是自动补 0。</p>
     *
     * @param projectId 项目 ID
     * @param days 回溯天数，1~365 自动钳制
     */
    public Map<String, List<TrendPoint>> trends(Long projectId, int days) {
        LocalDate start = LocalDate.now().minusDays(Math.min(Math.max(days, 1), 365) - 1L);
        Map<String, List<TrendPoint>> out = new LinkedHashMap<>();
        for (PerfSnapshot s : snapshotMapper.selectList(new QueryWrapper<PerfSnapshot>()
                .eq("project_id", projectId).ge("snap_date", start)
                .orderByAsc("snap_date"))) {
            out.computeIfAbsent(s.getMetricKey(), k -> new ArrayList<>())
                    .add(new TrendPoint(s.getSnapDate().toString(),
                            s.getValue() == null ? null : s.getValue().doubleValue()));
        }
        return out;
    }

    public record CfdPoint(String date, Map<String, Integer> byStatus) {
    }

    /**
     * 累积流图（CFD）主查询入口。
     *
     * <p>窗口会被钳制到 7~365 天范围。底层通过状态日志回放得到每日 WIP 存量，
     * 用于看板流动监控；该接口本身不落库。</p>
     *
     * @param projectId 项目 ID
     * @param days 期望回放窗口（小于 7 按 7 处理）
     */
    public List<CfdPoint> cfd(Long projectId, int days) {
        return replayCfd(perfMapper.genericStatusLogs(projectId), LocalDate.now(),
                Math.min(Math.max(days, 7), 365));
    }

    static final List<String> CFD_STAGES = List.of("Backlog", "Ready", "In Progress", "Verification", "Accepted");

    /**
     * 以状态时序回放方式重建 CFD。
     *
     * <p>每个 work item 的状态按 {@code endOfDay} 前最后一次迁移定义为当日状态；
     * 只统计 {@link #CFD_STAGES} 中定义状态。返回长度固定为 {@code days}，并为每周期开桶时补 0。</p>
     *
     * @param logs status log 列表（含 work_item_id、at、to_status）
     * @param today 截止日
     * @param days 窗口长度，单位天
     */
    static List<CfdPoint> replayCfd(List<Map<String, Object>> logs, LocalDate today, int days) {
        // item -> 按时间排好的 (at, toStatus)
        Map<Long, List<Map.Entry<LocalDateTime, String>>> byItem = new LinkedHashMap<>();
        for (Map<String, Object> r : logs) {
            Long item = ((Number) r.get("work_item_id")).longValue();
            LocalDateTime at = toLdt(r.get("at"));
            byItem.computeIfAbsent(item, k -> new ArrayList<>())
                    .add(Map.entry(at, String.valueOf(r.get("to_status"))));
        }
        List<CfdPoint> out = new ArrayList<>();
        LocalDate start = today.minusDays(days - 1L);
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            LocalDateTime endOfDay = d.plusDays(1).atStartOfDay();
            Map<String, Integer> counts = new LinkedHashMap<>();
            CFD_STAGES.forEach(s -> counts.put(s, 0));
            for (List<Map.Entry<LocalDateTime, String>> events : byItem.values()) {
                String status = null;
                for (Map.Entry<LocalDateTime, String> e : events) {
                    if (e.getKey().isBefore(endOfDay)) {
                        status = e.getValue();
                    } else {
                        break;
                    }
                }
                if (status != null && counts.containsKey(status)) {
                    counts.merge(status, 1, Integer::sum);
                }
            }
            out.add(new CfdPoint(d.toString(), counts));
        }
        return out;
    }

    // ---------- 指标计算 ----------

    /**
     * 复用一次性读取 + 多指标计算管线。
     *
     * <p>在一个方法内按五个域（delivery / cycle / quality / flow / gov）顺序计算，
     * 避免重复落库与重复 SQL；每一域的计算结果互不影响。</p>
     *
     * <p>该方法不落库，返回值供上层指标组装与快照写入。</p>
     */
    private Map<String, Double> computeValues(Long projectId, Map<String, Object> pm, LocalDate today) {
        Map<String, Double> v = new HashMap<>();
        LocalDateTime since4w = today.minusDays(28).atStartOfDay();

        // 交付速率
        v.put("delivery.throughput4w", (double) perfMapper.acceptedSince(projectId, since4w));
        // 承诺口径：以承诺快照为分母（拉入即承诺、移出不减），修正乐观偏差
        Iteration commitIt = pickCommitIteration(iterationMapper.selectList(new QueryWrapper<Iteration>()
                .eq("project_id", projectId)));
        if (commitIt != null) {
            List<IterationCommitment> commits = commitmentMapper.selectList(
                    new QueryWrapper<IterationCommitment>().eq("iteration_id", commitIt.getId()));
            if (!commits.isEmpty()) {
                Map<Long, WorkItem> items = new HashMap<>();
                for (WorkItem w : workItemMapper.selectBatchIds(
                        commits.stream().map(IterationCommitment::getWorkItemId).toList())) {
                    items.put(w.getId(), w);
                }
                long done = commits.stream().filter(c -> isAccepted(items.get(c.getWorkItemId()))).count();
                v.put("delivery.commitRateCount", rate(done, commits.size()));
                double committedPts = commits.stream().mapToDouble(c -> parsePoints(c.getEstimateSnap())).sum();
                double donePts = commits.stream().filter(c -> isAccepted(items.get(c.getWorkItemId())))
                        .mapToDouble(c -> parsePoints(c.getEstimateSnap())).sum();
                v.put("delivery.commitRatePoints", committedPts == 0 ? null : round1(donePts * 100.0 / committedPts));
            }
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
        List<WorkItem> risks = workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).eq("type", WorkItemType.RISK.name()));
        v.put("gov.riskOnTimeRate", riskOnTimeRate(risks, today));
        // 敞口总量：Open/Mitigating 风险的 p×i 求和（未评估 p/i 的按 0 计）
        double exposure = risks.stream()
                .filter(r -> "Open".equals(r.getStatus()) || "Mitigating".equals(r.getStatus()))
                .mapToDouble(r -> {
                    Integer e = RiskService.exposure(RiskService.parseExt(r.getExtFields(), objectMapper));
                    return e == null ? 0 : e;
                }).sum();
        v.put("gov.riskExposure", exposure);
        Map<String, Object> rl = perfMapper.redlineStats(projectId);
        v.put("gov.redlineRate", rate(num(rl.get("satisfied")), num(rl.get("total"))));
        Map<String, Object> ev = perfMapper.metEvidenceStats(projectId);
        v.put("gov.metEvidenceRate", rate(num(ev.get("with_evidence")), num(ev.get("total"))));
        return v;
    }

    /**
     * 组装图表聚合数据（吞吐周柱、类型占比、阶段停留、停滞项）。
     *
     * <p>默认返回值对齐前端展示口径：吞吐 8 周、类型占比 28 天、停滞项按停留时长倒序 top5。
     * 所有数值来自只读查询，不直接写数据库。</p>
     */
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

    /**
     * 承诺口径迭代：最近结束的（DONE/CLOSED，按结束日期）；无则 ACTIVE；再无为 null。
     *
     * <p>优先取“已结束”迭代，作为稳定承诺基线；若不存在则回退到活跃迭代，最终无则返回 null。</p>
     */
    static Iteration pickCommitIteration(List<Iteration> all) {
        return all.stream()
                .filter(i -> "DONE".equals(i.getStatus()) || "CLOSED".equals(i.getStatus()))
                .max(Comparator.comparing((Iteration i) -> i.getEndDate() == null ? LocalDate.MIN : i.getEndDate())
                        .thenComparing(Iteration::getId))
                .orElseGet(() -> all.stream().filter(i -> "ACTIVE".equals(i.getStatus()))
                        .max(Comparator.comparing(Iteration::getId)).orElse(null));
    }

    /**
     * 以工作项状态判定是否已被接受，作为承诺完成和达成率的基础判断。
     *
     * @param w 工作项
     * @return true 表示状态为 Accepted
     */
    private static boolean isAccepted(WorkItem w) {
        return w != null && "Accepted".equals(w.getStatus());
    }

    /**
     * 估算点解析：数字字符串→数值，非数字/空按 0 计（容错，绝不抛错）。
     *
     * <p>设计为“软失败”策略：一条坏估算不应中断全局指标计算。</p>
     */
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

    /**
     * 按 ISO 周（周一起）分桶，补零对齐最近 weeks 周。
     *
     * <p>输入按天聚合结果先归并到周起始日，再按固定周数返回补齐序列，便于图表展示。</p>
     */
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
     *
     * <p>边界说明：仅统计 Backlog/Ready/In Progress/Verification；
     * 未形成闭环时最后一段不纳入停留时长。</p>
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

    /**
     * 停滞项：最后一次状态变更距今超过 minDays 天。
     *
     * <p>last_move 为空表示无法定位变更时间，直接跳过该条避免误判。</p>
     */
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

    /**
     * 风险按期处置率：已到期风险中当前已闭环（Closed/Accepted）的比例。ext JSON 坏/无期限则跳过。
     *
     * <p>用于反映风险处置及时率，分母仅包含到期风险，分子要求已闭环，全部分母为 0 时返回 null。</p>
     */
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

    /**
     * 从风险 extFields 中提取 dueDate，用于逾期处置率计算。
     *
     * <p>解析失败或字段缺失返回 null，调用方按“未到期/非待评估”处理。</p>
     */
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

    /**
     * 达标判定：无值或无目标=none；higher 方向 value>=target 达标，lower 相反。
     *
     * @param direction 指标方向（higher/lower）
     * @param value 当前值
     * @param target 目标值
     * @return none / good / warn
     */
    static String status(String direction, Double value, Double target) {
        if (value == null || target == null) {
            return "none";
        }
        boolean good = "higher".equals(direction) ? value >= target : value <= target;
        return good ? "good" : "warn";
    }

    /**
     * 兼容空样本的分位数函数。
     *
     * <p>入参应为升序列表；空列表返回 null 表示不可计算，避免 0 或异常占位误导。</p>
     */
    static Double percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return (double) sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    /**
     * 非空过滤并升序排序，供后续 P 分位数计算复用，降低重复写法。
     */
    private static List<Integer> sortedNonNull(List<Integer> in) {
        return in.stream().filter(Objects::nonNull).sorted().toList();
    }

    /**
     * 平均值工具（空样本返回 null），避免除以零造成错误展示。
     */
    private static Double avg(List<Integer> in) {
        List<Integer> ok = in.stream().filter(Objects::nonNull).toList();
        return ok.isEmpty() ? null : round1(ok.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    /**
     * 百分比计算（整数分母），不满足 0 分母时返回 null，便于前端统一空态展示。
     */
    private static Double rate(long part, long total) {
        return total == 0 ? null : round1(part * 100.0 / total);
    }

    /**
     * 百分比计算（双精度分母），支持缺失数据时返回 null 而非抛异常。
     */
    private static Double rate(double part, double total) {
        return total == 0 ? null : round1(part * 100.0 / total);
    }

    /**
     * 一位小数四舍五入（通用显示精度）。
     */
    private static Double round1(double d) {
        return BigDecimal.valueOf(d).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** 两位小数四舍五入，主要用于缺陷密度等精度要求更高的衍生指标。 */
    private static Double round2(double d) {
        return BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 安全数值读取：对象为 null 时返回 0，用于 SQL 聚合值缺项时的兼容。
     */
    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    /**
     * 状态日志时间字段统一转 LocalDateTime，兼容 LocalDateTime / Timestamp / 字符串。
     *
     * <p>空值返回 null，字符串解析失败直接抛异常上抛，由上层统一回滚并记录异常；
     * 不在该层静默修正，避免掩盖日志时间字段质量问题。</p>
     */
    static LocalDateTime toLdt(Object o) {
        if (o instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (o instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return o == null ? null : LocalDateTime.parse(String.valueOf(o).replace(' ', 'T'));
    }

    /**
     * 读取项目目标配置并转为内存 map（metricKey→target）。
     *
     * <p>缺失目标不放入 map，便于上层区分“无目标”与“目标值为 0”；</p>
     */
    private Map<String, Double> loadTargets(Long projectId) {
        Map<String, Double> m = new HashMap<>();
        for (MetricTarget t : targetMapper.selectList(new QueryWrapper<MetricTarget>()
                .eq("project_id", projectId))) {
            m.put(t.getMetricKey(), t.getTargetValue() == null ? null : t.getTargetValue().doubleValue());
        }
        return m;
    }
}
