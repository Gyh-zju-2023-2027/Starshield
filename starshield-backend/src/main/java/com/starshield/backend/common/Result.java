package com.starshield.backend.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;

/**
 * 全局统一返回格式封装类
 * <p>
 * 所有接口响应均使用此类包装，便于前端统一处理。
 *
 * @param <T> 业务数据泛型
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // 为 null 的字段不参与序列化
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 状态码：200 成功，其余为失败
     */
    private Integer code;

    /**
     * 提示信息
     */
    private String message;

    /**
     * 业务数据
     */
    private T data;

    // ===================== 私有构造，强制使用静态工厂方法 =====================

    private Result() {}

    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ===================== 静态工厂方法 =====================

    /**
     * 成功（无数据）
     */
    public static <T> Result<T> success() {
        return new Result<>(200, "操作成功", null);
    }

    /**
     * 成功（携带数据）
     *
     * @param data 业务数据
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data);
    }

    /**
     * 成功（自定义 message 和数据）
     *
     * @param message 提示信息
     * @param data    业务数据
     */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data);
    }

    /**
     * 失败
     *
     * @param code    错误码
     * @param message 错误信息
     */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 失败（默认错误码 500）
     *
     * @param message 错误信息
     */
    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }

    // ===================== Getter / Setter =====================

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
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

    @Override
    public String toString() {
        return "Result{code=" + code + ", message='" + message + "', data=" + data + "}";
    }
}
