package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.StageGate;
import com.ipd.toolbox.mapper.DecisionMapper;
import com.ipd.toolbox.mapper.GateCriterionMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.StageGateMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 结项检查：计数口径与"未评审 gate 只看最新决策"。 */
class ClosureServiceTest {

    private WorkItemMapper workItemMapper;
    private StageGateMapper stageGateMapper;
    private DecisionMapper decisionMapper;
    private GateCriterionMapper criterionMapper;
    private ClosureService service;

    @BeforeEach
    void setUp() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        workItemMapper = mock(WorkItemMapper.class);
        stageGateMapper = mock(StageGateMapper.class);
        decisionMapper = mock(DecisionMapper.class);
        criterionMapper = mock(GateCriterionMapper.class);
        when(projectMapper.selectById(1L)).thenReturn(new Project());
        service = new ClosureService(projectMapper, workItemMapper, stageGateMapper,
                decisionMapper, criterionMapper);
    }

    private Decision dec(Long id, Long gateId, String conclusion) {
        Decision d = new Decision();
        d.setId(id);
        d.setSubjectType("STAGE_GATE");
        d.setSubjectId(gateId);
        d.setConclusion(conclusion);
        return d;
    }

    private StageGate gate(Long id) {
        StageGate g = new StageGate();
        g.setId(id);
        return g;
    }

    @Test
    void 未评审gate_只看每gate最新决策_修订为REJECT重新计为未评审() {
        // gate1 最新 PASS；gate2 先 PASS 后修订 REJECT → 未评审；gate3 无决策 → 未评审
        when(workItemMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(criterionMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(decisionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                dec(1L, 1L, "PASS"), dec(2L, 2L, "PASS"), dec(3L, 2L, "REJECT")));
        when(stageGateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                gate(1L), gate(2L), gate(3L)));

        ClosureService.CloseoutCheck c = service.check(1L);
        assertEquals(2, c.unreviewedGates());
        assertTrue(c.openRisks() == 0 && c.openDefects() == 0);
        assertFalse(c.clean());
    }

    @Test
    void 全部清零则clean() {
        when(workItemMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(criterionMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(decisionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(dec(1L, 1L, "PASS")));
        when(stageGateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(gate(1L)));
        assertTrue(service.check(1L).clean());
    }
}
