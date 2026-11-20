import { httpGet } from './request'

/** 后端任务行（BizTask 的 JSON 形状；TaskList 与 Home 共用） */
export interface TaskRow {
  id: number
  title: string
  status: number // 0待办 1进行中 2已完成 3已延期
  priority: number // 1低 2中 3高
  progress: number // 0-100
  planDate: string | null // YYYY-MM-DD
  actualHours: number | null
  parentId: number | null
  category: string | null
  isKey: number | null // 重点标记 1=重点 0=普通
  finishTime: string | null // ISO LocalDateTime，null = 未完成
  createTime: string | null
}

interface TaskPage {
  records: TaskRow[]
  total: number
}

/** 每页条数（与后端 TaskService 的 MAX_PAGE_SIZE 上限一致） */
export const TASKS_PAGE_SIZE = 500

/** 防御上限：最多拉 50 页（2.5 万条），超出截断并告警 */
const TASKS_MAX_PAGES = 50

/**
 * 分页循环拉取全部任务——修复"固定 size:500 只拉第一页、更多任务被静默遗漏"的问题。
 * 流程：首页探测 total → 并行拉取余下页 → 按 id 去重合并（防两页间数据漂移产生重复）；
 * 总数超过防御上限时截断并 console.warn。
 */
export async function fetchAllTaskRows(): Promise<TaskRow[]> {
  const first = await httpGet<TaskPage>('/tasks/page', { page: 1, size: TASKS_PAGE_SIZE })
  const byId = new Map<number, TaskRow>()
  for (const r of first.records ?? []) {
    if (r.id != null) byId.set(r.id, r)
  }
  const total = Math.max(first.total ?? 0, byId.size)
  const pages = Math.min(Math.ceil(total / TASKS_PAGE_SIZE), TASKS_MAX_PAGES)
  if (pages > 1) {
    console.warn(`任务共 ${total} 条，超过单页 ${TASKS_PAGE_SIZE} 条，分 ${pages} 页拉取`)
    const rest = await Promise.all(
      Array.from({ length: pages - 1 }, (_, i) =>
        httpGet<TaskPage>('/tasks/page', { page: i + 2, size: TASKS_PAGE_SIZE }),
      ),
    )
    for (const pg of rest) {
      for (const r of pg.records ?? []) {
        if (r.id != null && !byId.has(r.id)) byId.set(r.id, r)
      }
    }
    if (first.total != null && first.total > pages * TASKS_PAGE_SIZE) {
      console.warn(`任务总数 ${first.total} 超过防御上限 ${TASKS_MAX_PAGES} 页，已截断`)
    }
  }
  return [...byId.values()]
}
