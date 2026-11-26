package com.worktrace.insight.dto;

import java.time.LocalDate;

/** 洞察使用的统计快照；前端据此拒绝与当前任务卡片不一致的旧文案。待处理包含已延期。 */
public record InsightStatsVO(
        LocalDate date,
        int total,
        int done,
        int doing,
        int pending,
        int unfinished,
        int overdue,
        int highPending,
        int todayNew,
        int keyTasks,
        int completionRate
) {
}
