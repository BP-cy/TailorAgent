package com.changy.tailoragent.common.exception;

import com.changy.tailoragent.common.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * <p>统一捕获 Controller 抛出的异常，转换为 {@code ApiResponse} 的 error 形式返回前端，
 * 避免把堆栈或框架默认错误页暴露给前端。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：返回业务错误码与消息。
     */
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException ex) {
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    /**
     * 兜底异常：未预期的异常统一返回通用错误信息。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception ex) {
        return ApiResponse.error("服务器内部错误：" + ex.getMessage());
    }
}