package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.MetricsMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 驾驶舱指标（T701/T702）。全部由业务数据自动计算（读 SQL 视图 + 服务端聚合），无人工填报入口。
 * 严禁任何按人（故事点/人均任务/工时利用率）的绩效口径（规划§13）。
 */
@Service
public class MetricsService {

    private final MetricsMapper metricsMapper;
    private final WorkItemMapper workItemMapper;
    private final ReadinessService readinessService;

    public MetricsService(MetricsMapper metricsMapper, WorkItemMapper workItemMapper,
                          ReadinessService readinessService) {
        this.metricsMapper = metricsMapper;
        this.workItemMapper = workItemMapper;
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
