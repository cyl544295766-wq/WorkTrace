<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { Calendar, Check, Document, MagicStick, Plus, TrendCharts } from '@element-plus/icons-vue'
import { httpGet, httpPut } from '@/api/request'

// 设计稿裁切素材（桌面/WorkTrace/WorkTrace_UI_Assets）
import iconAll from '@/assets/home/kpi-icon-all-tasks.png'
import iconProgress from '@/assets/home/kpi-icon-in-progress.png'
import iconDone from '@/assets/home/kpi-icon-completed.png'
import iconPending from '@/assets/home/kpi-icon-pending.png'
// 报告中心图标改为 CSS 圆盘 + Element 图标绘制：
// 原 report-icon-*.png 素材裁切框偏移且圆形右/下边缘被画布裁掉（缺口约 12px），
// 像素无法复原，按设计稿样式重绘（见 .tile__badge）

// ── 按需注册：折线 + 环形（饼） + 常用组件 + Canvas 渲染器 ──
echarts.use([
  LineChart,
  PieChart,
  GridComponent,
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
])

/** 设计稿调色板（worktrace-theme.css + 效果图取色） */
const C = {
  blue: '#3988E8',
  teal: '#13B8A6',
  green: '#22C55E',
  orange: '#F5A623',
  text: '#25364A',
  muted: '#8796A8',
}

/** 任务行（首页只用到这些字段；口径与后端 BizTask 一致） */
interface HomeTask {
  id: number
  title: string
  status: number // 0待办 1进行中 2已完成 3已延期
  planDate: string | null // YYYY-MM-DD
  isKey: number | null // 重点标记 1=重点（任务管理「设重点」） 0=普通
  createTime: string | null // ISO LocalDateTime
  finishTime: string | null // ISO LocalDateTime，完成时间由后端派生
}

const router = useRouter()
const tasks = ref<HomeTask[]>([])
const loading = ref(true)

async function loadTasks(): Promise<void> {
  loading.value = true
  try {
    const page = await httpGet<{ records: HomeTask[] }>('/tasks/page', { page: 1, size: 500 })
    tasks.value = page.records ?? []
  } catch {
    tasks.value = [] // 错误提示由 request.ts 统一处理
  } finally {
    loading.value = false
  }
}
onMounted(() => {
  void loadTasks()
  void loadInsight() // AI 洞察与任务并行拉取，不阻塞页面
})

/* ── 日期与问候（真实日期；农历走 ICU 中文历，设计图中的星期/农历是错的） ── */
const now = ref(new Date())
let timer: number | undefined
onMounted(() => {
  timer = window.setInterval(() => (now.value = new Date()), 60_000)
})
onBeforeUnmount(() => window.clearInterval(timer))

const greeting = computed(() => {
  const h = now.value.getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const dateText = computed(() => {
  const d = now.value
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
})
const weekdayText = computed(() =>
  new Intl.DateTimeFormat('zh-CN', { weekday: 'long' }).format(now.value),
)
/** 农历：ICU 中文历（zh-u-ca-chinese）→ 农历九月廿八 形式 */
const lunarText = computed(() => {
  try {
    const parts = new Intl.DateTimeFormat('zh-CN-u-ca-chinese', {
      month: 'numeric',
      day: 'numeric',
    }).formatToParts(now.value)
    const month = parts.find((p) => p.type === 'month')?.value ?? ''
    const day = Number(parts.find((p) => p.type === 'day')?.value ?? 0)
    if (!month || !day) return ''
    return `农历${cnMonth(month)}${cnDay(day)}`
  } catch {
    return ''
  }
})
/**
 * ICU 不同版本对中文历 month 返回不一：Chromium 给 "9"，Node 给 "九月"，
 * 闰月可能带 "闰" 前缀——统一转成「九月/闰九月」形式。
 */
function cnMonth(raw: string): string {
  if (raw.includes('月')) return raw // 已是 "九月"/"闰九月"
  const leap = raw.startsWith('闰')
  const n = Number(leap ? raw.slice(1) : raw)
  const names = ['正', '二', '三', '四', '五', '六', '七', '八', '九', '十', '冬', '腊']
  if (!n || n < 1 || n > 12) return raw
  return `${leap ? '闰' : ''}${names[n - 1]}月`
}
function cnDay(n: number): string {
  const d10 = ['', '初一', '初二', '初三', '初四', '初五', '初六', '初七', '初八', '初九', '初十']
  const d20 = ['', '十一', '十二', '十三', '十四', '十五', '十六', '十七', '十八', '十九', '二十']
  const d30 = ['', '廿一', '廿二', '廿三', '廿四', '廿五', '廿六', '廿七', '廿八', '廿九', '三十']
  if (n <= 10) return d10[n] ?? String(n)
  if (n <= 20) return d20[n - 10] ?? String(n)
  if (n <= 30) return d30[n - 20] ?? String(n)
  return String(n)
}

/* ── KPI 统计（真数据；待处理含已延期——都是“需要关注”） ── */
const total = computed(() => tasks.value.length)
const doing = computed(() => tasks.value.filter((t) => t.status === 1).length)
const done = computed(() => tasks.value.filter((t) => t.status === 2).length)
const pending = computed(() => tasks.value.filter((t) => t.status === 0 || t.status === 3).length)

/** 卡片右侧装饰柱：高度取自设计稿形态（两色交替透明度） */
const kpis = computed(() => [
  {
    label: '全部任务',
    value: total.value,
    hint: '累计任务',
    icon: iconAll,
    color: C.blue,
    bars: [14, 22, 16, 30, 20, 34, 24, 38],
  },
  {
    label: '进行中',
    value: doing.value,
    hint: '正在推进',
    icon: iconProgress,
    color: C.teal,
    bars: [20, 14, 26, 18, 32, 22, 36, 26],
  },
  {
    label: '已完成',
    value: done.value,
    hint: '累计完成',
    icon: iconDone,
    color: C.green,
    bars: [12, 20, 28, 18, 34, 24, 38, 30],
  },
  {
    label: '待处理',
    value: pending.value,
    hint: '需要关注',
    icon: iconPending,
    color: C.orange,
    bars: [24, 16, 30, 20, 36, 26, 40, 32],
  },
])

/* ── 任务趋势（近 7/30/90 天：新增=createTime，完成=finishTime，按本地日聚合） ── */
const rangeDays = ref<7 | 30 | 90>(7)
const trendEl = ref<HTMLDivElement>()
let trendChart: echarts.ECharts | null = null

function dateKey(d: Date): string {
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}
const trendData = computed(() => {
  const n = rangeDays.value
  const labels: string[] = []
  const added: number[] = []
  const finished: number[] = []
  const weekCn = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(today.getTime() - i * 86_400_000)
    const key = dateKey(d)
    labels.push(n === 7 ? weekCn[d.getDay()] : `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`)
    added.push(tasks.value.filter((t) => t.createTime && dateKey(new Date(t.createTime)) === key).length)
    finished.push(
      tasks.value.filter((t) => t.finishTime && dateKey(new Date(t.finishTime)) === key).length,
    )
  }
  return { labels, added, finished }
})

function renderTrend(): void {
  if (!trendEl.value) return
  if (!trendChart) trendChart = echarts.init(trendEl.value)
  const { labels, added, finished } = trendData.value
  trendChart.setOption({
    color: [C.blue, C.green],
    legend: {
      top: 4,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      itemGap: 20,
      textStyle: { color: '#64748B', fontSize: 12 },
    },
    grid: { left: 8, right: 14, top: 42, bottom: 4, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: labels,
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#E5EAF0' } },
      axisTick: { show: false },
      axisLabel: { color: C.muted, fontSize: 12, margin: 14 },
    },
    yAxis: {
      type: 'value',
      splitNumber: 4,
      minInterval: 1,
      splitLine: { lineStyle: { color: '#F0F3F7' } },
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: C.muted, fontSize: 12 },
    },
    series: [
      {
        name: '新增任务',
        type: 'line',
        smooth: 0.4,
        symbol: 'circle',
        symbolSize: 7,
        lineStyle: { width: 2.5 },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(57,136,232,0.22)' },
              { offset: 1, color: 'rgba(57,136,232,0.02)' },
            ],
          },
        },
        data: added,
      },
      {
        name: '完成任务',
        type: 'line',
        smooth: 0.4,
        symbol: 'circle',
        symbolSize: 7,
        lineStyle: { width: 2.5 },
        data: finished,
      },
    ],
  })
}

/* ── 任务状态分布（环形 + 右侧图例，颜色按设计稿：蓝/青/橙） ── */
const donutEl = ref<HTMLDivElement>()
let donutChart: echarts.ECharts | null = null

const statusStats = computed(() => {
  const t = total.value
  const rows = [
    { name: '已完成', value: done.value, color: C.blue },
    { name: '进行中', value: doing.value, color: C.teal },
    { name: '待处理', value: pending.value, color: C.orange },
  ]
  return rows.map((r) => ({ ...r, pct: t ? Math.round((r.value / t) * 1000) / 10 : 0 }))
})

function renderDonut(): void {
  if (!donutEl.value) return
  if (!donutChart) donutChart = echarts.init(donutEl.value)
  const t = total.value
  donutChart.setOption({
    title: {
      text: String(t),
      subtext: '总任务数',
      left: 'center',
      // middle：文字组（数值+副文）垂直居中于容器 = 精确落在环形圆心，
      // 且图表高度任意变化都保持对齐（固定百分比会随高度漂移）
      top: 'middle',
      textStyle: { fontSize: 26, fontWeight: 700, color: C.text },
      subtextStyle: { fontSize: 12, color: C.muted },
    },
    tooltip: { trigger: 'item', formatter: '{b}：{c} 项（{d}%）' },
    series: [
      {
        type: 'pie',
        radius: ['62%', '84%'],
        center: ['50%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 3 },
        label: { show: false },
        labelLine: { show: false },
        data:
          t > 0
            ? statusStats.value.map((s) => ({
                name: s.name,
                value: s.value,
                itemStyle: { color: s.color },
              }))
            : // 空数据：浅灰底环，避免图表空白
              [{ value: 1, name: '', itemStyle: { color: '#E9EEF4' }, tooltip: { show: false } }],
      },
    ],
  })
}

function onResize(): void {
  trendChart?.resize()
  donutChart?.resize()
}
onMounted(() => {
  renderTrend()
  renderDonut()
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  trendChart?.dispose()
  donutChart?.dispose()
  trendChart = null
  donutChart = null
})
watch([tasks, rangeDays], () => {
  renderTrend()
  renderDonut()
})

/* ── 重点任务（任务管理「设重点」标记的任务，按计划日期最近在前最多 3 条；勾选走真接口并回读派生状态） ── */
const keyTasks = computed(() =>
  [...tasks.value]
    .filter((t) => t.isKey === 1)
    .sort((a, b) => (a.planDate ?? '9999-99-99').localeCompare(b.planDate ?? '9999-99-99'))
    .slice(0, 3),
)
async function toggleKeyRow(row: HomeTask): Promise<void> {
  const next = row.status === 2 ? 0 : 2
  try {
    await httpPut('/tasks', { id: row.id, status: next, progress: next === 2 ? 100 : 0 })
  } catch {
    return // 错误已由 request.ts 弹出
  }
  await loadTasks() // 回读：完成时间/父级派生以后端为准
}
function fmtMd(s: string | null): string {
  return s ? s.slice(5) : ''
}
const STATUS_TAG: Record<number, { label: string; cls: string }> = {
  0: { label: '待处理', cls: 'tag--pending' },
  1: { label: '进行中', cls: 'tag--doing' },
  2: { label: '已完成', cls: 'tag--done' },
  3: { label: '已延期', cls: 'tag--pending' },
}

/* ── AI 工作洞察：优先用 /api/insights/work 的 AI 文案；
 * 下面的 fallbackInsight 是本地规则兜底（按真实完成率分档，良好档与设计稿一致），
 * 在 AI 返回前占位、网络失败时保底 ── */
const fallbackInsight = computed(() => {
  const t = total.value
  const rate = t ? done.value / t : 0
  if (t === 0) {
    return {
      headline: '从第一件任务开始吧',
      para: '还没有任务记录，点击右上角「新增任务」开始记录你的工作轨迹。',
      advice: '万事开头难，先建一个小目标！',
    }
  }
  if (rate >= 0.6) {
    return {
      headline: '今日工作状态良好',
      para: '近期任务完成情况稳定，建议优先处理临近截止的任务，合理安排后续工作计划。',
      advice: '保持当前节奏，继续高效推进！',
    }
  }
  if (rate >= 0.2) {
    return {
      headline: '任务推进中，注意节奏',
      para: '部分任务仍在进行中，建议聚焦高优先级事项，为临近截止的任务预留缓冲时间。',
      advice: '抓大放小，优先攻克高优任务！',
    }
  }
  return {
    headline: '任务积压，需要加速',
    para: '待处理任务占比较高，建议今天先清理低优与过期事项，重新排定本周计划。',
    advice: '先清一批待办，再规划新任务！',
  }
})

/** AI 洞察返回体（GET /api/insights/work） */
interface AiInsight {
  headline: string
  para: string
  advice: string
  source: 'AI' | 'RULES'
}
const aiInsight = ref<AiInsight | null>(null)
async function loadInsight(): Promise<void> {
  try {
    aiInsight.value = await httpGet<AiInsight>('/insights/work')
  } catch {
    // 业务失败后端已降级 RULES 恒 200；此处多为网络异常，保留本地兜底（提示由 request.ts 统一处理）
  }
}
/** 展示优先级：AI 洞察 > 本地规则兜底 */
const activeInsight = computed(() => aiInsight.value ?? fallbackInsight.value)

/* ── AI 报告中心入口（带 type 预选跳转报告页） ── */
const reportTiles = [
  { label: '日报', sub: '今日工作总结', type: 'DAILY', icon: Document },
  { label: '周报', sub: '本周工作复盘', type: 'WEEKLY', icon: Calendar },
  { label: '月报', sub: '本月成果分析', type: 'MONTHLY', icon: TrendCharts },
]
</script>

<template>
  <div class="home-page" v-loading="loading">
    <!-- 问候横幅 -->
    <section class="banner">
      <svg
        class="banner__hills"
        viewBox="0 0 660 130"
        preserveAspectRatio="none"
        aria-hidden="true"
      >
        <path d="M0 130 L0 96 C 70 62, 150 58, 235 84 C 320 110, 400 96, 470 62 C 520 38, 580 34, 660 56 L660 130 Z" fill="#E1E9FA" />
        <path d="M0 130 L0 112 C 90 92, 170 90, 250 106 C 340 124, 430 116, 505 92 C 560 74, 610 72, 660 84 L660 130 Z" fill="#EDF2FD" />
      </svg>
      <div class="banner__left">
        <h1>{{ greeting }}，欢迎回来！</h1>
        <p>今天也要加油，完成更多有意义的工作<span class="banner__spark">✨</span></p>
      </div>
      <div class="banner__right">
        <div class="banner__date">
          <p class="date-main"><el-icon :size="15"><Calendar /></el-icon>{{ dateText }}</p>
          <p class="date-sub">{{ weekdayText }}<template v-if="lunarText"> · {{ lunarText }}</template></p>
        </div>
        <div class="banner__actions">
          <el-button size="large" class="btn-grad" :icon="Plus" @click="router.push('/tasks')">
            新增任务
          </el-button>
          <el-button size="large" class="btn-ai" :icon="MagicStick" @click="router.push('/reports')">
            AI 生成报告
          </el-button>
        </div>
      </div>
    </section>

    <!-- KPI 统计卡 -->
    <section class="kpi-row">
      <div v-for="k in kpis" :key="k.label" class="kpi card">
        <img class="kpi__icon" :src="k.icon" alt="" />
        <div class="kpi__text">
          <p class="kpi__label">{{ k.label }}</p>
          <p class="kpi__value">{{ k.value }}</p>
          <p class="kpi__hint">{{ k.hint }}</p>
        </div>
        <svg class="kpi__bars" viewBox="0 0 88 44" width="88" height="44" aria-hidden="true">
          <rect
            v-for="(h, i) in k.bars"
            :key="i"
            :x="i * 11"
            :y="44 - h"
            width="6"
            :height="h"
            rx="2"
            :fill="k.color"
            :opacity="i % 2 ? 0.9 : 0.4"
          />
        </svg>
      </div>
    </section>

    <!-- 图表行：任务趋势 + 任务状态分布 -->
    <section class="charts-row">
      <div class="card trend">
        <div class="card__head">
          <h2 class="card__title"><span class="title-bar" />任务走势</h2>
          <div class="range-pills">
            <button
              v-for="r in [7, 30, 90]"
              :key="r"
              type="button"
              :class="{ active: rangeDays === r }"
              @click="rangeDays = r as 7 | 30 | 90"
            >
              近{{ r }}天
            </button>
          </div>
        </div>
        <div ref="trendEl" class="trend__chart" />
      </div>

      <div class="card donut">
        <div class="card__head">
          <h2 class="card__title"><span class="title-bar" />任务状态分布</h2>
        </div>
        <div class="donut__body">
          <div ref="donutEl" class="donut__chart" />
          <ul class="donut__legend">
            <li v-for="s in statusStats" :key="s.name">
              <span class="dot" :style="{ background: s.color }" />
              <span class="name">{{ s.name }}</span>
              <span class="num">{{ s.value }}</span>
              <span class="pct">{{ s.pct }}%</span>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <!-- 底部：任务趋势（重点任务 + AI 工作洞察合并）/ AI 报告中心 -->
    <section class="bottom-row">
      <div class="card merged">
        <div class="card__head">
          <h2 class="card__title"><span class="title-bar" />任务趋势</h2>
          <RouterLink class="card__more" to="/tasks">查看全部 →</RouterLink>
        </div>
        <div class="merged__body">
          <div class="merged__col">
            <h3 class="merged__sub"><span class="title-bar title-bar--sm" />重点任务</h3>
            <ul v-if="keyTasks.length" class="today__list">
              <li v-for="row in keyTasks" :key="row.id" class="today__row">
                <span class="check" :class="{ done: row.status === 2 }" @click="toggleKeyRow(row)">
                  <el-icon v-if="row.status === 2" :size="12"><Check /></el-icon>
                </span>
                <span class="title" @click="router.push('/tasks')">{{ row.title }}</span>
                <span class="tag" :class="STATUS_TAG[row.status]?.cls">
                  {{ STATUS_TAG[row.status]?.label ?? '待办' }}
                </span>
                <span class="date">{{ fmtMd(row.planDate) }}</span>
              </li>
            </ul>
            <p v-else class="today__empty">还没有重点任务，去任务管理点「设重点」标记</p>
          </div>
          <div class="merged__col">
            <h3 class="merged__sub">
              <el-icon :size="15" color="#13B8A6"><MagicStick /></el-icon>
              AI 工作洞察
              <span class="pill">智能分析</span>
            </h3>
            <h4 class="insight__headline">{{ activeInsight.headline }}</h4>
            <p class="insight__para">{{ activeInsight.para }}</p>
            <p class="insight__advice">
              <span class="bulb">💡</span>
              <span class="label">AI 建议：</span>
              <span class="value">{{ activeInsight.advice }}</span>
            </p>
          </div>
        </div>
      </div>

      <div class="card reports">
        <div class="card__head">
          <h2 class="card__title"><span class="title-bar" />AI 报告中心</h2>
          <RouterLink class="card__more" to="/reports">更多 →</RouterLink>
        </div>
        <div class="reports__tiles">
          <div
            v-for="t in reportTiles"
            :key="t.type"
            class="tile"
            role="button"
            tabindex="0"
            @click="router.push(`/reports?type=${t.type}`)"
            @keyup.enter="router.push(`/reports?type=${t.type}`)"
          >
            <span class="tile__badge" :class="`tile__badge--${t.type.toLowerCase()}`">
              <el-icon :size="22" color="#fff"><component :is="t.icon" /></el-icon>
            </span>
            <p class="tile__name">{{ t.label }}</p>
            <p class="tile__sub">{{ t.sub }}</p>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.home-page {
  /* 四行网格：横幅/KPI 自适应 + 图表行/底行 1fr 等高分摊——右侧两卡恒等高 */
  display: grid;
  grid-template-rows: auto auto 1fr 1fr;
  gap: 16px;
  width: 100%;
  /* 填满 #app 剩余高度（壳层 min-height:100vh）；内容更高时自然撑开滚动 */
  flex: 1 0 auto;
  margin: 0;
  padding: 16px 24px;
  box-sizing: border-box;
  background: #f2f4f7;
}

.card {
  background: #fff;
  border: 1px solid #edf1f5;
  border-radius: 14px;
  box-shadow: 0 1px 2px rgba(37, 54, 74, 0.03);
}

.card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}

.card__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: #25364a;
  font-size: 16px;
  font-weight: 700;
}

.title-bar {
  width: 4px;
  height: 16px;
  border-radius: 2px;
  background: #3988e8;
}

.card__more {
  color: #8796a8;
  font-size: 13px;
  text-decoration: none;
}

.card__more:hover {
  color: #3988e8;
}

/* ── 问候横幅 ── */
.banner {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 104px;
  overflow: hidden;
  padding: 14px 32px;
  background: linear-gradient(100deg, #ffffff 0%, #fafcff 60%, #f1f6ff 100%);
}

.banner__hills {
  /* 拉伸满铺整个欢迎框（配合 preserveAspectRatio="none"），任意宽度无露底 */
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.banner__left {
  position: relative;
  z-index: 1;
}

.banner__left h1 {
  margin: 0 0 6px;
  color: #25364a;
  font-size: 22px;
  font-weight: 800;
  letter-spacing: 0.5px;
}

.banner__left p {
  margin: 0;
  color: #64748b;
  font-size: 14px;
}

.banner__spark {
  margin-left: 6px;
}

.banner__right {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 24px;
}

.banner__date .date-main {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: #25364a;
  font-size: 15px;
  font-weight: 600;
}

.banner__date .date-sub {
  margin: 4px 0 0 21px;
  color: #8796a8;
  font-size: 13px;
}

.banner__actions {
  display: flex;
  gap: 12px;
}

.btn-grad.el-button {
  border: 0;
  border-radius: 10px;
  background: linear-gradient(90deg, #13b8a6 0%, #3988e8 100%);
  color: #fff;
  font-weight: 600;
  box-shadow: 0 4px 10px rgba(57, 136, 232, 0.18);
}

.btn-grad.el-button:hover {
  opacity: 0.92;
  color: #fff;
}

.btn-ai.el-button {
  border: 1px solid #d8e7fc;
  border-radius: 10px;
  background: #f4f9ff;
  color: #3988e8;
  font-weight: 600;
}

.btn-ai.el-button:hover {
  background: #eaf3ff;
  color: #3988e8;
}

/* ── KPI 行 ── */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.kpi {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 22px 26px;
}

.kpi__icon {
  width: 48px;
  height: 48px;
  object-fit: contain;
}

.kpi__text {
  flex: 1;
  min-width: 0;
}

.kpi__label {
  margin: 0;
  color: #3a4a5f;
  font-size: 14px;
  font-weight: 600;
}

.kpi__value {
  margin: 2px 0;
  color: #25364a;
  font-size: 30px;
  font-weight: 800;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
}

.kpi__hint {
  margin: 0;
  color: #8796a8;
  font-size: 12px;
}

.kpi__bars {
  flex: none;
  margin-left: auto;
}

/* ── 图表行 ── */
.charts-row {
  display: grid;
  grid-template-columns: 1.7fr 1fr;
  gap: 16px;
  /* 与底行同一 1fr 行高：右侧「任务状态分布」「AI 报告中心」恒等高且列对齐 */
  min-height: 260px;
}

.trend,
.donut {
  display: flex;
  flex-direction: column;
  padding: 18px 20px 14px;
}

.range-pills {
  display: flex;
  gap: 2px;
  padding: 3px;
  border-radius: 9px;
  background: #f2f4f7;
}

.range-pills button {
  border: 0;
  border-radius: 7px;
  padding: 4px 14px;
  background: transparent;
  color: #64748b;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}

.range-pills button:hover {
  color: #3988e8;
}

.range-pills button.active {
  background: #3988e8;
  color: #fff;
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(57, 136, 232, 0.3);
}

/* 图表随卡片高度伸缩（flex:1），高度在挂载时即为最终值，ECharts 直接读取 */
.trend__chart {
  flex: 1;
  min-height: 180px;
}

.donut__body {
  display: flex;
  flex: 1;
  align-items: stretch;
  gap: 8px;
  min-height: 180px;
}

.donut__chart {
  flex: 0 0 56%;
}

.donut__legend {
  flex: 1;
  /* 图例在拉伸容器里保持垂直居中，与环图并排对齐 */
  align-self: center;
  margin: 0;
  padding: 0;
  list-style: none;
}

.donut__legend li {
  display: grid;
  grid-template-columns: 14px 1fr 40px 58px;
  align-items: center;
  gap: 8px;
  padding: 9px 0;
  font-size: 14px;
}

.donut__legend .dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.donut__legend .name {
  color: #3a4a5f;
}

.donut__legend .num {
  color: #25364a;
  font-weight: 700;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.donut__legend .pct {
  color: #64748b;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

/* ── 底部：任务趋势（合并）/ AI 报告中心 ── */
.bottom-row {
  display: grid;
  grid-template-columns: 1.7fr 1fr;
  gap: 16px;
  /* 与图表行同一 1fr 行高，整体协调对齐 */
  min-height: 260px;
}

.merged,
.reports {
  display: flex;
  flex-direction: column;
  padding: 18px 20px;
}

/* 合并模块：重点任务 | AI 工作洞察 左右分栏 */
.merged__body {
  display: flex;
  flex: 1;
  gap: 28px;
  min-height: 0;
}

.merged__col {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.merged__col + .merged__col {
  padding-left: 28px;
  border-left: 1px solid #f0f3f7;
}

.merged__sub {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 2px 0 8px;
  color: #25364a;
  font-size: 14px;
  font-weight: 700;
}

.title-bar--sm {
  width: 3px;
  height: 13px;
}

.today__list {
  /* 有任务时在剩余空间里垂直居中，避免卡高了内容却挤在顶部 */
  margin: auto 0;
  padding: 0;
  list-style: none;
}

.today__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid #f0f3f7;
}

.today__row:last-child {
  border-bottom: 0;
}

.check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 19px;
  height: 19px;
  flex: none;
  border: 2px solid #d5dde6;
  border-radius: 50%;
  color: #fff;
  cursor: pointer;
  transition: all 0.15s;
}

.check:hover {
  border-color: #13b8a6;
}

.check.done {
  border-color: #13b8a6;
  background: #13b8a6;
}

.today__row .title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: #25364a;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.today__row .title:hover {
  color: #3988e8;
}

.tag {
  flex: none;
  padding: 2px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
}

.tag--doing {
  background: #eaf3ff;
  color: #3988e8;
}

.tag--done {
  background: #e8f8f5;
  color: #13b8a6;
}

.tag--pending {
  background: #fdf3e8;
  color: #d99543;
}

.today__row .date {
  flex: none;
  width: 44px;
  color: #8796a8;
  font-size: 12px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.today__empty {
  margin: auto;
  color: #8796a8;
  font-size: 13px;
  text-align: center;
}

.pill {
  padding: 3px 10px;
  border-radius: 999px;
  background: #e8f8f5;
  color: #13b8a6;
  font-size: 12px;
  font-weight: 600;
}

.insight__headline {
  margin: 6px 0 8px;
  color: #13b8a6;
  font-size: 19px;
  font-weight: 800;
}

.insight__para {
  margin: 0;
  color: #64748b;
  font-size: 14px;
  line-height: 1.85;
}

.insight__advice {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: auto; /* 建议行钉在卡片底部，卡变高也不留空 */
  padding-top: 12px;
  border-top: 1px dashed #e5eaf0;
  font-size: 13px;
}

.insight__advice .bulb {
  font-size: 14px;
}

.insight__advice .label {
  color: #25364a;
  font-weight: 700;
}

.insight__advice .value {
  color: #13b8a6;
  font-weight: 700;
}

/* ── AI 报告中心 ── */
.reports__tiles {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  /* 卡片变高时报告格子垂直居中，不堆顶留空 */
  margin: auto 0;
}

.tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 20px 8px;
  border: 1px solid #edf1f5;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition: all 0.15s;
}

.tile:hover {
  border-color: #bcd7fb;
  background: #f8fbff;
  box-shadow: 0 4px 12px rgba(57, 136, 232, 0.08);
  transform: translateY(-1px);
}

.tile__badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  box-shadow: 0 4px 10px rgba(57, 136, 232, 0.18);
}

.tile__badge--daily {
  background: linear-gradient(135deg, #6faef2 0%, #3b82dc 100%);
  box-shadow: 0 4px 10px rgba(59, 130, 220, 0.28);
}

.tile__badge--weekly {
  background: linear-gradient(135deg, #56d0b4 0%, #2fb89e 100%);
  box-shadow: 0 4px 10px rgba(47, 184, 158, 0.28);
}

.tile__badge--monthly {
  background: linear-gradient(135deg, #7aa8f0 0%, #4e7fe0 100%);
  box-shadow: 0 4px 10px rgba(78, 127, 224, 0.28);
}

.tile__name {
  margin: 2px 0 0;
  color: #25364a;
  font-size: 14px;
  font-weight: 700;
}

.tile__sub {
  margin: 0;
  color: #8796a8;
  font-size: 11px;
  text-align: center;
}

/* ── 响应式（与任务页一致的收敛策略） ── */
@media (max-width: 1280px) {
  /* 窄屏单列堆叠：行高回归自然流，不做等高分配（等高是双列宽屏的布局目标） */
  .home-page {
    grid-template-rows: auto auto auto auto;
  }

  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }

  .charts-row,
  .bottom-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .home-page {
    padding: 14px 12px 24px;
  }

  .banner {
    padding: 18px 16px;
  }

  .banner__right {
    flex-wrap: wrap;
  }

  .kpi-row {
    grid-template-columns: 1fr;
  }
}
</style>
