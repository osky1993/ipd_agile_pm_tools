package com.ipd.toolbox.statemachine.guards;

import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.statemachine.GuardException;
import com.ipd.toolbox.statemachine.TransitionContext;
import com.ipd.toolbox.statemachine.TransitionGuard;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 守卫#4 Accepted 语义=业务/能力验收，须记录验收人+时间（开发计划 T309）。
 * 引擎在进入 Accepted 前会自动补齐 acceptedBy/acceptedAt，此守卫兜底校验。
 */
@Component
public class AcceptedGuard implements TransitionGuard {

    private static final Set<String> GENERIC = Set.of(
            WorkItemType.CAPABILITY.name(), WorkItemType.REQUIREMENT.name(),
            WorkItemType.STORY.name(), WorkItemType.TASK.name());

    @Override
    public boolean supports(WorkItem item, String toStatus) {
        return "Accepted".equals(toStatus) && GENERIC.contains(item.getType());
    }

    @Override
    public void check(WorkItem item, String toStatus, TransitionContext ctx) {
        if (item.getAcceptedBy() == null || item.getAcceptedAt() == null) {
            throw new GuardException("GUARD_ACCEPT_NO_ACCEPTOR", "验收必须记录验收人与时间");
        }
    }
}
