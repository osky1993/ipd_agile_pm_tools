package com.ipd.toolbox.service;

import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.Improvement;
import com.ipd.toolbox.domain.entity.Project;
import com.ipd.toolbox.mapper.ImprovementMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.security.UserContext;
import com.ipd.toolbox.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 改进项闭环：编号+自动基线、顺序流转、VERIFIED 必填实际值。 */
class ImprovementServiceTest {

    private ImprovementMapper mapper;
    private PerfService perfService;
    private ImprovementService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ImprovementMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        CodeGenerator codeGenerator = mock(CodeGenerator.class);
        perfService = mock(PerfService.class);
        service = new ImprovementService(mapper, projectMapper, codeGenerator,
                mock(AuditService.class), perfService);

        Project p = new Project();
        p.setId(1L);
        p.setCode("ROBO");
        when(projectMapper.selectById(1L)).thenReturn(p);
        when(codeGenerator.next(eq(1L), eq("ROBO"), eq("IMP"))).thenReturn("ROBO-IMP-001");
        UserContext.set(new UserPrincipal(1L, "pm", List.of("PM")));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Improvement fresh(String metricKey) {
        Improvement in = new Improvement();
        in.setProjectId(1L);
        in.setTitle("压缩需求验证等待");
        in.setMetricKey(metricKey);
        return in;
    }

    private Improvement existing(String status) {
        Improvement imp = new Improvement();
        imp.setId(9L);
        imp.setProjectId(1L);
        imp.setCode("ROBO-IMP-001");
        imp.setStatus(status);
        imp.setMetricKey("cycle.leadP85");
        imp.setBaselineValue(BigDecimal.valueOf(16));
        when(mapper.selectById(9L)).thenReturn(imp);
        return imp;
    }

    @Test
    void 创建_生成IMP编号_关联指标自动记基线() {
        when(perfService.currentValue(1L, "cycle.leadP85")).thenReturn(16.0);
        Improvement saved = service.create(fresh("cycle.leadP85"));
        assertEquals("ROBO-IMP-001", saved.getCode());
        assertEquals("OPEN", saved.getStatus());
        assertEquals(0, BigDecimal.valueOf(16.0).compareTo(saved.getBaselineValue()));
        verify(mapper).insert(saved);
    }

    @Test
    void 创建_非法指标拒绝_通用改进可不带指标_标题必填() {
        assertThrows(BusinessException.class, () -> service.create(fresh("no.such.metric")));

        Improvement generic = service.create(fresh(null));
        assertNull(generic.getMetricKey());
        assertNull(generic.getBaselineValue());

        Improvement noTitle = fresh(null);
        noTitle.setTitle(" ");
        assertThrows(BusinessException.class, () -> service.create(noTitle));
    }

    @Test
    void 流转_只允许顺序前进_跳级与回退拒绝() {
        existing("OPEN");
        assertThrows(BusinessException.class, () -> service.transition(9L, "DONE", null, null));
        assertThrows(BusinessException.class, () -> service.transition(9L, "OPEN", null, null));

        Improvement moved = service.transition(9L, "DOING", null, null);
        assertEquals("DOING", moved.getStatus());
    }

    @Test
    void VERIFIED_必填实际值_有指标时自动取数回落() {
        existing("DONE");
        // 未传实际值但有关联指标 → 自动取当前指标值
        when(perfService.currentValue(1L, "cycle.leadP85")).thenReturn(9.5);
        Improvement verified = service.transition(9L, "VERIFIED", null, "P85 16→9.5 达标");
        assertEquals(0, BigDecimal.valueOf(9.5).compareTo(verified.getResultValue()));
        assertEquals("P85 16→9.5 达标", verified.getConclusion());

        // 无指标且未传实际值 → 拒绝
        Improvement generic = existing("DONE");
        generic.setMetricKey(null);
        assertThrows(BusinessException.class, () -> service.transition(9L, "VERIFIED", null, null));
    }

    @Test
    void 编辑_仅OPEN或DOING可改_非PM拒绝() {
        existing("DONE");
        assertThrows(BusinessException.class, () -> service.update(9L, new Improvement()));

        existing("DOING");
        Improvement patch = new Improvement();
        patch.setMeasure("加验证环境自动化");
        assertEquals("加验证环境自动化", service.update(9L, patch).getMeasure());

        UserContext.set(new UserPrincipal(2L, "dev", List.of("DEV")));
        BusinessException e = assertThrows(BusinessException.class, () -> service.update(9L, patch));
        assertEquals(4030, e.getCode());
    }
}
