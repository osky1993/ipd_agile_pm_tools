package com.ipd.toolbox.statemachine.guards;

import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.statemachine.GuardException;
import com.ipd.toolbox.statemachine.TransitionContext;
import com.ipd.toolbox.statemachine.TransitionGuard;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 守卫 #1：通用工作项进入 Ready 的承诺条件校验（开发计划 T303）。
 * <p>目标：防止未具备最小交付条件的工作项提前进入可承诺执行状态。
 */
@Component
public class ReadyGuard implements TransitionGuard {

    private static final Set<String> GENERIC = Set.of(
            WorkItemType.CAPABILITY.name(), WorkItemType.REQUIREMENT.name(),
            WorkItemType.STORY.name(), WorkItemType.TASK.name());

    /**
     * 仅对通用类型且目标状态为 Ready 生效。
     *
     * <p>覆盖范围：
     * <ul>
     *   <li>CAPABILITY / REQUIREMENT / STORY / TASK。</li>
     *   <li>不覆盖 DEFECT/CHANGE/RISK，避免状态语义混淆。</li>
     * </ul>
     */
    @Override
    public boolean supports(WorkItem item, String toStatus) {
        return "Ready".equals(toStatus) && GENERIC.contains(item.getType());
    }

    /**
     * 校验 Ready 的最小交付先决条件。
     *
     * <p>验收条件、责任人、估算缺一不可；字段缺失直接拒绝迁移。
     *
     * <p>更新粒度：
     * <ul>
     *   <li>本检查不落库；仅以错误码阻断不合规迁移。</li>
     *   <li>统一抛出 GUARD_READY_UNMET，便于前端给出一致提示。</li>
     * </ul>
     */
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
