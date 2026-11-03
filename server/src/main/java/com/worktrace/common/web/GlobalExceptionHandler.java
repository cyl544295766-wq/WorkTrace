package com.worktrace.common.web;

import com.worktrace.common.api.Result;
import com.worktrace.common.api.ResultCode;
import com.worktrace.common.exception.BizException;
import com.worktrace.common.util.TraceIdUtil;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局异常处理：所有失败响应都走统一响应体。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Object>> handleBiz(BizException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .body(Result.fail(ex.getResultCode(), ex.getMessage(), ex.getData()));
    }

    /**
     * @Valid 校验失败：400 + 10001 + data.errors[{field,message}]。
     * MethodArgumentNotValidException 继承自 BindException，这里一并覆盖。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Object>> handleBind(BindException ex) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toErrorItem)
                .toList();
        return badRequestWithErrors(errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Object>> handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of(
                        "field", v.getPropertyPath().toString(),
                        "message", v.getMessage() == null ? "参数不合法" : v.getMessage()))
                .toList();
        return badRequestWithErrors(errors);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Object>> handleRequestParam(Exception ex) {
        String field = ex instanceof MissingServletRequestParameterException missing
                ? missing.getParameterName()
                : ((MethodArgumentTypeMismatchException) ex).getName();
        return badRequestWithErrors(List.of(Map.of("field", field, "message", "参数缺失或类型不合法")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.debug("Request body parse failed, traceId={}", TraceIdUtil.current(), ex);
        return ResponseEntity.badRequest()
                .body(Result.fail(ResultCode.BODY_UNREADABLE));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(ResultCode.METHOD_NOT_ALLOWED.getHttpStatus())
                .body(Result.fail(ResultCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Result<Object>> handleNotFound(Exception ex) {
        return ResponseEntity.status(ResultCode.RESOURCE_NOT_FOUND.getHttpStatus())
                .body(Result.fail(ResultCode.RESOURCE_NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleUnknown(Exception ex) {
        // 完整堆栈只进日志；响应不泄露任何内部信息
        log.error("Unhandled exception, traceId={}", TraceIdUtil.current(), ex);
        return ResponseEntity.status(ResultCode.INTERNAL_ERROR.getHttpStatus())
                .body(Result.fail(ResultCode.INTERNAL_ERROR, "系统繁忙，请稍后重试"));
    }

    private ResponseEntity<Result<Object>> badRequestWithErrors(List<Map<String, String>> errors) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errors", errors);
        return ResponseEntity.badRequest()
                .body(Result.fail(ResultCode.PARAM_VALIDATION_FAILED,
                        ResultCode.PARAM_VALIDATION_FAILED.getMessage(), data));
    }

    private Map<String, String> toErrorItem(FieldError fieldError) {
        return Map.of(
                "field", fieldError.getField(),
                "message", fieldError.getDefaultMessage() == null
                        ? "参数不合法"
                        : fieldError.getDefaultMessage());
    }
}
