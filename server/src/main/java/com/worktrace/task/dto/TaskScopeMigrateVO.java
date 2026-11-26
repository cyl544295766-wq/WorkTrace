package com.worktrace.task.dto;

/**
 * 范围迁移结果（PUT /api/tasks/{id}/scope）。
 *
 * @param id        被迁移的顶层任务 id
 * @param scope     迁移后的范围
 * @param migrated  实际被更新的任务数（含顶层与全部后代；已是目标范围时为 0）
 */
public record TaskScopeMigrateVO(Long id, String scope, int migrated) {
}
