/**
 * Fixed enums live only in the frontend (design.md §8.10).
 * The backend exposes no dictionary endpoints; the DDL CHECK constraints are the
 * single source of truth, these lists mirror them for typing and display.
 */
export const TASK_PRIORITY_OPTIONS = [
  { value: 0, label: 'P0' },
  { value: 1, label: 'P1' },
  { value: 2, label: 'P2' },
  { value: 3, label: 'P3' },
] as const;

export const TASK_STATUS_OPTIONS = [
  { value: 'TODO', label: '待办' },
  { value: 'DOING', label: '进行中' },
  { value: 'DONE', label: '已完成' },
] as const;

export const TIME_SOURCE_OPTIONS = [
  { value: 'MANUAL', label: '手动记录' },
  { value: 'TIMER', label: '计时器' },
] as const;

export const REPORT_TYPE_OPTIONS = [
  { value: 'DAILY', label: '日报' },
  { value: 'WEEKLY', label: '周报' },
] as const;

export const REPORT_STATUS_OPTIONS = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'FINAL', label: '定稿' },
] as const;

export const USER_ROLE_OPTIONS = [
  { value: 'USER', label: '普通用户' },
  { value: 'ADMIN', label: '管理员' },
] as const;

export const NOTIFICATION_TYPE_OPTIONS = [
  { value: 'DUE_REMIND', label: '到期提醒' },
  { value: 'DAILY_DIGEST', label: '每日摘要' },
  { value: 'WEEKLY_MISSING', label: '周报缺失' },
] as const;

export type TaskPriority = (typeof TASK_PRIORITY_OPTIONS)[number]['value'];
export type TaskStatus = (typeof TASK_STATUS_OPTIONS)[number]['value'];
export type TimeSource = (typeof TIME_SOURCE_OPTIONS)[number]['value'];
export type ReportType = (typeof REPORT_TYPE_OPTIONS)[number]['value'];
export type ReportStatus = (typeof REPORT_STATUS_OPTIONS)[number]['value'];
export type UserRole = (typeof USER_ROLE_OPTIONS)[number]['value'];
export type NotificationType = (typeof NOTIFICATION_TYPE_OPTIONS)[number]['value'];
