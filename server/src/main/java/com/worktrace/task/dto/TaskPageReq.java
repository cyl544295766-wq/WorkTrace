package com.worktrace.task.dto;

import java.time.LocalDate;

/**
 * 分页查询条件（GET /api/tasks/page）。
 * page 从 1 开始，size 默认 10；status / startDate / endDate 均可空。
 * taskScope 空 = COMPANY（缺省按公司任务过滤），非法值 400。
 */
public record TaskPageReq(
        int page,
        int size,
        /** 按状态筛（0-3），空=不过滤 */
        Integer status,
        /** plan_date >= startDate，空=不过滤 */
        LocalDate startDate,
        /** plan_date <= endDate，空=不过滤 */
        LocalDate endDate,
        /** 任务范围 COMPANY/PERSONAL，空=COMPANY */
        String taskScope
) {
}
