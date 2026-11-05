package com.worktrace.common.exception;

import com.worktrace.common.api.Result;

/**
 * 业务异常：可预期的失败（参数不合法、状态冲突、凭据错误等）。
 * 由 {@code GlobalExceptionHandler} 统一捕获并包装为 {@code Result.fail(code, message)}，
 * 业务代码里只管抛，不再各自 try/catch。
 */
public class BizException extends RuntimeException {

    private final int code;

    /** 指定业务码（HTTP 语义：400/401/403/404/500...） */
    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 默认 500 */
    public BizException(String message) {
        this(Result.SERVER_ERROR, message);
    }

    /** 允许包装底层原因（密码不匹配、下游调用失败等） */
    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
