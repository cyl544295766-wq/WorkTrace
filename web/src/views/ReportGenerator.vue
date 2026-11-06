<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Check, CopyDocument, Download } from '@element-plus/icons-vue'

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

// 首页「日报/周报/月报」入口经 /reports?type= 预选类型
const route = useRoute()
onMounted(() => {
  const t = String(route.query.type ?? '')
  if (REPORT_TYPE_OPTIONS.some((o) => o.value === t)) reportType.value = t
})
const dateRange = ref<string[] | null>(null)

const generating = ref(false)
const report = ref<BizReport | null>(null)
/** 可编辑的报告正文（流式生成期间实时追加，形成打字机效果） */
const content = ref('')

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

/** 复制 Markdown 到剪贴板 */
async function copyMarkdown(): Promise<void> {
  try {
    await navigator.clipboard.writeText(content.value)
    ElMessage.success('Markdown 已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动全选复制')
  }
}

/** 导出当前正文为 .md 文件（Blob + 临时 <a> 触发下载） */
function exportMarkdown(): void {
  if (!content.value.trim()) {
    ElMessage.warning('暂无可导出的报告内容')
    return
  }
  const typeLabel =
    REPORT_TYPE_OPTIONS.find((o) => o.value === report.value?.reportType)?.label ?? '报告'
  const fileName = `WorkTrace_${typeLabel}_${report.value?.startDate ?? ''}_${report.value?.endDate ?? ''}.md`

  const blob = new Blob([content.value], { type: 'text/markdown;charset=utf-8' })
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
          <el-select v-model="reportType" size="large" style="width: 140px" :disabled="generating">
            <el-option
              v-for="opt in REPORT_TYPE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
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
</style>
