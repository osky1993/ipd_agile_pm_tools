package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Iteration;
import com.ipd.toolbox.domain.entity.MetricTarget;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.*;
import com.ipd.toolbox.security.UserContext;
import com.ipd.toolbox.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 效能指标计算的纯函数与目标管理（规划§13：无个人绩效口径，注册表为唯一出口）。 */
class PerfServiceTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------- 纯函数 ----------

    @Test
    void 估算解析_数字求和_非数字与空按0容错() {
        assertEquals(3.0, PerfService.parsePoints("3"));
        assertEquals(5.5, PerfService.parsePoints(" 5.5 "));
        assertEquals(0.0, PerfService.parsePoints("XL"));
        assertEquals(0.0, PerfService.parsePoints(null));
        assertEquals(0.0, PerfService.parsePoints("  "));
    }

    @Test
    void 承诺迭代选取_优先最近结束的_无则ACTIVE_都无为null() {
        Iteration done1 = it(1L, "DONE", "2026-06-14");
        Iteration done2 = it(2L, "DONE", "2026-06-28");
        Iteration active = it(3L, "ACTIVE", null);
        Iteration planning = it(4L, "PLANNING", null);

        assertEquals(2L, PerfService.pickCommitIteration(List.of(done1, done2, active, planning)).getId());
        assertEquals(3L, PerfService.pickCommitIteration(List.of(active, planning)).getId());
        assertNull(PerfService.pickCommitIteration(List.of(planning)));
        assertNull(PerfService.pickCommitIteration(List.of()));
    }

    @Test
    void 周分桶_按周一归桶_补零对齐() {
        LocalDate today = LocalDate.of(2026, 8, 1); // 周六，本周一为 7-27
        Map<LocalDate, Long> byDay = new HashMap<>();
        byDay.put(LocalDate.of(2026, 7, 26), 2L); // 周日 → 归 7-20 那周
        byDay.put(LocalDate.of(2026, 7, 27), 3L); // 周一 → 归 7-27 那周
        byDay.put(LocalDate.of(2026, 7, 30), 1L); // 周四 → 归 7-27 那周

        List<PerfService.WeekPoint> weeks = PerfService.bucketWeeks(byDay, today, 8);

        assertEquals(8, weeks.size());
        assertEquals("2026-06-08", weeks.get(0).weekStart());
        assertEquals(0, weeks.get(0).count());
        assertEquals(2, weeks.get(6).count()); // 7-20 周
        assertEquals(4, weeks.get(7).count()); // 7-27 周 = 3+1
    }

    @Test
    void 阶段停留回放_只计完成段_末段不计_空日志不抛错() {
        List<Map<String, Object>> logs = List.of(
                log(1L, "Backlog", "2026-07-01T00:00:00"),
                log(1L, "Ready", "2026-07-03T00:00:00"),      // Backlog 停留 2 天
                log(1L, "In Progress", "2026-07-03T12:00:00") // Ready 停留 0.5 天；In Progress 进行中不计
        );
        List<PerfService.StageStay> stays = PerfService.stageDwell(logs);

        Map<String, PerfService.StageStay> m = new HashMap<>();
        stays.forEach(s -> m.put(s.stage(), s));
        assertEquals(2.0, m.get("Backlog").avgDays());
        assertEquals(1, m.get("Backlog").samples());
        assertEquals(0.5, m.get("Ready").avgDays());
        assertEquals(0, m.get("In Progress").samples());
        assertNull(m.get("In Progress").avgDays());

        // 空日志：四段全部 0 样本，不抛错
        assertEquals(4, PerfService.stageDwell(List.of()).size());
    }

    @Test
    void 达标判定_higher与lower方向_无值或无目标为none() {
        assertEquals("good", PerfService.status("higher", 90.0, 80.0));
        assertEquals("warn", PerfService.status("higher", 70.0, 80.0));
        assertEquals("good", PerfService.status("lower", 5.0, 7.0));
        assertEquals("warn", PerfService.status("lower", 9.0, 7.0));
        assertEquals("none", PerfService.status("higher", null, 80.0));
        assertEquals("none", PerfService.status("higher", 90.0, null));
    }

    @Test
    void 分位数_空列表null_单元素与边界() {
        assertNull(PerfService.percentile(List.of(), 50));
        assertEquals(4.0, PerfService.percentile(List.of(4), 85));
        assertEquals(2.0, PerfService.percentile(List.of(1, 2, 3, 4), 50));
        assertEquals(4.0, PerfService.percentile(List.of(1, 2, 3, 4), 95));
    }

    @Test
    void 风险按期处置率_只计已到期_JSON坏跳过() {
        PerfService svc = svc(mock(PerfMapper.class), mock(MetricsMapper.class), mock(MetricTargetMapper.class),
                mock(IterationMapper.class), mock(WorkItemMapper.class));
        LocalDate today = LocalDate.of(2026, 8, 1);
        WorkItem overdueClosed = risk("{\"dueDate\":\"2026-07-01\"}", "Closed");
        WorkItem overdueOpen = risk("{\"dueDate\":\"2026-07-15\"}", "Open");
        WorkItem future = risk("{\"dueDate\":\"2026-12-31\"}", "Open");     // 未到期不计
        WorkItem badJson = risk("不是JSON", "Open");                        // 坏数据跳过
        WorkItem noDue = risk("{\"mitigation\":\"x\"}", "Open");            // 无期限跳过

        Double rate = svc.riskOnTimeRate(List.of(overdueClosed, overdueOpen, future, badJson, noDue), today);
        assertEquals(50.0, rate); // 到期2条，闭环1条

        assertNull(svc.riskOnTimeRate(List.of(future), today)); // 无到期风险 → null
    }

    @Test
    void 累积流图回放_每天各状态存量_验收累积() {
        LocalDate today = LocalDate.of(2026, 8, 1);
        // item1：7-29 Backlog → 7-30 Ready → 8-01 Accepted；item2：7-31 Backlog
        List<Map<String, Object>> logs = List.of(
                log2(1L, "Backlog", "2026-07-29T10:00:00"),
                log2(1L, "Ready", "2026-07-30T10:00:00"),
                log2(1L, "Accepted", "2026-08-01T10:00:00"),
                log2(2L, "Backlog", "2026-07-31T09:00:00"));
        List<PerfService.CfdPoint> cfd = PerfService.replayCfd(logs, today, 4);

        assertEquals(4, cfd.size());
        assertEquals("2026-07-29", cfd.get(0).date());
        assertEquals(1, cfd.get(0).byStatus().get("Backlog"));       // 仅 item1 在 Backlog
        assertEquals(1, cfd.get(1).byStatus().get("Ready"));          // 7-30 item1 到 Ready
        assertEquals(1, cfd.get(2).byStatus().get("Backlog"));        // 7-31 item2 出现
        assertEquals(1, cfd.get(2).byStatus().get("Ready"));
        assertEquals(1, cfd.get(3).byStatus().get("Accepted"));       // 8-01 item1 验收
        assertEquals(1, cfd.get(3).byStatus().get("Backlog"));
    }

    private Map<String, Object> log2(Long itemId, String toStatus, String at) {
        return log(itemId, toStatus, at);
    }

    // ---------- 目标管理 ----------

    @Test
    void 设定目标_upsert与null清除_非PM拒绝_未知指标拒绝() {
        PerfMapper perfMapper = mock(PerfMapper.class);
        MetricsMapper metricsMapper = mock(MetricsMapper.class);
        MetricTargetMapper targetMapper = mock(MetricTargetMapper.class);
        IterationMapper iterationMapper = mock(IterationMapper.class);
        WorkItemMapper workItemMapper = mock(WorkItemMapper.class);
        PerfService svc = svc(perfMapper, metricsMapper, targetMapper, iterationMapper, workItemMapper);
        stubEmptyMetrics(perfMapper, metricsMapper, iterationMapper, workItemMapper);
        UserContext.set(new UserPrincipal(1L, "pm", List.of("PM")));

        // 新建目标 → insert
        when(targetMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        PerfService.Metric m = svc.setTarget(1L, "flow.wip", 5.0);
        verify(targetMapper).insert(any(MetricTarget.class));
        assertEquals(5.0, m.target());

        // 已有目标 → update
        MetricTarget existing = new MetricTarget();
        existing.setId(9L);
        when(targetMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        svc.setTarget(1L, "flow.wip", 6.0);
        verify(targetMapper).updateById(existing);

        // null → 删除
        svc.setTarget(1L, "flow.wip", null);
        verify(targetMapper).deleteById(9L);

        // 未知指标
        assertThrows(BusinessException.class, () -> svc.setTarget(1L, "no.such", 1.0));

        // 非 PM
        UserContext.set(new UserPrincipal(2L, "dev", List.of("DEV")));
        BusinessException e = assertThrows(BusinessException.class, () -> svc.setTarget(1L, "flow.wip", 1.0));
        assertEquals(4030, e.getCode());
    }

    @Test
    void 注册表_五组齐全_子指标parent有效_无个人维度() {
        assertEquals(5, PerfService.REGISTRY.stream().map(PerfService.MetricDef::group).distinct().count());
        for (PerfService.MetricDef d : PerfService.REGISTRY) {
            if (d.level() == 3) {
                assertTrue(PerfService.def(d.parent()).isPresent(), d.key() + " 的 parent 必须在注册表内");
            }
            assertFalse(d.name().contains("人均") || d.name().contains("工时") || d.name().contains("排名"),
                    "指标不得含个人绩效口径: " + d.name());
        }
    }

    // ---------- helpers ----------

    private PerfService svc(PerfMapper pm, MetricsMapper mm, MetricTargetMapper tm,
                            IterationMapper im, WorkItemMapper wm) {
        PerfSnapshotMapper ps = mock(PerfSnapshotMapper.class);
        when(ps.selectList(any(Wrapper.class))).thenReturn(List.of());
        IterationCommitmentMapper ic = mock(IterationCommitmentMapper.class);
        when(ic.selectList(any(Wrapper.class))).thenReturn(List.of());
        return new PerfService(pm, mm, tm, im, wm, ps, ic, mock(AuditService.class), new ObjectMapper());
    }

    /** 让 metrics()/currentValue() 在空数据下可跑通。 */
    private void stubEmptyMetrics(PerfMapper perfMapper, MetricsMapper metricsMapper,
                                  IterationMapper iterationMapper, WorkItemMapper workItemMapper) {
        when(metricsMapper.projectMetrics(1L)).thenReturn(new HashMap<>());
        when(metricsMapper.cycleDays(1L)).thenReturn(List.of());
        when(metricsMapper.testPassStats(1L)).thenReturn(new HashMap<>());
        when(metricsMapper.requirementCoverage(1L)).thenReturn(new HashMap<>());
        when(perfMapper.leadDays(1L)).thenReturn(List.of());
        when(perfMapper.defectFixDays(1L)).thenReturn(List.of());
        when(perfMapper.changeCycleDays(1L)).thenReturn(List.of());
        when(perfMapper.firstRunStats(1L)).thenReturn(new HashMap<>());
        when(perfMapper.defectReopenStats(1L)).thenReturn(new HashMap<>());
        when(perfMapper.redlineStats(1L)).thenReturn(new HashMap<>());
        when(perfMapper.metEvidenceStats(1L)).thenReturn(new HashMap<>());
        when(perfMapper.lastMoveOfOpenItems(1L)).thenReturn(List.of());
        when(iterationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(iterationMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(workItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    }

    private Iteration it(Long id, String status, String endDate) {
        Iteration i = new Iteration();
        i.setId(id);
        i.setStatus(status);
        if (endDate != null) {
            i.setEndDate(LocalDate.parse(endDate));
        }
        return i;
    }

    private Map<String, Object> log(Long itemId, String toStatus, String at) {
        Map<String, Object> m = new HashMap<>();
        m.put("work_item_id", itemId);
        m.put("to_status", toStatus);
        m.put("at", LocalDateTime.parse(at));
        return m;
    }

    private WorkItem risk(String ext, String status) {
        WorkItem w = new WorkItem();
        w.setType("RISK");
        w.setExtFields(ext);
        w.setStatus(status);
        return w;
    }
}
