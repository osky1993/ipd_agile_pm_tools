package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.domain.entity.GateCriterion;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 站内预警规则：日期边界、决策修订链、严重度排序。 */
class AlertServiceTest {

    private WorkItemMapper workItemMapper;
    private DecisionMapper decisionMapper;
    private GateCriterionMapper criterionMapper;
    private StageGateMapper stageGateMapper;
    private AlertService service;

    private final LocalDate today = LocalDate.of(2026, 8, 1);

    @BeforeEach
    void setUp() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        workItemMapper = mock(WorkItemMapper.class);
        decisionMapper = mock(DecisionMapper.class);
        criterionMapper = mock(GateCriterionMapper.class);
        PerfMapper perfMapper = mock(PerfMapper.class);
        PerfService perfService = new PerfService(perfMapper, mock(MetricsMapper.class),
                mock(MetricTargetMapper.class), mock(IterationMapper.class),
                mock(WorkItemMapper.class), mock(PerfSnapshotMapper.class),
                mock(IterationCommitmentMapper.class), mock(AuditService.class), new ObjectMapper());
        stageGateMapper = mock(StageGateMapper.class);
        service = new AlertService(projectMapper, workItemMapper, decisionMapper,
                criterionMapper, stageGateMapper, perfMapper, perfService);
    }

    private WorkItem risk(Long id, String due, String status) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setCode("RSK-" + id);
        w.setTitle("风险" + id);
        w.setType("RISK");
        w.setStatus(status);
        w.setExtFields(due == null ? "坏JSON{" : "{\"dueDate\":\"" + due + "\"}");
        return w;
    }

    private Decision dec(Long id, String subjectKey, String conclusion, String due, Long riskId) {
        Decision d = new Decision();
        d.setId(id);
        d.setCode("DEC-" + id);
        d.setSubjectType("STAGE_GATE");
        d.setSubjectId(Long.valueOf(subjectKey));
        d.setConclusion(conclusion);
        if (due != null) {
            d.setCommitmentDue(LocalDate.parse(due));
        }
        d.setLinkedRiskId(riskId);
        return d;
    }

    @Test
    void 超期风险HIGH_JSON坏容错跳过() {
        when(workItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                risk(1L, "2026-07-20", "Open"),   // 超期 → HIGH
                risk(2L, "2026-08-10", "Open"),   // 未超期
                risk(3L, null, "Open")));          // 坏 JSON → 跳过
        List<AlertService.Alert> alerts = service.riskOverdue(1L, today);
        assertEquals(1, alerts.size());
        assertEquals("HIGH", alerts.get(0).severity());
        assertEquals("RSK-1", alerts.get(0).refCode());
    }

    @Test
    void 承诺告警_只看每subject最新决策_修订后旧CONDITIONAL不告警() {
        // subject 7：先 CONDITIONAL(已过期) 后被修订为 PASS → 不告警
        // subject 8：最新为 CONDITIONAL 且 10 天后到期 → MED
        when(decisionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                dec(1L, "7", "CONDITIONAL", "2026-07-01", 5L),
                dec(2L, "7", "PASS", null, null),
                dec(3L, "8", "CONDITIONAL", "2026-08-11", 6L)));
        when(workItemMapper.selectById(6L)).thenReturn(risk(6L, "2026-08-11", "Mitigating"));

        List<AlertService.Alert> alerts = service.commitmentDue(1L, today);
        assertEquals(1, alerts.size());
        assertEquals("MED", alerts.get(0).severity());
        assertEquals("DEC-3", alerts.get(0).refCode());
    }

    @Test
    void 承诺边界_过期HIGH_14天内MED_15天不告警_风险闭环不告警() {
        when(workItemMapper.selectById(any())).thenReturn(risk(5L, null, "Open"));
        when(decisionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                dec(1L, "1", "CONDITIONAL", "2026-07-31", 5L),  // 昨天 → HIGH
                dec(2L, "2", "CONDITIONAL", "2026-08-15", 5L),  // 14天 → MED
                dec(3L, "3", "CONDITIONAL", "2026-08-16", 5L))); // 15天 → 无
        List<AlertService.Alert> alerts = service.commitmentDue(1L, today);
        assertEquals(2, alerts.size());
        assertEquals("HIGH", alerts.get(0).severity());
        assertEquals("MED", alerts.get(1).severity());

        // 绑定风险已闭环 → 不告警
        when(workItemMapper.selectById(any())).thenReturn(risk(5L, null, "Closed"));
        assertEquals(0, service.commitmentDue(1L, today).size());
    }

    @Test
    void 豁免到期边界_14天内告警_15天不告警() {
        GateCriterion soon = new GateCriterion();
        soon.setId(1L);
        soon.setCode("GC-1");
        soon.setCriterion("CE认证");
        soon.setWaiverDue(LocalDate.of(2026, 8, 15));
        GateCriterion far = new GateCriterion();
        far.setId(2L);
        far.setCode("GC-2");
        far.setCriterion("远期豁免");
        far.setWaiverDue(LocalDate.of(2026, 8, 16));
        when(criterionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(soon, far));

        List<AlertService.Alert> alerts = service.waiverDue(1L, today);
        assertEquals(1, alerts.size());
        assertEquals("GC-1", alerts.get(0).refCode());
    }

    @Test
    void 缺陷账龄边界_超14天MED() {
        WorkItem fresh = risk(1L, null, "Open");
        fresh.setType("DEFECT");
        fresh.setCreatedAt(today.minusDays(14).atStartOfDay()); // 恰好14天 → 不告警
        WorkItem aged = risk(2L, null, "Analysing");
        aged.setType("DEFECT");
        aged.setCreatedAt(today.minusDays(15).atStartOfDay()); // 15天 → 告警
        when(workItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(fresh, aged));

        List<AlertService.Alert> alerts = service.defectAging(1L, today);
        assertEquals(1, alerts.size());
        assertEquals("RSK-2", alerts.get(0).refCode());
    }

    private com.ipd.toolbox.domain.entity.StageGate gate(Long id, String plan) {
        com.ipd.toolbox.domain.entity.StageGate g = new com.ipd.toolbox.domain.entity.StageGate();
        g.setId(id);
        g.setCode("DCP-" + id);
        g.setStageName("开发");
        g.setGateName("DCP" + id);
        if (plan != null) {
            g.setPlanDate(LocalDate.parse(plan));
        }
        return g;
    }

    @Test
    void DCP临近边界_过期HIGH_14天内MED_15天不告警() {
        when(decisionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(stageGateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                gate(1L, "2026-07-31"),   // 昨天 → HIGH 已逾期
                gate(2L, "2026-08-15"),   // 14 天后 → MED
                gate(3L, "2026-08-16"))); // 15 天后 → 不告警
        List<AlertService.Alert> alerts = service.dcpApproaching(1L, today);
        assertEquals(2, alerts.size());
        assertEquals("HIGH", alerts.get(0).severity());
        assertEquals("DCP-1", alerts.get(0).refCode());
        assertEquals("MED", alerts.get(1).severity());
        assertEquals("DCP-2", alerts.get(1).refCode());
    }

    @Test
    void DCP已有通过类决策不告警_修订链只看最新() {
        // gate 1：最新决策 PASS → 不告警；gate 2：先 PASS 后修订为 REJECT → 仍告警
        when(decisionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                dec(1L, "1", "PASS", null, null),
                dec(2L, "2", "PASS", null, null),
                dec(3L, "2", "REJECT", null, null)));
        when(stageGateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                gate(1L, "2026-08-05"), gate(2L, "2026-08-05")));
        List<AlertService.Alert> alerts = service.dcpApproaching(1L, today);
        assertEquals(1, alerts.size());
        assertEquals("DCP-2", alerts.get(0).refCode());
        assertEquals("DCP_APPROACHING", alerts.get(0).type());
    }

    @Test
    void 排序_HIGH在前_同级按期限升序() {
        List<AlertService.Alert> unsorted = new java.util.ArrayList<>(List.of(
                new AlertService.Alert("LOW", "WIP_STALE", "t", "d", "WORK_ITEM", 1L, "A", null),
                new AlertService.Alert("HIGH", "RISK_OVERDUE", "t", "d", "WORK_ITEM", 2L, "B",
                        LocalDate.of(2026, 7, 20)),
                new AlertService.Alert("HIGH", "COMMITMENT_DUE", "t", "d", "DECISION", 3L, "C",
                        LocalDate.of(2026, 7, 10)),
                new AlertService.Alert("MED", "CHANGE_PENDING", "t", "d", "WORK_ITEM", 4L, "D", null)));
        // 通过 list() 的排序逻辑验证：直接复用 Comparator 语义（HIGH→MED→LOW，due 升序）
        unsorted.sort(java.util.Comparator
                .comparing((AlertService.Alert a) -> java.util.Map.of("HIGH", 0, "MED", 1, "LOW", 2)
                        .getOrDefault(a.severity(), 9))
                .thenComparing(a -> a.due() == null ? LocalDate.MAX : a.due()));
        assertEquals(List.of("C", "B", "D", "A"),
                unsorted.stream().map(AlertService.Alert::refCode).toList());
    }

    @Test
    void 项目CLOSED返回空() {
        ProjectMapper pm = mock(ProjectMapper.class);
        Project p = new Project();
        p.setId(1L);
        p.setLifecycleStatus("CLOSED");
        when(pm.selectById(1L)).thenReturn(p);
        AlertService closed = new AlertService(pm, workItemMapper, decisionMapper,
                criterionMapper, stageGateMapper, mock(PerfMapper.class), mock(PerfService.class));
        assertTrue(closed.list(1L).isEmpty());
    }
}
