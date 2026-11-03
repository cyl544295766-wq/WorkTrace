package com.worktrace.common.api;

import org.springframework.http.HttpStatus;

/**
 * 错误码段位（design.md §8.2）。编号一旦分配永不复用。
 * 已删除 30003（团队视图）与 90004（隐私守卫），此处不得恢复。
 */
public enum ResultCode {

    // 1xxxx 通用/协议
    PARAM_VALIDATION_FAILED(10001, "参数校验失败", HttpStatus.BAD_REQUEST),
    BODY_UNREADABLE(10002, "请求体解析失败", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND(10003, "资源不存在", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(10004, "方法不支持", HttpStatus.METHOD_NOT_ALLOWED),
    SORT_FIELD_INVALID(10005, "排序字段不在白名单", HttpStatus.BAD_REQUEST),

    // 2xxxx 认证/会话
    UNAUTHORIZED(20001, "未登录或会话已失效", HttpStatus.UNAUTHORIZED),
    BAD_CREDENTIALS(20002, "用户名或密码错误", HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED(20003, "账号已锁定，请稍后再试", HttpStatus.UNAUTHORIZED),
    CAPTCHA_INVALID(20004, "验证码错误或已过期", HttpStatus.BAD_REQUEST),
    MUST_CHANGE_PASSWORD(20005, "必须先修改初始密码", HttpStatus.FORBIDDEN),
    ACCOUNT_DISABLED(20006, "账号已停用", HttpStatus.FORBIDDEN),
    INVITE_CODE_INVALID(20007, "邀请码无效、已使用或已过期", HttpStatus.BAD_REQUEST),
    WEAK_PASSWORD(20008, "密码强度不足", HttpStatus.BAD_REQUEST),
    ORIGIN_FORBIDDEN(20009, "请求来源不合法", HttpStatus.FORBIDDEN),
    ACCOUNT_SOURCE_FORBIDDEN(20010, "当前建号方式不允许该操作", HttpStatus.BAD_REQUEST),

    // 3xxxx 授权/越权
    ROLE_FORBIDDEN(30001, "角色权限不足", HttpStatus.FORBIDDEN),
    RESOURCE_FORBIDDEN(30002, "资源不属于当前用户", HttpStatus.FORBIDDEN),

    // 4xxxx 任务业务
    TASK_TITLE_REQUIRED(40001, "标题不能为空", HttpStatus.BAD_REQUEST),
    PARENT_TASK_INVALID(40002, "父任务非法", HttpStatus.BAD_REQUEST),
    TASK_STATUS_INVALID(40003, "状态流转非法", HttpStatus.BAD_REQUEST),
    DUE_DATE_INVALID(40004, "截止日格式非法", HttpStatus.BAD_REQUEST),
    TAG_NAME_DUPLICATE(40005, "标签名重复", HttpStatus.CONFLICT),

    // 5xxxx 时间记录业务
    TIME_DURATION_EXCEEDED(50001, "时长不得超过 8 小时", HttpStatus.BAD_REQUEST),
    TIME_MANUAL_GRANULARITY(50002, "手动补录需为 15 分钟倍数", HttpStatus.BAD_REQUEST),
    TIMER_ALREADY_RUNNING(50003, "已有进行中的计时", HttpStatus.CONFLICT),
    TIME_REF_REQUIRED(50004, "任务与标签至少填一个", HttpStatus.BAD_REQUEST),

    // 6xxxx 笔记/报告业务
    NOTE_NOT_FOUND(60001, "笔记不存在或已删除", HttpStatus.NOT_FOUND),
    AUTO_BLOCK_IMMUTABLE(60002, "自动聚合区块不可修改或删除", HttpStatus.BAD_REQUEST),
    REPORT_FINALIZED(60003, "报告已定稿，修改将生成新版本", HttpStatus.CONFLICT),
    SECTION_KEY_INVALID(60004, "手写段键名非法", HttpStatus.BAD_REQUEST),

    // 7xxxx 数据导出
    EXPORT_IN_PROGRESS(70001, "已有导出任务进行中", HttpStatus.CONFLICT),
    EXPORT_NOT_READY(70002, "导出尚未完成", HttpStatus.CONFLICT),
    EXPORT_EXPIRED(70003, "导出文件已过期，请重新发起", HttpStatus.GONE),

    // 9xxxx 系统
    INTERNAL_ERROR(90001, "内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    DATABASE_UNAVAILABLE(90002, "数据库不可用", HttpStatus.SERVICE_UNAVAILABLE),
    RATE_LIMITED(90003, "请求过于频繁", HttpStatus.TOO_MANY_REQUESTS);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ResultCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
