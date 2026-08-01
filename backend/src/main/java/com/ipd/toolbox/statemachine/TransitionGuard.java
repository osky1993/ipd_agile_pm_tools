package com.ipd.toolbox.statemachine;

import com.ipd.toolbox.domain.entity.WorkItem;

/**
 * 状态迁移守卫。所有业务前置规则集中实现为守卫，不散落在各 Service 中（开发计划§12-2）。
 * 引擎在放行迁移前依次调用所有 supports 命中的守卫；任一 check 抛 GuardException 即拦截。
 */
public interface TransitionGuard {

    /** 该守卫是否作用于此迁移。 */
    boolean supports(WorkItem item, String toStatus);

    /** 校验，不通过则抛 GuardException。 */
    void check(WorkItem item, String toStatus, TransitionContext ctx);
}
