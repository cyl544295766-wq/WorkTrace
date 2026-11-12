<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, CopyDocument, Delete, Download, View } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { httpDelete, httpGet } from '@/api/request'

/** 后端报告（Result.data / done 事件后组装） */
interface BizReport {
  id: number
  userId: number
  reportType: string
  startDate: string
  endDate: string
  content: string
  aiModel: string
  createTime: string
}

/** 报告类型选项：日报 / 周报 / 月报 */
const REPORT_TYPE_OPTIONS = [
  { label: '日报', value: 'DAILY' },
  { label: '周报', value: 'WEEKLY' },
  { label: '月报', value: 'MONTHLY' },
] as const

const reportType = ref('DAILY')

/** 类型 → 默认统计周期：日报=今天；周报=本周一~周日；月报=本月 1 号~月末（可再手调） */
function periodForType(t: string): [string, string] {
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  const now = new Date()
  if (t === 'WEEKLY') {
    const monday = new Date(now)
    monday.setDate(now.getDate() - ((now.getDay() + 6) % 7)) // 周一为一周起点
    const sunday = new Date(monday)
    sunday.setDate(monday.getDate() + 6)
    return [fmt(monday), fmt(sunday)]
  }
  if (t === 'MONTHLY') {
    return [fmt(new Date(now.getFullYear(), now.getMonth(), 1)), fmt(new Date(now.getFullYear(), now.getMonth() + 1, 0))]
  }
  return [fmt(now), fmt(now)] // 日报 = 今天
}

// 首页「日报/周报/月报」入口经 /reports?type= 预选类型
const route = useRoute()
onMounted(() => {
  const t = String(route.query.type ?? '')
  if (REPORT_TYPE_OPTIONS.some((o) => o.value === t)) reportType.value = t
  dateRange.value = periodForType(reportType.value) // 进页面即带出默认周期
})
const dateRange = ref<string[] | null>(null)

/** 切换类型 = 重新带出对应周期（用户仍可手动微调日期） */
function onTypeChange(): void {
  dateRange.value = periodForType(reportType.value)
}

const generating = ref(false)
const report = ref<BizReport | null>(null)
/** 可编辑的报告正文（流式生成期间实时追加，形成打字机效果） */
const content = ref('')

/* ── 历史报告记录（GET /api/reports/page，仅本人数据） ── */

/** 报告列表行（不含正文全文，仅摘要） */
interface ReportItem {
  id: number
  reportType: string
  startDate: string
  endDate: string
  aiModel: string
  createTime: string
  summary: string
}

const TYPE_LABEL: Record<string, string> = { DAILY: '日报', WEEKLY: '周报', MONTHLY: '月报' }
const TYPE_TAG: Record<string, string> = { DAILY: 'tag-daily', WEEKLY: 'tag-weekly', MONTHLY: 'tag-monthly' }

const historyItems = ref<ReportItem[]>([])
const historyTotal = ref(0)
const historyPage = ref(1)
const historySize = ref(10)
const historyLoading = ref(false)

async function loadHistory(): Promise<void> {
  historyLoading.value = true
  try {
    const page = await httpGet<{ records: ReportItem[]; total: number }>('/reports/page', {
      page: historyPage.value,
      size: historySize.value,
    })
    historyItems.value = page.records ?? []
    historyTotal.value = page.total ?? 0
  } catch {
    // 错误提示由 request.ts 统一处理
  } finally {
    historyLoading.value = false
  }
}
onMounted(loadHistory)

function onHistorySizeChange(): void {
  historyPage.value = 1
  void loadHistory()
}

/* ── 查看报告（Markdown 渲染抽屉） ── */
const md = new MarkdownIt({ html: false, linkify: true })
const viewDrawer = ref(false)
const viewReport = ref<BizReport | null>(null)
const viewContent = ref('')
/** 渲染 + 消毒：markdown-it 已禁 raw html，DOMPurify 再兜一层（v-html 安全） */
const viewHtml = computed(() => DOMPurify.sanitize(md.render(viewContent.value || '')))

async function openView(item: ReportItem): Promise<void> {
  try {
    viewReport.value = await httpGet<BizReport>(`/reports/${item.id}`)
    viewContent.value = viewReport.value?.content ?? ''
    viewDrawer.value = true
  } catch {
    // 错误提示由 request.ts 统一处理
  }
}

/** 删除报告记录（确认后物理删除） */
async function removeReport(item: ReportItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除「${TYPE_LABEL[item.reportType] ?? '报告'} ${item.startDate} ~ ${item.endDate}」吗？`,
      '删除报告',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 取消
  }
  try {
    await httpDelete(`/reports/${item.id}`)
  } catch {
    return // 失败已由 request.ts 弹错
  }
  ElMessage.success('已删除')
  // 本页最后一条被删且不在首页时回退一页
  if (historyItems.value.length === 1 && historyPage.value > 1) historyPage.value -= 1
  await loadHistory()
}

/** 请求中止控制器：页面卸载时中断未完成的流 */
let abortController: AbortController | null = null
onUnmounted(() => abortController?.abort())

/**
 * 解析一条 SSE 事件块（以空行分隔的多行文本）。
 * Spring SseEmitter 会把含换行的 data 拆成多个 data: 行，需按行收集后再合并。
 */
function parseSseEvent(raw: string): { event: string; data: string } {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).replace(/^ /, ''))
    }
  }
  return { event, data: dataLines.join('\n') }
}

/**
 * 一键生成：fetch 读取后端 SSE 流（POST /reports/stream-generate）。
 * 事件约定（与 ReportService 对齐）：
 *  - 默认 message 事件：增量 Markdown 文本块 → 实时追加（打字机）
 *  - done 事件：{"reportId":...} → 生成完毕并已落库
 *  - error 事件：{"message":"..."} → 生成失败
 */
async function generateReport(): Promise<void> {
  if (!dateRange.value || dateRange.value.length !== 2) {
    ElMessage.warning('请先选择统计周期（起止日期）')
    return
  }

  generating.value = true
  content.value = ''
  // 先放一个骨架对象，让结果卡片在流式输出期间就出现
  report.value = {
    id: 0,
    userId: 0,
    reportType: reportType.value,
    startDate: dateRange.value[0],
    endDate: dateRange.value[1],
    content: '',
    aiModel: '生成中…',
    createTime: '',
  }

  abortController?.abort()
  abortController = new AbortController()

  try {
    const resp = await fetch(
      `${import.meta.env.VITE_API_BASE || '/api'}/reports/stream-generate`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        credentials: 'include', // Session 方案：携带会话 Cookie
        body: JSON.stringify({
          reportType: reportType.value,
          startDate: dateRange.value[0],
          endDate: dateRange.value[1],
        }),
        signal: abortController.signal,
      },
    )

    if (!resp.ok || !resp.body) {
      // 拦截器 401 / 参数错误等：HTTP 层就失败了，尝试取出统一响应体里的 message
      let msg = `请求失败（HTTP ${resp.status}）`
      try {
        const body = (await resp.json()) as { message?: string }
        if (body?.message) msg = body.message
      } catch { /* 非 JSON 响应体，忽略 */ }
      throw new Error(msg)
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = '' // SSE 事件以空行(\n\n)分隔；最后一段可能不完整，留待下一轮
    let failed: string | null = null

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const chunks = buffer.split('\n\n')
      buffer = chunks.pop() ?? '' // 半截事件留在缓冲区

      for (const raw of chunks) {
        if (!raw.trim()) continue
        const { event, data } = parseSseEvent(raw)

        if (event === 'error') {
          failed = (JSON.parse(data || '{}') as { message?: string }).message ?? '报告生成失败'
        } else if (event === 'done') {
          const { reportId } = JSON.parse(data || '{}') as { reportId?: number }
          // 生成完毕：补全报告元信息（内容以用户可能手动编辑过的 content 为准）
          if (report.value) {
            report.value = {
              ...report.value,
              id: reportId ?? 0,
              aiModel: report.value.aiModel === '生成中…' ? 'AI' : report.value.aiModel,
              content: content.value,
            }
          }
          void loadHistory() // 新记录入列
        } else {
          // 默认 message 事件：增量 Markdown 文本块 → 打字机追加
          content.value += data
        }
      }

      if (failed) break
    }

    if (failed) {
      ElMessage.error(failed)
    } else {
      ElMessage.success('AI 报告生成完毕，可直接编辑')
    }
  } catch (e) {
    // 主动 abort（页面卸载）不提示
    if ((e as Error)?.name !== 'AbortError') {
      ElMessage.error((e as Error)?.message || '报告生成失败，请重试')
    }
  } finally {
    generating.value = false
  }
}

/** 把编辑后的正文回写到报告对象（本地会话内保存） */
function saveEdit(): void {
  if (!report.value) return
  report.value = { ...report.value, content: content.value }
  ElMessage.success('修改已保存')
}

/** 复制文本到剪贴板（结果卡与查看抽屉共用） */
async function copyText(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('Markdown 已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动全选复制')
  }
}

/** 导出文本为 .md 文件（Blob + 临时 <a> 触发下载；结果卡与查看抽屉共用） */
function exportText(text: string, meta: Pick<BizReport, 'reportType' | 'startDate' | 'endDate'> | null): void {
  if (!text.trim()) {
    ElMessage.warning('暂无可导出的报告内容')
    return
  }
  const typeLabel = REPORT_TYPE_OPTIONS.find((o) => o.value === meta?.reportType)?.label ?? '报告'
  const fileName = `WorkTrace_${typeLabel}_${meta?.startDate ?? ''}_${meta?.endDate ?? ''}.md`

  const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url) // 释放临时对象 URL
  ElMessage.success(`已导出：${fileName}`)
}

/** 结果卡的复制 / 导出 */
function copyMarkdown(): void {
  void copyText(content.value)
}
function exportMarkdown(): void {
  exportText(content.value, report.value)
}

/** 查看抽屉的复制 / 导出 */
function copyView(): void {
  void copyText(viewContent.value)
}
function exportView(): void {
  exportText(viewContent.value, viewReport.value)
}
</script>

<template>
  <div class="report-page">
    <div class="page-header">
      <div>
        <h1>AI 智能报告</h1>
        <p>选择报告类型与统计周期，基于你的任务数据一键生成 Markdown 报告</p>
      </div>
    </div>

    <!-- ① 顶部表单控制区 -->
    <el-card shadow="never" class="control-card">
      <div class="control-row">
        <div class="control-item">
          <span class="control-label">报告类型</span>
          <el-radio-group v-model="reportType" size="large" :disabled="generating" @change="onTypeChange">
            <el-radio-button v-for="opt in REPORT_TYPE_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </el-radio-button>
          </el-radio-group>
        </div>

        <div class="control-item">
          <span class="control-label">统计周期</span>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            size="large"
            style="width: 300px"
            :disabled="generating"
          />
        </div>

        <el-button type="primary" size="large" :loading="generating" @click="generateReport">
          {{ generating ? '正在生成…' : '🚀 一键让 AI 生成报告' }}
        </el-button>
      </div>
    </el-card>

    <!-- ② 结果展示与微调区（流式生成期间即出现，正文实时追加） -->
    <el-card v-if="report" shadow="never" class="result-card">
      <template #header>
        <div class="result-header">
          <span class="result-header__title">报告内容</span>
          <span class="result-header__meta">
            AI 模型：{{ report.aiModel }} · {{ report.startDate }} 至 {{ report.endDate }}
          </span>
        </div>
      </template>

      <el-input
        v-model="content"
        type="textarea"
        :rows="16"
        resize="vertical"
        placeholder="AI 正在流式生成报告，文字会实时出现在这里…"
      />

      <div class="result-actions">
        <el-button type="primary" :icon="Check" :disabled="generating" @click="saveEdit">
          保存修改
        </el-button>
        <el-button :icon="CopyDocument" @click="copyMarkdown">复制 Markdown</el-button>
        <el-button type="success" :icon="Download" :disabled="generating" @click="exportMarkdown">
          导出 Markdown
        </el-button>
      </div>
    </el-card>

    <el-empty v-else description="选择报告类型与统计周期，点击上方按钮生成报告" :image-size="90" />

    <!-- ③ 生成记录：历史报告列表（仅本人，按 userId 隔离） -->
    <el-card shadow="never" class="history-card">
      <template #header>
        <div class="result-header">
          <span class="result-header__title">生成记录</span>
          <span class="result-header__meta">共 {{ historyTotal }} 条 · 按生成时间倒序</span>
        </div>
      </template>

      <el-table v-loading="historyLoading" :data="historyItems" style="width: 100%">
        <el-table-column label="类型" width="90" align="center">
          <template #default="{ row }">
            <span class="type-tag" :class="TYPE_TAG[row.reportType]">
              {{ TYPE_LABEL[row.reportType] ?? row.reportType }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="统计周期" width="210" align="center">
          <template #default="{ row }">{{ row.startDate }} 至 {{ row.endDate }}</template>
        </el-table-column>
        <el-table-column label="摘要" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.summary || '—' }}</template>
        </el-table-column>
        <el-table-column label="生成时间" width="170" align="center">
          <template #default="{ row }">{{ (row.createTime || '').replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="center">
          <template #default="{ row }">
            <el-button link type="primary" :icon="View" @click="openView(row as ReportItem)">查看</el-button>
            <el-button link class="btn-delete" :icon="Delete" @click="removeReport(row as ReportItem)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有生成过报告，上方生成一条试试" :image-size="70" />
        </template>
      </el-table>

      <div class="history-pagination">
        <el-pagination
          v-model:current-page="historyPage"
          v-model:page-size="historySize"
          :total="historyTotal"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          size="small"
          @current-change="loadHistory"
          @size-change="onHistorySizeChange"
        />
      </div>
    </el-card>

    <!-- 查看报告：Markdown 渲染抽屉（markdown-it + DOMPurify） -->
    <el-drawer
      v-model="viewDrawer"
      size="60%"
      :title="`${TYPE_LABEL[viewReport?.reportType ?? ''] ?? '报告'} · ${viewReport?.startDate ?? ''} 至 ${viewReport?.endDate ?? ''}`"
    >
      <div class="view-meta">
        AI 模型：{{ viewReport?.aiModel }} · 生成于 {{ (viewReport?.createTime ?? '').replace('T', ' ').slice(0, 16) }}
      </div>
      <div class="view-body" v-html="viewHtml" />
      <div class="result-actions">
        <el-button :icon="CopyDocument" @click="copyView">复制 Markdown</el-button>
        <el-button type="success" :icon="Download" @click="exportView">导出 Markdown</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.report-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 24px;
  min-height: 100vh;
  box-sizing: border-box;
  background: var(--el-bg-color-page);
}

.page-header h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
}

.page-header p {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.control-card {
  border-radius: 12px;
}

.control-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 16px;
}

.control-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.control-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.result-card {
  border-radius: 12px;
}

.result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.result-header__title {
  font-weight: 600;
}

.result-header__meta {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-weight: 400;
}

.result-actions {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}

/* ── 生成记录卡 ── */
.history-card {
  border-radius: 12px;
}

.history-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

/* 类型标签：白底芯片三色（与任务页标签体系一致） */
.type-tag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 6px;
  background: #fff;
  font-size: 12px;
  font-weight: 500;
}

.tag-daily {
  color: #2e71c2;
  box-shadow: inset 0 0 0 1px rgb(46 113 194 / 32%);
}

.tag-weekly {
  color: #0e9f6e;
  box-shadow: inset 0 0 0 1px rgb(14 159 110 / 34%);
}

.tag-monthly {
  color: #c97a1f;
  box-shadow: inset 0 0 0 1px rgb(201 122 31 / 34%);
}

/* 删除按钮降噪（同任务页） */
.btn-delete {
  color: var(--el-text-color-secondary);
}

.btn-delete:hover {
  color: var(--el-color-danger);
}

/* ── 查看抽屉：渲染后的 Markdown ── */
.view-meta {
  margin-bottom: 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.view-body {
  line-height: 1.8;
  color: #25364a;
}

.view-body :deep(h1),
.view-body :deep(h2),
.view-body :deep(h3) {
  margin: 18px 0 8px;
  font-size: 17px;
}

.view-body :deep(p) {
  margin: 8px 0;
}

.view-body :deep(ul),
.view-body :deep(ol) {
  padding-left: 22px;
}

.view-body :deep(code) {
  padding: 1px 6px;
  border-radius: 4px;
  background: #f2f4f7;
}

.view-body :deep(pre) {
  padding: 12px;
  border-radius: 8px;
  background: #f7f9fc;
  overflow-x: auto;
}

.view-body :deep(table) {
  border-collapse: collapse;
}

.view-body :deep(th),
.view-body :deep(td) {
  padding: 6px 10px;
  border: 1px solid #e5eaf0;
}

.view-body :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid #3988e8;
  color: #8796a8;
}
</style>
