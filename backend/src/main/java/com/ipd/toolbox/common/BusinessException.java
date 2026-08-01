package com.ipd.toolbox.common;

/**
 * 业务异常。code 用于结构化错误（如守卫错误码 GUARD_READY_UNMET）。
 */
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(String message) {
        this(4000, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
