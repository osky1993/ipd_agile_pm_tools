package com.ipd.toolbox.statemachine.guards;

import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.statemachine.GuardException;
import com.ipd.toolbox.statemachine.TransitionContext;
import com.ipd.toolbox.statemachine.TransitionGuard;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 守卫#1 Ready：通用工作项进入 Ready 必须具备验收条件、责任人、估算，
 * 否则不允许承诺进 Sprint（开发计划 T303）。
 */
@Component
public class ReadyGuard implements TransitionGuard {

    private static final Set<String> GENERIC = Set.of(
            WorkItemType.CAPABILITY.name(), WorkItemType.REQUIREMENT.name(),
            WorkItemType.STORY.name(), WorkItemType.TASK.name());

    @Override
    public boolean supports(WorkItem item, String toStatus) {
        return "Ready".equals(toStatus) && GENERIC.contains(item.getType());
    }

    @Override
    public void check(WorkItem item, String toStatus, TransitionContext ctx) {
        if (isBlank(item.getAcceptanceCriteria())) {
            throw new GuardException("GUARD_READY_UNMET", "进入 Ready 需先填写验收条件");
        }
        if (item.getOwnerId() == null) {
            throw new GuardException("GUARD_READY_UNMET", "进入 Ready 需先指定责任人");
        }
        if (isBlank(item.getEstimate())) {
            throw new GuardException("GUARD_READY_UNMET", "进入 Ready 需先填写估算");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
