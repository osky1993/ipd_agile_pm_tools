package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.domain.entity.GateCriterion;
import com.ipd.toolbox.domain.entity.StageGate;
import com.ipd.toolbox.mapper.GateCriterionMapper;
import com.ipd.toolbox.mapper.StageGateMapper;
import com.ipd.toolbox.mapper.TraceLinkMapper;
import com.ipd.toolbox.security.UserContext;
import com.ipd.toolbox.security.UserPrincipal;
import com.ipd.toolbox.statemachine.GuardException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * DCP 规则引擎（T502/T505，规划§7.3/§7.4）：
 * 准备度快照（红线未满足/证据缺失/无责任人/待评审）与评审决策规则。
 */
class DcpServiceTest {

    private GateCriterionMapper criterionMapper;
    private StageGateMapper stageGateMapper;
    private TraceLinkMapper traceLinkMapper;
    private DecisionService decisionService;
    private ReadinessService readinessService;
    private DcpService service;

    private static final long GATE_ID = 7L;

    @BeforeEach
    void setUp() {
        criterionMapper = mock(GateCriterionMapper.class);
        stageGateMapper = mock(StageGateMapper.class);
        traceLinkMapper = mock(TraceLinkMapper.class);
        decisionService = mock(DecisionService.class);
        readinessService = mock(ReadinessService.class);
        service = new DcpService(criterionMapper, stageGateMapper, traceLinkMapper,
                decisionService, readinessService, new ObjectMapper());

        StageGate gate = new StageGate();
        gate.setId(GATE_ID);
        gate.setProjectId(1L);
        when(stageGateMapper.selectById(GATE_ID)).thenReturn(gate);
        when(readinessService.readinessRedlineUnmet(1L)).thenReturn(List.of());
        asReviewer();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void asReviewer() {
        UserContext.set(new UserPrincipal(1L, "reviewer", List.of("REVIEWER")));
    }

    private GateCriterion gc(long id, String code, String domain, String status,
                             int redline, Long ownerId, String reviewConclusion) {
        GateCriterion c = new GateCriterion();
        c.setId(id);
        c.setCode(code);
        c.setDomain(domain);
        c.setCriterion("条件 " + code);
        c.setStatus(status);
        c.setIsRedline(redline);
        c.setOwnerId(ownerId);
        c.setReviewConclusion(reviewConclusion);
        return c;
    }

    @Test
    void 准备度快照_分领域统计与三清单() {
        when(criterionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                gc(1, "GC-001", "研发", "MET", 1, 1L, "GO"),        // 证据1份：正常
                gc(2, "GC-002", "研发", "MET", 0, 1L, null),          // MET 但无证据 → 证据缺失+待评审
                gc(3, "GC-003", "质量", "NOT_READY", 1, null, null),  // 红线未满足+无责任人+待评审
                gc(4, "GC-004", "质量", "WAIVED", 1, 2L, "GO"),      // 红线但已豁免 → 不算未满足
                gc(5, "GC-005", "质量", "PARTIAL", 1, 2L, "GO")));    // 红线部分满足 → 仍算未满足
        // 证据数按 criteria 顺序逐条返回
        when(traceLinkMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 0L, 0L, 0L, 0L);
        when(readinessService.readinessRedlineUnmet(1L)).thenReturn(List.of("制造成熟度"));

        DcpService.Snapshot snap = service.overview(GATE_ID).snapshot();

        DcpService.DomainStat rd = snap.byDomain().get("研发");
        assertEquals(2, rd.total());
        assertEquals(2, rd.met());
        DcpService.DomainStat qa = snap.byDomain().get("质量");
        assertEquals(3, qa.total());
        assertEquals(1, qa.partial());
        assertEquals(1, qa.notReady());
        assertEquals(1, qa.waived());

        // 红线：NOT_READY + PARTIAL 上榜，WAIVED 不上榜；准备度红线并入
        assertEquals(List.of("GC-003", "GC-005", "制造成熟度(准备度)"), snap.redlineUnmet());
        assertEquals(List.of("GC-002"), snap.evidenceMissing());
        assertEquals(List.of("GC-003"), snap.ownerMissing());
        assertEquals(2, snap.pending());
    }

    @Test
    void 红线未满足时不能判PASS() {
        when(criterionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(gc(3, "GC-003", "质量", "NOT_READY", 1, 1L, null)));
        when(traceLinkMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        GuardException e = assertThrows(GuardException.class,
                () -> service.review(GATE_ID, "PASS", "看起来不错", null, null));
        assertEquals("GUARD_REDLINE_UNMET", e.getGuardCode());
        verify(decisionService, never()).record(any());
    }

    @Test
    void 有条件通过必须绑定风险与期限() {
        when(criterionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThrows(BusinessException.class,
                () -> service.review(GATE_ID, "CONDITIONAL", "遗留问题", null, LocalDate.now()));
        assertThrows(BusinessException.class,
                () -> service.review(GATE_ID, "CONDITIONAL", "遗留问题", 6L, null));

        Decision ok = service.review(GATE_ID, "CONDITIONAL", "遗留问题", 6L, LocalDate.of(2026, 9, 30));
        assertNull(ok); // record 为 mock，返回 null 即可——重点是绑定校验通过后走到落库
        ArgumentCaptor<Decision> captor = ArgumentCaptor.forClass(Decision.class);
        verify(decisionService).record(captor.capture());
        assertEquals(6L, captor.getValue().getLinkedRiskId());
        assertEquals(LocalDate.of(2026, 9, 30), captor.getValue().getCommitmentDue());
    }

    @Test
    void 干净门禁PASS_决策固化快照() {
        when(criterionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(gc(1, "GC-001", "研发", "MET", 1, 1L, "GO")));
        when(traceLinkMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(decisionService.record(any())).thenAnswer(inv -> inv.getArgument(0));

        Decision d = service.review(GATE_ID, "PASS", "达标", null, null);

        assertEquals("DCP", d.getDecisionType());
        assertEquals("STAGE_GATE", d.getSubjectType());
        assertEquals(GATE_ID, d.getSubjectId());
        assertEquals("PASS", d.getConclusion());
        assertNotNull(d.getSnapshot());
        assertTrue(d.getSnapshot().contains("byDomain"), "快照应为可复现的 JSON");
    }

    @Test
    void 无效结论与非授权角色拦截() {
        assertThrows(BusinessException.class,
                () -> service.review(GATE_ID, "MAYBE", null, null, null));

        UserContext.set(new UserPrincipal(2L, "dev", List.of("DEV")));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.review(GATE_ID, "PASS", null, null, null));
        assertEquals(4030, e.getCode());
        verify(decisionService, never()).record(any());
    }

    @Test
    void 门禁不存在报错() {
        when(stageGateMapper.selectById(anyLong())).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.review(99L, "PASS", null, null, null));
    }
}
