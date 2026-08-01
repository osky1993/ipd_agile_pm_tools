package com.ipd.toolbox.statemachine;

import com.ipd.toolbox.common.BusinessException;

/**
 * 守卫拦截异常。code 固定 4090，message 带守卫错误码前缀（docs/02），前端统一提示。
 */
public class GuardException extends BusinessException {

    private final String guardCode;

    public GuardException(String guardCode, String message) {
        super(4090, "[" + guardCode + "] " + message);
        this.guardCode = guardCode;
    }

    public String getGuardCode() {
        return guardCode;
    }
}
