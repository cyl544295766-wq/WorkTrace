<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, CopyDocument } from '@element-plus/icons-vue'
import { httpPost } from '@/api/request'

/** 后端 POST /api/reports/generate 返回的报告（Result.data） */
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
const dateRange = ref<string[] | null>(null)

const generating = ref(false)
const report = ref<BizReport | null>(null)
/** 可编辑的报告正文（生成后初始化，支持二次编辑） */
const content = ref('')

/** 一键生成：校验周期 → POST → 展示结果 */
async function generateReport(): Promise<void> {
  if (!dateRange.value || dateRange.value.length !== 2) {
    ElMessage.warning('请先选择统计周期（起止日期）')
    return
  }

  generating.value = true
  try {
    const result = await httpPost<BizReport>('/reports/generate', {
      reportType: reportType.value,
      startDate: dateRange.value[0],
      endDate: dateRange.value[1],
    })
    report.value = result
    content.value = result?.content ?? ''
    ElMessage.success('AI 报告已生成，可直接编辑')
  } catch (e) {
    // 失败提示由 api/request.ts 统一 toast（生成失败 500 / 网络异常 / 会话失效均覆盖）
    console.error('[generate report]', e)
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
          <el-select v-model="reportType" size="large" style="width: 140px">
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
          />
        </div>

        <el-button type="primary" size="large" :loading="generating" @click="generateReport">
          {{ generating ? '正在生成…' : '🚀 一键让 AI 生成报告' }}
        </el-button>
      </div>
    </el-card>

    <!-- ② 结果展示与微调区 -->
    <el-card v-if="report" shadow="never" class="result-card">
      <template #header>
        <div class="result-header">
          <span class="result-header__title">报告内容</span>
          <span class="result-header__meta">AI 模型：{{ report.aiModel }} · {{ report.startDate }} 至 {{ report.endDate }}</span>
        </div>
      </template>

      <el-input
        v-model="content"
        type="textarea"
        :rows="16"
        resize="vertical"
        placeholder="AI 生成的报告会显示在这里，可直接编辑"
      />

      <div class="result-actions">
        <el-button type="primary" :icon="Check" @click="saveEdit">保存修改</el-button>
        <el-button :icon="CopyDocument" @click="copyMarkdown">复制 Markdown</el-button>
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
