package com.ipd.toolbox.service;

import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.TestCase;
import com.ipd.toolbox.domain.entity.TestRun;
import com.ipd.toolbox.mapper.*;
import com.ipd.toolbox.security.UserContext;
import com.ipd.toolbox.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 测试用例状态管理：默认/校验/流转/删除 + 仅启用可执行。 */
class TestCaseStatusTest {

    private TestCaseMapper mapper;
    private TestCaseService service;

    @BeforeEach
    void setUp() {
        mapper = mock(TestCaseMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        Project p = new Project();
        p.setId(1L);
        p.setCode("OVN1");
        when(projectMapper.selectById(1L)).thenReturn(p);
        CodeGenerator cg = mock(CodeGenerator.class);
        when(cg.next(anyLong(), anyString(), anyString())).thenReturn("OVN1-TC-001");
        service = new TestCaseService(mapper, projectMapper, cg, mock(AuditService.class),
                mock(TraceLinkService.class), mock(TraceLinkMapper.class), mock(WorkItemMapper.class));
        UserContext.set(new UserPrincipal(1L, "qa", List.of("QA")));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private TestCase existing(String status) {
        TestCase tc = new TestCase();
        tc.setId(9L);
        tc.setProjectId(1L);
        tc.setCode("OVN1-TC-009");
        tc.setTitle("旧标题");
        tc.setStatus(status);
        when(mapper.selectById(9L)).thenReturn(tc);
        return tc;
    }

    @Test
    void 创建_默认ACTIVE_显式DRAFT保留_非法状态拒绝() {
        TestCase tc = new TestCase();
        tc.setProjectId(1L);
        tc.setTitle("A");
        assertEquals("ACTIVE", service.create(tc, null).getStatus());

        TestCase draft = new TestCase();
        draft.setProjectId(1L);
        draft.setTitle("B");
        draft.setStatus("DRAFT");
        assertEquals("DRAFT", service.create(draft, null).getStatus());

        TestCase bad = new TestCase();
        bad.setProjectId(1L);
        bad.setTitle("C");
        bad.setStatus("WHATEVER");
        assertThrows(BusinessException.class, () -> service.create(bad, null));
    }

    @Test
    void 状态流转_合法值更新_非法拒绝_非QA或PM拒绝() {
        existing("DRAFT");
        assertEquals("ACTIVE", service.changeStatus(9L, "ACTIVE").getStatus());
        assertThrows(BusinessException.class, () -> service.changeStatus(9L, "DELETED"));

        UserContext.set(new UserPrincipal(2L, "dev", List.of("DEV")));
        BusinessException e = assertThrows(BusinessException.class, () -> service.changeStatus(9L, "ACTIVE"));
        assertEquals(4030, e.getCode());
    }

    @Test
    void 编辑_空标题拒绝_正常更新() {
        existing("ACTIVE");
        TestCase patch = new TestCase();
        patch.setTitle(" ");
        assertThrows(BusinessException.class, () -> service.update(9L, patch));

        TestCase ok = new TestCase();
        ok.setTitle("新标题");
        ok.setSteps("新步骤");
        TestCase saved = service.update(9L, ok);
        assertEquals("新标题", saved.getTitle());
        assertEquals("新步骤", saved.getSteps());
    }

    @Test
    void 删除_走逻辑删() {
        existing("ACTIVE");
        service.delete(9L);
        verify(mapper).deleteById(9L);
    }

    @Test
    void 执行_非ACTIVE用例被拒() {
        TestCaseMapper tcm = mock(TestCaseMapper.class);
        TestCase draft = new TestCase();
        draft.setId(5L);
        draft.setCode("OVN1-TC-005");
        draft.setStatus("DRAFT");
        when(tcm.selectById(5L)).thenReturn(draft);
        TestRunService runSvc = new TestRunService(mock(TestRunMapper.class), tcm,
                mock(TraceLinkMapper.class), mock(ProjectMapper.class), mock(CodeGenerator.class),
                mock(AuditService.class), mock(WorkItemService.class), mock(TraceLinkService.class));
        TestRun run = new TestRun();
        run.setTestCaseId(5L);
        BusinessException e = assertThrows(BusinessException.class, () -> runSvc.execute(run, true));
        assertTrue(e.getMessage().contains("草稿"));
    }
}
