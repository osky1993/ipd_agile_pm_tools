package com.ipd.toolbox.statemachine;

import java.util.HashMap;
import java.util.Map;

/**
 * 状态迁移上下文。
 *
 * <p>用途：聚合状态流转过程中的辅助参数（如回退原因、验证标签、外部判定结果），
 * 供状态机执行链与守卫统一读取，避免在签名中硬编码大量可选字段。
 *
 * <p>更新粒度说明：
 * <ul>
 *   <li>写路径主要是服务层构造/填充并沿调用链透传，服务外不应在无序列场景下并发复用。</li>
 *   <li>本对象是可变对象，适配一次迁移请求的临时上下文，不承载跨请求共享状态。</li>
 * </ul>
 */
public class TransitionContext {

    /**
     * 回退/强制迁移理由。
     *
     * <p>规则：当迁移判定为回退时，上层会要求 reason 非空；未满足时通常抛 403/业务码并回滚状态变更请求。
     */
    private String reason;
    /**
     * 守卫链可写入的附加上下文（如扩展字段映射、外部检查参数）。
     * 键值可为任意对象，调用方应约定类型并避免与序列化无关对象长期保存。
     */
    private final Map<String, Object> attrs = new HashMap<>();

    /**
     * 获取当前迁移上下文中的回退/强制迁移理由。
     *
     * @return 说明文本；未设置则为 null
     */
    public String getReason() {
        return reason;
    }

    /**
     * 设置回退/强制迁移理由。
     *
     * <p>副作用：
     * <ul>
     *   <li>仅更新对象状态，不产生数据库/外部系统副作用。</li>
     *   <li>支持链式调用，便于在同一构建链中表达上下文语义。</li>
     * </ul>
     *
     * @param reason 迁移理由文本
     * @return 当前 TransitionContext 实例
     */
    public TransitionContext setReason(String reason) {
        this.reason = reason;
        return this;
    }

    /**
     * 读取守卫附加上下文字段。
     *
     * @param key 约定字段名
     * @return 对应字段值；缺失返回 null
     */
    public Object get(String key) {
        return attrs.get(key);
    }

    /**
     * 设置守卫附加字段。
     *
     * <p>边界：
     * <ul>
     *   <li>value 可为 null，表示显式清空该键。</li>
     *   <li>键冲突以最后一次 put 覆盖。</li>
     * </ul>
     *
     * @param key 字段名
     * @param value 字段值
     * @return 当前 TransitionContext 实例
     */
    public TransitionContext put(String key, Object value) {
        attrs.put(key, value);
        return this;
    }
}
