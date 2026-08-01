package com.ipd.toolbox.service;

import com.ipd.toolbox.domain.entity.Iteration;
import com.ipd.toolbox.domain.entity.IterationCommitment;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.domain.entity.WorkItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 团队协作屏规则引擎（纯函数直测，不需要 mock）。 */
class TeamServiceTest {

    private final LocalDate today = LocalDate.of(2026, 8, 1);

    private WorkItem wi(Long id, String type, String status) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setCode(type.substring(0, 3) + "-" + id);
        w.setTitle("项" + id);
        w.setType(type);
        w.setStatus(status);
        w.setCreatedAt(LocalDateTime.of(2026, 7, 1, 0, 0));
        return w;
    }

    private TraceLink link(Long sid, Long tid, String relation) {
        TraceLink l = new TraceLink();
        l.setSourceType("WORK_ITEM");
        l.setSourceId(sid);
        l.setTargetType("WORK_ITEM");
        l.setTargetId(tid);
        l.setRelation(relation);
        return l;
    }

    private Map<Long, WorkItem> items(WorkItem... ws) {
        Map<Long, WorkItem> m = new LinkedHashMap<>();
        for (WorkItem w : ws) m.put(w.getId(), w);
        return m;
    }

    @Test
    void 完成判定_表驱动() {
        assertTrue(TeamService.isDone(wi(1L, "REQUIREMENT", "Accepted")));
        assertFalse(TeamService.isDone(wi(1L, "REQUIREMENT", "Verification")));
        assertTrue(TeamService.isDone(wi(2L, "DEFECT", "Closed")));
        assertFalse(TeamService.isDone(wi(2L, "DEFECT", "Retesting")));
        assertTrue(TeamService.isDone(wi(3L, "CHANGE", "Verified")));
        assertTrue(TeamService.isDone(wi(3L, "CHANGE", "Rejected")));
        assertFalse(TeamService.isDone(wi(3L, "CHANGE", "Approved")));
        assertTrue(TeamService.isDone(wi(4L, "RISK", "Accepted")));
    }

    @Test
    void 依赖对归一化_dependsOn与blocks等价_悬空链丢弃_去重() {
        Set<Long> valid = Set.of(1L, 2L);
        // A(1) depends_on B(2) 与 B(2) blocks A(1) → 同一对 (前置2→后继1)
        var pairs = TeamService.toDepPairs(List.of(
                link(1L, 2L, "depends_on"),
                link(2L, 1L, "blocks"),
                link(1L, 99L, "depends_on")), valid);  // 99 不存在 → 丢弃
        assertEquals(1, pairs.size());
        assertEquals(2L, pairs.get(0).prerequisite());
        assertEquals(1L, pairs.get(0).dependent());
    }

    @Test
    void DEP_BLOCKED_按后继聚合多前置_前置完成不算() {
        WorkItem b1 = wi(1L, "REQUIREMENT", "Verification");
        WorkItem b2 = wi(2L, "REQUIREMENT", "In Progress");
        WorkItem done = wi(3L, "REQUIREMENT", "Accepted");
        WorkItem a = wi(4L, "REQUIREMENT", "In Progress");
        var deps = List.of(
                new TeamService.DepPair(1L, 4L),
                new TeamService.DepPair(2L, 4L),
                new TeamService.DepPair(3L, 4L)); // 已完成前置不计
        var all = TeamService.evaluateBlockers(items(b1, b2, done, a), deps,
                Map.of(), Map.of(), List.of(), null, null, today);
        var blockers = all.stream().filter(x -> "DEP_BLOCKED".equals(x.rule())).toList();

        assertEquals(1, blockers.size());
        var b = blockers.get(0);
        assertEquals("HIGH", b.severity());
        assertEquals(4L, b.itemId());
        assertEquals(List.of(1L, 2L), b.causeIds());
        assertTrue(b.detail().contains("REQ-1") && b.detail().contains("REQ-2"));
    }

    @Test
    void DEP_UPCOMING_仅活跃迭代内Backlog_未排期不报_无迭代放宽() {
        WorkItem pre = wi(1L, "REQUIREMENT", "In Progress");
        WorkItem inSprint = wi(2L, "REQUIREMENT", "Backlog");
        inSprint.setIterationId(7L);
        WorkItem outside = wi(3L, "REQUIREMENT", "Backlog");
        var deps = List.of(new TeamService.DepPair(1L, 2L), new TeamService.DepPair(1L, 3L));

        var withSprint = TeamService.evaluateBlockers(items(pre, inSprint, outside), deps,
                Map.of(), Map.of(), List.of(), 7L, null, today);
        assertEquals(1, withSprint.stream().filter(b -> "DEP_UPCOMING".equals(b.rule())).count());
        assertEquals(2L, withSprint.stream().filter(b -> "DEP_UPCOMING".equals(b.rule()))
                .findFirst().orElseThrow().itemId());

        var noSprint = TeamService.evaluateBlockers(items(pre, inSprint, outside), deps,
                Map.of(), Map.of(), List.of(), null, null, today);
        assertEquals(2, noSprint.stream().filter(b -> "DEP_UPCOMING".equals(b.rule())).count());
    }

    @Test
    void STALE_WIP_边界7天不报8天报_缺陷Fixing也算() {
        WorkItem w7 = wi(1L, "REQUIREMENT", "In Progress");
        WorkItem w8 = wi(2L, "DEFECT", "Fixing");
        Map<Long, LocalDateTime> lastMove = Map.of(
                1L, today.minusDays(7).atStartOfDay(),
                2L, today.minusDays(8).atStartOfDay());
        var blockers = TeamService.evaluateBlockers(items(w7, w8), List.of(),
                lastMove, Map.of(), List.of(), null, null, today);
        assertEquals(1, blockers.size());
        assertEquals("STALE_WIP", blockers.get(0).rule());
        assertEquals(2L, blockers.get(0).itemId());
        assertEquals(8L, blockers.get(0).days());
    }

    @Test
    void VERIFY_WAIT_无用例MED_未执行MED_有FAIL则HIGH_全PASS不报() {
        WorkItem noCase = wi(1L, "REQUIREMENT", "Verification");
        WorkItem noRun = wi(2L, "REQUIREMENT", "Verification");
        WorkItem failed = wi(3L, "REQUIREMENT", "Verification");
        WorkItem passed = wi(4L, "REQUIREMENT", "Verification");
        Map<Long, TeamService.VerifyStat> verify = Map.of(
                2L, new TeamService.VerifyStat(1, 0, 0),
                3L, new TeamService.VerifyStat(2, 1, 1),
                4L, new TeamService.VerifyStat(1, 1, 0));
        var blockers = TeamService.evaluateBlockers(items(noCase, noRun, failed, passed),
                List.of(), Map.of(), verify, List.of(), null, null, today);

        Map<Long, String> sevById = new HashMap<>();
        blockers.stream().filter(b -> "VERIFY_WAIT".equals(b.rule()))
                .forEach(b -> sevById.put(b.itemId(), b.severity()));
        assertEquals(Map.of(1L, "MED", 2L, "MED", 3L, "HIGH"), sevById);
    }

    @Test
    void DEFECT_DRAG_推进中MED_已验收HIGH_已关缺陷不报() {
        WorkItem open = wi(1L, "DEFECT", "Analysing");
        WorkItem closed = wi(2L, "DEFECT", "Closed");
        WorkItem inProg = wi(3L, "REQUIREMENT", "In Progress");
        WorkItem accepted = wi(4L, "REQUIREMENT", "Accepted");
        var links = List.of(link(1L, 3L, "affects"), link(1L, 4L, "affects"), link(2L, 3L, "affects"));
        var blockers = TeamService.evaluateBlockers(items(open, closed, inProg, accepted),
                List.of(), Map.of(), Map.of(), links, null, null, today);

        var drag = blockers.stream().filter(b -> "DEFECT_DRAG".equals(b.rule())).toList();
        assertEquals(2, drag.size());
        assertEquals("HIGH", drag.stream().filter(b -> b.itemId().equals(4L)).findFirst().orElseThrow().severity());
        assertEquals("MED", drag.stream().filter(b -> b.itemId().equals(3L)).findFirst().orElseThrow().severity());
    }

    @Test
    void CHANGE_FREEZE_待审批波及未完成项_已批准或目标完成不报() {
        WorkItem pending = wi(1L, "CHANGE", "Impact Analysed");
        WorkItem approved = wi(2L, "CHANGE", "Approved");
        WorkItem target = wi(3L, "REQUIREMENT", "In Progress");
        WorkItem doneTarget = wi(4L, "REQUIREMENT", "Accepted");
        var links = List.of(link(1L, 3L, "changes"), link(1L, 4L, "affects"), link(2L, 3L, "changes"));
        var blockers = TeamService.evaluateBlockers(items(pending, approved, target, doneTarget),
                List.of(), Map.of(), Map.of(), links, null, null, today);

        var freeze = blockers.stream().filter(b -> "CHANGE_FREEZE".equals(b.rule())).toList();
        assertEquals(1, freeze.size());
        assertEquals(3L, freeze.get(0).itemId());
    }

    @Test
    void SPRINT_RISK_边界_时间60不报61报_完成50不报49报() {
        var cols = List.<TeamService.ColumnCount>of();
        var risk61 = new TeamService.SprintPulse(1L, "S", null, null, null, 100, 61, 61, 4, 1, 0, 0, 25, cols);
        var risk60 = new TeamService.SprintPulse(1L, "S", null, null, null, 100, 60, 60, 4, 1, 0, 0, 25, cols);
        var done50 = new TeamService.SprintPulse(1L, "S", null, null, null, 100, 61, 61, 4, 2, 0, 0, 50, cols);

        assertTrue(TeamService.evaluateBlockers(Map.of(), List.of(), Map.of(), Map.of(),
                List.of(), 1L, risk61, today).stream().anyMatch(b -> "SPRINT_RISK".equals(b.rule())));
        assertFalse(TeamService.evaluateBlockers(Map.of(), List.of(), Map.of(), Map.of(),
                List.of(), 1L, risk60, today).stream().anyMatch(b -> "SPRINT_RISK".equals(b.rule())));
        assertFalse(TeamService.evaluateBlockers(Map.of(), List.of(), Map.of(), Map.of(),
                List.of(), 1L, done50, today).stream().anyMatch(b -> "SPRINT_RISK".equals(b.rule())));
    }

    @Test
    void 排序_HIGH在前() {
        WorkItem pre = wi(1L, "REQUIREMENT", "In Progress");
        WorkItem blockedA = wi(2L, "REQUIREMENT", "Ready");
        WorkItem stale = wi(3L, "REQUIREMENT", "In Progress");
        var blockers = TeamService.evaluateBlockers(items(pre, blockedA, stale),
                List.of(new TeamService.DepPair(1L, 2L)),
                Map.of(3L, today.minusDays(10).atStartOfDay()), Map.of(), List.of(), null, null, today);
        assertEquals("HIGH", blockers.get(0).severity());
        assertEquals("DEP_BLOCKED", blockers.get(0).rule());
    }

    @Test
    void 依赖图_一跳含已完成邻居_verifies折算badge_不产边() {
        WorkItem donePre = wi(1L, "REQUIREMENT", "Accepted");
        WorkItem active = wi(2L, "REQUIREMENT", "In Progress");
        WorkItem far = wi(3L, "REQUIREMENT", "Accepted"); // 无边且已完成 → 不进图
        var deps = List.of(new TeamService.DepPair(1L, 2L));
        var graph = TeamService.buildGraph(items(donePre, active, far),
                List.of(link(1L, 2L, "depends_on")), deps,
                Map.of(2L, new TeamService.VerifyStat(1, 0, 1)),
                Set.of(), null, List.of());

        assertEquals(2, graph.nodes().size());
        assertFalse(graph.truncated());
        assertEquals(1, graph.edges().size());
        assertEquals("dep", graph.edges().get(0).relation());
        assertEquals(1L, graph.edges().get(0).source()); // 方向：前置→后继
        var node2 = graph.nodes().stream().filter(n -> n.id().equals(2L)).findFirst().orElseThrow();
        assertEquals("FAIL", node2.testBadge());
    }

    @Test
    void 依赖图_超限裁剪_保留dep边节点() {
        Map<Long, WorkItem> many = new LinkedHashMap<>();
        for (long i = 1; i <= 70; i++) {
            many.put(i, wi(i, "TASK", "Ready"));
        }
        var deps = List.of(new TeamService.DepPair(1L, 2L));
        var graph = TeamService.buildGraph(many, List.of(link(1L, 2L, "blocks")), deps,
                Map.of(), Set.of(), null, List.of());
        assertTrue(graph.truncated());
        assertEquals(60, graph.nodes().size());
        assertTrue(graph.nodes().stream().anyMatch(n -> n.id().equals(1L)));
        assertTrue(graph.nodes().stream().anyMatch(n -> n.id().equals(2L)));
    }

    @Test
    void 交接台_四类推导与解锁_去重() {
        WorkItem req = wi(1L, "REQUIREMENT", "Verification");
        WorkItem chg = wi(2L, "CHANGE", "Approved");
        WorkItem def = wi(3L, "DEFECT", "Retesting");
        WorkItem pre = wi(4L, "REQUIREMENT", "Accepted");
        WorkItem downstream = wi(5L, "REQUIREMENT", "Ready");
        var links = List.of(link(2L, 1L, "changes"), link(3L, 1L, "affects"));
        var deps = List.of(new TeamService.DepPair(4L, 5L));
        List<Map<String, Object>> transitions = List.of(
                trans(1L, "Verification", "REQUIREMENT", "REQ-1", "需求一"),
                trans(2L, "Approved", "CHANGE", "CHG-2", "变更二"),
                trans(3L, "Retesting", "DEFECT", "DEF-3", "缺陷三"),
                trans(4L, "Accepted", "REQUIREMENT", "REQ-4", "前置四"),
                trans(4L, "Accepted", "REQUIREMENT", "REQ-4", "前置四")); // 重复 → 去重

        var handoffs = TeamService.deriveHandoffs(transitions,
                items(req, chg, def, pre, downstream), deps, links);

        Map<String, Long> byKind = new HashMap<>();
        handoffs.forEach(h -> byKind.merge(h.kind(), 1L, Long::sum));
        assertEquals(1L, byKind.get("TEST_READY"));
        assertEquals(1L, byKind.get("CHANGE_APPROVED"));
        assertEquals(1L, byKind.get("RETEST"));
        assertEquals(1L, byKind.get("UNLOCKED"));
        var unlocked = handoffs.stream().filter(h -> "UNLOCKED".equals(h.kind())).findFirst().orElseThrow();
        assertEquals(5L, unlocked.downstreamId());
    }

    @Test
    void 迭代脉搏_clamp与估算容错() {
        Iteration it = new Iteration();
        it.setId(1L);
        it.setName("S2");
        it.setStartDate(LocalDate.of(2026, 7, 20));
        it.setEndDate(LocalDate.of(2026, 8, 2));
        WorkItem done = wi(1L, "REQUIREMENT", "Accepted");
        WorkItem open = wi(2L, "REQUIREMENT", "In Progress");
        var commits = List.of(commit(1L, "5"), commit(2L, "XL")); // 非数字按 0

        var pulse = TeamService.buildPulse(it, commits, items(done, open), LocalDate.of(2026, 8, 1));
        assertEquals(14, pulse.totalDays());
        assertEquals(13, pulse.daysGone());
        assertEquals(92, pulse.timePct());
        assertEquals(2, pulse.committedCount());
        assertEquals(1, pulse.doneCount());
        assertEquals(5.0, pulse.committedPoints());
        assertEquals(5.0, pulse.donePoints());
        assertEquals(50, pulse.donePct());

        // 未开始 clamp 0
        var early = TeamService.buildPulse(it, List.of(), Map.of(), LocalDate.of(2026, 7, 1));
        assertEquals(0, early.daysGone());
        // 已结束 clamp totalDays
        var late = TeamService.buildPulse(it, List.of(), Map.of(), LocalDate.of(2026, 9, 1));
        assertEquals(14, late.daysGone());
    }

    @Test
    void 负责人在办_分组排序_无产出字段仅分派透明() {
        WorkItem v = wi(1L, "REQUIREMENT", "Verification");
        v.setOwnerId(1L);
        v.setEstimate("8");
        WorkItem r = wi(2L, "TASK", "Ready");
        r.setOwnerId(1L);
        r.setEstimate("3");
        WorkItem unassigned = wi(3L, "STORY", "In Progress");
        var owners = TeamService.buildOwners(items(v, r, unassigned),
                Map.of(1L, "管理员"), Set.of(2L), Map.of(), today);

        assertEquals(2, owners.size());
        assertEquals("管理员", owners.get(0).ownerName());
        assertEquals(11.0, owners.get(0).points());
        assertEquals("Verification", owners.get(0).items().get(0).status()); // 验证中排前
        assertTrue(owners.get(0).items().get(1).blocked());
        assertEquals("未分派", owners.get(1).ownerName()); // null 组排最后
    }

    private Map<String, Object> trans(Long id, String to, String type, String code, String title) {
        Map<String, Object> m = new HashMap<>();
        m.put("work_item_id", id);
        m.put("from_status", "X");
        m.put("to_status", to);
        m.put("at", LocalDateTime.of(2026, 7, 30, 10, 0));
        m.put("code", code);
        m.put("type", type);
        m.put("title", title);
        return m;
    }

    private IterationCommitment commit(Long itemId, String estimate) {
        IterationCommitment c = new IterationCommitment();
        c.setIterationId(1L);
        c.setWorkItemId(itemId);
        c.setEstimateSnap(estimate);
        return c;
    }
}
