package com.worktrace.common.web;

import com.worktrace.common.api.Result;
import com.worktrace.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：所有未处理异常统一包装为 {@code Result.fail(...)} 返回，
 * 前端永远拿不到原生堆栈。
 *
 * <p>约定：HTTP 状态码保持 200，错误语义全部通过 body.code（HTTP 风格：400/401/404/500）表达，
 * 与 {@code Result} 的约定一致。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：码与消息原样透传（属可预期失败，warn 级别即可） */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验失败（@Valid @RequestBody）：把字段级错误拼接成可读消息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return Result.fail(Result.BAD_REQUEST, detail);
    }

    /** 请求体不可读 / 参数类型不匹配等 400 类 */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public Result<Void> handleBadRequest(Exception e) {
        return Result.fail(Result.BAD_REQUEST, "请求参数格式错误");
    }

    /** 接口/静态资源不存在（Spring 6.1 起未匹配路径抛 NoResourceFoundException） */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNotFound(NoResourceFoundException e) {
        return Result.fail(Result.NOT_FOUND, "接口不存在: " + e.getResourcePath());
    }

    /**
     * 兜底：捕获一切未处理异常（NullPointerException 等系统异常都在此）。
     * 完整堆栈只进日志，对外只返回统一提示，不暴露任何实现细节。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(Result.SERVER_ERROR, "系统繁忙，请稍后重试");
    }
}
