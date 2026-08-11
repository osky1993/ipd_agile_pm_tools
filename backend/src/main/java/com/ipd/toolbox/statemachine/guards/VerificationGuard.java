package com.ipd.toolbox.statemachine.guards;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.mapper.TraceLinkMapper;
import com.ipd.toolbox.statemachine.GuardException;
import com.ipd.toolbox.statemachine.TransitionContext;
import com.ipd.toolbox.statemachine.TransitionGuard;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 守卫 #3：通用工作项进入 Verification 前的可验证性校验（开发计划 T308）。
 * <p>要求：已关联产品版本，或存在 evidences 追溯关系（其中任一满足即可）。
 */
@Component
public class VerificationGuard implements TransitionGuard {

    private static final Set<String> GENERIC = Set.of(
            WorkItemType.CAPABILITY.name(), WorkItemType.REQUIREMENT.name(),
            WorkItemType.STORY.name(), WorkItemType.TASK.name());

    private final TraceLinkMapper traceLinkMapper;

    public VerificationGuard(TraceLinkMapper traceLinkMapper) {
        this.traceLinkMapper = traceLinkMapper;
    }

    /**
     * 仅对通用类型且目标状态为 Verification 时生效。
     *
     * <p>边界：仅在状态入口场景判断，不在其他阶段强制执行。
     */
    @Override
    public boolean supports(WorkItem item, String toStatus) {
        return "Verification".equals(toStatus) && GENERIC.contains(item.getType());
    }

    /**
     * 执行可验证性校验（任一条件满足即可）。
     *
     * <p>策略：
     * <ul>
     *   <li>优先检查 product_version_id，不为空则直接放行。</li>
     *   <li>否则再检查 evidences 追溯关系 count>0。</li>
     *   <li>仅做关系存在性判断，不校验版本质量/证据有效性。</li>
     * </ul>
     *
     * <p>失败回退：
     * 任何条件不满足都会抛 GUARD_VERIFICATION_NO_EVIDENCE，阻断迁移，工作项停留原状态。
     */
    @Override
    public void check(WorkItem item, String toStatus, TransitionContext ctx) {
        if (item.getProductVersionId() != null) {
            return;
        }
        Long evidenceLinks = traceLinkMapper.selectCount(new QueryWrapper<com.ipd.toolbox.domain.entity.TraceLink>()
                .eq("source_type", "WORK_ITEM")
                .eq("source_id", item.getId())
                .eq("relation", "evidences"));
        if (evidenceLinks == null || evidenceLinks == 0) {
            throw new GuardException("GUARD_VERIFICATION_NO_EVIDENCE",
                    "进入 Verification 需关联可验证版本或证据");
        }
    }
}
