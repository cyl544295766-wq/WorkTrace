package com.worktrace.insight.dto;

/**
 * AI 工作洞察返回体（首页「AI 工作洞察」卡）。
 *
 * @param headline 短标题（≤12 字）
 * @param para     分析文案（≤80 字）
 * @param advice   行动建议（≤18 字）
 * @param source   文案来源：{@link #SOURCE_AI} 大模型生成 / {@link #SOURCE_RULES} 规则兜底
 */
public record InsightVO(String headline, String para, String advice, String source) {

    /** 大模型生成 */
    public static final String SOURCE_AI = "AI";

    /** 规则兜底（AI key 缺失 / 超时 / 解析失败 / 无任务） */
    public static final String SOURCE_RULES = "RULES";
}
