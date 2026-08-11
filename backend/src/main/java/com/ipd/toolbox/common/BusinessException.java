package com.ipd.toolbox.common;

/**
 * 业务异常。code 用于结构化错误（如守卫错误码 GUARD_READY_UNMET）。
 */
public class BusinessException extends RuntimeException {
    /** 前端与上层服务约定读取的业务错误码（0=成功，非 0=失败）。 */
    private final int code;

    /**
     * 默认按通用业务错误码 4000 返回。  
     * 建议在可预期失败场景使用更细分码（如 4040/4090/4091 等）。
     */
    public BusinessException(String message) {
        this(4000, message);
    }

    /**
     * 创建业务错误并携带结构化错误码。
     * @param code 领域内约定码，建议统一从调用链的业务约束中映射。
     * @param message 人可读错误原因，会直接返回给前端统一显示。
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 获取用于前后端协议的错误码。 */
    public int getCode() {
        return code;
    }
}
