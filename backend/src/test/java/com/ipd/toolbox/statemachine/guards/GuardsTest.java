package com.ipd.toolbox.statemachine.guards;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.mapper.TestRunMapper;
import com.ipd.toolbox.mapper.TraceLinkMapper;
import com.ipd.toolbox.statemachine.GuardException;
import com.ipd.toolbox.statemachine.TransitionContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 5 条守卫的规则校验（docs/02 守卫错误码）。 */
class GuardsTest {

    private final TransitionContext ctx = new TransitionContext();

    private WorkItem item(WorkItemType type, String status) {
        WorkItem w = new WorkItem();
        w.setId(100L);
        w.setType(type.name());
        w.setStatus(status);
        return w;
    }

    @Nested
    class 守卫1_Ready承诺 {
        private final ReadyGuard guard = new ReadyGuard();

        private WorkItem readyCandidate() {
            WorkItem w = item(WorkItemType.REQUIREMENT, "Backlog");
            w.setAcceptanceCriteria("能烤熟面包");
            w.setOwnerId(1L);
            w.setEstimate("3d");
            return w;
        }

        @Test
        void 仅作用于通用工作项进Ready() {
            assertTrue(guard.supports(item(WorkItemType.REQUIREMENT, "Backlog"), "Ready"));
            assertTrue(guard.supports(item(WorkItemType.STORY, "Backlog"), "Ready"));
            assertFalse(guard.supports(item(WorkItemType.DEFECT, "Open"), "Ready"));
            assertFalse(guard.supports(item(WorkItemType.REQUIREMENT, "Backlog"), "In Progress"));
        }

        @Test
        void 缺验收条件_责任人_估算均拦截() {
            WorkItem noAc = readyCandidate();
            noAc.setAcceptanceCriteria(" ");
            GuardException e1 = assertThrows(GuardException.class, () -> guard.check(noAc, "Ready", ctx));
            assertEquals("GUARD_READY_UNMET", e1.getGuardCode());

            WorkItem noOwner = readyCandidate();
            noOwner.setOwnerId(null);
            assertThrows(GuardException.class, () -> guard.check(noOwner, "Ready", ctx));

            WorkItem noEstimate = readyCandidate();
            noEstimate.setEstimate(null);
            assertThrows(GuardException.class, () -> guard.check(noEstimate, "Ready", ctx));
        }

        @Test
        void 三要素齐备放行() {
            assertDoesNotThrow(() -> guard.check(readyCandidate(), "Ready", ctx));
        }
    }

    @Nested
    class 守卫2_缺陷关闭须复测通过 {

        @Test
        void 无PASS复测拦截_有则放行() {
            TestRunMapper mapper = mock(TestRunMapper.class);
            DefectCloseGuard guard = new DefectCloseGuard(mapper);
            WorkItem defect = item(WorkItemType.DEFECT, "Retesting");

            assertTrue(guard.supports(defect, "Closed"));
            assertFalse(guard.supports(defect, "Fixing"));
            assertFalse(guard.supports(item(WorkItemType.REQUIREMENT, "Verification"), "Closed"));

            when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
            GuardException e = assertThrows(GuardException.class, () -> guard.check(defect, "Closed", ctx));
            assertEquals("GUARD_DEFECT_NO_RETEST", e.getGuardCode());

            when(mapper.selectCount(any(Wrapper.class))).thenReturn(1L);
            assertDoesNotThrow(() -> guard.check(defect, "Closed", ctx));
        }
    }

    @Nested
    class 守卫3_Verification须有版本或证据 {

        @Test
        void 已关联版本直接放行_否则看证据链() {
            TraceLinkMapper mapper = mock(TraceLinkMapper.class);
            VerificationGuard guard = new VerificationGuard(mapper);

            WorkItem withVersion = item(WorkItemType.REQUIREMENT, "In Progress");
            withVersion.setProductVersionId(9L);
            assertDoesNotThrow(() -> guard.check(withVersion, "Verification", ctx));

            WorkItem bare = item(WorkItemType.REQUIREMENT, "In Progress");
            when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
            GuardException e = assertThrows(GuardException.class, () -> guard.check(bare, "Verification", ctx));
            assertEquals("GUARD_VERIFICATION_NO_EVIDENCE", e.getGuardCode());

            when(mapper.selectCount(any(Wrapper.class))).thenReturn(2L);
            assertDoesNotThrow(() -> guard.check(bare, "Verification", ctx));
        }
    }

    @Nested
    class 守卫4_Accepted须记录验收人 {
        private final AcceptedGuard guard = new AcceptedGuard();

        @Test
        void 缺验收人或时间拦截_齐备放行() {
            WorkItem w = item(WorkItemType.REQUIREMENT, "Verification");
            GuardException e = assertThrows(GuardException.class, () -> guard.check(w, "Accepted", ctx));
            assertEquals("GUARD_ACCEPT_NO_ACCEPTOR", e.getGuardCode());

            w.setAcceptedBy(1L);
            assertThrows(GuardException.class, () -> guard.check(w, "Accepted", ctx));

            w.setAcceptedAt(LocalDateTime.now());
            assertDoesNotThrow(() -> guard.check(w, "Accepted", ctx));
        }
    }

    @Nested
    class 守卫5_变更须先完成影响分析 {
        private final ChangeImpactGuard guard = new ChangeImpactGuard(new ObjectMapper());

        @Test
        void 未分析_解析失败_标记false均拦截() {
            WorkItem chg = item(WorkItemType.CHANGE, "Submitted");
            assertTrue(guard.supports(chg, "Impact Analysed"));
            assertFalse(guard.supports(item(WorkItemType.REQUIREMENT, "Backlog"), "Impact Analysed"));

            GuardException e = assertThrows(GuardException.class, () -> guard.check(chg, "Impact Analysed", ctx));
            assertEquals("GUARD_CHANGE_NO_IMPACT", e.getGuardCode());

            chg.setExtFields("不是JSON");
            assertThrows(GuardException.class, () -> guard.check(chg, "Impact Analysed", ctx));

            chg.setExtFields("{\"impactAnalysed\":false}");
            assertThrows(GuardException.class, () -> guard.check(chg, "Impact Analysed", ctx));
        }

        @Test
        void 已完成影响分析放行() {
            WorkItem chg = item(WorkItemType.CHANGE, "Submitted");
            chg.setExtFields("{\"impactAnalysed\":true,\"impactCount\":3}");
            assertDoesNotThrow(() -> guard.check(chg, "Impact Analysed", ctx));
        }
    }
}
