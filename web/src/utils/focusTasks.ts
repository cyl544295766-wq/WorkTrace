import type { TaskRow } from '@/api/tasks'

type FocusTask = Pick<TaskRow, 'id' | 'isKey' | 'status' | 'priority' | 'planDate'>

export type DeadlineTone = 'overdue' | 'today' | 'upcoming' | 'unscheduled'

export interface TaskDeadline {
  label: string
  tone: DeadlineTone
  daysUntil: number | null
}

/** 使用日历日期的 UTC 序号计算天数，避免夏令时让一天变成 23 或 25 小时。 */
function calendarDay(value: string | null): number | null {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const milliseconds = Date.parse(`${value}T00:00:00Z`)
  if (!Number.isFinite(milliseconds) || new Date(milliseconds).toISOString().slice(0, 10) !== value) return null
  return milliseconds / 86_400_000
}

export function taskDeadline(planDate: string | null, today: string): TaskDeadline {
  const planned = calendarDay(planDate)
  const current = calendarDay(today)
  if (planned === null || current === null) {
    return { label: '未设置日期', tone: 'unscheduled', daysUntil: null }
  }
  const daysUntil = planned - current
  if (daysUntil < 0) return { label: `已逾期 ${-daysUntil} 天`, tone: 'overdue', daysUntil }
  if (daysUntil === 0) return { label: '今日到期', tone: 'today', daysUntil }
  if (daysUntil === 1) return { label: '明日到期', tone: 'upcoming', daysUntil }
  const date = planDate!
  return { label: date.slice(0, 4) === today.slice(0, 4) ? date.slice(5) : date, tone: 'upcoming', daysUntil }
}

const DEADLINE_ORDER: Record<DeadlineTone, number> = { overdue: 0, today: 1, upcoming: 2, unscheduled: 3 }

/** 仅生成首页视图，不改写任务状态、日期或重点标记。 */
export function buildFocusTasks<T extends FocusTask>(tasks: readonly T[], today: string) {
  const marked = tasks.filter(task => task.isKey === 1)
  const unfinished = marked.filter(task => task.status !== 2)
    .map(task => ({ ...task, deadline: taskDeadline(task.planDate, today) }))
    .sort((a, b) => DEADLINE_ORDER[a.deadline.tone] - DEADLINE_ORDER[b.deadline.tone]
      || (a.deadline.daysUntil ?? 0) - (b.deadline.daysUntil ?? 0)
      || b.priority - a.priority
      || a.id - b.id)
  const items = unfinished.slice(0, 3)
  return { total: marked.length, unfinished: unfinished.length, remaining: unfinished.length - items.length, items }
}
