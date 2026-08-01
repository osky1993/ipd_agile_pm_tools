package com.ipd.toolbox.service;

import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.GateCriterion;
import com.ipd.toolbox.mapper.GateCriterionMapper;
import com.ipd.toolbox.mapper.ProjectMapper;
import com.ipd.toolbox.security.UserContext;
import com.ipd.toolbox.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 准入条件豁免规则（规划§7.3）：豁免必须有授权人、理由、期限。 */
class GateCriterionServiceTest {

    private GateCriterionMapper mapper;
    private GateCriterionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(GateCriterionMapper.class);
        service = new GateCriterionService(mapper, mock(ProjectMapper.class),
                mock(CodeGenerator.class), mock(AuditService.class));
        UserContext.set(new UserPrincipal(3L, "pm", List.of("PM")));

        GateCriterion old = new GateCriterion();
        old.setId(1L);
        old.setCode("GC-001");
        old.setProjectId(1L);
        old.setStatus("NOT_READY");
        when(mapper.selectById(1L)).thenReturn(old);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void 豁免缺理由或期限拦截() {
        GateCriterion patch = new GateCriterion();
        patch.setStatus("WAIVED");
        assertThrows(BusinessException.class, () -> service.update(1L, patch));

        patch.setWaiverReason("首件试产不涉及该项");
        assertThrows(BusinessException.class, () -> service.update(1L, patch));
    }

    @Test
    void 豁免齐备_自动记录授权人为当前用户() {
        GateCriterion patch = new GateCriterion();
        patch.setStatus("WAIVED");
        patch.setWaiverReason("首件试产不涉及该项");
        patch.setWaiverDue(LocalDate.of(2026, 10, 1));

        GateCriterion saved = service.update(1L, patch);

        assertEquals("WAIVED", saved.getStatus());
        assertEquals(3L, saved.getWaiverBy());
        assertEquals(LocalDate.of(2026, 10, 1), saved.getWaiverDue());
    }

    @Test
    void 非PM或QUALITY角色不能改条件() {
        UserContext.set(new UserPrincipal(9L, "dev", List.of("DEV")));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.update(1L, new GateCriterion()));
        assertEquals(4030, e.getCode());
    }
}
