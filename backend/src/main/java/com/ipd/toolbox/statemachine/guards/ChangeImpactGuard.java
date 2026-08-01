package com.ipd.toolbox.statemachine.guards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.statemachine.GuardException;
import com.ipd.toolbox.statemachine.TransitionContext;
import com.ipd.toolbox.statemachine.TransitionGuard;
import org.springframework.stereotype.Component;

/**
 * 守卫#5 变更进入 Impact Analysed 前必须完成影响分析（开发计划 T401）：
 * ext_fields.impactAnalysed = true。完整影响分析清单在 E4 落地。
 */
@Component
public class ChangeImpactGuard implements TransitionGuard {

    private final ObjectMapper objectMapper;

    public ChangeImpactGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(WorkItem item, String toStatus) {
        return WorkItemType.CHANGE.name().equals(item.getType()) && "Impact Analysed".equals(toStatus);
    }

    @Override
    public void check(WorkItem item, String toStatus, TransitionContext ctx) {
        boolean analysed = false;
        if (item.getExtFields() != null && !item.getExtFields().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(item.getExtFields());
                analysed = node.path("impactAnalysed").asBoolean(false);
            } catch (Exception ignored) {
                // 解析失败视为未完成
            }
        }
        if (!analysed) {
            throw new GuardException("GUARD_CHANGE_NO_IMPACT", "变更进入影响分析前必须先完成影响分析");
        }
    }
}
