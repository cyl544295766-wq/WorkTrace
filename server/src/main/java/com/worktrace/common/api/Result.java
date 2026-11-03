package com.worktrace.common.api;

import com.worktrace.common.util.TraceIdUtil;

/**
 * 统一响应体：{code, message, data, traceId, ts}。
 * code = 0 成功；非 0 时 data 必须为 null（带 warnings 的成功响应除外）。
 */
public record Result<T>(
        int code,
        String message,
        T data,
        String traceId,
        long ts
) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data, TraceIdUtil.current(), System.currentTimeMillis());
    }

    public static Result<Void> ok() {
        return new Result<>(0, "ok", null, TraceIdUtil.current(), System.currentTimeMillis());
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        return fail(resultCode, resultCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return fail(resultCode, message, null);
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message, T data) {
        return new Result<>(resultCode.getCode(), message, data,
                TraceIdUtil.current(), System.currentTimeMillis());
    }
}
