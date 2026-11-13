package com.worktrace.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 报告正文编辑保存请求（PUT /api/reports/{id}）。
 * 只允许改 content——归属 / 类型 / 日期不可编辑；user_id 来自登录会话。
 */
public record ReportContentReq(
        @NotBlank(message = "报告内容不能为空")
        @Size(max = 30_000, message = "报告内容过长（上限 3 万字符）")
        String content
) {
}
