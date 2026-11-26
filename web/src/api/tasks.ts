import { httpGet, ApiError } from './request'

/** 任务范围：COMPANY 公司任务 / PERSONAL 个人任务（缺省 COMPANY，后端同口径） */
export type TaskScope = 'COMPANY' | 'PERSONAL'

export const SCOPE_COMPANY: TaskScope = 'COMPANY'
export const SCOPE_PERSONAL: TaskScope = 'PERSONAL'

export const SCOPE_OPTIONS: { label: string; value: TaskScope }[] = [
  { label: '公司任务', value: 'COMPANY' },
  { label: '个人任务', value: 'PERSONAL' },
]

export function scopeLabel(scope: TaskScope): string {
  return scope === 'PERSONAL' ? '个人任务' : '公司任务'
}

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
  taskScope: TaskScope // 任务范围（历史行后端兜底为 COMPANY）
  finishTime: string | null // ISO LocalDateTime，null = 未完成
  createTime: string | null
}

/** 游标分页响应（GET /tasks/scroll）：hasMore=true 时 nextCursor* 必同时非空 */
interface TaskScrollPage {
  records: TaskRow[]
  hasMore: boolean
  nextCursorTime: string | null
  nextCursorId: number | null
}

/** 每页条数（与后端 TaskService 的 MAX_PAGE_SIZE 上限一致） */
export const TASKS_PAGE_SIZE = 500

/**
 * 游标分页拉取指定范围的全部任务——后端 keyset（create_time DESC, id DESC 稳定排序），无页码上限，
 * 任务再多也不会像旧 offset 分页那样被页码钳制截断。
 *
 * <p>游标分页每页依赖上一页的游标，天然串行（在途请求恒为 1，对后端最温和）。
 * 终止条件只有后端 hasMore=false；任何异常一律整体抛错——调用方进入失败态，
 * 绝不把半截数据当全量返回：
 * <ul>
 *   <li>非首页出现任何已见 id → 重复页（游标未推进）→ 报错中止；</li>
 *   <li>hasMore=true 却缺游标 / 声称有下一页却返回空页 → 分页无法推进 → 报错中止。</li>
 * </ul>
 *
 * @param scope 任务范围；缺省 COMPANY（旧调用方不传 = 公司任务，与后端缺省一致）
 */
export async function fetchAllTaskRows(scope: TaskScope = SCOPE_COMPANY): Promise<TaskRow[]> {
  // Map 保插入序：吸收按页序进行，最终数组顺序与后端排序一致
  const byId = new Map<number, TaskRow>()
  const absorb = (rows: TaskRow[] | undefined): void => {
    for (const r of rows ?? []) {
      if (r.id != null && !byId.has(r.id)) byId.set(r.id, r)
    }
  }

  let cursorTime: string | null = null
  let cursorId: number | null = null
  let firstPage = true

  while (true) {
    const pg: TaskScrollPage = await httpGet<TaskScrollPage>('/tasks/scroll', {
      cursorTime,
      cursorId,
      size: TASKS_PAGE_SIZE,
      taskScope: scope,
    })
    const rows = pg.records ?? []

    // keyset 游标严格递进，下一页不可能重复交付已见数据；出现重复 = 后端游标异常
    if (!firstPage && rows.some((r) => r.id != null && byId.has(r.id))) {
      throw new ApiError(500, '任务分页出现重复数据（游标未推进），已中止加载')
    }
    if (rows.length === 0) {
      if (pg.hasMore) {
        throw new ApiError(500, '任务分页无法推进：后端声称还有更多但返回了空页')
      }
      break // 空页且无更多 = 正常结束
    }
    absorb(rows)

    if (!pg.hasMore) break
    if (pg.nextCursorTime == null || pg.nextCursorId == null) {
      throw new ApiError(500, '任务分页无法推进：后端未返回下一页游标')
    }
    cursorTime = pg.nextCursorTime
    cursorId = pg.nextCursorId
    firstPage = false
  }

  if (byId.size > TASKS_PAGE_SIZE) {
    console.info(`任务游标分页拉全量完成：共 ${byId.size} 条`)
  }
  return [...byId.values()]
}
