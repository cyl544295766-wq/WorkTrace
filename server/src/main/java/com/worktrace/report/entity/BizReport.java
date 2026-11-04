package com.worktrace.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 生成的工作报告，对应表 biz_report。
 *
 * <ul>
 *   <li>report_type：DAILY / WEEKLY / MONTHLY</li>
 *   <li>content：大模型返回的 Markdown（LongText）</li>
 *   <li>ai_model：生成时使用的模型名（留痕，便于追溯/复现）</li>
 * </ul>
 *
 * 归属 user_id（会话用户）；下划线↔驼峰映射由 map-underscore-to-camel-case 完成。
 */
@Data
@TableName("biz_report")
public class BizReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 id（会话用户，绝不来自请求体） */
    private Long userId;

    /** DAILY / WEEKLY / MONTHLY */
    private String reportType;

    /** 起始日期 start_date */
    private LocalDate startDate;

    /** 结束日期 end_date */
    private LocalDate endDate;

    /** 报告正文（Markdown，LongText） */
    private String content;

    /** 生成所用模型，如 deepseek-chat / gpt-4o-mini */
    private String aiModel;

    private LocalDateTime createTime;
}
