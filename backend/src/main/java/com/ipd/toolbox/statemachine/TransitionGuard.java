package com.ipd.toolbox.statemachine;

import com.ipd.toolbox.domain.entity.WorkItem;

/**
 * 状态迁移守卫。所有业务前置规则集中实现为守卫，不散落在各 Service 中（开发计划§12-2）。
 * 引擎在放行迁移前依次调用所有 supports 命中的守卫；任一 check 抛 GuardException 即拦截。
 */
public interface TransitionGuard {

    /**
     * 判定守卫是否处理本次迁移请求。
     *
     * <p>用途：允许每个守卫只绑定自己负责的迁移路径，避免无关校验在全链路重复执行。
     *
     * <p>边界：
     * <ul>
     *   <li>返回 true 的守卫将进入 check() 执行；返回 false 则跳过。</li>
     *   <li>返回 false 不代表通过该维度校验，只表示该守卫不负责本场景。</li>
     * </ul>
     */
    boolean supports(WorkItem item, String toStatus);

    /**
     * 执行校验逻辑。检查失败直接抛 GuardException 中断迁移链。
     *
     * <p>副作用与失败语义：
     * <ul>
     *   <li>应仅做前置校验，避免在此阶段修改数据库状态。</li>
     *   <li>通过抛出 GuardException 传播拒绝码，保持迁移请求幂等安全（同一次失败可重试）。</li>
     * </ul>
     *
     * @param item 当前工作项实体（含 ID、类型、扩展字段等）
     * @param toStatus 目标状态
     * @param ctx 可选上下文（如回退理由、外部系统返回参数）
     */
    void check(WorkItem item, String toStatus, TransitionContext ctx);
}
