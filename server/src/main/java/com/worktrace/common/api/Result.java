package com.worktrace.common.api;

/**
 * 统一返回结果包装：{ code, message, data }。
 *
 * <p>code 沿用 HTTP 语义，前后端共用一套认知：
 * <ul>
 *   <li>200 成功；400 参数错误；401 未登录/会话过期；403 无权限；404 资源不存在；500 系统异常</li>
 * </ul>
 * 无论业务成功失败，HTTP 状态码统一 200（除拦截器直接 401 的场景），前端只看 body.code。
 */
public record Result<T>(int code, String message, T data) {

    public static final int SUCCESS = 200;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int SERVER_ERROR = 500;

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS, "ok", data);
    }

    public static Result<Void> ok() {
        return new Result<>(SUCCESS, "ok", null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** 默认 500 的快捷失败 */
    public static <T> Result<T> fail(String message) {
        return fail(SERVER_ERROR, message);
    }

    public boolean isSuccess() {
        return code == SUCCESS;
    }
}
