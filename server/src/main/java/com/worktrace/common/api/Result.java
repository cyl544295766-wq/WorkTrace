package com.worktrace.common.api;

/**
 * 统一返回结果包装：{ code, message, data }。
 * code = 0 表示成功；非 0 为业务错误码。
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static Result<Void> ok() {
        return new Result<>(0, "ok", null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
