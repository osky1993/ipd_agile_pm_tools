package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.TraceLinkMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 排期推演：基于依赖网络（depends_on/blocks 归一化）与 estimate（天）做 CPM 关键路径计算。
 * 口径：今天=第 0 天的相对推演（不猜项目开始日）；已完成项（Accepted/Closed）视为已满足从网络剔除；
 * 环成员整体剔除并输出警告；未估算项按 1 天计且显式标注（不再静默当 0）。
 * 复用 TeamService 的包私有纯函数 toDepPairs / findDepCycles（关键路径基于全量依赖，不受
 * buildGraph 的 60 节点展示裁剪影响）。
 */
@Service
public class ScheduleService {

    private static final Set<String> DONE_STATUSES = Set.of("Accepted", "Closed");
    private static final double EPS = 1e-6;

    public record CpmNode(Long id, String code, String title, String status,
                          double duration, boolean estimated,
                          double es, double ef, double ls, double lf,
                          double slack, boolean critical) {
    }

    public record CpmEdge(Long from, Long to, boolean critical) {
    }

    public record CpmResult(List<CpmNode> nodes, List<CpmEdge> edges, List<Long> criticalChain,
                            double totalDuration, List<String> unestimatedCritical,
                            List<List<Long>> cycles) {
    }

    private final WorkItemMapper workItemMapper;
    private final TraceLinkMapper traceLinkMapper;

    /**
     * 排期服务依赖注入：工作项作为节点全集，trace_link 作为依赖边来源。
     */
    public ScheduleService(WorkItemMapper workItemMapper, TraceLinkMapper traceLinkMapper) {
        this.workItemMapper = workItemMapper;
        this.traceLinkMapper = traceLinkMapper;
    }

    /**
     * 获取项目关键路径计算结果。
     * 汇聚项目内依赖关系并过滤已完成状态、检测环后生成 CPM 与关键链路（含未估算告警点）。
     *
     * @param projectId 项目 ID
     */
    public CpmResult criticalPath(Long projectId) {
        Map<Long, WorkItem> items = new HashMap<>();
        for (WorkItem w : workItemMapper.selectList(new QueryWrapper<WorkItem>()
                .eq("project_id", projectId))) {
            items.put(w.getId(), w);
        }
        List<TraceLink> links = traceLinkMapper.selectList(new QueryWrapper<TraceLink>()
                .eq("project_id", projectId).in("relation", "depends_on", "blocks"));
        return computeCpm(items, TeamService.toDepPairs(links, items.keySet()));
    }

    /** CPM 纯函数：前向 ES/EF → 后向 LF/LS → slack；critical = slack≈0。 */
    static CpmResult computeCpm(Map<Long, WorkItem> items, List<TeamService.DepPair> deps) {
        // 1) 已完成项从网络剔除（其作为前置的边视为已满足）
        List<TeamService.DepPair> active = new ArrayList<>();
        for (TeamService.DepPair p : deps) {
            WorkItem pre = items.get(p.prerequisite());
            WorkItem dep = items.get(p.dependent());
            if (pre == null || dep == null) {
                continue;
            }
            if (DONE_STATUSES.contains(pre.getStatus()) || DONE_STATUSES.contains(dep.getStatus())) {
                continue;
            }
            active.add(p);
        }
        // 2) 环成员整体剔除（Kahn 无法排序），输出警告
        List<List<Long>> cycles = TeamService.findDepCycles(active);
        Set<Long> inCycle = new HashSet<>();
        for (List<Long> c : cycles) {
            inCycle.addAll(c);
        }
        List<TeamService.DepPair> usable = active.stream()
                .filter(p -> !inCycle.contains(p.prerequisite()) && !inCycle.contains(p.dependent()))
                .toList();
        // 3) 参与网络的节点 = 有有效依赖边的未完成项（孤立项不参与推演）
        Set<Long> nodeIds = new LinkedHashSet<>();
        Map<Long, List<Long>> succ = new HashMap<>();
        Map<Long, List<Long>> pred = new HashMap<>();
        for (TeamService.DepPair p : usable) {
            nodeIds.add(p.prerequisite());
            nodeIds.add(p.dependent());
            succ.computeIfAbsent(p.prerequisite(), k -> new ArrayList<>()).add(p.dependent());
            pred.computeIfAbsent(p.dependent(), k -> new ArrayList<>()).add(p.prerequisite());
        }
        if (nodeIds.isEmpty()) {
            return new CpmResult(List.of(), List.of(), List.of(), 0, List.of(), cycles);
        }
        // 工期：未估算按 1 天计并标注
        Map<Long, Double> dur = new HashMap<>();
        Map<Long, Boolean> estimated = new HashMap<>();
        for (Long id : nodeIds) {
            double d = PerfService.parsePoints(items.get(id).getEstimate());
            estimated.put(id, d > 0);
            dur.put(id, d > 0 ? d : 1.0);
        }
        // 4) 拓扑序（Kahn；usable 已无环）
        Map<Long, Integer> indeg = new HashMap<>();
        for (Long id : nodeIds) {
            indeg.put(id, 0);
        }
        for (TeamService.DepPair p : usable) {
            indeg.merge(p.dependent(), 1, Integer::sum);
        }
        Deque<Long> queue = new ArrayDeque<>();
        indeg.forEach((id, d) -> {
            if (d == 0) {
                queue.add(id);
            }
        });
        List<Long> topo = new ArrayList<>();
        Map<Long, Integer> remaining = new HashMap<>(indeg);
        while (!queue.isEmpty()) {
            Long n = queue.poll();
            topo.add(n);
            for (Long s : succ.getOrDefault(n, List.of())) {
                if (remaining.merge(s, -1, Integer::sum) == 0) {
                    queue.add(s);
                }
            }
        }
        // 5) 前向遍历
        Map<Long, Double> es = new HashMap<>();
        Map<Long, Double> ef = new HashMap<>();
        for (Long n : topo) {
            double start = pred.getOrDefault(n, List.of()).stream()
                    .mapToDouble(ef::get).max().orElse(0);
            es.put(n, start);
            ef.put(n, start + dur.get(n));
        }
        double total = ef.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        // 6) 后向遍历
        Map<Long, Double> lf = new HashMap<>();
        Map<Long, Double> ls = new HashMap<>();
        for (int i = topo.size() - 1; i >= 0; i--) {
            Long n = topo.get(i);
            double finish = succ.getOrDefault(n, List.of()).stream()
                    .mapToDouble(ls::get).min().orElse(total);
            lf.put(n, finish);
            ls.put(n, finish - dur.get(n));
        }
        // 7) 组装结果
        List<CpmNode> nodes = new ArrayList<>();
        List<Long> criticalChain = new ArrayList<>();
        List<String> unestimatedCritical = new ArrayList<>();
        for (Long n : topo) {
            double slack = ls.get(n) - es.get(n);
            boolean critical = Math.abs(slack) < EPS;
            WorkItem w = items.get(n);
            nodes.add(new CpmNode(n, w.getCode(), w.getTitle(), w.getStatus(),
                    dur.get(n), estimated.get(n),
                    es.get(n), ef.get(n), ls.get(n), lf.get(n),
                    Math.round(slack * 10) / 10.0, critical));
            if (critical) {
                criticalChain.add(n);
                if (!estimated.get(n)) {
                    unestimatedCritical.add(w.getCode());
                }
            }
        }
        List<CpmEdge> edges = new ArrayList<>();
        for (TeamService.DepPair p : usable) {
            boolean critical = Math.abs(ls.get(p.prerequisite()) - es.get(p.prerequisite())) < EPS
                    && Math.abs(ls.get(p.dependent()) - es.get(p.dependent())) < EPS
                    && Math.abs(ef.get(p.prerequisite()) - es.get(p.dependent())) < EPS;
            edges.add(new CpmEdge(p.prerequisite(), p.dependent(), critical));
        }
        return new CpmResult(nodes, edges, criticalChain, total, unestimatedCritical, cycles);
    }
}
