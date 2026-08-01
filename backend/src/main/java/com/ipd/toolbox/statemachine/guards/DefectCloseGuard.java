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
 * 守卫#2 缺陷关闭必须有复测通过（开发计划 T307）：
 * 存在 defect_id 指向本缺陷且 result=PASS 的 TestRun。
 */
@Component
public class DefectCloseGuard implements TransitionGuard {

    private final TestRunMapper testRunMapper;

    public DefectCloseGuard(TestRunMapper testRunMapper) {
        this.testRunMapper = testRunMapper;
    }

    @Override
    public boolean supports(WorkItem item, String toStatus) {
        return WorkItemType.DEFECT.name().equals(item.getType()) && "Closed".equals(toStatus);
    }

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
