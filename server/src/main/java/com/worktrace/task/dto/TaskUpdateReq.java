package com.worktrace.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 更新任务请求（PUT /api/tasks）。
 * <b>部分更新</b>：字段为空则不修改（MyBatis-Plus 按 NOT_NULL 策略生成 SET）。
 * id 必填；归属校验（id + user_id）在 Service 完成。
 */
public record TaskUpdateReq(
        @NotNull(message = "id 不能为空")
        Long id,

        String title,

        String content,

        @Min(value = 0, message = "状态取值 0-3")
        @Max(value = 3, message = "状态取值 0-3")
        Integer status,

        @Min(value = 1, message = "优先级取值 1-3")
        @Max(value = 3, message = "优先级取值 1-3")
        Integer priority,

        LocalDate planDate,

        BigDecimal actualHours
) {
}
