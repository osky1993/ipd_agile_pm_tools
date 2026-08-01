package com.ipd.toolbox.statemachine;

import com.ipd.toolbox.domain.enums.WorkItemType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 状态机迁移表校验（docs/02）：初始状态、合法/非法迁移、回退判定。 */
class StateMachineTest {

    @Test
    void 各类型初始状态() {
        assertEquals("Backlog", StateMachine.initialStatus(WorkItemType.REQUIREMENT));
        assertEquals("Backlog", StateMachine.initialStatus(WorkItemType.CAPABILITY));
        assertEquals("Backlog", StateMachine.initialStatus(WorkItemType.STORY));
        assertEquals("Backlog", StateMachine.initialStatus(WorkItemType.TASK));
        assertEquals("Open", StateMachine.initialStatus(WorkItemType.DEFECT));
        assertEquals("Open", StateMachine.initialStatus(WorkItemType.RISK));
        assertEquals("Submitted", StateMachine.initialStatus(WorkItemType.CHANGE));
    }

    @Test
    void 通用工作项_相邻前进与回退合法_跳级非法() {
        WorkItemType t = WorkItemType.REQUIREMENT;
        assertTrue(StateMachine.canTransition(t, "Backlog", "Ready"));
        assertTrue(StateMachine.canTransition(t, "Ready", "In Progress"));
        assertTrue(StateMachine.canTransition(t, "In Progress", "Verification"));
        assertTrue(StateMachine.canTransition(t, "Verification", "Accepted"));
        // 相邻回退
        assertTrue(StateMachine.canTransition(t, "Ready", "Backlog"));
        assertTrue(StateMachine.canTransition(t, "Accepted", "Verification"));
        // 跳级
        assertFalse(StateMachine.canTransition(t, "Backlog", "In Progress"));
        assertFalse(StateMachine.canTransition(t, "Backlog", "Accepted"));
        assertFalse(StateMachine.canTransition(t, "Accepted", "Backlog"));
    }

    @Test
    void 缺陷_复测不过可打回() {
        WorkItemType t = WorkItemType.DEFECT;
        assertTrue(StateMachine.canTransition(t, "Open", "Analysing"));
        assertTrue(StateMachine.canTransition(t, "Retesting", "Closed"));
        // 复测不过：Retesting 打回 Fixing
        assertTrue(StateMachine.canTransition(t, "Retesting", "Fixing"));
        assertFalse(StateMachine.canTransition(t, "Open", "Closed"));
    }

    @Test
    void 风险_可直接接受_关闭后可重新打开() {
        WorkItemType t = WorkItemType.RISK;
        assertTrue(StateMachine.canTransition(t, "Open", "Accepted"));
        assertTrue(StateMachine.canTransition(t, "Mitigating", "Accepted"));
        assertTrue(StateMachine.canTransition(t, "Mitigating", "Closed"));
        assertFalse(StateMachine.canTransition(t, "Open", "Closed"));
        // 重新打开
        assertTrue(StateMachine.canTransition(t, "Closed", "Mitigating"));
        assertTrue(StateMachine.canTransition(t, "Accepted", "Mitigating"));
    }

    @Test
    void 变更_审批分叉与实施验证() {
        WorkItemType t = WorkItemType.CHANGE;
        assertTrue(StateMachine.canTransition(t, "Submitted", "Impact Analysed"));
        assertTrue(StateMachine.canTransition(t, "Impact Analysed", "Approved"));
        assertTrue(StateMachine.canTransition(t, "Impact Analysed", "Rejected"));
        assertTrue(StateMachine.canTransition(t, "Impact Analysed", "Submitted"));
        assertTrue(StateMachine.canTransition(t, "Approved", "Implemented"));
        assertTrue(StateMachine.canTransition(t, "Implemented", "Verified"));
        assertTrue(StateMachine.canTransition(t, "Rejected", "Impact Analysed"));
        // 未经影响分析/审批不能跳
        assertFalse(StateMachine.canTransition(t, "Submitted", "Approved"));
        assertFalse(StateMachine.canTransition(t, "Approved", "Verified"));
        assertFalse(StateMachine.canTransition(t, "Rejected", "Approved"));
    }

    @Test
    void 回退判定_用于强制填写回退理由() {
        assertTrue(StateMachine.isBackward(WorkItemType.REQUIREMENT, "Ready", "Backlog"));
        assertFalse(StateMachine.isBackward(WorkItemType.REQUIREMENT, "Backlog", "Ready"));
        assertTrue(StateMachine.isBackward(WorkItemType.DEFECT, "Retesting", "Fixing"));
        assertTrue(StateMachine.isBackward(WorkItemType.CHANGE, "Rejected", "Impact Analysed"));
        assertFalse(StateMachine.isBackward(WorkItemType.CHANGE, "Approved", "Implemented"));
        // 无法定位的状态不算回退
        assertFalse(StateMachine.isBackward(WorkItemType.REQUIREMENT, "Ready", "不存在"));
    }

    @Test
    void nextStatuses_未知状态返回空集() {
        assertEquals(Set.of(), StateMachine.nextStatuses(WorkItemType.REQUIREMENT, "不存在"));
        assertEquals(Set.of("Ready"), StateMachine.nextStatuses(WorkItemType.REQUIREMENT, "Backlog"));
    }
}
