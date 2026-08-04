package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ipd.toolbox.domain.entity.Iteration;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.mapper.IterationMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 「我的一天」聚合：分组归类、超期口径（风险 ext / 其他 forecastDate）、预警过滤。 */
class MyServiceTest {

    private ProjectMapper projectMapper;
    private WorkItemMapper workItemMapper;
    private IterationMapper iterationMapper;
    private AlertService alertService;
    private PerfService perfService;
    private MyService service;

    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        projectMapper = mock(ProjectMapper.class);
        workItemMapper = mock(WorkItemMapper.class);
        iterationMapper = mock(IterationMapper.class);
        alertService = mock(AlertService.class);
        perfService = mock(PerfService.class);
        service = new MyService(projectMapper, workItemMapper, iterationMapper, alertService, perfService);

        Project p = new Project();
        p.setId(1L);
        p.setCode("OVN1");
        when(projectMapper.selectList(any(Wrapper.class))).thenReturn(List.of(p));
    }

    private WorkItem wi(Long id, String type, String status, LocalDate forecast) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setCode("OVN1-X-" + id);
        w.setProjectId(1L);
        w.setType(type);
        w.setStatus(status);
        w.setTitle("t" + id);
        w.setForecastDate(forecast);
        return w;
    }

    @Test
    void 分组归类_进行中_复测_超期口径() {
        WorkItem doing = wi(1L, "TASK", "In Progress", null);
        WorkItem retest = wi(2L, "DEFECT", "Retesting", null);
        WorkItem riskOverdue = wi(3L, "RISK", "Open", null);
        WorkItem reqOverdue = wi(4L, "REQUIREMENT", "Backlog", today.minusDays(3));
        when(workItemMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(doing, retest, riskOverdue, reqOverdue));
        when(perfService.riskDueDate(riskOverdue)).thenReturn(today.minusDays(1));
        when(iterationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(alertService.list(1L)).thenReturn(List.of());

        MyService.Today t = service.today(9L);
        assertEquals(1, t.inProgress().size());
        assertEquals("OVN1-X-1", t.inProgress().get(0).code());
        assertEquals(1, t.retest().size());
        assertEquals(2, t.overdue().size());
        // 超期按期限升序：风险(昨天) 在 需求(3天前) 之后
        assertEquals("OVN1-X-4", t.overdue().get(0).code());
        assertEquals("OVN1", t.overdue().get(0).projectCode());
    }

    @Test
    void 迭代临近_7天内收录_超7天不收录_预警只留HIGH与关键类型() {
        when(workItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(workItemMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        Iteration soon = new Iteration();
        soon.setId(1L);
        soon.setCode("IT-1");
        soon.setName("冲刺A");
        soon.setProjectId(1L);
        soon.setEndDate(today.plusDays(3));
        Iteration far = new Iteration();
        far.setId(2L);
        far.setCode("IT-2");
        far.setName("冲刺B");
        far.setProjectId(1L);
        far.setEndDate(today.plusDays(8));
        when(iterationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(soon, far));
        when(alertService.list(1L)).thenReturn(List.of(
                new AlertService.Alert("HIGH", "RISK_OVERDUE", "a", "d", "WORK_ITEM", 1L, "C1", null),
                new AlertService.Alert("MED", "DCP_APPROACHING", "b", "d", "STAGE_GATE", 2L, "C2", null),
                new AlertService.Alert("LOW", "WIP_STALE", "c", "d", "WORK_ITEM", 3L, "C3", null)));

        MyService.Today t = service.today(9L);
        assertEquals(1, t.endingSoon().size());
        assertEquals("IT-1", t.endingSoon().get(0).code());
        assertEquals(2L, t.endingSoon().get(0).myOpenCount());
        assertEquals(2, t.projectAlerts().size());
        assertTrue(t.projectAlerts().stream().noneMatch(a -> "WIP_STALE".equals(a.type())));
    }
}
