package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.mapper.DecisionMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.security.UserContext;
import com.ipd.toolbox.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 决策修订链：record 自动串 prev（调用方显式给时尊重）。 */
class DecisionServiceTest {

    private DecisionMapper mapper;
    private DecisionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DecisionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        CodeGenerator codeGenerator = mock(CodeGenerator.class);
        Project p = new Project();
        p.setId(1L);
        p.setCode("OVN1");
        when(projectMapper.selectById(1L)).thenReturn(p);
        when(codeGenerator.next(any(), any(), any())).thenReturn("OVN1-DEC-002");
        service = new DecisionService(mapper, projectMapper, codeGenerator, mock(AuditService.class));
        UserContext.set(new UserPrincipal(1L, "admin", List.of("REVIEWER")));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Decision base(String subjectType, Long subjectId) {
        Decision d = new Decision();
        d.setProjectId(1L);
        d.setDecisionType("DCP");
        d.setSubjectType(subjectType);
        d.setSubjectId(subjectId);
        d.setConclusion("PASS");
        return d;
    }

    @Test
    void 二次评审自动串到最新决策() {
        Decision first = new Decision();
        first.setId(10L);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(first);

        Decision saved = service.record(base("STAGE_GATE", 7L));
        assertEquals(10L, saved.getPrevDecisionId());
    }

    @Test
    void 首次评审无prev_显式给prev时尊重() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertNull(service.record(base("STAGE_GATE", 7L)).getPrevDecisionId());

        Decision explicit = base("STAGE_GATE", 7L);
        explicit.setPrevDecisionId(5L);
        assertEquals(5L, service.record(explicit).getPrevDecisionId());
        // 显式给时不再查询最新
        verify(mapper, times(1)).selectOne(any(Wrapper.class));
    }
}
