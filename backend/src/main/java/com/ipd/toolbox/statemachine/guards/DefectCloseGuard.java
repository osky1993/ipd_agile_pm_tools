package com.ipd.toolbox.statemachine.guards;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.domain.entity.TestRun;
import com.ipd.toolbox.domain.entity.WorkItem;
import com.ipd.toolbox.domain.enums.WorkItemType;
import com.ipd.toolbox.mapper.TestRunMapper;
import com.ipd.toolbox.statemachine.GuardException;
import com.ipd.toolbox.statemachine.TransitionContext;
import com.ipd.toolbox.statemachine.TransitionGuard;
import org.springframework.stereotype.Component;

/**
 * 守卫 #2：缺陷关闭前必须存在复测通过记录（开发计划 T307）。
 * <p>要求：至少存在一条关联 defect_id 且 result=PASS 的 TestRun。
 */
@Component
public class DefectCloseGuard implements TransitionGuard {

    private final TestRunMapper testRunMapper;

    public DefectCloseGuard(TestRunMapper testRunMapper) {
        this.testRunMapper = testRunMapper;
    }

    /**
     * 仅对缺陷类型且目标状态为 Closed 时触发。
     *
     * <p>不处理非缺陷类型或其他状态，避免规则耦合。
     */
    @Override
    public boolean supports(WorkItem item, String toStatus) {
        return WorkItemType.DEFECT.name().equals(item.getType()) && "Closed".equals(toStatus);
    }

    /**
     * 校验是否存在通过复测。
     *
     * <p>更新粒度说明：
     * <ul>
     *   <li>查询条件为 defect_id=item.id 且 result="PASS"。</li>
     *   <li>计数 null 或 0 均视为未通过。</li>
     *   <li>不改变状态机图或工作项数据，仅进行是否可迁移决策。</li>
     * </ul>
     *
     * <p>失败策略：
     * 当未命中 PASS 记录时抛 GUARD_DEFECT_NO_RETEST，阻止 Closed 迁移；调用方可补测后重试。
     */
    @Override
    public void check(WorkItem item, String toStatus, TransitionContext ctx) {
        Long pass = testRunMapper.selectCount(new QueryWrapper<TestRun>()
                .eq("defect_id", item.getId())
                .eq("result", "PASS"));
        if (pass == null || pass == 0) {
            throw new GuardException("GUARD_DEFECT_NO_RETEST", "缺陷关闭前必须有复测通过（PASS）的测试执行");
        }
    }
}
