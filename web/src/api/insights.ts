import type { TaskRow } from './tasks'

/** 与服务端 InsightStatsVO 一致：待处理含已延期，未完成还包含进行中。 */
export interface InsightStats {
  date: string
  total: number
  done: number
  doing: number
  pending: number
  unfinished: number
  overdue: number
  highPending: number
  todayNew: number
  keyTasks: number
  completionRate: number
}

export interface AiInsight {
  headline: string
  para: string
  advice: string
  source: 'AI' | 'RULES'
  stats: InsightStats
}

type InsightTask = Pick<TaskRow, 'status' | 'priority' | 'isKey' | 'planDate' | 'createTime'>

/** API 与任务列表独立请求：快照不匹配时不能把旧文案与新卡片拼在一起展示。 */
export function isInsightCurrent(insight: AiInsight | null, tasks: readonly InsightTask[], now = new Date()): boolean {
  const s = insight?.stats
  if (!s) return false // 旧接口没有快照，也不能证明文案与当前卡片一致
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  if (s.date !== today) return false

  let done = 0
  let doing = 0
  let pending = 0
  let overdue = 0
  let highPending = 0
  let todayNew = 0
  let keyTasks = 0
  for (const t of tasks) {
    if (t.status === 2) done++
    if (t.status === 1) doing++
    if (t.status === 0 || t.status === 3) pending++
    if (t.status !== 2 && t.planDate && t.planDate < today) overdue++
    if (t.status !== 2 && t.priority === 3) highPending++
    if (t.createTime?.slice(0, 10) === today) todayNew++
    if (t.isKey === 1) keyTasks++
  }
  return s.total === tasks.length && s.done === done && s.doing === doing && s.pending === pending
    && s.unfinished === tasks.length - done && s.overdue === overdue && s.highPending === highPending
    && s.todayNew === todayNew && s.keyTasks === keyTasks
    && s.completionRate === (tasks.length ? Math.round(done * 100 / tasks.length) : 0)
}
