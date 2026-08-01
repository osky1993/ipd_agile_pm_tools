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
 * 守卫#3 进入 Verification 必须关联可验证版本或证据（开发计划 T308）。
 * 证据以 evidences 追溯关系判断（证据上传在 E4 落地后自然生效）。
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

    @Override
    public boolean supports(WorkItem item, String toStatus) {
        return "Verification".equals(toStatus) && GENERIC.contains(item.getType());
    }

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
