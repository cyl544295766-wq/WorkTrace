package com.worktrace.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增任务跟进记录请求。
 * <b>不含 user_id</b> —— 归属用户一律来自登录会话（Controller 注入），防越权写入。
 */
public record TaskLogAddReq(
        /** 所属任务 id（必须存在且属于当前用户） */
        @NotNull(message = "taskId 不能为空")
        Long taskId,

        /** 记录类型：VERIFY/NOTE/DEV/OTHER */
        @NotBlank(message = "记录类型不能为空")
        String logType,

        /** 跟进内容 */
        @NotBlank(message = "跟进内容不能为空")
        @Size(max = 500, message = "跟进内容最长 500 字")
        String content
) {
}
