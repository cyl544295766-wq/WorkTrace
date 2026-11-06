package com.worktrace.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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

        /** 进度 0-100（叶子任务手动维护；空=不修改/默认 0） */
        @Min(value = 0, message = "进度取值 0-100")
        @Max(value = 100, message = "进度取值 0-100")
        Integer progress,

        @Min(value = 1, message = "优先级取值 1-3")
        @Max(value = 3, message = "优先级取值 1-3")
        Integer priority,

        LocalDate planDate,

        BigDecimal actualHours,

        /** 业务分类（空=不修改；层级 parentId 创建后不可变，需换父请删除重建） */
        @Size(max = 32, message = "分类最长 32 字")
        String category
) {
}
