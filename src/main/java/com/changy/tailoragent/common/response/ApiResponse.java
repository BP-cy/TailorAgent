package com.changy.tailoragent.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> implements Serializable {

    // 状态码
    private Integer code;

    // 提示信息
    private String message;

    // 响应业务数据
    private T data;

    // 禁止外部实例化 采用静态工厂方法获取响应对象
    private ApiResponse() {}

    /**
     * 构建响应（自定义状态码与消息，不含数据）
     */
    private static <T> ApiResponse<T> customizedCodeAndMessage(Integer code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(code);
        response.setMessage(message);
        return response;
    }

    // ==================== 成功响应 ====================

    /**
     * 成功响应（默认状态码 1，默认消息，无数据）
     */
    public static <T> ApiResponse<T> success() {
        return customizedCodeAndMessage(1, "success");
    }

    /**
     * 成功响应（默认状态码 1，自定义消息，无数据）
     */
    public static <T> ApiResponse<T> success(String message) {
        return customizedCodeAndMessage(1, message);
    }

    /**
     * 成功响应（默认状态码 1，自定义消息和数据）
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        ApiResponse<T> response = customizedCodeAndMessage(1, message);
        response.setData(data);
        return response;
    }

    /**
     * 成功响应（默认状态码 1、默认消息 "success"，仅自定义数据）
     */
    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = customizedCodeAndMessage(1, "success");
        response.setData(data);
        return response;
    }

    /**
     * 成功响应（自定义状态码、消息与数据）
     */
    public static <T> ApiResponse<T> success(Integer code, String message, T data) {
        ApiResponse<T> response = customizedCodeAndMessage(code, message);
        response.setData(data);
        return response;
    }

    // ==================== 失败响应 ====================

    /**
     * 失败响应（默认状态码 -1，默认消息，无数据）
     */
    public static <T> ApiResponse<T> error() {
        return customizedCodeAndMessage(-1, "error");
    }

    /**
     * 失败响应（默认状态码 -1，自定义消息，无数据）
     */
    public static <T> ApiResponse<T> error(String message) {
        return customizedCodeAndMessage(-1, message);
    }

    /**
     * 失败响应（默认状态码 -1，自定义消息和数据）
     */
    public static <T> ApiResponse<T> error(String message, T data) {
        ApiResponse<T> response = customizedCodeAndMessage(-1, message);
        response.setData(data);
        return response;
    }

    /**
     * 失败响应（自定义状态码与消息，无数据）
     */
    public static <T> ApiResponse<T> error(Integer code, String message) {
        return customizedCodeAndMessage(code, message);
    }

    /**
     * 失败响应（自定义状态码、消息与数据）
     */
    public static <T> ApiResponse<T> error(Integer code, String message, T data) {
        ApiResponse<T> response = customizedCodeAndMessage(code, message);
        response.setData(data);
        return response;
    }

}