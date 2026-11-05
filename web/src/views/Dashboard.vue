<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

// ── 按需注册：折线 / 环形（饼） / 柱状 + 常用组件 + Canvas 渲染器 ──
echarts.use([
  LineChart,
  PieChart,
  BarChart,
  TitleComponent,
  TooltipComponent,
  GridComponent,
  LegendComponent,
  CanvasRenderer,
])

/** 任务状态口径与后端一致：0 待办 / 1 进行中 / 2 已完成 / 3 已延期 */
interface StatCard {
  label: string
  value: string
  hint: string
}

/** ── 统计卡片（模拟接口数据） ───────────────────────────
 * 实际接入时替换为 GET /api/tasks/page 聚合结果
 */
const statusCount = { done: 12, doing: 5, todo: 8, delayed: 3 } // 本周各状态任务数
const weekHours = 32.5 // 本周总耗时

const cards = ref<StatCard[]>([
  {
    label: '本周完成任务',
    value: String(statusCount.done),
    hint: `进行中 ${statusCount.doing} · 待办 ${statusCount.todo}`,
  },
  { label: '本周总耗时', value: `${weekHours} h`, hint: '日均约 ' + (weekHours / 7).toFixed(1) + ' h' },
  {
    label: '任务延期率',
    value: ((statusCount.delayed / (statusCount.done + statusCount.doing + statusCount.todo + statusCount.delayed)) * 100).toFixed(1) + '%',
    hint: `已延期 ${statusCount.delayed} 项`,
  },
  { label: '本周新增任务', value: '18', hint: '较上周 +3' },
])

/** ── 图表容器与实例 ── */
const donutEl = ref<HTMLDivElement>()
const trendEl = ref<HTMLDivElement>()
let donutChart: echarts.ECharts | null = null
let trendChart: echarts.ECharts | null = null

/** 环形图：任务状态占比 */
function initDonutChart(): void {
  if (!donutEl.value) return
  donutChart = echarts.init(donutEl.value)
  donutChart.setOption({
    title: { text: '任务状态占比', left: 'center', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'item', formatter: '{b}：{c} 项（{d}%）' },
    legend: { bottom: 0, icon: 'circle' },
    series: [
      {
        type: 'pie',
        radius: ['45%', '70%'], // 环形
        center: ['50%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 6, borderColor: 'transparent', borderWidth: 2 },
        label: { show: false },
        data: [
          { name: '已完成', value: statusCount.done, itemStyle: { color: '#1FB6A6' } },
          { name: '进行中', value: statusCount.doing, itemStyle: { color: '#409EFF' } },
          { name: '待办', value: statusCount.todo, itemStyle: { color: '#909399' } },
          { name: '已延期', value: statusCount.delayed, itemStyle: { color: '#F56C6C' } },
        ],
      },
    ],
  })
}

/** 折线 + 柱状组合图：近 7 天每日工时投入（柱）与工时趋势（线） */
function initTrendChart(): void {
  if (!trendEl.value) return
  // 近 7 天日期（含今天）
  const days: string[] = Array.from({ length: 7 }, (_, i) => {
    const d = new Date()
    d.setDate(d.getDate() - (6 - i))
    return `${d.getMonth() + 1}/${d.getDate()}`
  })
  // 模拟数据：实际接入时替换为工时统计接口
  const dailyHours = [3.5, 5.0, 2.0, 6.5, 4.0, 0, 7.5]
  // 3 日滑动平均趋势线
  const trend = dailyHours.map((_, i) => {
    const slice = dailyHours.slice(Math.max(0, i - 2), i + 1)
    return +(slice.reduce((a, b) => a + b, 0) / slice.length).toFixed(1)
  })

  trendChart = echarts.init(trendEl.value)
  trendChart.setOption({
    title: { text: '近 7 天工时投入', left: 'center', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { left: 40, right: 40, top: 50, bottom: 60 },
    xAxis: { type: 'category', data: days },
    yAxis: [
      { type: 'value', name: '工时(h)', minInterval: 1 },
      { type: 'value', name: '趋势(h)', show: false },
    ],
    series: [
      {
        name: '每日工时',
        type: 'bar', // 柱状：当日工时
        data: dailyHours,
        barWidth: '40%',
        itemStyle: { color: '#1FB6A6', borderRadius: [4, 4, 0, 0] },
      },
      {
        name: '3日均值',
        type: 'line', // 折线：趋势
        yAxisIndex: 1,
        data: trend,
        smooth: true,
        showSymbol: false,
        lineStyle: { color: '#E6A23C', width: 2 },
        itemStyle: { color: '#E6A23C' },
      },
    ],
  })
}

/** 窗口自适应：两张图同步 resize */
function handleResize(): void {
  donutChart?.resize()
  trendChart?.resize()
}

onMounted(() => {
  // 模拟接口延迟后初始化图表与卡片数据
  initDonutChart()
  initTrendChart()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  donutChart?.dispose()
  trendChart?.dispose()
  donutChart = null
  trendChart = null
})
</script>

<template>
  <div class="dashboard">
    <div class="page-header">
      <h1>工作统计大屏</h1>
      <p>任务状态与工时投入总览（当前为模拟数据）</p>
    </div>

    <!-- ① 统计卡片 -->
    <div class="card-grid">
      <el-card v-for="c in cards" :key="c.label" shadow="hover" class="stat-card">
        <div class="stat-label">{{ c.label }}</div>
        <div class="stat-value">{{ c.value }}</div>
        <div class="stat-hint">{{ c.hint }}</div>
      </el-card>
    </div>

    <!-- ② 图表：左环形 / 右折线 -->
    <div class="chart-grid">
      <el-card shadow="never" class="chart-card">
        <div ref="donutEl" class="chart-box" />
      </el-card>
      <el-card shadow="never" class="chart-card">
        <div ref="trendEl" class="chart-box" />
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.dashboard {
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

/* 卡片区：4 列，窄屏自动降列 */
.card-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-card {
  border-radius: 12px;
  text-align: center;
}

.stat-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  margin: 8px 0 4px;
  color: var(--el-color-primary);
}

.stat-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

/* 图表区：左 1 右 1，窄屏上下堆叠 */
.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.chart-card {
  border-radius: 12px;
}

.chart-box {
  width: 100%;
  height: 360px;
}

@media (max-width: 900px) {
  .card-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
