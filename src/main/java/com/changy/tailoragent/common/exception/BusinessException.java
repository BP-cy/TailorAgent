package com.changy.tailoragent.common.exception;

import lombok.Getter;

/**
 * 自定义业务异常。
 *
 * <p>业务逻辑中遇到可预期的错误时抛出，由全局异常处理器统一捕获并以
 * {@code ApiResponse} 的 error 形式返回给前端。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务错误码（默认 -1）。 */
    private final Integer code;

    public BusinessException(String message) {
        this(-1, message);
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}