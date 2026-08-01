package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.*;
import com.ipd.toolbox.mapper.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 团队协作屏（/teamboard）：让成员看清自己的在办、工作项上下游、以及已出现/将出现的阻塞。
 * 规则引擎全部为 static 纯函数（便于直测）；负责人面板仅做分派透明，
 * 严禁任何 per-owner 产出/排名口径（规划§13 红线）。
 */
@Service
public class TeamService {

    // ---------- 常量（与 AlertService 保持一致的 7 天口径） ----------
    static final int STALE_DAYS = 7;
    static final int HANDOFF_DAYS = 7;
    static final int GRAPH_NODE_LIMIT = 60;
    static final int SPRINT_RISK_TIME_PCT = 60;
    static final int SPRINT_RISK_DONE_PCT = 50;

    static final Set<String> GENERIC_TYPES = Set.of("CAPABILITY", "REQUIREMENT", "STORY", "TASK");
    static final Map<String, Set<String>> DONE_STATES = Map.of(
            "DEFECT", Set.of("Closed"),
            "CHANGE", Set.of("Verified", "Rejected"),
            "RISK", Set.of("Closed", "Accepted"));

    static boolean isDone(WorkItem w) {
        return DONE_STATES.getOrDefault(w.getType(), Set.of("Accepted")).contains(w.getStatus());
    }

    // ---------- DTO ----------
    public record ColumnCount(String status, int count) {
    }

    public record SprintPulse(Long iterationId, String name, String goal, String startDate, String endDate,
                              int totalDays, int daysGone, int timePct,
                              int committedCount, int doneCount, double committedPoints, double donePoints,
                              Integer donePct, List<ColumnCount> columns) {
    }

    public record GraphNode(Long id, String code, String type, String title, String status,
                            String ownerName, boolean inActiveSprint, boolean blocked,
                            String testBadge, int degree) {
    }

    /** 边方向已归一化：前置→后继 / 父→子 / 缺陷|变更→受影响项。 */
    public record GraphEdge(Long source, Long target, String relation) {
    }

    public record DependencyGraph(List<GraphNode> nodes, List<GraphEdge> edges, boolean truncated) {
    }

    public record Blocker(String severity, String rule, String title, String detail,
                          Long itemId, String itemCode, List<Long> causeIds, Long days) {
    }

    public record Handoff(String at, String kind, String actionText,
                          Long itemId, String itemCode, String itemTitle, String toStatus,
                          Long downstreamId, String downstreamCode) {
    }

    public record OwnerItem(Long id, String code, String type, String title, String status,
                            String priority, boolean blocked, long stallDays) {
    }

    public record OwnerLoad(Long ownerId, String ownerName, double points, List<OwnerItem> items) {
    }

    public record Overview(Long projectId, String projectCode, String projectName,
                           SprintPulse sprint, DependencyGraph graph,
                           List<Blocker> blockers, List<Handoff> handoffs, List<OwnerLoad> owners) {
    }

    /** 依赖对：A depends_on B ≡ B blocks A → (前置 B → 后继 A)。 */
    record DepPair(Long prerequisite, Long dependent) {
    }

    record VerifyStat(int total, int pass, int fail) {
    }

    // ---------- 依赖注入 ----------
    private final ProjectMapper projectMapper;
    private final WorkItemMapper workItemMapper;
    private final TraceLinkMapper traceLinkMapper;
    private final IterationMapper iterationMapper;
    private final IterationCommitmentMapper commitmentMapper;
    private final SysUserMapper sysUserMapper;
    private final PerfMapper perfMapper;
    private final TeamMapper teamMapper;

    public TeamService(ProjectMapper projectMapper, WorkItemMapper workItemMapper,
                       TraceLinkMapper traceLinkMapper, IterationMapper iterationMapper,
                       IterationCommitmentMapper commitmentMapper, SysUserMapper sysUserMapper,
                       PerfMapper perfMapper, TeamMapper teamMapper) {
        this.projectMapper = projectMapper;
        this.workItemMapper = workItemMapper;
        this.traceLinkMapper = traceLinkMapper;
        this.iterationMapper = iterationMapper;
        this.commitmentMapper = commitmentMapper;
        this.sysUserMapper = sysUserMapper;
        this.perfMapper = perfMapper;
        this.teamMapper = teamMapper;
    }

    public Overview overview(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(4040, "项目不存在");
        }
        LocalDate today = LocalDate.now();

        // 全量拉取（单项目几十条，内存计算便于纯函数化）
        Map<Long, WorkItem> items = workItemMapper.selectList(new QueryWrapper<WorkItem>()
                        .eq("project_id", projectId)).stream()
                .collect(Collectors.toMap(WorkItem::getId, w -> w, (a, b) -> a, LinkedHashMap::new));
        List<TraceLink> links = traceLinkMapper.selectList(new QueryWrapper<TraceLink>()
                .eq("project_id", projectId));
        Map<Long, LocalDateTime> lastMove = new HashMap<>();
        for (Map<String, Object> r : perfMapper.lastMoveOfOpenItems(projectId)) {
            lastMove.put(((Number) r.get("id")).longValue(), PerfService.toLdt(r.get("last_move")));
        }
        Map<Long, VerifyStat> verify = new HashMap<>();
        for (Map<String, Object> r : teamMapper.verifyStats(projectId)) {
            verify.put(((Number) r.get("item_id")).longValue(), new VerifyStat(
                    (int) num(r.get("cases_total")), (int) num(r.get("pass_cnt")), (int) num(r.get("fail_cnt"))));
        }
        Map<Long, String> userNames = new HashMap<>();
        for (SysUser u : sysUserMapper.selectList(null)) {
            userNames.put(u.getId(), u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
        }

        // ACTIVE 迭代 + 承诺
        Iteration active = iterationMapper.selectOne(new QueryWrapper<Iteration>()
                .eq("project_id", projectId).eq("status", "ACTIVE")
                .orderByDesc("start_date").orderByDesc("id").last("LIMIT 1"));
        List<IterationCommitment> commits = active == null ? List.of()
                : commitmentMapper.selectList(new QueryWrapper<IterationCommitment>()
                .eq("iteration_id", active.getId()));
        SprintPulse pulse = active == null ? null : buildPulse(active, commits, items, today);

        // 归一化依赖对（两端都必须在项目工作项内——防悬空链）
        List<DepPair> deps = toDepPairs(links, items.keySet());

        List<Blocker> blockers = evaluateBlockers(items, deps, lastMove, verify, links,
                active == null ? null : active.getId(), pulse, today);
        Set<Long> blockedIds = blockers.stream().filter(b -> "DEP_BLOCKED".equals(b.rule()))
                .map(Blocker::itemId).collect(Collectors.toSet());

        DependencyGraph graph = buildGraph(items, links, deps, verify, blockedIds,
                active == null ? null : active.getId(), commits);

        List<Handoff> handoffs = deriveHandoffs(
                teamMapper.recentTransitions(projectId, LocalDateTime.now().minusDays(HANDOFF_DAYS)),
                items, deps, links);

        List<OwnerLoad> owners = buildOwners(items, userNames, blockedIds, lastMove, today);

        return new Overview(projectId, project.getCode(), project.getName(),
                pulse, graph, blockers, handoffs, owners);
    }

    // ---------- 纯函数：依赖对归一化 ----------
    static List<DepPair> toDepPairs(List<TraceLink> links, Set<Long> validItemIds) {
        List<DepPair> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TraceLink l : links) {
            if (!"WORK_ITEM".equals(l.getSourceType()) || !"WORK_ITEM".equals(l.getTargetType())) {
                continue;
            }
            if (!validItemIds.contains(l.getSourceId()) || !validItemIds.contains(l.getTargetId())) {
                continue; // 悬空链防御
            }
            DepPair p = null;
            if ("depends_on".equals(l.getRelation())) {
                p = new DepPair(l.getTargetId(), l.getSourceId()); // A depends_on B：B 前置
            } else if ("blocks".equals(l.getRelation())) {
                p = new DepPair(l.getSourceId(), l.getTargetId()); // B blocks A：B 前置
            }
            if (p != null && seen.add(p.prerequisite() + ">" + p.dependent())) {
                out.add(p);
            }
        }
        return out;
    }

    // ---------- 纯函数：阻塞规则引擎 ----------
    static List<Blocker> evaluateBlockers(Map<Long, WorkItem> items, List<DepPair> deps,
                                          Map<Long, LocalDateTime> lastMove, Map<Long, VerifyStat> verify,
                                          List<TraceLink> links, Long activeSprintId,
                                          SprintPulse pulse, LocalDate today) {
        Map<String, Blocker> out = new LinkedHashMap<>();

        // DEP_BLOCKED / DEP_UPCOMING：按后继 A 聚合全部未完成前置
        Map<Long, List<WorkItem>> unmetByDependent = new LinkedHashMap<>();
        for (DepPair p : deps) {
            WorkItem pre = items.get(p.prerequisite());
            WorkItem dep = items.get(p.dependent());
            if (pre == null || dep == null || isDone(pre) || isDone(dep)) {
                continue;
            }
            unmetByDependent.computeIfAbsent(dep.getId(), k -> new ArrayList<>()).add(pre);
        }
        for (Map.Entry<Long, List<WorkItem>> e : unmetByDependent.entrySet()) {
            WorkItem a = items.get(e.getKey());
            String causes = e.getValue().stream()
                    .map(b -> b.getCode() + "(" + b.getStatus() + ")").collect(Collectors.joining("、"));
            List<Long> causeIds = e.getValue().stream().map(WorkItem::getId).toList();
            if (GENERIC_TYPES.contains(a.getType())
                    && Set.of("Ready", "In Progress", "Verification").contains(a.getStatus())) {
                put(out, new Blocker("HIGH", "DEP_BLOCKED", "被上游卡住",
                        a.getCode() + " " + a.getTitle() + " 的前置 " + causes + " 尚未完成",
                        a.getId(), a.getCode(), causeIds, null));
            } else if ("Backlog".equals(a.getStatus())
                    && (activeSprintId == null || activeSprintId.equals(a.getIterationId()))) {
                put(out, new Blocker("MED", "DEP_UPCOMING", "开工前置未就绪",
                        a.getCode() + " " + a.getTitle() + " 计划开工，但前置 " + causes + " 未完成",
                        a.getId(), a.getCode(), causeIds, null));
            }
        }

        // STALE_WIP：进行中状态长时间无变化
        Set<String> wipStates = Set.of("In Progress", "Analysing", "Fixing");
        for (WorkItem w : items.values()) {
            LocalDateTime lm = lastMove.get(w.getId());
            if (lm == null || !wipStates.contains(w.getStatus())) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(lm.toLocalDate(), today);
            if (days > STALE_DAYS) {
                put(out, new Blocker("MED", "STALE_WIP", "在制品停滞",
                        w.getCode() + " " + w.getTitle() + " 已 " + days + " 天无状态变化（" + w.getStatus() + "）",
                        w.getId(), w.getCode(), List.of(), days));
            }
        }

        // VERIFY_WAIT：验证中但用例缺失/未执行/FAIL
        for (WorkItem w : items.values()) {
            if (!GENERIC_TYPES.contains(w.getType()) || !"Verification".equals(w.getStatus())) {
                continue;
            }
            VerifyStat vs = verify.get(w.getId());
            if (vs == null) {
                put(out, new Blocker("MED", "VERIFY_WAIT", "验证中但无用例覆盖",
                        w.getCode() + " " + w.getTitle() + " 在验证中，但没有关联验证用例",
                        w.getId(), w.getCode(), List.of(), null));
            } else if (vs.fail() > 0) {
                put(out, new Blocker("HIGH", "VERIFY_WAIT", "验证失败待处理",
                        w.getCode() + " " + w.getTitle() + " 的用例最新执行 FAIL（" + vs.fail() + " 个）",
                        w.getId(), w.getCode(), List.of(), null));
            } else if (vs.pass() == 0) {
                put(out, new Blocker("MED", "VERIFY_WAIT", "用例尚未执行",
                        w.getCode() + " " + w.getTitle() + " 在验证中，关联用例还没有执行记录",
                        w.getId(), w.getCode(), List.of(), null));
            }
        }

        // DEFECT_DRAG：未关缺陷拖拽受影响需求
        for (WorkItem d : items.values()) {
            if (!"DEFECT".equals(d.getType()) || "Closed".equals(d.getStatus())) {
                continue;
            }
            for (TraceLink l : links) {
                if (!"affects".equals(l.getRelation()) || !d.getId().equals(l.getSourceId())
                        || !"WORK_ITEM".equals(l.getSourceType()) || !"WORK_ITEM".equals(l.getTargetType())) {
                    continue;
                }
                WorkItem x = items.get(l.getTargetId());
                if (x == null || !GENERIC_TYPES.contains(x.getType())) {
                    continue;
                }
                if ("Accepted".equals(x.getStatus())) {
                    put(out, new Blocker("HIGH", "DEFECT_DRAG", "已验收需求存在未关缺陷",
                            x.getCode() + " 已验收，但缺陷 " + d.getCode() + " " + d.getTitle() + " 仍未关闭",
                            x.getId(), x.getCode(), List.of(d.getId()), null));
                } else if (Set.of("In Progress", "Verification").contains(x.getStatus())) {
                    put(out, new Blocker("MED", "DEFECT_DRAG", "缺陷未闭环需求仍在推进",
                            x.getCode() + " 推进中，关联缺陷 " + d.getCode() + "（" + d.getStatus() + "）未闭环",
                            x.getId(), x.getCode(), List.of(d.getId()), null));
                }
            }
        }

        // CHANGE_FREEZE：待审批变更波及的未完成项
        for (WorkItem c : items.values()) {
            if (!"CHANGE".equals(c.getType())
                    || !Set.of("Submitted", "Impact Analysed").contains(c.getStatus())) {
                continue;
            }
            for (TraceLink l : links) {
                if (!c.getId().equals(l.getSourceId()) || !"WORK_ITEM".equals(l.getSourceType())
                        || !"WORK_ITEM".equals(l.getTargetType())
                        || !Set.of("changes", "affects").contains(l.getRelation())) {
                    continue;
                }
                WorkItem x = items.get(l.getTargetId());
                if (x != null && !isDone(x)) {
                    put(out, new Blocker("MED", "CHANGE_FREEZE", "存在待审批变更",
                            x.getCode() + " " + x.getTitle() + " 被变更 " + c.getCode()
                                    + " 波及且变更未决策，继续投入可能返工",
                            x.getId(), x.getCode(), List.of(c.getId()), null));
                }
            }
        }

        // SPRINT_RISK：迭代时间进度与完成率倒挂
        if (pulse != null && pulse.donePct() != null
                && pulse.timePct() > SPRINT_RISK_TIME_PCT && pulse.donePct() < SPRINT_RISK_DONE_PCT) {
            put(out, new Blocker("HIGH", "SPRINT_RISK", "迭代交付风险",
                    pulse.name() + " 时间已过 " + pulse.timePct() + "%，承诺完成仅 " + pulse.donePct() + "%",
                    null, null, List.of(), null));
        }

        List<Blocker> list = new ArrayList<>(out.values());
        List<String> sevOrder = List.of("HIGH", "MED", "LOW");
        List<String> ruleOrder = List.of("DEP_BLOCKED", "VERIFY_WAIT", "SPRINT_RISK",
                "DEFECT_DRAG", "CHANGE_FREEZE", "DEP_UPCOMING", "STALE_WIP");
        list.sort(Comparator
                .comparingInt((Blocker b) -> sevOrder.indexOf(b.severity()))
                .thenComparingInt(b -> ruleOrder.indexOf(b.rule()))
                .thenComparing(b -> b.days() == null ? -1L : -b.days())
                .thenComparing(b -> b.itemCode() == null ? "" : b.itemCode()));
        return list;
    }

    private static void put(Map<String, Blocker> out, Blocker b) {
        out.putIfAbsent(b.rule() + ":" + b.itemId(), b);
    }

    // ---------- 纯函数：依赖图 ----------
    static DependencyGraph buildGraph(Map<Long, WorkItem> items, List<TraceLink> links,
                                      List<DepPair> deps, Map<Long, VerifyStat> verify,
                                      Set<Long> blockedIds, Long activeSprintId,
                                      List<IterationCommitment> commits) {
        Set<Long> committed = commits.stream().map(IterationCommitment::getWorkItemId)
                .collect(Collectors.toSet());
        // 种子：未完成项 ∪ 活跃迭代承诺项
        Set<Long> seed = items.values().stream()
                .filter(w -> !isDone(w) || committed.contains(w.getId()))
                .map(WorkItem::getId).collect(Collectors.toCollection(LinkedHashSet::new));

        // 边（方向归一化）：dep 前置→后继；parent_of 父→子；affects/changes 源→受影响
        List<GraphEdge> allEdges = new ArrayList<>();
        for (DepPair p : deps) {
            allEdges.add(new GraphEdge(p.prerequisite(), p.dependent(), "dep"));
        }
        for (TraceLink l : links) {
            if (!"WORK_ITEM".equals(l.getSourceType()) || !"WORK_ITEM".equals(l.getTargetType())
                    || !items.containsKey(l.getSourceId()) || !items.containsKey(l.getTargetId())) {
                continue;
            }
            switch (l.getRelation()) {
                case "parent_of" -> allEdges.add(new GraphEdge(l.getSourceId(), l.getTargetId(), "parent_of"));
                case "affects" -> allEdges.add(new GraphEdge(l.getSourceId(), l.getTargetId(), "affects"));
                case "changes" -> allEdges.add(new GraphEdge(l.getSourceId(), l.getTargetId(), "changes"));
                default -> { /* verifies 等不产边 */ }
            }
        }

        // 一跳扩展（含已完成邻居=绿灯）
        Set<Long> nodesIds = new LinkedHashSet<>(seed);
        for (GraphEdge e : allEdges) {
            if (seed.contains(e.source()) || seed.contains(e.target())) {
                nodesIds.add(e.source());
                nodesIds.add(e.target());
            }
        }

        // 裁剪：有 dep 边的 > 迭代内 > 其余按 createdAt 新者优先；孤立节点最先裁
        boolean truncated = false;
        if (nodesIds.size() > GRAPH_NODE_LIMIT) {
            truncated = true;
            Set<Long> depTouched = new HashSet<>();
            deps.forEach(p -> {
                depTouched.add(p.prerequisite());
                depTouched.add(p.dependent());
            });
            Set<Long> edgeTouched = new HashSet<>();
            allEdges.forEach(e -> {
                edgeTouched.add(e.source());
                edgeTouched.add(e.target());
            });
            nodesIds = nodesIds.stream()
                    .sorted(Comparator
                            .comparing((Long id) -> !depTouched.contains(id))
                            .thenComparing(id -> !committed.contains(id))
                            .thenComparing(id -> !edgeTouched.contains(id))
                            .thenComparing(id -> {
                                WorkItem w = items.get(id);
                                return w == null || w.getCreatedAt() == null
                                        ? LocalDateTime.MIN : w.getCreatedAt();
                            }, Comparator.reverseOrder()))
                    .limit(GRAPH_NODE_LIMIT)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<Long> finalIds = nodesIds;
        List<GraphEdge> edges = allEdges.stream()
                .filter(e -> finalIds.contains(e.source()) && finalIds.contains(e.target()))
                .toList();
        Map<Long, Integer> degree = new HashMap<>();
        edges.forEach(e -> {
            degree.merge(e.source(), 1, Integer::sum);
            degree.merge(e.target(), 1, Integer::sum);
        });

        List<GraphNode> nodes = new ArrayList<>();
        for (Long id : finalIds) {
            WorkItem w = items.get(id);
            if (w == null) {
                continue;
            }
            VerifyStat vs = verify.get(id);
            String badge = vs == null ? null : vs.fail() > 0 ? "FAIL" : vs.pass() > 0 ? "PASS" : "NO_RUN";
            nodes.add(new GraphNode(id, w.getCode(), w.getType(), w.getTitle(), w.getStatus(),
                    null, w.getIterationId() != null && w.getIterationId().equals(activeSprintId),
                    blockedIds.contains(id), badge, degree.getOrDefault(id, 0)));
        }
        return new DependencyGraph(nodes, edges, truncated);
    }

    // ---------- 纯函数：交接台 ----------
    static List<Handoff> deriveHandoffs(List<Map<String, Object>> transitions,
                                        Map<Long, WorkItem> items, List<DepPair> deps,
                                        List<TraceLink> links) {
        List<Handoff> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> r : transitions) {
            Long id = ((Number) r.get("work_item_id")).longValue();
            String to = String.valueOf(r.get("to_status"));
            String type = String.valueOf(r.get("type"));
            String code = String.valueOf(r.get("code"));
            String title = String.valueOf(r.get("title"));
            String at = String.valueOf(PerfService.toLdt(r.get("at")));

            if (GENERIC_TYPES.contains(type) && "Verification".equals(to)) {
                add(out, seen, new Handoff(at, "TEST_READY", "已提验证——测试可开始执行用例",
                        id, code, title, to, null, null));
            } else if ("CHANGE".equals(type) && "Approved".equals(to)) {
                boolean any = false;
                for (TraceLink l : links) {
                    if (id.equals(l.getSourceId()) && "WORK_ITEM".equals(l.getSourceType())
                            && "WORK_ITEM".equals(l.getTargetType())
                            && Set.of("changes", "affects").contains(l.getRelation())
                            && items.containsKey(l.getTargetId())) {
                        WorkItem x = items.get(l.getTargetId());
                        add(out, seen, new Handoff(at, "CHANGE_APPROVED", "变更已批准——可开始实施",
                                id, code, title, to, x.getId(), x.getCode()));
                        any = true;
                    }
                }
                if (!any) {
                    add(out, seen, new Handoff(at, "CHANGE_APPROVED", "变更已批准——可开始实施",
                            id, code, title, to, null, null));
                }
            } else if ("DEFECT".equals(type) && "Retesting".equals(to)) {
                Long reqId = null;
                String reqCode = null;
                for (TraceLink l : links) {
                    if (id.equals(l.getSourceId()) && "affects".equals(l.getRelation())
                            && "WORK_ITEM".equals(l.getTargetType()) && items.containsKey(l.getTargetId())) {
                        reqId = l.getTargetId();
                        reqCode = items.get(reqId).getCode();
                        break;
                    }
                }
                add(out, seen, new Handoff(at, "RETEST", "修复完成——待复测确认",
                        id, code, title, to, reqId, reqCode));
            }

            // 终态 → 解锁下游
            WorkItem w = items.get(id);
            if (w != null && DONE_STATES.getOrDefault(type, Set.of("Accepted")).contains(to)) {
                for (DepPair p : deps) {
                    if (p.prerequisite().equals(id)) {
                        WorkItem a = items.get(p.dependent());
                        if (a != null && !isDone(a)) {
                            add(out, seen, new Handoff(at, "UNLOCKED",
                                    "前置已完成——" + a.getCode() + " 已解锁可开工",
                                    id, code, title, to, a.getId(), a.getCode()));
                        }
                    }
                }
            }
        }
        return out.size() > 20 ? out.subList(0, 20) : out;
    }

    private static void add(List<Handoff> out, Set<String> seen, Handoff h) {
        if (seen.add(h.kind() + ":" + h.itemId() + ":" + h.downstreamId())) {
            out.add(h);
        }
    }

    // ---------- SprintPulse / OwnerLoad ----------
    static SprintPulse buildPulse(Iteration it, List<IterationCommitment> commits,
                                  Map<Long, WorkItem> items, LocalDate today) {
        LocalDate start = it.getStartDate();
        LocalDate end = it.getEndDate();
        int totalDays = start == null || end == null ? 0
                : (int) ChronoUnit.DAYS.between(start, end) + 1;
        int daysGone = start == null ? 0
                : (int) Math.max(0, Math.min(ChronoUnit.DAYS.between(start, today) + 1, totalDays));
        int timePct = totalDays == 0 ? 0 : daysGone * 100 / totalDays;

        List<IterationCommitment> effective = commits;
        int committedCount = effective.size();
        int doneCount = 0;
        double committedPoints = 0;
        double donePoints = 0;
        for (IterationCommitment c : effective) {
            double pts = PerfService.parsePoints(c.getEstimateSnap());
            committedPoints += pts;
            WorkItem w = items.get(c.getWorkItemId());
            if (w != null && isDone(w)) {
                doneCount++;
                donePoints += pts;
            }
        }
        Integer donePct = committedCount == 0 ? null : doneCount * 100 / committedCount;

        List<ColumnCount> columns = new ArrayList<>();
        for (String s : List.of("Backlog", "Ready", "In Progress", "Verification", "Accepted")) {
            int n = (int) items.values().stream()
                    .filter(w -> GENERIC_TYPES.contains(w.getType())
                            && it.getId().equals(w.getIterationId()) && s.equals(w.getStatus()))
                    .count();
            columns.add(new ColumnCount(s, n));
        }
        return new SprintPulse(it.getId(), it.getName(), it.getGoal(),
                start == null ? null : start.toString(), end == null ? null : end.toString(),
                totalDays, daysGone, timePct, committedCount, doneCount,
                committedPoints, donePoints, donePct, columns);
    }

    static List<OwnerLoad> buildOwners(Map<Long, WorkItem> items, Map<Long, String> userNames,
                                       Set<Long> blockedIds, Map<Long, LocalDateTime> lastMove,
                                       LocalDate today) {
        Map<Long, List<WorkItem>> byOwner = new LinkedHashMap<>();
        for (WorkItem w : items.values()) {
            if (GENERIC_TYPES.contains(w.getType())
                    && Set.of("Ready", "In Progress", "Verification").contains(w.getStatus())) {
                byOwner.computeIfAbsent(w.getOwnerId(), k -> new ArrayList<>()).add(w);
            }
        }
        List<String> statusOrder = List.of("Verification", "In Progress", "Ready");
        List<OwnerLoad> out = new ArrayList<>();
        for (Map.Entry<Long, List<WorkItem>> e : byOwner.entrySet()) {
            List<WorkItem> ws = e.getValue();
            ws.sort(Comparator
                    .comparingInt((WorkItem w) -> statusOrder.indexOf(w.getStatus()))
                    .thenComparing(w -> w.getPriority() == null ? "P9" : w.getPriority())
                    .thenComparing(WorkItem::getCode));
            double points = ws.stream().mapToDouble(w -> PerfService.parsePoints(w.getEstimate())).sum();
            List<OwnerItem> ois = ws.stream().map(w -> {
                LocalDateTime lm = lastMove.get(w.getId());
                long stall = lm == null ? 0 : ChronoUnit.DAYS.between(lm.toLocalDate(), today);
                return new OwnerItem(w.getId(), w.getCode(), w.getType(), w.getTitle(),
                        w.getStatus(), w.getPriority(), blockedIds.contains(w.getId()), stall);
            }).toList();
            Long ownerId = e.getKey();
            out.add(new OwnerLoad(ownerId,
                    ownerId == null ? "未分派" : userNames.getOrDefault(ownerId, "#" + ownerId),
                    points, ois));
        }
        out.sort(Comparator.comparing((OwnerLoad o) -> o.ownerId() == null)
                .thenComparing(o -> o.ownerId() == null ? Long.MAX_VALUE : o.ownerId()));
        return out;
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
