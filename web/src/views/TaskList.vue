<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
import { Delete, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

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
  planDate: string // YYYY-MM-DD
  hours: number
  children: Task[]
}

/** 可内联编辑的字段 */
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

const router = useRouter()
function goReport(): void {
  router.push('/reports')
}

/* ── 数据：本地演示（接入后端接口后换成 httpGet/httpPost） ── */
const tasks = ref<Task[]>([
  {
    id: 1,
    title: '登录与会话模块上线',
    category: 'JOINT',
    status: 'doing',
    priority: 'high',
    planDate: '2026-11-04',
    hours: 4,
    children: [
      { id: 11, title: 'AuthController 接口开发', category: 'JOINT', status: 'done', priority: 'high', planDate: '2026-11-03', hours: 2, children: [] },
      { id: 12, title: '拦截器与会话验证', category: 'JOINT', status: 'done', priority: 'high', planDate: '2026-11-04', hours: 1.5, children: [] },
      { id: 13, title: '联调缺陷修复', category: 'BUG', status: 'doing', priority: 'medium', planDate: '2026-11-05', hours: 0.5, children: [] },
    ],
  },
  {
    id: 2,
    title: 'Docker 单机部署',
    category: 'OPS',
    status: 'done',
    priority: 'medium',
    planDate: '2026-11-02',
    hours: 3,
    children: [
      { id: 21, title: 'compose 编排', category: 'OPS', status: 'done', priority: 'medium', planDate: '2026-11-02', hours: 2, children: [] },
      { id: 22, title: '健康检查验证', category: 'OPS', status: 'done', priority: 'low', planDate: '2026-11-02', hours: 1, children: [] },
    ],
  },
  { id: 3, title: '任务列表页开发', category: 'FEATURE', status: 'todo', priority: 'medium', planDate: '2026-11-05', hours: 4, children: [] },
  { id: 4, title: '数据库备份演练', category: 'DATA', status: 'todo', priority: 'high', planDate: '2026-11-06', hours: 1.5, children: [] },
])

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

/* ── 层级：子任务全完成 → 父任务自动勾选完成 ── */
function deriveStatus(children: Task[]): TaskStatus {
  if (children.length === 0) return 'todo'
  const done = children.filter((c) => c.status === 'done').length
  if (done === children.length) return 'done'
  return done > 0 ? 'doing' : 'todo'
}
function syncParents(list: Task[]): void {
  for (const t of list) {
    if (t.children.length > 0) {
      syncParents(t.children)
      t.status = deriveStatus(t.children)
    }
  }
}
function setLeaf(t: Task, done: boolean): void {
  if (t.children.length === 0) {
    t.status = done ? 'done' : 'todo'
    return
  }
  for (const c of t.children) setLeaf(c, done)
}
function toggleDone(task: Task, done: boolean): void {
  setLeaf(task, done)
  syncParents(tasks.value)
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
syncParents(tasks.value)

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

/** 把编辑值写回任务字段（value 来自组件事件，在此按字段收窄类型） */
function commitField(row: Task, field: TaskField, value: unknown): void {
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
  syncParents(tasks.value)
}

/* ── 新增：选中大任务 → 加小任务；未选中 → 加大任务 ── */
function addTask(): void {
  editing.value = null
  const draft: Task = {
    id: Date.now(),
    title: '',
    category: 'FEATURE',
    status: 'todo',
    priority: 'medium',
    planDate: '',
    hours: 0,
    children: [],
  }

  const parentId = selectedId.value
  let target: Task
  if (parentId != null) {
    const parent = findTask(parentId)
    if (parent) {
      parent.children.push(draft) // 选中任务 → 作为它的子任务
      target = parent             // 保持选中大任务，便于连续加多个子任务
    } else {
      tasks.value.unshift(draft)
      target = draft
    }
  } else {
    tasks.value.unshift(draft) // 未选中 → 顶层大任务
    target = draft
  }
  syncParents(tasks.value)

  selectedId.value = target.id
  tableRef.value?.setCurrentRow(target) // target 一定是顶层行，EP 可选中
  editing.value = { id: draft.id, field: 'title' } // 立即编辑新任务标题
  titleDraft.value = ''
}

function handleDelete(row: Task): void {
  ElMessageBox.confirm(`确定删除「${row.title || '未命名任务'}」及其子任务吗？`, '删除任务', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消',
  })
    .then(() => {
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
        syncParents(tasks.value)
        ElMessage.success('已删除')
      }
    })
    .catch(() => undefined)
}
</script>

<template>
  <div class="task-page">
    <div class="page-header">
      <div class="page-header__text">
        <h1>任务管理</h1>
        <p>
          点击单元格直接修改 · 选中任务后「新增」会加为它的子任务
          <template v-if="selectedId != null">（当前选中：{{ findTask(selectedId)?.title || '未命名' }}）</template>
        </p>
      </div>
      <div class="page-header__actions">
        <el-button size="large" @click="goReport">AI 生成报告</el-button>
        <el-button type="primary" :icon="Plus" size="large" @click="addTask">
          {{ selectedId != null ? '新增子任务' : '新增任务' }}
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
          <el-button type="primary" :icon="Search">查询</el-button>
          <el-button :icon="Refresh" @click="resetFilter">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 任务树：默认只显示大任务；点击单元格内联编辑 -->
    <el-card shadow="never">
      <div class="table-meta">共 {{ filteredTasks.length }} 条</div>

      <el-table
        ref="tableRef"
        :data="filteredTasks"
        row-key="id"
        :tree-props="{ children: 'children' }"
        highlight-current-row
        stripe
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

        <!-- 标题：内联文本编辑 -->
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
            <span
              v-else
              class="cell-editable"
              :class="{ 'is-done': row.status === 'done' }"
              @click="startEdit(row as Task, 'title')"
            >{{ row.title || '点击输入标题' }}</span>
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

        <el-table-column label="操作" width="90" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" :icon="Delete" @click="handleDelete(row as Task)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无任务，点击右上角「新增任务」创建" :image-size="80" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.task-page {
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

.filter-card {
  border-radius: 12px;
}

.table-meta {
  margin-bottom: 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
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
</style>
