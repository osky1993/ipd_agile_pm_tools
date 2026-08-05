package com.ipd.toolbox.service;

import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.entity.WorkItemStatusLog;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 时点回放纯函数：存在性/最后流转生效/初始状态兜底/已删项参与。 */
class TimeMachineServiceTest {

    private WorkItem wi(Long id, String type, String createdAt, Integer deleted) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setCode("X-" + id);
        w.setTitle("t" + id);
        w.setType(type);
        w.setCreatedAt(LocalDateTime.parse(createdAt));
        w.setDeleted(deleted);
        return w;
    }

    private WorkItemStatusLog log(Long itemId, String to, String at) {
        WorkItemStatusLog l = new WorkItemStatusLog();
        l.setWorkItemId(itemId);
        l.setToStatus(to);
        l.setAt(LocalDateTime.parse(at));
        return l;
    }

    @Test
    void 回放_未创建不存在_最后一条流转生效_无流转取初始状态() {
        List<WorkItem> items = List.of(
                wi(1L, "REQUIREMENT", "2026-07-01T09:00:00", 0),  // 两次流转
                wi(2L, "DEFECT", "2026-07-05T09:00:00", 0),       // 无流转 → 初始 Open
                wi(3L, "TASK", "2026-08-01T09:00:00", 0));        // date 后创建 → 不存在
        List<WorkItemStatusLog> logs = List.of(
                log(1L, "Ready", "2026-07-02T10:00:00"),
                log(1L, "In Progress", "2026-07-10T10:00:00"),   // date 当天
                log(1L, "Accepted", "2026-07-20T10:00:00"));     // date 之后 → 不生效

        Map<Long, String> at = TimeMachineService.replay(items, logs, LocalDate.of(2026, 7, 10));
        assertEquals("In Progress", at.get(1L)); // 当天收盘取当天流转
        assertEquals("Open", at.get(2L));        // 初始状态兜底
        assertFalse(at.containsKey(3L));         // 尚未创建
    }

    @Test
    void 回放_创建当天即存在_已删项照常参与() {
        List<WorkItem> items = List.of(
                wi(1L, "STORY", "2026-07-10T15:00:00", 0),
                wi(2L, "REQUIREMENT", "2026-07-01T09:00:00", 1)); // 现已逻辑删除
        List<WorkItemStatusLog> logs = List.of(log(2L, "Ready", "2026-07-03T10:00:00"));

        Map<Long, String> at = TimeMachineService.replay(items, logs, LocalDate.of(2026, 7, 10));
        assertEquals("Backlog", at.get(1L)); // 创建当天收盘即存在
        assertEquals("Ready", at.get(2L));   // 已删项的历史真实呈现
    }

    @Test
    void 回放_边界_创建次日零点之后不算当天() {
        List<WorkItem> items = List.of(wi(1L, "TASK", "2026-07-11T00:00:00", 0));
        Map<Long, String> at = TimeMachineService.replay(items, List.of(), LocalDate.of(2026, 7, 10));
        assertFalse(at.containsKey(1L)); // 7-11 00:00 创建 ≥ 7-10 收盘 → 7-10 不存在
    }

    @Test
    void 双时点KPI口径_两次回放独立() {
        List<WorkItem> items = List.of(
                wi(1L, "REQUIREMENT", "2026-07-01T09:00:00", 0),
                wi(2L, "DEFECT", "2026-07-01T09:00:00", 0),
                wi(3L, "RISK", "2026-07-20T09:00:00", 0)); // B 时点才存在
        List<WorkItemStatusLog> logs = List.of(
                log(1L, "Ready", "2026-07-02T10:00:00"),
                log(1L, "In Progress", "2026-07-12T10:00:00"),
                log(1L, "Verification", "2026-07-18T10:00:00"),
                log(1L, "Accepted", "2026-07-25T10:00:00"),
                log(2L, "Closed", "2026-07-22T10:00:00"));

        Map<Long, String> atA = TimeMachineService.replay(items, logs, LocalDate.of(2026, 7, 10));
        Map<Long, String> atB = TimeMachineService.replay(items, logs, LocalDate.of(2026, 7, 30));
        TimeMachineService.Kpis a = TimeMachineService.kpis(items, atA);
        TimeMachineService.Kpis b = TimeMachineService.kpis(items, atB);

        assertEquals(0, a.reqAccepted());
        assertEquals(1, a.defectsOpen()); // 当时 Open
        assertEquals(0, a.risksOpen());   // 尚不存在
        assertEquals(1, b.reqAccepted());
        assertEquals(0, b.defectsOpen()); // 已关闭
        assertEquals(1, b.risksOpen());   // 期间登记
    }
}
