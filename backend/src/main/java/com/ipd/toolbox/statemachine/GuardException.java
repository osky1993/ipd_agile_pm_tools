package com.ipd.toolbox.statemachine;

import com.ipd.toolbox.common.BusinessException;

/**
 * 守卫拦截异常。
 *
 * <p>用途：
 * <ul>
 *   <li>将状态迁移前置规则失败统一映射为业务异常，复用统一异常处理链。</li>
 *   <li>通过 message 前缀携带 guardCode，便于前端与日志做可观察性分类。</li>
 * </ul>
 *
 * <p>行为约束：
 * <ul>
 *   <li>code 固定为 4090，由统一响应约束承接。</li>
 *   <li>guardCode 与 message 中的业务码前缀一一对应，便于问题定位。</li>
 * </ul>
 */
public class GuardException extends BusinessException {

    private final String guardCode;

    public GuardException(String guardCode, String message) {
        super(4090, "[" + guardCode + "] " + message);
        this.guardCode = guardCode;
    }

    /**
     * 返回守卫错误码。
     *
     * @return 守卫短码（例如 GUARD_READY_UNMET）
     */
    public String getGuardCode() {
        return guardCode;
    }
}
