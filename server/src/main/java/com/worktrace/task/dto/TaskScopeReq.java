package com.worktrace.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 调整任务范围请求（PUT /api/tasks/{id}/scope）。
 *
 * <p>范围变更必须整棵子树一起迁移（顶层任务及其全部后代），子任务不能单独跨范围，
 * 因此走专用接口而不是普通更新。目标范围非法 → 400。
 */
public record TaskScopeReq(
        @NotBlank(message = "目标范围不能为空")
        @Pattern(regexp = "COMPANY|PERSONAL", message = "任务范围须为 COMPANY 或 PERSONAL")
        String scope
) {
}
