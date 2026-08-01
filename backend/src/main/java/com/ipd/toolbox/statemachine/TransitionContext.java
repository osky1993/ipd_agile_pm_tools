package com.ipd.toolbox.statemachine;

import java.util.HashMap;
import java.util.Map;

/** 迁移上下文：承载回退理由、验收信息等迁移时附带参数，供守卫与引擎使用。 */
public class TransitionContext {

    private String reason;
    private final Map<String, Object> attrs = new HashMap<>();

    public String getReason() {
        return reason;
    }

    public TransitionContext setReason(String reason) {
        this.reason = reason;
        return this;
    }

    public Object get(String key) {
        return attrs.get(key);
    }

    public TransitionContext put(String key, Object value) {
        attrs.put(key, value);
        return this;
    }
}
