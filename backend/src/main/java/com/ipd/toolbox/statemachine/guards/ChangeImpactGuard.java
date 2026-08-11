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
 * 守卫 #5：Change 进入 Impact Analysed 前必须完成影响分析（开发计划 T401）。
 *
 * <p>校验依据：
 * <ul>
 *   <li>从 work_item.ext_fields 读取 impactAnalysed 字段。</li>
 *   <li>值必须显式为 true；解析失败视为 false，执行 fail-fast。</li>
 * </ul>
 *
 * <p>更新粒度注释：
 * 本守卫只关注“进入 Impact Analysed 的入口条件”，不承担影响分析内容的落库或变更。
 */
@Component
public class ChangeImpactGuard implements TransitionGuard {

    private final ObjectMapper objectMapper;

    public ChangeImpactGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 仅对 Change 类型且目标为 Impact Analysed 时触发。
     *
     * <p>这样可保证不会影响其他类型/状态的迁移链路，也便于规则归档。
     */
    @Override
    public boolean supports(WorkItem item, String toStatus) {
        return WorkItemType.CHANGE.name().equals(item.getType()) && "Impact Analysed".equals(toStatus);
    }

    /**
     * 读取 ext_fields JSON 的 {@code impactAnalysed} 开关。
     *
     * <p>异常与回退策略：
     * <ul>
     *   <li>ext_fields 为空、字段缺失或 JSON 解析失败都按未完成处理。</li>
     *   <li>不解析成功时不尝试默认成功，避免因为坏数据造成误放行。</li>
     *   <li>检查失败抛出 GUARD_CHANGE_NO_IMPACT，并中止后续迁移。</li>
     * </ul>
     *
     * <p>副作用：无数据库更新；仅做读 ext_fields 与 JSON 解析。
     */
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
