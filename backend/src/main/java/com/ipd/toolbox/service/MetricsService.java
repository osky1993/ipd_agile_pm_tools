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

    public MetricsService(MetricsMapper metricsMapper, WorkItemMapper workItemMapper,
                          MetricSnapshotMapper snapshotMapper, ReadinessService readinessService) {
        this.metricsMapper = metricsMapper;
        this.workItemMapper = workItemMapper;
        this.snapshotMapper = snapshotMapper;
        this.readinessService = readinessService;
    }

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

    /** 指标下钻到原始工作项（规划§15.5：所有指标可下钻）。 */
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

    /** 趋势序列（规划§7.4）。读取时顺带对"今天"做一次快照 upsert，历史随使用累积。 */
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

    /** 对当天做快照 upsert：同日重复读取只刷新数值，不产生重复行。 */
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

    /** DATE() 分组结果 → 日期计数表。驱动可能返回 java.sql.Date/LocalDate，统一走字符串解析。 */
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

    /** 项目全部工作项（供 CSV 导出）。 */
    public List<WorkItem> drilldownAll(Long projectId) {
        return workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId).orderByAsc("type").orderByAsc("id"));
    }

    private long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private int percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
