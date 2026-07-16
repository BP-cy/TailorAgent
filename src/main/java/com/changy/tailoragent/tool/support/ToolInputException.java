package com.changy.tailoragent.tool.support;

/**
 * 工具入参/前置条件错误。
 * <p>
 * 工具方法捕获它后,把 {@link #getMessage()} 作为<b>可操作的中文提示</b>返回给模型
 * (而非抛裸异常),让模型据此自我纠正(例如"编辑前必须先 Read")。
 */
public class ToolInputException extends RuntimeException {
    public ToolInputException(String message) {
        super(message);
    }
}
