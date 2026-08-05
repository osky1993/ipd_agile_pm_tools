package com.ipd.toolbox.service;

import com.ipd.toolbox.domain.entity.WorkItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** CPM 纯函数：链式/菱形/未估算默认1天/环剔除/已完成剔除/孤立排除。 */
class ScheduleServiceTest {

    private WorkItem wi(Long id, String estimate, String status) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setCode("T-" + id);
        w.setTitle("t" + id);
        w.setType("TASK");
        w.setStatus(status);
        w.setEstimate(estimate);
        return w;
    }

    private TeamService.DepPair dep(long pre, long dep) {
        return new TeamService.DepPair(pre, dep);
    }

    private Map<Long, WorkItem> items(WorkItem... ws) {
        return List.of(ws).stream().collect(Collectors.toMap(WorkItem::getId, w -> w));
    }

    @Test
    void 链式_总工期与全链关键() {
        // 1(3d) → 2(2d) → 3(5d)
        ScheduleService.CpmResult r = ScheduleService.computeCpm(
                items(wi(1L, "3", "Ready"), wi(2L, "2", "Ready"), wi(3L, "5", "Ready")),
                List.of(dep(1, 2), dep(2, 3)));
        assertEquals(10.0, r.totalDuration(), 1e-6);
        assertEquals(List.of(1L, 2L, 3L), r.criticalChain());
        assertTrue(r.edges().stream().allMatch(ScheduleService.CpmEdge::critical));
    }

    @Test
    void 菱形_短支路有浮动不关键() {
        // 1(2d) → 2(5d) → 4(1d)；1 → 3(1d) → 4：3 有 4 天浮动
        ScheduleService.CpmResult r = ScheduleService.computeCpm(
                items(wi(1L, "2", "Ready"), wi(2L, "5", "Ready"),
                        wi(3L, "1", "Ready"), wi(4L, "1", "Ready")),
                List.of(dep(1, 2), dep(1, 3), dep(2, 4), dep(3, 4)));
        assertEquals(8.0, r.totalDuration(), 1e-6);
        Map<Long, ScheduleService.CpmNode> byId = r.nodes().stream()
                .collect(Collectors.toMap(ScheduleService.CpmNode::id, n -> n));
        assertTrue(byId.get(2L).critical());
        assertFalse(byId.get(3L).critical());
        assertEquals(4.0, byId.get(3L).slack(), 1e-6);
        assertEquals(List.of(1L, 2L, 4L), r.criticalChain());
        // 短支路的边不关键
        assertTrue(r.edges().stream().filter(e -> e.to() == 3L || e.from() == 3L)
                .noneMatch(ScheduleService.CpmEdge::critical));
    }

    @Test
    void 未估算按1天计且标注_关键路径上给出提示() {
        ScheduleService.CpmResult r = ScheduleService.computeCpm(
                items(wi(1L, null, "Ready"), wi(2L, "abc", "Ready")),
                List.of(dep(1, 2)));
        assertEquals(2.0, r.totalDuration(), 1e-6);
        assertTrue(r.nodes().stream().noneMatch(ScheduleService.CpmNode::estimated));
        assertEquals(List.of("T-1", "T-2"), r.unestimatedCritical());
    }

    @Test
    void 已完成前置剔除_其后继从0开始() {
        // 1 已验收 → 2(3d)：1 不入网络，但 2 无其他依赖 → 孤立也被排除？
        // 2 与 3 仍有依赖边 → 2、3 参与，2 从 0 开始
        ScheduleService.CpmResult r = ScheduleService.computeCpm(
                items(wi(1L, "5", "Accepted"), wi(2L, "3", "Ready"), wi(3L, "2", "Ready")),
                List.of(dep(1, 2), dep(2, 3)));
        assertEquals(5.0, r.totalDuration(), 1e-6);
        assertTrue(r.nodes().stream().noneMatch(n -> n.id() == 1L));
        assertEquals(0.0, r.nodes().stream().filter(n -> n.id() == 2L).findFirst().get().es(), 1e-6);
    }

    @Test
    void 环成员整体剔除并输出警告() {
        // 1↔2 成环；3→4 正常
        ScheduleService.CpmResult r = ScheduleService.computeCpm(
                items(wi(1L, "2", "Ready"), wi(2L, "2", "Ready"),
                        wi(3L, "1", "Ready"), wi(4L, "1", "Ready")),
                List.of(dep(1, 2), dep(2, 1), dep(3, 4)));
        assertFalse(r.cycles().isEmpty());
        assertTrue(r.nodes().stream().noneMatch(n -> n.id() == 1L || n.id() == 2L));
        assertEquals(2.0, r.totalDuration(), 1e-6);
    }

    @Test
    void 孤立项不参与_空网络返回空() {
        ScheduleService.CpmResult r = ScheduleService.computeCpm(
                items(wi(1L, "3", "Ready"), wi(2L, "2", "Ready")), List.of());
        assertTrue(r.nodes().isEmpty());
        assertEquals(0, r.totalDuration(), 1e-6);
    }
}
