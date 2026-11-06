<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
import { Delete, Memo, Plus, Refresh } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { httpDelete, httpGet, httpPost, httpPut } from '@/api/request'

type TaskStatus = 'todo' | 'doing' | 'done'
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
  category: TaskCategory
  status: TaskStatus
  priority: TaskPriority
  progress: number // 0-100：叶子任务手动维护；父任务展示时按子任务均值派生
  planDate: string // YYYY-MM-DD
  hours: number
  parentId: number | null // NULL=顶层大任务（组树用）
  finishTime: string // 完成时间（后端派生），'' = 未完成
  children: Task[]
}

/** 可内联编辑的字段（进度走滑块的 preview/commit 专用链路，不进内联编辑） */
type TaskField = 'title' | 'category' | 'status' | 'priority' | 'planDate' | 'hours'

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
const STATUS_LABEL: Record<TaskStatus, string> = { todo: '待办', doing: '进行中', done: '已完成' }
const STATUS_TYPE: Record<TaskStatus, 'info' | 'warning' | 'success'> = {
  todo: 'info',
  doing: 'warning',
  done: 'success',
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
const CATEGORY_TYPE: Record<TaskCategory, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  FEATURE: 'primary',
  BUG: 'danger',
  DATA: 'warning',
  JOINT: 'success',
  DOC: 'info',
  OPS: 'warning',
  OTHER: 'info',
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
  finishTime: string | null // ISO LocalDateTime，null = 未完成
}

const STATUS_TO_API: Record<TaskStatus, number> = { todo: 0, doing: 1, done: 2 }
const PRIORITY_TO_API: Record<TaskPriority, number> = { low: 1, medium: 2, high: 3 }

/** 后端行 → 视图模型（后端 3=已延期 前端暂无该展示态，归入待办；UI 也不会产生 3） */
function fromApi(r: ApiTask): Task {
  const status: TaskStatus = r.status === 1 ? 'doing' : r.status === 2 ? 'done' : 'todo'
  const priority: TaskPriority = r.priority === 1 ? 'low' : r.priority === 3 ? 'high' : 'medium'
  const known = TASK_CATEGORY_OPTIONS.some((o) => o.value === r.category)
  return {
    id: r.id,
    title: r.title,
    category: known ? (r.category as TaskCategory) : 'OTHER',
    status,
    priority,
    progress: r.progress ?? 0,
    planDate: r.planDate ?? '',
    hours: r.actualHours ?? 0,
    parentId: r.parentId,
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
  return [t.title, t.category, t.status, t.priority, t.progress, t.planDate, t.hours].join('')
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
  } catch (e) {
    console.error('loadTasks failed', e)
    tasks.value = [] // 错误提示由 request.ts 统一处理
  } finally {
    loading.value = false
  }
}
function buildTree(items: Task[]): Task[] {
  const byId = new Map(items.map((t) => [t.id, t]))
  const roots: Task[] = []
  for (const t of items) {
    const parent = t.parentId != null ? byId.get(t.parentId) : undefined
    if (parent && parent !== t) parent.children.push(t)
    else roots.push(t)
  }
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

/** 父任务已完成子任务数（进度徽标用） */
function doneCount(t: Task): number {
  return t.children.filter((c) => c.status === 'done').length
}

/** 任务进度（递归）：叶子=用户手动维护的 progress；父任务=子任务进度均值 */
function progressOf(t: Task): number {
  if (t.children.length === 0) return t.progress
  return Math.round(t.children.reduce((s, c) => s + progressOf(c), 0) / t.children.length)
}

/** 进度条颜色：进行中品牌青，完成转成功绿 */
function progressColor(pct: number): string {
  return pct >= 100 ? 'var(--el-color-success)' : '#1FB6A6'
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

/** 行级样式：父/子任务分层 + 已完成行整行淡出 */
function rowClassName({ row }: { row: Task }): string {
  const level = row.parentId == null ? 'row-parent' : 'row-child'
  return row.status === 'done' ? `${level} row-done` : level
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

function isEditing(row: Task, field: TaskField): boolean {
  return editing.value?.id === row.id && editing.value.field === field
}
function startEdit(row: Task, field: TaskField): void {
  if (editing.value?.id === row.id && editing.value.field === field) return
  editing.value = { id: row.id, field }
  if (field === 'title') titleDraft.value = String(row.title ?? '')
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
      row.category = value as TaskCategory
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
    case 'hours':
      row.hours = Number(value ?? 0)
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
  }).catch(() => null)
  if (!created) return // 失败已由 request.ts 弹错

  const draft = fromApi(created)
  const parent = parentId != null ? findTask(parentId) : undefined
  let target: Task
  if (parent) {
    parent.children.unshift(draft)
    target = parent // 保持选中大任务，便于连续加多个子任务
  } else {
    tasks.value.unshift(draft)
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
        <el-button size="large" text @click="goReport">AI 生成报告</el-button>
        <el-button type="primary" :icon="Plus" size="large" @click="addTask(null)">新增任务</el-button>
        <el-button
          type="primary"
          plain
          :icon="Plus"
          size="large"
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
          <el-button :icon="Refresh" @click="resetFilter">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 任务树：默认只显示大任务；点击单元格内联编辑 -->
    <el-card v-loading="loading" shadow="never">
      <div class="table-meta">
        共 <span class="table-meta__num">{{ statSummary.total }}</span> 个任务（含
        <span class="table-meta__num">{{ statSummary.sub }}</span> 个子任务）· 已完成
        <span class="table-meta__num table-meta__num--done">{{ statSummary.done }}</span>
      </div>

      <el-table
        ref="tableRef"
        :data="filteredTasks"
        row-key="id"
        :tree-props="{ children: 'children' }"
        :indent="20"
        :row-class-name="rowClassName"
        highlight-current-row
        style="width: 100%"
        @current-change="onCurrentChange"
      >
        <el-table-column label="完成" width="72" align="center">
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

        <!-- 类型 -->
        <el-table-column label="类型" width="130" align="center">
          <template #default="{ row }">
            <el-select
              v-if="isEditing(row as Task, 'category')"
              :model-value="row.category"
              size="small"
              @update:model-value="(v) => commitField(row as Task, 'category', v)"
              @click.stop
            >
              <el-option v-for="o in TASK_CATEGORY_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-tag v-else class="cell-editable" :type="CATEGORY_TYPE[row.category as TaskCategory]" @click="startEdit(row as Task, 'category')">
              {{ CATEGORY_LABEL[row.category as TaskCategory] }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 状态 -->
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-select
              v-if="isEditing(row as Task, 'status')"
              :model-value="row.status"
              size="small"
              @update:model-value="(v) => commitField(row as Task, 'status', v)"
              @click.stop
            >
              <el-option v-for="o in STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-tag v-else class="cell-editable" :type="STATUS_TYPE[row.status as TaskStatus]" @click="startEdit(row as Task, 'status')">
              {{ STATUS_LABEL[row.status as TaskStatus] }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 优先级 -->
        <el-table-column label="优先级" width="100" align="center">
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
        <el-table-column label="进度" width="150" align="center">
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
        <el-table-column label="计划日期" width="150" align="center">
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

        <!-- 完成时间：后端派生（状态→已完成时打戳，移出清空），只读展示 -->
        <el-table-column label="完成时间" width="150" align="center">
          <template #default="{ row }">
            <span :class="{ 'finish-time': (row as Task).finishTime }">
              {{ fmtTime((row as Task).finishTime) || '—' }}
            </span>
          </template>
        </el-table-column>

        <!-- 工时 -->
        <el-table-column label="工时(h)" width="120" align="right">
          <template #default="{ row }">
            <el-input-number
              v-if="isEditing(row as Task, 'hours')"
              :model-value="row.hours"
              :min="0"
              :step="0.5"
              size="small"
              controls-position="right"
              style="width: 110px"
              @update:model-value="(v) => commitField(row as Task, 'hours', v)"
              @click.stop
            />
            <span v-else class="cell-editable" @click="startEdit(row as Task, 'hours')">{{ row.hours }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Memo" @click="openLogs(row as Task)">记录</el-button>
            <el-button link class="btn-delete" :icon="Delete" @click="handleDelete(row as Task)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无任务，点击右上角「新增任务」创建" :image-size="80" />
        </template>
      </el-table>
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
  /* 品牌色（C1）：与 Dashboard 统一为青绿色系 */
  --el-color-primary: #1fb6a6;
  --el-color-primary-light-3: color-mix(in srgb, #1fb6a6 70%, #fff);
  --el-color-primary-light-5: color-mix(in srgb, #1fb6a6 50%, #fff);
  --el-color-primary-light-7: color-mix(in srgb, #1fb6a6 30%, #fff);
  --el-color-primary-light-8: color-mix(in srgb, #1fb6a6 20%, #fff);
  --el-color-primary-light-9: color-mix(in srgb, #1fb6a6 10%, #fff);
  --el-color-primary-dark-2: color-mix(in srgb, #1fb6a6 80%, #000);

  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 24px;
  min-height: 100vh;
  box-sizing: border-box;
  background: var(--el-bg-color-page);
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
  font-size: 22px;
  font-weight: 700;
}

.page-header__text p {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
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

.filter-card {
  border-radius: 12px;
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

/* 父行整体浅底色（更浅一档，避免与选中高亮/hover 叠加发浑） */
:deep(.el-table .el-table__row.row-parent > td.el-table__cell) {
  background-color: var(--el-fill-color-lighter);
}

/* 当前选中高亮仍优于此底色 */
:deep(.el-table .el-table__row.row-parent.current-row > td.el-table__cell) {
  background-color: var(--el-color-primary-light-9);
}

/* 父任务标题：加粗、字号略大 */
:deep(.el-table .row-parent .task-title__text) {
  font-weight: 600;
  font-size: 14px;
}

/* 子任务标题：降饱和、略小 */
:deep(.el-table .row-child .task-title__text) {
  color: var(--el-text-color-secondary);
  font-size: 13px;
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

/* 子任务：CSS 肘线引导（替代字符 └，更精致） */
.task-title__prefix {
  box-sizing: border-box;
  flex-shrink: 0;
  width: 9px;
  height: 9px;
  margin-right: 6px;
  border-left: 2px solid var(--el-border-color);
  border-bottom: 2px solid var(--el-border-color);
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

/* 表格密度：行更紧凑，同屏多显内容 */
:deep(.el-table) {
  font-size: 13px;
}

:deep(.el-table .el-table__cell) {
  padding: 9px 0;
}

/* 展开箭头：更大点击热区 */
:deep(.el-table__expand-icon) {
  width: 28px;
  height: 28px;
  font-size: 15px;
  line-height: 28px;
}

/* 删除按钮降噪：默认灰，hover 才变红 */
:deep(.btn-delete) {
  color: var(--el-text-color-secondary);
}

:deep(.btn-delete:hover) {
  color: var(--el-color-danger);
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

/* 进度列：叶子手动滑块（紧凑、按钮不贴格边）/ 父任务派生进度条 */
.progress-slider {
  padding: 0 10px;
}

.progress-derived {
  padding: 0 4px;
}

:deep(.el-progress.is-success .el-progress-bar__outer),
:deep(.el-progress.is-success .el-progress-bar__inner) {
  background-color: var(--el-color-success);
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
