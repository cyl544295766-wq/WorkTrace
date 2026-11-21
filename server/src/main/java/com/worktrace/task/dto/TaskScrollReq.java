package com.worktrace.task.dto;

import java.time.LocalDateTime;

/**
 * 游标分页请求（GET /api/tasks/scroll，keyset 分页）。
 *
 * <p>cursorTime / cursorId = 上一页最后一条记录的 (create_time, id)，两者必须<b>同时</b>出现；
 * 同时为空 = 从头开始（取最新一页）。排序固定 create_time DESC, id DESC——id 兜底同秒创建的
 * 任务，保证游标推进严格有序（稳定排序，同 create_time 不会跨页重复或遗漏）。
 */
public record TaskScrollReq(
        /** 上一页最后一条的 create_time（ISO 格式），首页为 null */
        LocalDateTime cursorTime,
        /** 上一页最后一条的 id，首页为 null */
        Long cursorId,
        /** 单页条数，服务端钳制 1-500 */
        int size
) {
}
