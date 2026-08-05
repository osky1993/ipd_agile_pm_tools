package com.ipd.toolbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.TraceLinkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 风险深化：ext 容错解析、敞口分级边界、任务化守卫与防重。 */
class RiskServiceTest {

    private WorkItemService workItemService;
    private TraceLinkService traceLinkService;
    private TraceLinkMapper traceLinkMapper;
    private RiskService service;

    @BeforeEach
    void setUp() {
        workItemService = mock(WorkItemService.class);
        traceLinkService = mock(TraceLinkService.class);
        traceLinkMapper = mock(TraceLinkMapper.class);
        service = new RiskService(workItemService, traceLinkService, traceLinkMapper,
                mock(AuditService.class), new ObjectMapper());
    }

    @Test
    void parseExt_容错_坏JSON_缺键_字符串数字() {
        ObjectMapper om = new ObjectMapper();
        assertNull(RiskService.parseExt("{{{", om).probability());
        assertNull(RiskService.parseExt(null, om).mitigation());
        RiskService.RiskExt e = RiskService.parseExt(
                "{\"probability\":\"4\",\"impact\":5,\"strategy\":\"MITIGATE\",\"dueDate\":\"2026-09-30\",\"wsjf\":{}}", om);
        assertEquals(4, e.probability()); // 字符串数字可解析
        assertEquals(5, e.impact());
        assertEquals("MITIGATE", e.strategy());
        assertEquals(LocalDate.of(2026, 9, 30), e.dueDate());
        assertNull(RiskService.parseExt("{\"probability\":\"abc\"}", om).probability());
    }

    @Test
    void exposure_分级边界_15高_8中_7低_未评估null() {
        assertEquals("HIGH", RiskService.exposureLevel(15));
        assertEquals("MED", RiskService.exposureLevel(8));
        assertEquals("LOW", RiskService.exposureLevel(7));
        assertNull(RiskService.exposureLevel(null));
        assertNull(RiskService.exposure(new RiskService.RiskExt(null, null, 3, null, null)));
        assertEquals(12, RiskService.exposure(new RiskService.RiskExt(null, null, 3, 4, null)));
    }

    private WorkItem risk(String type, String ext) {
        WorkItem w = new WorkItem();
        w.setId(1L);
        w.setProjectId(1L);
        w.setCode("R-1");
        w.setTitle("断供风险");
        w.setType(type);
        w.setExtFields(ext);
        return w;
    }

    @Test
    void 任务化_非风险与无措施被拒() {
        when(workItemService.get(1L)).thenReturn(risk("TASK", null));
        assertThrows(BusinessException.class, () -> service.createMitigationTask(1L));

        when(workItemService.get(1L)).thenReturn(risk("RISK", "{}"));
        assertThrows(BusinessException.class, () -> service.createMitigationTask(1L));
    }

    @Test
    void 任务化_生成TASK并建affects链_已有链防重() {
        WorkItem r = risk("RISK", "{\"mitigation\":\"引入二供\",\"dueDate\":\"2026-09-30\"}");
        r.setOwnerId(7L);
        when(workItemService.get(1L)).thenReturn(r);
        when(traceLinkMapper.selectCount(any())).thenReturn(0L);
        WorkItem created = new WorkItem();
        created.setId(99L);
        created.setCode("T-99");
        created.setProjectId(1L);
        when(workItemService.create(any(), isNull())).thenReturn(created);

        WorkItem task = service.createMitigationTask(1L);
        assertEquals("T-99", task.getCode());
        verify(workItemService).create(argThat(w ->
                "TASK".equals(w.getType()) && w.getTitle().startsWith("应对：")
                        && "引入二供".equals(w.getDescription()) && Long.valueOf(7L).equals(w.getOwnerId())
                        && LocalDate.of(2026, 9, 30).equals(w.getForecastDate())), isNull());
        verify(traceLinkService).create(argThat(l ->
                "affects".equals(l.getRelation()) && l.getSourceId() == 99L && l.getTargetId() == 1L));

        // 已有链 → 防重
        when(traceLinkMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BusinessException.class, () -> service.createMitigationTask(1L));
    }
}
