package com.worktrace.task.dto;

import com.worktrace.task.entity.BizTask;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 游标分页响应（GET /api/tasks/scroll）。
 *
 * <p>hasMore=true 时 nextCursorTime / nextCursorId 必同时非空（= 本页最后一条的排序键），
 * 前端凭它请求下一页；hasMore=false 时两者为 null，全量拉取结束。
 */
public record TaskScrollPage(
        /** 本页任务记录（字段形状与 /page 的 records 一致） */
        List<BizTask> records,
        /** 是否还有下一页 */
        boolean hasMore,
        /** 下一页游标：本页最后一条的 create_time，hasMore=false 时为 null */
        LocalDateTime nextCursorTime,
        /** 下一页游标：本页最后一条的 id，hasMore=false 时为 null */
        Long nextCursorId
) {
}
