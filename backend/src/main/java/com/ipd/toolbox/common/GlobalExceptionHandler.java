package com.ipd.toolbox.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
/**
 * 全局异常兜底。所有 Controller 抛出的异常最终会经过这里转为 Result 结构，避免散落 try/catch。
 * 规则：
 * - 业务异常保留业务 code 透传
 * - 参数校验异常映射为 4001
 * - 兜底异常统一返回 5000 并打日志，防止泄露敏感栈。
 */
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：沿用异常本身的 code/message（用于守卫、校验、缺失资源等）。 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.error(e.getCode(), e.getMessage());
    }

    /** 入参约束失败：优先返回第一个字段错误信息给调用者。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getDefaultMessage()
                : "参数校验失败";
        return Result.error(4001, msg);
    }

    /** 未识别异常：记录完整堆栈，外部只返回可读错误。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return Result.error(5000, "服务器内部错误: " + e.getMessage());
    }
}
