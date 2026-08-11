package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.MetricSnapshot;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.MetricSnapshotMapper;
import com.ipd.toolbox.mapper.MetricsMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 驾驶舱指标（T701/T702）。全部由业务数据自动计算（读 SQL 视图 + 服务端聚合），无人工填报入口。
 * 严禁任何按人（故事点/人均任务/工时利用率）的绩效口径（规划§13）。
 */
@Service
public class MetricsService {

    private final MetricsMapper metricsMapper;
    private final WorkItemMapper workItemMapper;
    private final MetricSnapshotMapper snapshotMapper;
    private final ReadinessService readinessService;

    /**
     * 指标服务依赖注入。
     * metricsMapper 做主指标聚合；workItemMapper 用于 drilldown；snapshotMapper 做趋势存储；
     * readinessService 注入 maturity 聚合结果。
     */
    public MetricsService(MetricsMapper metricsMapper, WorkItemMapper workItemMapper,
                          MetricSnapshotMapper snapshotMapper, ReadinessService readinessService) {
        this.metricsMapper = metricsMapper;
        this.workItemMapper = workItemMapper;
        this.snapshotMapper = snapshotMapper;
        this.readinessService = readinessService;
    }

    /**
     * 获取驾驶舱指标总览（T701/T702 的汇总入口）。
     * 组合能力/需求/流程/质量四大域的聚合值，并附带 readiness 总结，供首页与统一看板。
     * 任何 projectId 不存在时抛 4040，避免返回空结构导致前端判空歧义。
     *
     * <p>更新/副作用说明：
     * 当前方法不写明细数据；trend() 在同一服务中会触发快照写入，因此这里的结果可重复计算。</p>
     */
    public Map<String, Object> overview(Long projectId) {
        Map<String, Object> m = metricsMapper.projectMetrics(projectId);
        if (m == null) {
            throw new BusinessException(4040, "项目不存在");
        }
        Map<String, Object> cov = metricsMapper.requirementCoverage(projectId);
        Map<String, Object> pass = metricsMapper.testPassStats(projectId);
        List<Integer> cycles = metricsMapper.cycleDays(projectId).stream()
                .filter(Objects::nonNull).sorted().toList();

        long reqTotal = num(cov.get("req_total"));
        long reqCovered = num(cov.get("req_covered"));
        long casesWithRuns = num(pass.get("cases_with_runs"));
        long passCases = num(pass.get("pass_cases"));

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("capabilityTotal", num(m.get("capability_total")));
        value.put("capabilityAccepted", num(m.get("capability_accepted")));
        value.put("requirementTotal", num(m.get("requirement_total")));
        value.put("requirementAccepted", num(m.get("requirement_accepted")));
        value.put("pendingChanges", num(m.get("pending_changes")));
        value.put("dcpByStatus", metricsMapper.dcpStatusDist(projectId));

        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("wip", num(m.get("wip")));
        flow.put("throughput", num(m.get("accepted_count")));
        flow.put("blocked", num(m.get("blocked_count")));
        flow.put("cycleP50", percentile(cycles, 50));
        flow.put("cycleP85", percentile(cycles, 85));
        flow.put("cycleP95", percentile(cycles, 95));

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("openDefects", num(m.get("open_defects")));
        quality.put("defectTotal", num(m.get("defect_total")));
        quality.put("defectClosed", num(m.get("defect_closed")));
        quality.put("casesWithRuns", casesWithRuns);
        quality.put("passCases", passCases);
        quality.put("testPassRate", casesWithRuns == 0 ? 0 : Math.round(passCases * 100.0 / casesWithRuns));
        quality.put("reqTotal", reqTotal);
        quality.put("reqCovered", reqCovered);
        quality.put("reqCoverage", reqTotal == 0 ? 0 : Math.round(reqCovered * 100.0 / reqTotal));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", value);
        out.put("flow", flow);
        out.put("quality", quality);
        out.put("maturity", readinessService.summary(projectId));
        return out;
    }

    /**
     * 指标下钻：返回指标口径对应的明细工作项列表。
     * 支持指标值与条目列表一一对应，便于前端展开查看。
     *
     * <p>入参 metric 做白名单分支；未知指标立即抛 BusinessException，防止上层误将错误参数
     * 误解为“空列表”。</p>
     */
    public List<WorkItem> drilldown(Long projectId, String metric) {
        QueryWrapper<WorkItem> qw = new QueryWrapper<WorkItem>().eq("project_id", projectId);
        switch (metric) {
            case "wip" -> qw.eq("status", "In Progress");
            case "throughput", "accepted" -> qw.eq("status", "Accepted");
            case "openDefects" -> qw.eq("type", "DEFECT").ne("status", "Closed");
            case "defectTotal" -> qw.eq("type", "DEFECT");
            case "capabilityAccepted" -> qw.eq("type", "CAPABILITY").eq("status", "Accepted");
            case "capabilityTotal" -> qw.eq("type", "CAPABILITY");
            case "requirementTotal" -> qw.eq("type", "REQUIREMENT");
            case "requirementAccepted" -> qw.eq("type", "REQUIREMENT").eq("status", "Accepted");
            case "pendingChanges" -> qw.eq("type", "CHANGE").eq("status", "Impact Analysed");
            case "uncoveredRequirements" -> qw.eq("type", "REQUIREMENT").notInSql("id",
                    "SELECT target_id FROM trace_link WHERE relation='verifies' AND target_type='WORK_ITEM' AND project_id=" + projectId);
            default -> throw new BusinessException("未知下钻指标: " + metric);
        }
        qw.orderByDesc("created_at");
        return workItemMapper.selectList(qw);
    }

    /**
     * 趋势点：缺陷流入/关闭由时间戳精确回算（完整历史）；
     * 存量类指标（未关缺陷、条件满足、需求验收）来自每日快照，无快照的日期为 null。
     */
    public record TrendPoint(String date, long defectInflow, long defectClosed,
                             Integer openDefects, Integer criteriaTotal, Integer criteriaMet,
                             Integer reqTotal, Integer reqAccepted) {
    }

    /**
     * 趋势序列（规划§7.4）。
     *
     * <p>执行顺序：
     * 1) 先触发 {@link #snapshotToday(Long)} 做指标快照 upsert（同日幂等）；
     * 2) 再读取历史 snapshot + 缺陷入/闭环日报，构建日期连续窗口。</p>
     *
     * <p>快照副作用：首次/当天首次调用会写 {@code metric_snapshot}，后续按同日更新，不重复新增；</p>
     * <p>窗口约束：days 从 1 开始钳制，窗口起点不早于 today- (days-1)。</p>
     */
    @Transactional
    public List<TrendPoint> trend(Long projectId, int days) {
        snapshotToday(projectId);

        Map<LocalDate, Long> inflow = byDay(metricsMapper.defectInflowByDay(projectId));
        Map<LocalDate, Long> closed = byDay(metricsMapper.defectClosedByDay(projectId));

        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(Math.max(1, days) - 1L);
        Map<LocalDate, MetricSnapshot> snaps = new HashMap<>();
        for (MetricSnapshot s : snapshotMapper.selectList(new QueryWrapper<MetricSnapshot>()
                .eq("project_id", projectId).ge("snap_date", start))) {
            snaps.put(s.getSnapDate(), s);
        }

        List<TrendPoint> points = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            MetricSnapshot s = snaps.get(d);
            points.add(new TrendPoint(d.toString(),
                    inflow.getOrDefault(d, 0L), closed.getOrDefault(d, 0L),
                    s == null ? null : s.getOpenDefects(),
                    s == null ? null : s.getCriteriaTotal(),
                    s == null ? null : s.getCriteriaMet(),
                    s == null ? null : s.getReqTotal(),
                    s == null ? null : s.getReqAccepted()));
        }
        return points;
    }

    /**
     * 对当天做快照 upsert。
     *
     * <p>持久化对象包含 criteriaTotal/criteriaMet/openDefects/reqTotal/reqAccepted，
     * 并写入 createdAt/updatedAt；存在即 update，不存在即 insert。</p>
     *
     * <p>失败边界：
     * 项目标识不存在时直接抛 4040，避免生成错误项目的空指标快照污染趋势。</p>
     */
    private void snapshotToday(Long projectId) {
        Map<String, Object> m = metricsMapper.projectMetrics(projectId);
        if (m == null) {
            throw new BusinessException(4040, "项目不存在");
        }
        Map<String, Object> gc = metricsMapper.criteriaStats(projectId);
        LocalDate today = LocalDate.now();

        MetricSnapshot s = snapshotMapper.selectOne(new QueryWrapper<MetricSnapshot>()
                .eq("project_id", projectId).eq("snap_date", today));
        boolean fresh = s == null;
        if (fresh) {
            s = new MetricSnapshot();
            s.setProjectId(projectId);
            s.setSnapDate(today);
            s.setCreatedAt(LocalDateTime.now());
        }
        s.setCriteriaTotal((int) num(gc.get("total")));
        s.setCriteriaMet((int) num(gc.get("met")));
        s.setOpenDefects((int) num(m.get("open_defects")));
        s.setReqTotal((int) num(m.get("requirement_total")));
        s.setReqAccepted((int) num(m.get("requirement_accepted")));
        s.setUpdatedAt(LocalDateTime.now());
        if (fresh) {
            snapshotMapper.insert(s);
        } else {
            snapshotMapper.updateById(s);
        }
    }

    /**
     * 按天聚合映射。
     *
     * <p>兼容不同数据库 DATE() 返回类型，统一转为 {@link LocalDate} 作为 key；
     * 同一天多行自动累加，未命中日期的行忽略。</p>
     */
    private Map<LocalDate, Long> byDay(List<Map<String, Object>> rows) {
        Map<LocalDate, Long> map = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Object d = r.get("d");
            if (d == null) {
                continue;
            }
            map.put(LocalDate.parse(String.valueOf(d).substring(0, 10)), num(r.get("cnt")));
        }
        return map;
    }

    /**
     * 全项目工作项导出查询（按 type/id 排序），用于 CSV 等批量导出场景。
     *
     * <p>返回字段覆盖 {@code projectMapper/drilldownAll} 的全量导出语义；
     * 不分页、仅按 type/id 排序，消费方需自行控制导出口径。</p>
     */
    public List<WorkItem> drilldownAll(Long projectId) {
        return workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).orderByAsc("type").orderByAsc("id"));
    }

    /**
     * 安全数字转换。
     *
     * @param o 数据库聚合列
     * @return null -> 0，避免空值传播到数学运算
     */
    private long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    /**
     * 有序样本 P 分位数计算（输入已排序）。
     */
    private int percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
