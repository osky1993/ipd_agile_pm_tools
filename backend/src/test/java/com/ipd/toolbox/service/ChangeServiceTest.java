package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Decision;
import com.ipd.toolbox.domain.entity.TestCase;
import com.ipd.toolbox.domain.entity.TraceLink;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.mapper.TestCaseMapper;
import com.ipd.toolbox.mapper.TraceLinkMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import com.ipd.toolbox.security.UserContext;
import com.ipd.toolbox.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 变更影响分析（两跳追溯，T401）与审批决策（T405）。 */
class ChangeServiceTest {

    private WorkItemMapper workItemMapper;
    private TraceLinkMapper traceLinkMapper;
    private TestCaseMapper testCaseMapper;
    private WorkItemService workItemService;
    private DecisionService decisionService;
    private ChangeService service;

    private static final long CHANGE_ID = 5L;

    @BeforeEach
    void setUp() {
        workItemMapper = mock(WorkItemMapper.class);
        traceLinkMapper = mock(TraceLinkMapper.class);
        testCaseMapper = mock(TestCaseMapper.class);
        workItemService = mock(WorkItemService.class);
        decisionService = mock(DecisionService.class);
        service = new ChangeService(workItemMapper, traceLinkMapper, testCaseMapper,
                workItemService, decisionService, mock(AuditService.class), new ObjectMapper());
        UserContext.set(new UserPrincipal(1L, "reviewer", List.of("REVIEWER")));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private WorkItem change(String status) {
        WorkItem w = new WorkItem();
        w.setId(CHANGE_ID);
        w.setCode("CHG-001");
        w.setProjectId(1L);
        w.setType(WorkItemType.CHANGE.name());
        w.setStatus(status);
        return w;
    }

    private TraceLink link(String st, Long sid, String tt, Long tid, String relation) {
        TraceLink l = new TraceLink();
        l.setSourceType(st);
        l.setSourceId(sid);
        l.setTargetType(tt);
        l.setTargetId(tid);
        l.setRelation(relation);
        return l;
    }

    @Test
    void 影响分析_两跳追溯并去重_置impactAnalysed标记() {
        when(workItemMapper.selectById(CHANGE_ID)).thenReturn(change("Submitted"));

        WorkItem req = new WorkItem();
        req.setId(10L);
        req.setCode("REQ-001");
        req.setTitle("上下火独立控温");
        req.setType(WorkItemType.REQUIREMENT.name());
        when(workItemMapper.selectById(10L)).thenReturn(req);

        TestCase tc = new TestCase();
        tc.setId(20L);
        tc.setCode("TC-001");
        tc.setTitle("控温精度测试");
        when(testCaseMapper.selectById(20L)).thenReturn(tc);

        // selectList 依调用顺序：Hop1 出向 → 受影响需求的 verifies → 需求的 released_in
        when(traceLinkMapper.selectList(any(Wrapper.class))).thenReturn(
                List.of(link("WORK_ITEM", CHANGE_ID, "WORK_ITEM", 10L, "affects")),
                // 同一用例两条 verifies，验证去重
                List.of(link("TEST_CASE", 20L, "WORK_ITEM", 10L, "verifies"),
                        link("TEST_CASE", 20L, "WORK_ITEM", 10L, "verifies")),
                List.of(link("WORK_ITEM", 10L, "PRODUCT_VERSION", 30L, "released_in")));

        ChangeService.ImpactResult result = service.analyze(CHANGE_ID);

        assertEquals(3, result.total());
        Set<String> categories = result.items().stream()
                .map(ChangeService.ImpactItem::category).collect(Collectors.toSet());
        assertEquals(Set.of("需求", "测试", "版本"), categories);
        assertTrue(result.items().stream().anyMatch(i -> "REQ-001".equals(i.code())));
        assertTrue(result.items().stream().anyMatch(i -> "TC-001".equals(i.code())));

        // 完成后置 ext_fields.impactAnalysed=true，守卫#5 才放行
        ArgumentCaptor<WorkItem> patch = ArgumentCaptor.forClass(WorkItem.class);
        verify(workItemService).update(eq(CHANGE_ID), patch.capture());
        assertTrue(patch.getValue().getExtFields().contains("\"impactAnalysed\":true"));
        assertTrue(patch.getValue().getExtFields().contains("\"impactCount\":3"));
    }

    @Test
    void 影响分析_非变更工作项报错() {
        WorkItem req = change("Backlog");
        req.setType(WorkItemType.REQUIREMENT.name());
        when(workItemMapper.selectById(CHANGE_ID)).thenReturn(req);
        assertThrows(BusinessException.class, () -> service.analyze(CHANGE_ID));
    }

    @Test
    void 审批_须先进入ImpactAnalysed状态() {
        when(workItemMapper.selectById(CHANGE_ID)).thenReturn(change("Submitted"));
        assertThrows(BusinessException.class, () -> service.decide(CHANGE_ID, true, "同意"));
        verify(decisionService, never()).record(any());
    }

    @Test
    void 审批通过_生成决策并流转Approved() {
        when(workItemMapper.selectById(CHANGE_ID)).thenReturn(change("Impact Analysed"));
        when(decisionService.record(any())).thenAnswer(inv -> {
            Decision d = inv.getArgument(0);
            d.setCode("DEC-001");
            return d;
        });

        Decision d = service.decide(CHANGE_ID, true, "影响可控");

        assertEquals("CHANGE", d.getDecisionType());
        assertEquals("APPROVED", d.getConclusion());
        assertEquals(CHANGE_ID, d.getSubjectId());
        verify(workItemService).transition(eq(CHANGE_ID), eq("Approved"), contains("DEC-001"));
    }

    @Test
    void 审批否决_流转Rejected() {
        when(workItemMapper.selectById(CHANGE_ID)).thenReturn(change("Impact Analysed"));
        when(decisionService.record(any())).thenAnswer(inv -> inv.getArgument(0));

        Decision d = service.decide(CHANGE_ID, false, "影响过大");

        assertEquals("REJECTED", d.getConclusion());
        verify(workItemService).transition(eq(CHANGE_ID), eq("Rejected"), any());
    }

    @Test
    void 审批需要REVIEWER角色() {
        UserContext.set(new UserPrincipal(2L, "dev", List.of("DEV")));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.decide(CHANGE_ID, true, null));
        assertEquals(4030, e.getCode());
    }
}
