package com.worktrace.common.exception;

import com.worktrace.common.api.ResultCode;
import org.springframework.http.HttpStatus;

/**
 * 业务异常基类。HTTP 状态码由 ResultCode 决定。
 */
public class BizException extends RuntimeException {

    private final ResultCode resultCode;
    private final transient Object data;

    public BizException(ResultCode resultCode) {
        this(resultCode, resultCode.getMessage(), null);
    }

    public BizException(ResultCode resultCode, String message) {
        this(resultCode, message, null);
    }

    public BizException(ResultCode resultCode, String message, Object data) {
        super(message);
        this.resultCode = resultCode;
        this.data = data;
    }

    public BizException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause);
        this.resultCode = resultCode;
        this.data = null;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }

    public int getCode() {
        return resultCode.getCode();
    }

    public HttpStatus getHttpStatus() {
        return resultCode.getHttpStatus();
    }

    public Object getData() {
        return data;
    }
}
