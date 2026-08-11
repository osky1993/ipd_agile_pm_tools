package com.ipd.toolbox.common;

/**
 * 统一响应体。守卫失败等业务错误通过 code + message 返回，前端统一提示。
 */
public class Result<T> {
    /** 统一响应业务码，约定 0=ok，非 0=业务错误。 */
    private int code;
    /** 可直接展示给用户或日志的错误/成功信息。 */
    private String message;
    /** 具体返回数据载荷，成功场景为 T，失败一般为 null。 */
    private T data;

    /** 无参构造用于序列化框架反序列化。 */
    public Result() {
    }

    /** 标准构造器，所有返回都应走统一字段，避免混用 Result 与直接 map 返回。 */
    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 快速构造成功响应（data 可为空）。 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    /** 无数据成功响应（用于 delete 类动作或提交类接口）。 */
    public static <T> Result<T> ok() {
        return new Result<>(0, "ok", null);
    }

    /**
     * 构造失败响应。
     * @param code 约定错误码，前端会按 code 分流展示。
     * @param message 失败说明。
     */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
