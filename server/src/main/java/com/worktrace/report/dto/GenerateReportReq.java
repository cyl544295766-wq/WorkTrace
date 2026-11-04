package com.worktrace.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/**
 * 生成报告请求（POST /api/reports/generate）。
 * 不含 user_id —— 归属用户来自登录会话。
 */
public record GenerateReportReq(
        @NotBlank(message = "报告类型不能为空")
        @Pattern(regexp = "DAILY|WEEKLY|MONTHLY", message = "报告类型须为 DAILY / WEEKLY / MONTHLY")
        String reportType,

        @NotNull(message = "开始日期不能为空")
        LocalDate startDate,

        @NotNull(message = "结束日期不能为空")
        LocalDate endDate
) {
}
