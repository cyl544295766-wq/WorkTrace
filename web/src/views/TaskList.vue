<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
import { ArrowRight, Delete, Filter, Memo, Plus, Refresh, Search, Star, StarFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { httpDelete, httpGet, httpPost, httpPut } from '@/api/request'

type TaskStatus = 'todo' | 'doing' | 'done' | 'delayed'
type TaskPriority = 'low' | 'medium' | 'high'

/** 任务业务类型可选项（as const + 派生 value 类型，避免退化成 string） */
const TASK_CATEGORY_OPTIONS = [
  { value: 'FEATURE', label: '新增需求' },
  { value: 'BUG', label: 'bug修改' },
  { value: 'DATA', label: '数据处理' },
  { value: 'JOINT', label: '联调对接' },
  { value: 'DOC', label: '文档' },
  { value: 'OPS', label: '部署运维' },
  { value: 'OTHER', label: '其他' },
] as const

type TaskCategory = (typeof TASK_CATEGORY_OPTIONS)[number]['value']

interface Task {
  id: number
  title: string
  /** 已知枚举值（FEATURE/…/OTHER）或用户自定义文本（如"评审"）——后端存自由字符串 */
  category: string
  status: TaskStatus
  priority: TaskPriority
  progress: number // 0-100：叶子任务手动维护；父任务展示时按子任务均值派生
  planDate: string // YYYY-MM-DD
  hours: number
  parentId: number | null // NULL=顶层大任务（组树用）
  isKey: number // 重点标记 1=重点（首页「重点任务」卡） 0=普通
  finishTime: string // 完成时间（后端派生），'' = 未完成
  children: Task[]
}

/** 可内联编辑的字段（进度走滑块的 preview/commit 专用链路，不进内联编辑） */
type TaskField = 'title' | 'category' | 'status' | 'priority' | 'planDate'

/* ── 枚举显示 / 编辑器选项 ─────────────────── */
const STATUS_OPTIONS: { value: TaskStatus; label: string }[] = [
  { value: 'todo', label: '待办' },
  { value: 'doing', label: '进行中' },
  { value: 'done', label: '已完成' },
]
const PRIORITY_OPTIONS: { value: TaskPriority; label: string }[] = [
  { value: 'low', label: '低' },
  { value: 'medium', label: '中' },
  { value: 'high', label: '高' },
]
const STATUS_LABEL: Record<TaskStatus, string> = {
  todo: '待处理',
  doing: '进行中',
  done: '已完成',
  delayed: '已逾期',
}
const STATUS_TYPE: Record<TaskStatus, 'warning' | 'primary' | 'success' | 'danger'> = {
  todo: 'warning',
  doing: 'primary',
  done: 'success',
  delayed: 'danger',
}
const PRIORITY_LABEL: Record<TaskPriority, string> = { low: '低', medium: '中', high: '高' }
const PRIORITY_TYPE: Record<TaskPriority, 'info' | 'warning' | 'danger'> = {
  low: 'info',
  medium: 'warning',
  high: 'danger',
}
const CATEGORY_LABEL: Record<TaskCategory, string> = Object.fromEntries(
  TASK_CATEGORY_OPTIONS.map((o) => [o.value, o.label]),
) as Record<TaskCategory, string>
const CATEGORY_TYPE: Record<TaskCategory, 'primary' | 'success'> = {
  FEATURE: 'primary',
  BUG: 'success',
  DATA: 'primary',
  JOINT: 'success',
  DOC: 'primary',
  OPS: 'success',
  OTHER: 'primary',
}

/* ── 类型显示/编辑辅助：已知枚举走映射，自定义文本原样显示 ── */
function isKnownCategory(cat: string): boolean {
  return TASK_CATEGORY_OPTIONS.some((o) => o.value === cat)
}
function categoryLabel(cat: string): string {
  return (CATEGORY_LABEL as Record<string, string>)[cat] ?? cat
}
function categoryType(cat: string): 'primary' | 'success' {
  return (CATEGORY_TYPE as Record<string, 'primary' | 'success'>)[cat] ?? 'success'
}

/* ── 跟进记录类型（与后端 TaskLogService.ALLOWED_TYPES 对齐） ── */
const LOG_TYPE_OPTIONS = [
  { value: 'VERIFY', label: '找领导核实' },
  { value: 'NOTE', label: '备注' },
  { value: 'DEV', label: '开发' },
  { value: 'OTHER', label: '其他' },
] as const
type TaskLogType = (typeof LOG_TYPE_OPTIONS)[number]['value']
const LOG_TYPE_LABEL: Record<TaskLogType, string> = Object.fromEntries(
  LOG_TYPE_OPTIONS.map((o) => [o.value, o.label]),
) as Record<TaskLogType, string>
/** 时间线节点圆点颜色 */
const LOG_TYPE_COLOR: Record<TaskLogType, string> = {
  VERIFY: '#E6A23C',
  NOTE: '#909399',
  DEV: '#1FB6A6',
  OTHER: '#C0C4CC',
}

/** 跟进记录（GET /tasks/logs 返回行，createTime 为 ISO LocalDateTime） */
interface TaskLog {
  id: number
  taskId: number
  logType: TaskLogType
  content: string
  createTime: string
}

const router = useRouter()
function goReport(): void {
  router.push('/reports')
}

/* ── 小屏适配：窄视口自动收起次要列（≤1440 隐藏完成时间），
 * matchMedia 断点驱动，v-if 摘除列让 el-table 正常重排，不裁数据只收 UI ── */
const mqlHideFinish = window.matchMedia('(max-width: 1440px)')
const hideFinishCol = ref(mqlHideFinish.matches)
const syncBreaks = (): void => {
  hideFinishCol.value = mqlHideFinish.matches
}
syncBreaks()
mqlHideFinish.addEventListener('change', syncBreaks)
onBeforeUnmount(() => {
  mqlHideFinish.removeEventListener('change', syncBreaks)
})

/* ── 数据：真接口（GET /tasks/page 拉全量 → 前端按 parentId 组树 → 改动即时回写） ── */
const tasks = ref<Task[]>([])
const loading = ref(true)

/** 后端 BizTask 的 JSON 形状（status/priority 数字枚举，日期可空） */
interface ApiTask {
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
}

const STATUS_TO_API: Record<TaskStatus, number> = { todo: 0, doing: 1, done: 2, delayed: 3 }
const PRIORITY_TO_API: Record<TaskPriority, number> = { low: 1, medium: 2, high: 3 }

/** 后端行 → 视图模型（后端 3=已延期 前端暂无该展示态，归入待办；UI 也不会产生 3） */
function fromApi(r: ApiTask): Task {
  const status: TaskStatus =
    r.status === 1 ? 'doing' : r.status === 2 ? 'done' : r.status === 3 ? 'delayed' : 'todo'
  const priority: TaskPriority = r.priority === 1 ? 'low' : r.priority === 3 ? 'high' : 'medium'
  return {
    id: r.id,
    title: r.title,
    // 原样透传：已知枚举与自定义文本都保留（映射成 OTHER 会在下次回写时吞掉自定义值）
    category: r.category || 'OTHER',
    status,
    priority,
    progress: r.progress ?? 0,
    planDate: r.planDate ?? '',
    hours: r.actualHours ?? 0,
    parentId: r.parentId,
    isKey: r.isKey ?? 0,
    finishTime: r.finishTime ?? '',
    children: [],
  }
}

/** 完成时间显示：ISO LocalDateTime → YYYY-MM-DD HH:mm */
function fmtTime(s: string): string {
  return s ? s.replace('T', ' ').slice(0, 16) : ''
}

/** 可编辑字段序列化——快照 diff 用：任一字段变化即该行需回写 */
function serialize(t: Task): string {
  return [t.title, t.category, t.status, t.priority, t.progress, t.planDate, t.hours, t.isKey].join('')
}
function snapshot(): Map<number, string> {
  const m = new Map<number, string>()
  const walk = (list: Task[]) =>
    list.forEach((t) => {
      m.set(t.id, serialize(t))
      walk(t.children)
    })
  walk(tasks.value)
  return m
}

/**
 * 把本地变更回写服务端：只 PUT 快照之后有差异的行
 * （覆盖内联编辑、勾选级联出的父状态派生、删除后剩余父级的状态漂移）。
 * 回写失败 → 以服务端为准重建视图（错误 toast 已由 request.ts 统一弹）。
 */
async function persistDiff(before: Map<number, string>): Promise<void> {
  const changed: Task[] = []
  const walk = (list: Task[]) =>
    list.forEach((t) => {
      if (before.get(t.id) !== serialize(t)) changed.push(t)
      walk(t.children)
    })
  walk(tasks.value)
  if (changed.length === 0) return
  try {
    await Promise.all(
      changed.map(async (t) => {
        const upd = await httpPut<ApiTask>('/tasks', {
          id: t.id,
          title: t.title,
          category: t.category,
          status: STATUS_TO_API[t.status],
          priority: PRIORITY_TO_API[t.priority],
          progress: t.progress,
          planDate: t.planDate || null,
          actualHours: t.hours,
          isKey: t.isKey,
        })
        // 完成时间由后端派生（状态→已完成打戳/移出清空），回写本地视图即时可见
        t.finishTime = upd?.finishTime ?? ''
      }),
    )
  } catch {
    await loadTasks()
  }
}

/** 拉全量组树；parentId 指向缺失父级的行降级为顶层（不丢数据） */
async function loadTasks(): Promise<void> {
  loading.value = true
  try {
    const page = await httpGet<{ records: ApiTask[]; total: number }>('/tasks/page', {
      page: 1,
      size: 500, // 个人任务量级：一次拉全，前端组树/筛选/级联都在内存做
    })
    tasks.value = buildTree((page.records ?? []).map(fromApi))
    expandedIds.value = new Set() // 新数据默认全部收起
  } catch (e) {
    console.error('loadTasks failed', e)
    tasks.value = [] // 错误提示由 request.ts 统一处理
  } finally {
    loading.value = false
  }
}
/** 行深度表（id → 层级：根=0，子=1，孙=2…）：rowClassName 区分「子级 / 子孙级」样式用 */
const rowDepth = new Map<number, number>()

function buildTree(items: Task[]): Task[] {
  const byId = new Map(items.map((t) => [t.id, t]))
  const roots: Task[] = []
  for (const t of items) {
    const parent = t.parentId != null ? byId.get(t.parentId) : undefined
    if (parent && parent !== t) parent.children.push(t)
    else roots.push(t)
  }
  const walk = (list: Task[], depth: number) =>
    list.forEach((t) => {
      rowDepth.set(t.id, depth)
      walk(t.children, depth + 1)
    })
  walk(roots, 0)
  return roots
}
onMounted(loadTasks)

/* ── 顶部筛选：状态 + 日期范围 ── */
const statusFilter = ref('')
const dateRange = ref<string[] | null>(null)
const filteredTasks = computed(() =>
  tasks.value.filter((t) => {
    if (statusFilter.value && t.status !== statusFilter.value) return false
    const range = dateRange.value
    if (range && range.length === 2 && (t.planDate < range[0] || t.planDate > range[1])) return false
    return true
  }),
)
function resetFilter(): void {
  statusFilter.value = ''
  dateRange.value = null
}

/** 轻量统计条：基于筛选后的树统计 任务总数 / 子任务数 / 已完成数 */
const statSummary = computed(() => {
  let total = 0
  let sub = 0
  let done = 0
  const walk = (list: Task[]) =>
    list.forEach((t) => {
      total += 1
      if (t.parentId != null) sub += 1
      if (t.status === 'done') done += 1
      walk(t.children)
    })
  walk(filteredTasks.value)
  return { total, sub, done }
})

/* ── 统计卡片 / 状态 Tab / 搜索：纯展示层派生，不改动任何业务逻辑 ── */

/** 四张统计卡（全部/进行中/已完成/待处理），口径与 statSummary 一致 */
const statCards = computed(() => {
  const counts = { all: 0, doing: 0, done: 0, todo: 0 }
  const walk = (list: Task[]) =>
    list.forEach((t) => {
      counts.all += 1
      if (t.status === 'doing') counts.doing += 1
      else if (t.status === 'done') counts.done += 1
      else counts.todo += 1
      walk(t.children)
    })
  walk(filteredTasks.value)
  return [
    { label: '全部任务', value: counts.all, color: 'blue', hint: `含 ${statSummary.value.sub} 个子任务` },
    { label: '进行中', value: counts.doing, color: 'teal', hint: '正在推进' },
    { label: '已完成', value: counts.done, color: 'green', hint: '累计完成' },
    { label: '待处理', value: counts.todo, color: 'orange', hint: '需要关注' },
  ]
})

/** 状态 Tab 计数（全节点口径，与统计卡片一致） */
const tabCounts = computed(() => {
  const c = { all: 0, todo: 0, doing: 0, done: 0, delayed: 0 }
  const walk = (list: Task[]) =>
    list.forEach((t) => {
      c.all += 1
      if (t.status === 'doing') c.doing += 1
      else if (t.status === 'done') c.done += 1
      else if (t.status === 'delayed') c.delayed += 1
      else c.todo += 1
      walk(t.children)
    })
  walk(tasks.value)
  return c
})

/** Tab 定义（value 即现有 statusFilter 取值，行为零变化） */
const TAB_DEFS = [
  { value: '', label: '全部', key: 'all' },
  { value: 'todo', label: '待处理', key: 'todo' },
  { value: 'doing', label: '进行中', key: 'doing' },
  { value: 'done', label: '已完成', key: 'done' },
  { value: 'delayed', label: '已逾期', key: 'delayed' },
] as const

/** 搜索（纯展示过滤）：标题命中，或任一子任务标题命中（保持父节点可见） */
const searchText = ref('')
const visibleTasks = computed(() => {
  const q = searchText.value.trim().toLowerCase()
  if (!q) return filteredTasks.value
  const hit = (t: Task): boolean =>
    (t.title ?? '').toLowerCase().includes(q) || t.children.some(hit)
  return filteredTasks.value.filter(hit)
})

/** 列表右上「筛选」按钮：滚回筛选卡并聚焦首个控件（不隐藏任何字段） */
function jumpToFilter(): void {
  const el = document.querySelector('.filter-card')
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

/* ── 分页（纯展示层切片）：顶层任务分页，子任务随父行保留 ── */
const page = ref(1)
const pageSize = ref(10)
const pagedTasks = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return visibleTasks.value.slice(start, start + pageSize.value)
})
// 筛选/搜索变化时回到第一页（避免停留在空页）
watch([statusFilter, dateRange, searchText], () => {
  page.value = 1
})

/** 父任务已完成子任务数（进度徽标用） */
function doneCount(t: Task): number {
  return t.children.filter((c) => c.status === 'done').length
}

/** 任务进度（递归）：叶子=用户手动维护的 progress；父任务=子任务进度均值 */
function progressOf(t: Task): number {
  if (t.children.length === 0) return t.progress
  return Math.round(t.children.reduce((s, c) => s + progressOf(c), 0) / t.children.length)
}

/** 进度条颜色：青绿（规范色），完成转成功绿 */
function progressColor(pct: number): string {
  return pct >= 100 ? '#22C55E' : '#13B8A6'
}

/* ── 叶子任务进度滑块：拖动中本地预览，松手一次性回写（含状态联动） ──
 * el-slider 的 change 换算的是父组件 prop，因此 update:model-value 必须
 * 把值落进 row.progress（否则 change 拿到旧值、且预览会被打回）。
 * 预览已改行值，故拖动前先记快照基线，change 时用基线算 diff 才能 PUT 出去。
 */
let progressBaseline: Map<number, string> | null = null

function clampProgress(v: unknown): number {
  return Math.max(0, Math.min(100, Math.round(Number(v ?? 0))))
}

function previewProgress(row: Task, v: unknown): void {
  if (!progressBaseline) progressBaseline = snapshot() // 首个 update：拖动前基线
  row.progress = clampProgress(v)
}

async function commitProgress(row: Task, v: unknown): Promise<void> {
  const before = progressBaseline ?? snapshot()
  progressBaseline = null
  const p = clampProgress(v)
  row.progress = p
  if (p === 100 && row.status !== 'done') row.status = 'done' // 拖满 → 自动完成
  else if (p < 100 && row.status === 'done') row.status = 'doing' // 拖离 100 → 移出完成
  if (row.children.length === 0) syncParents(tasks.value) // 叶子：可能补全父级
  await persistDiff(before)
}

/* ── 跟进记录抽屉（工作留痕时间线） ── */
const logDrawer = ref(false)
const logTask = ref<Task | null>(null)
const logs = ref<TaskLog[]>([])
const logsLoading = ref(false)
const logForm = ref<{ logType: TaskLogType; content: string }>({ logType: 'NOTE', content: '' })

/** 打开某任务的跟进记录抽屉并拉取历史 */
function openLogs(t: Task): void {
  logTask.value = t
  logForm.value = { logType: 'NOTE', content: '' }
  logDrawer.value = true
  void loadLogs(t)
}

async function loadLogs(t: Task): Promise<void> {
  logsLoading.value = true
  try {
    logs.value = await httpGet<TaskLog[]>('/tasks/logs', { taskId: t.id })
  } catch {
    // 错误提示已由 request.ts 统一弹
  } finally {
    logsLoading.value = false
  }
}

async function addLog(): Promise<void> {
  const t = logTask.value
  if (!t) return
  const content = logForm.value.content.trim()
  if (!content) {
    ElMessage.warning('请填写跟进内容')
    return
  }
  const created = await httpPost<TaskLog>('/tasks/logs', {
    taskId: t.id,
    logType: logForm.value.logType,
    content,
  }).catch(() => null)
  if (!created) return
  logs.value.push(created)
  logForm.value.content = ''
  ElMessage.success('已记录')
}

async function removeLog(log: TaskLog): Promise<void> {
  try {
    await httpDelete(`/tasks/logs/${log.id}`)
  } catch {
    return // 失败已由 request.ts 弹错
  }
  logs.value = logs.value.filter((l) => l.id !== log.id)
  ElMessage.success('已删除')
}

/* ── 层级：子任务全完成 → 父任务自动勾选完成 ──
 * 派生只发生在子任务变化（勾选/新增/删除）时；
 * 父任务手动设置的状态（待办/进行中）不被覆盖 —— 否则父任务状态永远改不了（bug）。
 */
function syncParents(list: Task[]): void {
  for (const t of list) {
    if (t.children.length === 0) continue
    syncParents(t.children)
    const done = t.children.filter((c) => c.status === 'done').length
    if (done === t.children.length) {
      t.status = 'done' // 子任务全完成 → 父自动完成
    } else if (t.status === 'done') {
      t.status = 'doing' // 原已完成但子任务又有未完成的 → 回落进行中
    }
  }
}
function setLeaf(t: Task, done: boolean): void {
  if (t.children.length === 0) {
    t.status = done ? 'done' : 'todo'
    if (done) t.progress = 100 // 勾完成 → 进度拉满
    else if (t.progress === 100) t.progress = 0 // 取消完成 → 回到未开始（与待办一致）
    return
  }
  for (const c of t.children) setLeaf(c, done)
}
async function toggleDone(task: Task, done: boolean): Promise<void> {
  const before = snapshot()
  setLeaf(task, done)
  syncParents(tasks.value)
  await persistDiff(before) // 叶子与被派生的父级一并回写
  ElMessage.success(done ? '已标记为完成' : '已标记为未完成')
}

/** 重点标记切换：首页「重点任务」卡的数据源（走快照 diff 持久化） */
async function toggleKey(row: Task): Promise<void> {
  const before = snapshot()
  row.isKey = row.isKey === 1 ? 0 : 1
  await persistDiff(before)
  ElMessage.success(row.isKey === 1 ? '已设为重点，首页可见' : '已取消重点')
}
function findTask(id: number): Task | undefined {
  const walk = (list: Task[]): Task | undefined => {
    for (const t of list) {
      if (t.id === id) return t
      const hit = walk(t.children)
      if (hit) return hit
    }
    return undefined
  }
  return walk(tasks.value)
}

/** 行级样式：父 / 子级 / 子孙级（深度≥2）三层分层 + 已完成行整行淡出 */
function rowClassName({ row }: { row: Task }): string {
  const level =
    row.parentId == null
      ? 'row-parent'
      : `row-child${(rowDepth.get(row.id) ?? 1) >= 2 ? ' row-grand' : ''}`
  return row.status === 'done' ? `${level} row-done` : level
}

/* ── 树展开：EP 原生缩进/箭头渲染在首列（复选框列）且按深度溢出到标题列，
 * 深层箭头会被标题文字盖住（点击误触行内编辑）——隐藏之，
 * 由标题列自制的 tree-indent（深度×64px 缩进）+ tree-toggle 箭头接管 ── */
const expandedIds = ref(new Set<number>())
function toggleExpand(row: Task): void {
  const next = !expandedIds.value.has(row.id)
  if (next) expandedIds.value.add(row.id)
  else expandedIds.value.delete(row.id)
  tableRef.value?.toggleRowExpansion(row, next) // 显式传目标态，避免父子组件状态漂移
}

/* ── 行选中（判断"选中大任务 → 加子任务"） ── */
const tableRef = ref<TableInstance>()
const selectedId = ref<number | null>(null)
function onCurrentChange(row: Task | null): void {
  selectedId.value = row?.id ?? null
}

/* ── 内联编辑：点击单元格直接修改 ── */
const editing = ref<{ id: number; field: TaskField } | null>(null)
const titleDraft = ref('')
/** 类型编辑子态：false=下拉选已知类型；true=自定义输入框（选“其他”进入） */
const categoryInputMode = ref(false)
const categoryDraft = ref('')

function isEditing(row: Task, field: TaskField): boolean {
  return editing.value?.id === row.id && editing.value.field === field
}
function startEdit(row: Task, field: TaskField): void {
  if (editing.value?.id === row.id && editing.value.field === field) return
  editing.value = { id: row.id, field }
  if (field === 'title') titleDraft.value = String(row.title ?? '')
  if (field === 'category') {
    // 已知类型 → 下拉；纯“其他”或自定义文本 → 直接进输入框（自定义可就地改）
    categoryInputMode.value = !(isKnownCategory(row.category) && row.category !== 'OTHER')
    categoryDraft.value = categoryInputMode.value ? row.category : ''
  }
}

/** 下拉选类型：选“其他”转入自定义输入（预填原自定义文本便于修改），其余直接提交 */
function onCategoryPick(row: Task, v: unknown): void {
  const value = String(v ?? '')
  if (value === 'OTHER') {
    categoryInputMode.value = true
    categoryDraft.value = isKnownCategory(row.category) ? '' : row.category
    return
  }
  if (value) void commitField(row, 'category', value)
}
/** 输入框提交：空值回落为“其他”；enter 与 blur 连发靠 mode 守卫防重 */
async function commitCategoryCustom(row: Task): Promise<void> {
  if (!categoryInputMode.value) return
  const value = categoryDraft.value.trim() || 'OTHER'
  categoryInputMode.value = false
  await commitField(row, 'category', value)
}
/** 输入框 → 返回下拉（mousedown.prevent 挡掉 blur 提交，取消修改） */
function backToCategorySelect(): void {
  categoryDraft.value = ''
  categoryInputMode.value = false
}

/** 把编辑值写回任务字段并回写服务端（value 来自组件事件，在此按字段收窄类型） */
async function commitField(row: Task, field: TaskField, value: unknown): Promise<void> {
  const before = snapshot()
  switch (field) {
    case 'title': {
      const s = String(value ?? '').trim()
      if (s) row.title = s
      break
    }
    case 'category':
      // 自由字符串：已知枚举或自定义文本；空值兜底为 OTHER（显示“其他”）
      row.category = String(value ?? '').trim() || 'OTHER'
      break
    case 'status':
      row.status = value as TaskStatus
      if (row.status === 'done') row.progress = 100 // 勾完成 → 进度拉满
      else if (row.progress === 100) row.progress = 0 // 移出完成 → 避免待办/进行中却显示 100%
      break
    case 'priority':
      row.priority = value as TaskPriority
      break
    case 'planDate':
      row.planDate = String(value ?? '')
      break
  }
  editing.value = null
  // 父任务（有子节点）的编辑不触发派生：手动改的状态要保留；
  // 叶子（含子任务）状态变化可能补全父任务 → 派生
  if (row.children.length === 0) syncParents(tasks.value)
  await persistDiff(before) // 本行 + 派生父级（如状态改动后父级自动完成）
}

/** 今日本地日期（YYYY-MM-DD）：新任务默认计划日期 */
function today(): string {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

/* ── 新增：addTask(顶级) / addTask(选中行 id) 加为子任务（先 POST 落库拿真 id） ── */
async function addTask(parentId: number | null): Promise<void> {
  editing.value = null
  // 指定的父级可能已被删除 → 降级为顶层，不丢操作
  if (parentId != null && !findTask(parentId)) parentId = null

  const created = await httpPost<ApiTask>('/tasks', {
    title: '新任务', // @NotBlank 校验；立即进入标题编辑，用户可改
    category: 'FEATURE',
    status: 0,
    priority: 1, // 默认低优先级
    progress: 0, // 进度由用户后续手动拖动维护
    planDate: today(), // 默认计划日期 = 今日
    actualHours: 0,
    parentId,
    isKey: 0,
  }).catch(() => null)
  if (!created) return // 失败已由 request.ts 弹错

  const draft = fromApi(created)
  const parent = parentId != null ? findTask(parentId) : undefined
  let target: Task
  if (parent) {
    parent.children.unshift(draft)
    rowDepth.set(draft.id, (rowDepth.get(parent.id) ?? 0) + 1) // 新行深度 = 父 +1
    expandedIds.value.add(parent.id)
    tableRef.value?.toggleRowExpansion(parent, true) // 确保新子任务立即可见
    target = parent // 保持选中大任务，便于连续加多个子任务
  } else {
    tasks.value.unshift(draft)
    rowDepth.set(draft.id, 0)
    target = draft
  }
  // 新增叶子可能推翻父级已持久化的派生状态 → 快照已含新行，diff 只会回写漂移的父级
  const before = snapshot()
  syncParents(tasks.value)
  await persistDiff(before)

  selectedId.value = target.id
  tableRef.value?.setCurrentRow(target) // target 一定是顶层行，EP 可选中
  editing.value = { id: draft.id, field: 'title' } // 立即编辑新任务标题
  titleDraft.value = ''
}

async function handleDelete(row: Task): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除「${row.title || '未命名任务'}」及其子任务吗？`, '删除任务', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return // 取消
  }
  try {
    await httpDelete(`/tasks/${row.id}`) // 后端级联删除全部后代
  } catch {
    return // 失败已由 request.ts 弹错，本地视图不动
  }
  const before = snapshot()
  const walk = (list: Task[]): boolean => {
    const i = list.findIndex((t) => t.id === row.id)
    if (i !== -1) {
      list.splice(i, 1)
      return true
    }
    return list.some((t) => walk(t.children))
  }
  if (walk(tasks.value)) {
    if (selectedId.value === row.id) selectedId.value = null
    syncParents(tasks.value) // 被删节点的父级派生状态可能变化
    await persistDiff(before)
    ElMessage.success('已删除')
  }
}
</script>

<template>
  <div class="task-page">
    <div class="page-header">
      <div class="page-header__text">
        <h1>任务管理</h1>
        <p>
          点击单元格直接修改 · 选中任务后点「新增子任务」加为它的子任务
          <template v-if="selectedId != null">（当前选中：{{ findTask(selectedId)?.title || '未命名' }}）</template>
        </p>
      </div>
      <div class="page-header__actions">
        <el-button size="large" class="btn-ghost" @click="goReport">AI 生成报告</el-button>
        <el-button size="large" class="btn-accent" :icon="Plus" @click="addTask(null)">新增任务</el-button>
        <el-button
          size="large"
          class="btn-ghost"
          :icon="Plus"
          :disabled="selectedId == null"
          @click="addTask(selectedId)"
        >
          新增子任务
        </el-button>
      </div>
    </div>

    <el-card class="filter-card" shadow="never">
      <el-form inline @submit.prevent>
        <el-form-item label="任务状态">
          <el-select
            v-model="statusFilter"
            placeholder="全部状态"
            clearable
            style="width: 150px"
            @change="(v) => (statusFilter = v ?? '')"
          >
            <el-option v-for="o in STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="计划日期">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            range-separator="至"
            style="width: 270px"
          />
        </el-form-item>
        <el-form-item>
          <el-button class="btn-ghost" :icon="Refresh" @click="resetFilter">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 任务统计卡片：口径与原统计条一致（基于当前筛选结果的真实数据） -->
    <section class="stat-row">
      <div v-for="s in statCards" :key="s.label" class="stat-card">
        <div class="stat-card__label">{{ s.label }}</div>
        <div class="stat-card__value" :class="`is-${s.color}`">{{ s.value }}</div>
        <div class="stat-card__hint">{{ s.hint }}</div>
      </div>
    </section>

    <!-- 任务树：默认只显示大任务；点击单元格内联编辑 -->
    <el-card v-loading="loading" shadow="never">
      <div class="list-head">
        <div class="list-tabs" role="tablist">
          <button
            v-for="tab in TAB_DEFS"
            :key="tab.value"
            type="button"
            class="list-tab"
            :class="{ 'is-active': statusFilter === tab.value }"
            @click="statusFilter = tab.value"
          >
            {{ tab.label }}
            <span class="list-tab__count">{{ tabCounts[tab.key] }}</span>
          </button>
        </div>
        <div class="list-tools">
          <el-input
            v-model="searchText"
            class="list-search"
            placeholder="搜索任务标题"
            :prefix-icon="Search"
            clearable
          />
          <el-button class="btn-ghost" :icon="Filter" @click="jumpToFilter">筛选</el-button>
        </div>
      </div>
      <div class="table-meta">
        共 <span class="table-meta__num">{{ statSummary.total }}</span> 个任务（含
        <span class="table-meta__num">{{ statSummary.sub }}</span> 个子任务）· 已完成
        <span class="table-meta__num table-meta__num--done">{{ statSummary.done }}</span>
      </div>

      <el-table
        ref="tableRef"
        :data="pagedTasks"
        row-key="id"
        :tree-props="{ children: 'children' }"
        :row-class-name="rowClassName"
        highlight-current-row
        style="width: 100%"
        @current-change="onCurrentChange"
      >
        <el-table-column label="完成" width="64" align="center">
          <template #default="{ row }">
            <el-checkbox
              :model-value="row.status === 'done'"
              @change="(checked) => toggleDone(row as Task, !!checked)"
            />
          </template>
        </el-table-column>

        <!-- 标题：内联文本编辑（父任务：状态色条+加粗+进度徽标；子任务：肘线引导+降级字重） -->
        <el-table-column label="任务标题" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <el-input
              v-if="isEditing(row as Task, 'title')"
              v-model="titleDraft"
              size="small"
              placeholder="输入任务标题"
              :style="{ marginLeft: `${(rowDepth.get((row as Task).id) ?? 0) * 64}px` }"
              @update:model-value="(v) => (titleDraft = String(v))"
              @keyup.enter="commitField(row as Task, 'title', titleDraft)"
              @blur="commitField(row as Task, 'title', titleDraft)"
            />
            <div
              v-else
              class="cell-editable task-title-wrap"
              :class="{ 'is-done': row.status === 'done' }"
              @click="startEdit(row as Task, 'title')"
            >
              <div class="task-title">
                <!-- 层级缩进占位：深度 × 64px（EP 原生缩进渲染在首列、不位移标题，此处自制） -->
                <span class="tree-indent" :style="{ width: `${(rowDepth.get((row as Task).id) ?? 0) * 64}px` }" />
                <!-- 展开箭头：有子任务才显示（EP 原生箭头深层会被标题盖住，自制接管） -->
                <span
                  v-if="(row as Task).children.length > 0"
                  class="tree-toggle"
                  :class="{ 'is-expanded': expandedIds.has((row as Task).id) }"
                  @click.stop="toggleExpand(row as Task)"
                >
                  <el-icon :size="12"><ArrowRight /></el-icon>
                </span>
                <!-- 重点星标：「设重点」后显示（首页「重点任务」卡同源数据） -->
                <el-icon v-if="(row as Task).isKey === 1" class="task-title__star"><StarFilled /></el-icon>
                <!-- 子任务：层级引导肘线；父任务：状态色条 -->
                <span v-if="(row as Task).parentId != null" class="task-title__prefix" />
                <span v-else class="task-title__bar" :class="`is-${row.status}`" />
                <span class="task-title__text">{{ row.title || '点击输入标题' }}</span>
                <el-tag
                  v-if="(row as Task).children.length > 0"
                  class="task-title__count"
                  size="small"
                  :type="doneCount(row as Task) === (row as Task).children.length ? 'success' : 'info'"
                  effect="plain"
                  round
                >
                  {{ doneCount(row as Task) }}/{{ (row as Task).children.length }} 子任务
                </el-tag>
              </div>
            </div>
          </template>
        </el-table-column>

        <!-- 类型：已知类型下拉；选“其他”→ 自定义输入框（可输自定义类型并显示） -->
        <el-table-column label="类型" width="104" align="center" class-name="col-category">
          <template #default="{ row }">
            <el-input
              v-if="isEditing(row as Task, 'category') && categoryInputMode"
              v-model="categoryDraft"
              size="small"
              maxlength="32"
              placeholder="输入类型名称"
              @keyup.enter="commitCategoryCustom(row as Task)"
              @blur="commitCategoryCustom(row as Task)"
              @click.stop
            >
              <template #append>
                <el-button @mousedown.prevent @click="backToCategorySelect">选类型</el-button>
              </template>
            </el-input>
            <el-select
              v-else-if="isEditing(row as Task, 'category')"
              :model-value="row.category"
              size="small"
              @update:model-value="(v) => onCategoryPick(row as Task, v)"
              @click.stop
            >
              <el-option v-for="o in TASK_CATEGORY_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
              <!-- 当前是自定义文本：合成一项让它可被选中显示（借此可切回已知类型） -->
              <el-option
                v-if="row.category && !isKnownCategory(row.category)"
                :value="row.category"
                :label="row.category"
              />
            </el-select>
            <el-tag
              v-else
              class="cell-editable"
              :type="categoryType(row.category)"
              @click="startEdit(row as Task, 'category')"
            >
              {{ categoryLabel(row.category) }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 状态 -->
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-select
              v-if="isEditing(row as Task, 'status')"
              :model-value="row.status"
              size="small"
              @update:model-value="(v) => commitField(row as Task, 'status', v)"
              @click.stop
            >
              <el-option v-for="o in STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
              <!-- 当前值不在下拉集中（如已逾期）：合成一项保证能显示、可原样提交 -->
              <el-option
                v-if="row.status && !STATUS_OPTIONS.some((o) => o.value === row.status)"
                :value="row.status"
                :label="STATUS_LABEL[row.status as TaskStatus]"
              />
            </el-select>
            <el-tag v-else class="cell-editable" :type="STATUS_TYPE[row.status as TaskStatus]" @click="startEdit(row as Task, 'status')">
              {{ STATUS_LABEL[row.status as TaskStatus] }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 优先级 -->
        <el-table-column label="优先级" width="88" align="center">
          <template #default="{ row }">
            <el-select
              v-if="isEditing(row as Task, 'priority')"
              :model-value="row.priority"
              size="small"
              @update:model-value="(v) => commitField(row as Task, 'priority', v)"
              @click.stop
            >
              <el-option v-for="o in PRIORITY_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-tag v-else class="cell-editable" :type="PRIORITY_TYPE[row.priority as TaskPriority]" @click="startEdit(row as Task, 'priority')">
              {{ PRIORITY_LABEL[row.priority as TaskPriority] }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 进度：叶子=手动滑块（用户直接拖），父任务=子任务进度均值派生 -->
        <el-table-column label="进度" width="132" align="center">
          <template #default="{ row }">
            <el-slider
              v-if="(row as Task).children.length === 0"
              class="progress-slider"
              :model-value="row.progress"
              :min="0"
              :max="100"
              :step="5"
              size="small"
              @update:model-value="(v) => previewProgress(row as Task, v)"
              @change="(v) => commitProgress(row as Task, v)"
            />
            <div v-else class="progress-derived">
              <el-progress
                :percentage="progressOf(row as Task)"
                :stroke-width="6"
                :color="progressColor"
              />
            </div>
          </template>
        </el-table-column>

        <!-- 计划日期 -->
        <el-table-column label="计划日期" width="128" align="center">
          <template #default="{ row }">
            <el-date-picker
              v-if="isEditing(row as Task, 'planDate')"
              :model-value="row.planDate"
              type="date"
              value-format="YYYY-MM-DD"
              size="small"
              style="width: 130px"
              @update:model-value="(v) => commitField(row as Task, 'planDate', v)"
              @click.stop
            />
            <span v-else class="cell-editable" @click="startEdit(row as Task, 'planDate')">
              {{ row.planDate || '—' }}
            </span>
          </template>
        </el-table-column>

        <!-- 完成时间：后端派生（状态→已完成时打戳，移出清空），只读展示；窄视口收起 -->
        <el-table-column v-if="!hideFinishCol" label="完成时间" width="140" align="center">
          <template #default="{ row }">
            <span :class="{ 'finish-time': (row as Task).finishTime }">
              {{ fmtTime((row as Task).finishTime) || '—' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="212" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              class="btn-key"
              :class="{ 'is-key': (row as Task).isKey === 1 }"
              :icon="(row as Task).isKey === 1 ? StarFilled : Star"
              @click="toggleKey(row as Task)"
            >
              {{ (row as Task).isKey === 1 ? '取消重点' : '设重点' }}
            </el-button>
            <el-button link type="primary" :icon="Memo" @click="openLogs(row as Task)">记录</el-button>
            <el-button link class="btn-delete" :icon="Delete" @click="handleDelete(row as Task)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无任务，点击右上角「新增任务」创建" :image-size="80" />
        </template>
      </el-table>

      <!-- 分页：顶层任务分页，子任务随父行展开；纯展示切片，不改任何业务数据 -->
      <div class="pagination">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="visibleTasks.length"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          size="small"
        />
      </div>
    </el-card>

    <!-- 跟进记录抽屉：工作留痕时间线 -->
    <el-drawer v-model="logDrawer" size="440px" :title="`跟进记录 · ${logTask?.title || '未命名任务'}`">
      <div class="log-form">
        <el-select v-model="logForm.logType" style="width: 150px">
          <el-option v-for="o in LOG_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-input
          v-model="logForm.content"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          resize="none"
          placeholder="记录跟进内容…"
        />
        <el-button type="primary" @click="addLog">保存记录</el-button>
      </div>

      <el-divider />

      <div v-loading="logsLoading">
        <el-timeline v-if="logs.length > 0" class="log-timeline">
          <el-timeline-item
            v-for="l in logs"
            :key="l.id"
            :timestamp="fmtTime(l.createTime)"
            :color="LOG_TYPE_COLOR[l.logType]"
            placement="top"
          >
            <div class="log-item">
              <el-tag size="small" effect="plain" :color="LOG_TYPE_COLOR[l.logType]" class="log-item__type">
                {{ LOG_TYPE_LABEL[l.logType] }}
              </el-tag>
              <span class="log-item__content">{{ l.content }}</span>
              <el-button link class="btn-delete" :icon="Delete" size="small" @click="removeLog(l)" />
            </div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无跟进记录，上方写下第一条" :image-size="60" />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.task-page {
  /* 品牌色板（与首页一致）：品牌蓝 / 主操作青绿 / 白灰底 */
  --wt-blue: #3988e8;
  --wt-teal: #13b8a6;
  --wt-green: #22c55e;
  --wt-orange: #d99543;
  --wt-border: #e5eaf0;
  --wt-text: #25364a;
  --wt-muted: #8796a8;

  --el-color-primary: #3988e8;
  --el-color-primary-light-3: color-mix(in srgb, #3988e8 70%, #fff);
  --el-color-primary-light-5: color-mix(in srgb, #3988e8 50%, #fff);
  --el-color-primary-light-7: color-mix(in srgb, #3988e8 30%, #fff);
  --el-color-primary-light-8: color-mix(in srgb, #3988e8 20%, #fff);
  --el-color-primary-light-9: color-mix(in srgb, #3988e8 10%, #fff);
  --el-color-primary-dark-2: color-mix(in srgb, #3988e8 80%, #000);
  /* 表格悬停极浅蓝、行分隔线弱化 */
  --el-table-row-hover-bg-color: #f2f7ff;
  --el-table-border-color: #eef1f6;

  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 24px;
  min-height: 100vh;
  box-sizing: border-box;
  background: #f2f4f7;
}

.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.page-header__text h1 {
  margin: 0;
  color: var(--wt-text);
  font-size: 24px;
  font-weight: 800;
  letter-spacing: 0.5px;
}

.page-header__text p {
  margin: 8px 0 0;
  color: var(--wt-muted);
  font-size: 13px;
}

.page-header__actions {
  display: flex;
  gap: 12px;
}

/* flex + gap 下抵消 EP 相邻按钮的默认 margin，避免间距叠加 */
.page-header__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

/* 主操作：青绿实底；次要操作：白底浅边框——统一高度与圆角 */
.btn-accent {
  height: 40px;
  padding: 0 20px;
  border: 1px solid var(--wt-teal);
  border-radius: 10px;
  background: var(--wt-teal);
  color: #fff;
  font-weight: 600;
}

.btn-accent:hover,
.btn-accent:focus {
  border-color: #0f9c8c;
  background: #0f9c8c;
  color: #fff;
}

.btn-ghost {
  height: 40px;
  padding: 0 20px;
  border: 1px solid var(--wt-border);
  border-radius: 10px;
  background: #fff;
  color: var(--wt-text);
  font-weight: 500;
}

.btn-ghost:hover,
.btn-ghost:focus {
  border-color: var(--wt-blue);
  color: var(--wt-blue);
}

/* ── 筛选区：独立白卡，控件统一高度/圆角/浅灰描边，聚焦品牌蓝 ── */
.filter-card {
  border: 1px solid var(--wt-border);
  border-radius: 14px;
  box-shadow: 0 1px 2px rgba(37, 54, 74, 0.03);
}

.filter-card :deep(.el-form-item) {
  margin: 0 24px 0 0;
}

.filter-card :deep(.el-form-item__label) {
  color: var(--wt-muted);
  font-size: 13px;
}

.filter-card :deep(.el-input__wrapper),
.filter-card :deep(.el-select__wrapper) {
  min-height: 38px;
  border: 1px solid var(--wt-border);
  border-radius: 8px;
  box-shadow: none;
}

.filter-card :deep(.el-input__wrapper:hover),
.filter-card :deep(.el-select__wrapper:hover),
/* 聚焦品牌蓝描边（EP 聚焦内阴影已被上方 box-shadow:none 收掉，由边框承担反馈） */
.filter-card :deep(.el-input__wrapper.is-focus),
.filter-card :deep(.el-select__wrapper.is-focused) {
  border-color: var(--wt-blue);
}

/* 统一筛选区控件高度：下拉框 / 日期选择器 / 重置按钮同 38px */
.filter-card :deep(.el-button) {
  height: 38px;
}

/* ── 任务统计卡片 ── */
.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-card {
  padding: 16px 20px;
  border: 1px solid var(--wt-border);
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(37, 54, 74, 0.03);
}

.stat-card__label {
  color: var(--wt-muted);
  font-size: 13px;
}

.stat-card__value {
  margin: 4px 0;
  font-size: 28px;
  font-weight: 800;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
}

.stat-card__value.is-blue {
  color: var(--wt-blue);
}

.stat-card__value.is-teal {
  color: var(--wt-teal);
}

.stat-card__value.is-green {
  color: var(--wt-green);
}

.stat-card__value.is-orange {
  color: var(--wt-orange);
}

.stat-card__hint {
  color: var(--wt-muted);
  font-size: 12px;
}

/* ── 列表头：状态 Tab（品牌蓝文字 + 底部指示线）+ 右侧搜索/筛选 ── */
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}

.list-tabs {
  display: flex;
  gap: 24px;
}

.list-tab {
  position: relative;
  padding: 8px 2px 10px;
  border: 0;
  border-bottom: 3px solid transparent;
  background: none;
  color: var(--wt-muted);
  font-size: 14px;
  cursor: pointer;
  transition: color 0.15s;
}

.list-tab:hover {
  color: var(--wt-blue);
}

.list-tab.is-active {
  border-bottom-color: var(--wt-blue);
  color: var(--wt-blue);
  font-weight: 600;
}

.list-tab__count {
  display: inline-block;
  min-width: 22px;
  margin-left: 6px;
  padding: 1px 7px;
  border-radius: 999px;
  background: #f2f4f7;
  color: var(--wt-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.list-tab.is-active .list-tab__count {
  background: var(--el-color-primary-light-9);
  color: var(--wt-blue);
}

.list-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}

.list-search {
  width: 240px;
}

.list-search :deep(.el-input__wrapper) {
  min-height: 38px;
  border: 1px solid var(--wt-border);
  border-radius: 8px;
  box-shadow: none;
}

.list-search :deep(.el-input__wrapper:hover),
.list-search :deep(.el-input__wrapper.is-focus) {
  border-color: var(--wt-blue);
}

.list-tools .btn-ghost {
  height: 38px;
  padding: 0 14px;
}

.table-meta {
  margin-bottom: 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.table-meta__num {
  color: var(--el-color-primary);
  font-weight: 600;
}

.table-meta__num--done {
  color: var(--el-color-success);
}

/* 完成时间：有值时用成功色，与状态徽标呼应 */
.finish-time {
  color: var(--el-color-success);
  font-variant-numeric: tabular-nums;
}

.cell-editable {
  display: inline-block;
  min-width: 40px;
  padding: 2px 6px;
  margin: -2px -6px;
  border-radius: 4px;
  cursor: text;
}

.cell-editable:hover {
  background: var(--el-fill-color);
}

.is-done {
  color: var(--el-text-color-placeholder);
  text-decoration: line-through;
}

/* ── 子任务层级感 ─────────────────────────────
 * 父行：浅底色 + 标题加粗 + 状态色条 + 进度徽标；
 * 子行：白底 + 降级字色字号 + CSS 肘线引导。
 * 树形缩进由 el-table 的 tree-props + :indent 提供。
 */

/* ── 分层：父行=浅蓝底 + 左缘品牌蓝条（视觉上的“分组条”）；子行=白底 + 更深缩进 ── */
:deep(.el-table .el-table__row.row-parent > td.el-table__cell) {
  background-color: #dfeaf8;
}

/* 左缘品牌蓝条打在首列上，随行纵向贯穿 */
:deep(.el-table .el-table__row.row-parent > td.el-table__cell:first-child) {
  box-shadow: inset 4px 0 0 var(--wt-blue);
}

/* 父行 hover 深一档（全局 hover #f2f7ff 比父行底色浅，需单独覆盖才可见） */
:deep(.el-table .el-table__row.row-parent:hover > td.el-table__cell) {
  background-color: #d3e4f6;
}

/* 当前选中高亮：比父行底色明显更深，置于 hover 之后（同特异性后者胜） */
:deep(.el-table .el-table__row.row-parent.current-row > td.el-table__cell) {
  background-color: #c8ddf3;
}

/* 父任务标题：加粗、字号略大（分组头质感） */
:deep(.el-table .row-parent .task-title__text) {
  font-weight: 700;
  font-size: 14px;
}

/* 子任务标题：降饱和、略小 */
:deep(.el-table .row-child .task-title__text) {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

/* ── 子孙级（深度≥2）：与直接子级拉开明显区隔 ──
 * 可见浅蓝底 + 左缘浅蓝条 + 标题降一档 + 引导线更淡——越深层越“退后” */
:deep(.el-table .el-table__row.row-grand > td.el-table__cell) {
  background-color: #eaf1f9;
}

/* 左缘浅蓝条：比父行的品牌蓝条浅一档，标记“深层区” */
:deep(.el-table .el-table__row.row-grand > td.el-table__cell:first-child) {
  box-shadow: inset 3px 0 0 #b5d0ec;
}

:deep(.el-table .el-table__row.row-grand:hover > td.el-table__cell) {
  background-color: #dfeaf6;
}

:deep(.el-table .row-grand .task-title__text) {
  color: #7e8ea0;
  font-weight: 400;
}

:deep(.el-table .row-grand .task-title__prefix) {
  border-color: #ccd9e6;
}

/* 已完成任务标题：删除线优先级最高 */
:deep(.el-table .task-title-wrap.is-done .task-title__text) {
  color: var(--el-text-color-placeholder);
  text-decoration: line-through;
}

/* 已完成行整行淡出（跳过勾选列，保持可点） */
:deep(.el-table .el-table__row.row-done > td.el-table__cell:not(:first-child) .cell) {
  opacity: 0.55;
}

/* 子任务：全高树形引导线——竖线顶满行高，同层兄弟 x 对齐连成“主干”，横臂指向标题（经典文件树视觉） */
.task-title__prefix {
  box-sizing: border-box;
  flex-shrink: 0;
  align-self: stretch;
  width: 12px;
  /* 上下顶满行高（抵消子行 9px 垂直 padding），竖线贯穿行间 */
  margin: -9px 8px -9px 0;
  border-left: 2px solid #b9c8d9;
  border-bottom: 2px solid #b9c8d9;
}

/* 父任务状态色条：颜色跟随任务状态 */
.task-title__bar {
  flex-shrink: 0;
  width: 4px;
  height: 14px;
  margin-right: 8px;
  border-radius: 2px;
}

.task-title__bar.is-todo {
  background: var(--el-color-info-light-3);
}

.task-title__bar.is-doing {
  background: var(--el-color-primary);
}

.task-title__bar.is-done {
  background: var(--el-color-success);
}

/* 进度徽标（完成数/总数）：不参与换行截断 */
.task-title__count {
  flex-shrink: 0;
  margin-left: 8px;
  font-weight: 400;
}

/* ── 密度与交互细节 ──────────────────────── */

:deep(.el-table) {
  font-size: 13px;
}

/* 表头：浅灰底 + 弱化文字 */
:deep(.el-table th.el-table__cell) {
  background: #f7f9fc;
  color: var(--wt-muted);
  font-size: 12px;
  font-weight: 600;
}

:deep(.el-table .el-table__cell) {
  padding: 12px 0;
}

/* 分层行距：父行更舒展、子行更紧凑（强化“组与成员”的视觉差） */
:deep(.el-table .el-table__row.row-parent .el-table__cell) {
  padding: 14px 0;
}

:deep(.el-table .el-table__row.row-child .el-table__cell) {
  padding: 9px 0;
}

/* 行间浅灰细分隔线 */
:deep(.el-table td.el-table__cell) {
  border-bottom: 1px solid #eef1f6;
}

/* 标题外辅助信息弱化 */
:deep(.el-table .el-table__cell .cell) {
  color: var(--wt-text);
}

/* EP 的树缩进/箭头渲染在首列（复选框列）且按深度向右溢出到标题列，
   深层箭头会被标题文字盖住（真实点击落不到箭头上，反而误触行内编辑）。
   隐藏之，由标题列自制的 tree-indent（深度×64px）+ tree-toggle 接管缩进与展开 */
:deep(.el-table__expand-icon),
:deep(.el-table__indent) {
  display: none !important;
}

/* 自制展开箭头：紧跟缩进占位，展开时旋转 90° 朝下 */
.tree-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  margin-right: 4px;
  border-radius: 4px;
  color: var(--wt-muted);
  cursor: pointer;
  transition: transform 0.15s, background-color 0.15s, color 0.15s;
}

.tree-toggle:hover {
  background-color: var(--el-fill-color);
  color: var(--wt-blue);
}

.tree-toggle.is-expanded {
  transform: rotate(90deg);
  color: var(--wt-blue);
}

/* 缩进占位：宽度由模板按行深度内联给定 */
.tree-indent {
  flex-shrink: 0;
}

/* 删除按钮降噪：默认灰，hover 才变红 */
:deep(.btn-delete) {
  color: var(--el-text-color-secondary);
}

:deep(.btn-delete:hover) {
  color: var(--el-color-danger);
}

/* 重点按钮：默认灰，已标记时橙色星+加粗（与删除的降噪风格一致） */
:deep(.btn-key) {
  color: var(--el-text-color-secondary);
}

:deep(.btn-key:hover) {
  color: var(--wt-orange);
}

:deep(.btn-key.is-key) {
  color: var(--wt-orange);
  font-weight: 600;
}

/* 标题前的重点星标 */
.task-title__star {
  flex-shrink: 0;
  margin-right: 4px;
  color: var(--wt-orange);
}

/* 操作列三个文字按钮间距收紧（列宽 212 内放下） */
:deep(.el-table .el-table__cell .el-button + .el-button) {
  margin-left: 6px;
}

/* 小屏：页边距收窄，给表格让出宽度 */
@media (max-width: 1280px) {
  .task-page {
    padding: 16px;
  }
}

/* 标题外层：点击编辑热区 + hover 底色 */
.task-title-wrap {
  padding: 2px 6px;
  margin: -2px -6px;
  border-radius: 4px;
  cursor: text;
  text-align: left;
}

/* 标题行：flex 对齐色条/肘线/文本/徽标，文本超长省略、徽标永不截断 */
.task-title {
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
}

.task-title__text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 进度列：叶子手动滑块（青绿）/ 父任务派生进度条 */
.progress-slider {
  padding: 0 10px;
}

/* 进度条使用青绿色（规范色） */
.progress-slider :deep(.el-slider__bar) {
  background-color: var(--wt-teal);
}

.progress-slider :deep(.el-slider__button) {
  border-color: var(--wt-teal);
}

.progress-derived {
  padding: 0 4px;
}

:deep(.el-progress-bar__inner) {
  background-color: var(--wt-teal);
}

:deep(.el-progress.is-success .el-progress-bar__inner) {
  background-color: var(--wt-green);
}

/* ── 标签：现代轻量风 ──
 * 状态/优先级：药丸形 + 同色圆点；类型列：圆角芯片（分类不是状态，无点）。
 * 浅底精确色 + 同色加深文字 + 同色细描边——在父/孙行的浅底色上也读得清。
 * plain 徽标（子任务计数）与带内联 color 的跟进类型标签不受影响 */
/* cell-editable 的 inline-block 会覆写 EP 的 inline-flex，导致药丸内文字/圆点不居中——显式恢复 */
:deep(.el-tag) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 22px;
  padding: 0 9px;
  border: 0;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
}

:deep(.el-tag:not(.el-tag--plain))::before {
  content: '';
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  margin-right: 5px;
  border-radius: 999px;
  background: currentColor;
}

/* 类型列：方圆角芯片、无圆点（tag 元素在本组件模板内，普通 scoped 选择器即可命中） */
.col-category .el-tag {
  border-radius: 6px;
}

.col-category .el-tag::before {
  display: none;
}

/* 白底芯片：彩色浅底压在父行蓝带/孙行浅蓝底上会色叠色出“错层”，
   改白底 + 同色细描边 + 圆点 + 加深文字，任何行底色上都干净 */
:deep(.el-tag--primary:not(.el-tag--plain)) {
  background: #fff;
  color: #2e71c2;
  box-shadow: inset 0 0 0 1px rgb(46 113 194 / 32%);
}

:deep(.el-tag--success:not(.el-tag--plain)) {
  background: #fff;
  color: #0e9f6e;
  box-shadow: inset 0 0 0 1px rgb(14 159 110 / 34%);
}

:deep(.el-tag--warning:not(.el-tag--plain)) {
  background: #fff;
  color: #c97a1f;
  box-shadow: inset 0 0 0 1px rgb(201 122 31 / 34%);
}

:deep(.el-tag--danger:not(.el-tag--plain)) {
  background: #fff;
  color: #d93843;
  box-shadow: inset 0 0 0 1px rgb(217 56 67 / 32%);
}

:deep(.el-tag--info:not(.el-tag--plain)) {
  background: #fff;
  color: #64748b;
  box-shadow: inset 0 0 0 1px rgb(100 116 139 / 32%);
}

/* ── 分页区：品牌蓝当前页，白底浅灰边框页码 ── */
.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.pagination :deep(.el-pagination) {
  --el-pagination-bg-color: transparent;
  --el-pagination-button-width: 32px;
  --el-pagination-button-height: 32px;
  gap: 6px;
}

.pagination :deep(.el-pager li) {
  min-width: 32px;
  height: 32px;
  border: 1px solid var(--wt-border);
  border-radius: 8px;
  background: #fff;
  color: var(--wt-text);
  font-size: 13px;
  line-height: 30px;
}

.pagination :deep(.el-pager li:hover) {
  border-color: var(--wt-blue);
  color: var(--wt-blue);
}

.pagination :deep(.el-pager li.is-active) {
  border-color: var(--wt-blue);
  background: var(--wt-blue);
  color: #fff;
}

.pagination :deep(.el-pagination button) {
  border: 1px solid var(--wt-border);
  border-radius: 8px;
  background: #fff;
  color: var(--wt-text);
}

.pagination :deep(.el-pagination button:hover) {
  border-color: var(--wt-blue);
  color: var(--wt-blue);
}

.pagination :deep(.el-pagination__total),
.pagination :deep(.el-pagination__jump),
.pagination :deep(.el-select__wrapper) {
  color: var(--wt-muted);
  font-size: 13px;
}

.pagination :deep(.el-input__wrapper) {
  border: 1px solid var(--wt-border);
  border-radius: 8px;
  box-shadow: none;
}

/* ── 跟进记录抽屉 ── */
.log-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.log-timeline {
  padding-left: 4px;
}

.log-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

/* el-tag 传入 color 属性即为背景色，文字固定白色 */
.log-item__type {
  flex-shrink: 0;
  border: none;
  color: #fff;
}

.log-item__content {
  flex: 1;
  min-width: 0;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
}
</style>
