package com.worktrace.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新增任务请求。
 * <b>不含 user_id</b> —— 归属用户一律来自登录会话（Controller 注入），防越权写入。
 */
public record TaskCreateReq(
        @NotBlank(message = "标题不能为空")
        @Size(max = 100, message = "标题最长 100 字")
        String title,

        String content,

        /** 0待办 1进行中 2已完成 3已延期；空则服务端默认 0 */
        @Min(value = 0, message = "状态取值 0-3")
        @Max(value = 3, message = "状态取值 0-3")
        Integer status,

        /** 进度 0-100；空则默认 0 */
        @Min(value = 0, message = "进度取值 0-100")
        @Max(value = 100, message = "进度取值 0-100")
        Integer progress,

        /** 1低 2中 3高；空则服务端默认 2 */
        @Min(value = 1, message = "优先级取值 1-3")
        @Max(value = 3, message = "优先级取值 1-3")
        Integer priority,

        /** 计划日期 YYYY-MM-DD，可空 */
        LocalDate planDate,

        /** 实际工时，可空 */
        BigDecimal actualHours,

        /** 父任务 id（空=顶层大任务）；非空时校验父任务必须属于当前用户，否则 400 */
        Long parentId,

        /** 业务分类 FEATURE/BUG/DATA/JOINT/DOC/OPS/OTHER，空则默认 OTHER */
        @Size(max = 32, message = "分类最长 32 字")
        String category,

        /** 重点标记 1=重点 0=普通；空则默认 0 */
        @Min(value = 0, message = "重点标记取值 0-1")
        @Max(value = 1, message = "重点标记取值 0-1")
        Integer isKey
) {
}
