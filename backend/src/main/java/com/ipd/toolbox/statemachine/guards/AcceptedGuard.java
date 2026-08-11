package com.ipd.toolbox.statemachine.guards;

import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.statemachine.GuardException;
import com.ipd.toolbox.statemachine.TransitionContext;
import com.ipd.toolbox.statemachine.TransitionGuard;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 守卫 #4：Accepted 语义=业务/能力验收。
 * <p>迁移到 Accepted 前需确认验收主数据完整（验收人和验收时间），用于：
 * <ul>
 *   <li>审计链路可追溯“谁在何时完成验收”；</li>
 *   <li>避免跳过服务层流程直接落库导致记录缺失。</li>
 * </ul>
 *
 * <p>说明：实际验收动作通常由服务层在迁移前回填 acceptedBy/acceptedAt，本守卫仅做兜底校验。
 */
@Component
public class AcceptedGuard implements TransitionGuard {

    private static final Set<String> GENERIC = Set.of(
            WorkItemType.CAPABILITY.name(), WorkItemType.REQUIREMENT.name(),
            WorkItemType.STORY.name(), WorkItemType.TASK.name());

    /**
     * 仅对通用类型且目标状态为 Accepted 时执行。
     *
     * <p>更新粒度说明：不在缺陷/变更/风险等分支复用 Accepted 语义。
     */
    @Override
    public boolean supports(WorkItem item, String toStatus) {
        return "Accepted".equals(toStatus) && GENERIC.contains(item.getType());
    }

    /**
     * 验收前置校验：验收人/验收时间必须非空。
     *
     * <p>边界与失败策略：
     * <ul>
     *   <li>acceptedBy 或 acceptedAt 任一为空即抛出 GUARD_ACCEPT_NO_ACCEPTOR。</li>
     *   <li>失败直接阻断状态迁移，避免无审计链的 Accepted 记录。</li>
     * </ul>
     * <p>本方法无持久化副作用；只读工作项主数据。
     */
    @Override
    public void check(WorkItem item, String toStatus, TransitionContext ctx) {
        if (item.getAcceptedBy() == null || item.getAcceptedAt() == null) {
            throw new GuardException("GUARD_ACCEPT_NO_ACCEPTOR", "验收必须记录验收人与时间");
        }
    }
}
