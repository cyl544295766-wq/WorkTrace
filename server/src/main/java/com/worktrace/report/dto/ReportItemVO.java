package com.worktrace.report.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 报告列表行（GET /api/reports/page）。
 * 不含正文全文（LONGTEXT 撑响应），仅 80 字摘要；查看全文走 GET /api/reports/{id}。
 *
 * @param id         报告 id
 * @param reportType DAILY/WEEKLY/MONTHLY
 * @param taskScope  生成范围 COMPANY/PERSONAL（历史报告统一为 COMPANY）
 * @param startDate  统计开始日期
 * @param endDate    统计结束日期
 * @param aiModel    生成模型
 * @param createTime 生成时间
 * @param summary    正文摘要（换行已压成空格）
 */
public record ReportItemVO(
        Long id,
        String reportType,
        String taskScope,
        LocalDate startDate,
        LocalDate endDate,
        String aiModel,
        LocalDateTime createTime,
        String summary
) {
}
