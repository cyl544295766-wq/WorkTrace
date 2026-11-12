<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, type Directive } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  Calendar,
  Check,
  CircleCheck,
  Clock,
  Document,
  List,
  MagicStick,
  Plus,
  Sunny,
  TrendCharts,
  VideoPlay,
} from '@element-plus/icons-vue'
import { httpGet, httpPut } from '@/api/request'

// ── 按需注册：折线 + 环形（饼） + 常用组件 + Canvas 渲染器 ──
echarts.use([
  LineChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
])

/** 设计系统色板 */
const C = {
  blue: '#3978F6',
  blueSoft: '#78A8FF',
  teal: '#20C5C1',
  green: '#25B978',
  orange: '#FFA63D',
  purple: '#7C6CF0',
  text: '#24324A',
  muted: '#6B7A91', // 次级文字加深：图表轴标/图例对比度提升
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

/* ── 日期与问候（真实日期；农历走 ICU 中文历） ── */
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

/** 卡片右侧装饰柱：高度沿用设计稿形态，颜色跟随卡片主题 */
const kpis = computed(() => [
  {
    label: '全部任务',
    value: total.value,
    hint: '累计任务',
    icon: List,
    color: C.blue,
    soft: C.blueSoft,
    glow: 'rgba(57, 120, 246, 0.24)',
    bars: [14, 22, 16, 30, 20, 34, 24, 38],
  },
  {
    label: '进行中',
    value: doing.value,
    hint: '正在推进',
    icon: VideoPlay,
    color: C.teal,
    soft: C.blueSoft,
    glow: 'rgba(32, 197, 193, 0.22)',
    bars: [20, 14, 26, 18, 32, 22, 36, 26],
  },
  {
    label: '已完成',
    value: done.value,
    hint: '累计完成',
    icon: CircleCheck,
    color: C.green,
    soft: '#7BE0B4',
    glow: 'rgba(37, 185, 120, 0.20)',
    bars: [12, 20, 28, 18, 34, 24, 38, 30],
  },
  {
    label: '待处理',
    value: pending.value,
    hint: '需要关注',
    icon: Clock,
    color: C.orange,
    soft: '#FFD39A',
    glow: 'rgba(255, 166, 61, 0.22)',
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
    labels.push(
      n === 7
        ? weekCn[d.getDay()]
        : `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`,
    )
    added.push(tasks.value.filter((t) => t.createTime && dateKey(new Date(t.createTime)) === key).length)
    finished.push(
      tasks.value.filter((t) => t.finishTime && dateKey(new Date(t.finishTime)) === key).length,
    )
  }
  return { labels, added, finished }
})

function prefersReducedMotion(): boolean {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** 玻璃拟态 Tooltip（趋势 / 环形共用）：更通透的毛玻璃 + 柔和投影 */
const glassTooltip = {
  backgroundColor: 'rgba(255, 255, 255, 0.78)',
  borderColor: 'rgba(255, 255, 255, 0.9)',
  borderWidth: 1,
  padding: [11, 15],
  textStyle: { color: C.text, fontSize: 12.5 },
  extraCssText:
    'backdrop-filter: blur(18px) saturate(1.4); -webkit-backdrop-filter: blur(18px) saturate(1.4);'
    + 'border-radius: 14px; box-shadow: 0 20px 44px -20px rgba(36, 50, 74, 0.3), 0 2px 8px rgba(36, 50, 74, 0.06);',
}

function renderTrend(): void {
  if (!trendEl.value) return
  if (!trendChart) trendChart = echarts.init(trendEl.value)
  const { labels, added, finished } = trendData.value
  const reduce = prefersReducedMotion()
  const showSymbol = rangeDays.value <= 30
  const symbolSize = showSymbol ? 6 : 0
  trendChart.setOption({
    animation: !reduce,
    animationDuration: reduce ? 0 : 900,
    animationEasing: 'cubicOut',
    color: [C.blue, C.green],
    legend: {
      top: 2,
      right: 4,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      itemGap: 18,
      textStyle: { color: C.muted, fontSize: 13 },
    },
    grid: { left: 6, right: 16, top: 44, bottom: 6, containLabel: true },
    tooltip: {
      trigger: 'axis',
      confine: true,
      ...glassTooltip,
      axisPointer: {
        type: 'line',
        lineStyle: { color: 'rgba(57, 120, 246, 0.24)', width: 1, type: 'dashed' },
      },
    },
    xAxis: {
      type: 'category',
      data: labels,
      boundaryGap: false,
      axisLine: { lineStyle: { color: 'rgba(137, 151, 172, 0.18)' } },
      axisTick: { show: false },
      axisLabel: { color: C.muted, fontSize: 12, margin: 14 },
    },
    yAxis: {
      type: 'value',
      splitNumber: 4,
      minInterval: 1,
      splitLine: { lineStyle: { color: 'rgba(137, 151, 172, 0.13)' } },
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: C.muted, fontSize: 12 },
    },
    series: [
      {
        name: '新增任务',
        type: 'line',
        smooth: 0.45,
        showSymbol,
        symbol: 'circle',
        symbolSize,
        lineStyle: {
          width: 3,
          color: C.blue,
          shadowBlur: 14,
          shadowColor: 'rgba(57, 120, 246, 0.28)',
          shadowOffsetY: 6,
        },
        itemStyle: {
          color: C.blue,
          borderColor: '#fff',
          borderWidth: 2,
          shadowBlur: 12,
          shadowColor: 'rgba(57, 120, 246, 0.45)',
        },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(57, 120, 246, 0.30)' },
              { offset: 0.55, color: 'rgba(57, 120, 246, 0.11)' },
              { offset: 1, color: 'rgba(57, 120, 246, 0.01)' },
            ],
          },
        },
        emphasis: { focus: 'series' },
        data: added,
      },
      {
        name: '完成任务',
        type: 'line',
        smooth: 0.45,
        showSymbol,
        symbol: 'circle',
        symbolSize: Math.max(symbolSize - 1, 0),
        itemStyle: {
          color: C.green,
          borderColor: '#fff',
          borderWidth: 2,
          shadowBlur: 10,
          shadowColor: 'rgba(37, 185, 120, 0.38)',
        },
        lineStyle: {
          width: 2.5,
          color: C.green,
          shadowBlur: 10,
          shadowColor: 'rgba(37, 185, 120, 0.22)',
          shadowOffsetY: 5,
        },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(37, 185, 120, 0.16)' },
              { offset: 0.6, color: 'rgba(37, 185, 120, 0.05)' },
              { offset: 1, color: 'rgba(37, 185, 120, 0.00)' },
            ],
          },
        },
        emphasis: { focus: 'series' },
        data: finished,
      },
    ],
  })
}

/* ── 任务状态分布（立体环形 + 右侧图例） ── */
const donutEl = ref<HTMLDivElement>()
let donutChart: echarts.ECharts | null = null

const statusStats = computed(() => {
  const t = total.value
  const rows = [
    {
      name: '已完成',
      value: done.value,
      color: C.green,
      light: '#7BE0B4',
      deep: '#168A58',
      glow: 'rgba(37, 185, 120, 0.16)',
    },
    {
      name: '进行中',
      value: doing.value,
      color: C.teal,
      light: '#8FF3EE',
      deep: '#128F8C',
      glow: 'rgba(32, 197, 193, 0.16)',
    },
    {
      name: '待处理',
      value: pending.value,
      color: C.orange,
      light: '#FFD39A',
      deep: '#D9821F',
      glow: 'rgba(255, 166, 61, 0.16)',
    },
  ]
  return rows.map((r) => ({ ...r, pct: t ? Math.round((r.value / t) * 1000) / 10 : 0 }))
})

function renderDonut(): void {
  if (!donutEl.value) return
  if (!donutChart) donutChart = echarts.init(donutEl.value)
  const reduce = prefersReducedMotion()
  const t = total.value
  const empty = t === 0
  const activeSectors = statusStats.value.filter((s) => s.value > 0).length
  const depthData = empty
    ? [{ value: 1, itemStyle: { color: '#DCE5F2' } }]
    : statusStats.value.map((s) => ({
        name: s.name,
        value: s.value,
        itemStyle: { color: s.deep, opacity: 0.32 },
      }))
  const mainData = empty
    ? [{ value: 1, name: '', itemStyle: { color: '#E9EEF4' }, tooltip: { show: false } }]
    : statusStats.value.map((s) => ({
        name: s.name,
        value: s.value,
        itemStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 1,
            y2: 1,
            colorStops: [
              { offset: 0, color: s.light },
              { offset: 1, color: s.color },
            ],
          },
        },
      }))
  donutChart.setOption({
    animation: !reduce,
    animationDuration: reduce ? 0 : 950,
    animationEasing: 'cubicOut',
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: '{b}：{c} 项（{d}%）',
      ...glassTooltip,
    },
    series: [
      {
        type: 'pie',
        radius: ['62%', '85.4%'],
        center: ['50%', '51.15%'],
        silent: true,
        animation: false,
        z: 1,
        label: { show: false },
        labelLine: { show: false },
        itemStyle: { borderWidth: 0, shadowBlur: 10, shadowColor: 'rgba(36, 50, 74, 0.1)' },
        data: depthData,
      },
      {
        type: 'pie',
        radius: ['62%', '84%'],
        center: ['50%', '50%'],
        z: 3,
        avoidLabelOverlap: true,
        selectedMode: false,
        itemStyle: {
          borderColor: 'rgba(255, 255, 255, 0.95)',
          borderWidth: 3,
          borderRadius: activeSectors > 1 ? 9 : 0,
          shadowBlur: 18,
          shadowColor: 'rgba(36, 50, 74, 0.12)',
          shadowOffsetY: 8,
        },
        emphasis: {
          scale: true,
          scaleSize: 7,
          itemStyle: {
            shadowBlur: 24,
            shadowColor: 'rgba(36, 50, 74, 0.2)',
            shadowOffsetY: 8,
          },
        },
        label: { show: false },
        labelLine: { show: false },
        data: mainData,
      },
    ],
  })
}

function onResize(): void {
  trendChart?.resize()
  donutChart?.resize()
}

let chartObserver: ResizeObserver | null = null

onMounted(() => {
  renderTrend()
  renderDonut()
  window.addEventListener('resize', onResize)
  if (typeof ResizeObserver !== 'undefined') {
    chartObserver = new ResizeObserver(() => {
      trendChart?.resize()
      donutChart?.resize()
    })
    if (trendEl.value) chartObserver.observe(trendEl.value)
    if (donutEl.value) chartObserver.observe(donutEl.value)
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chartObserver?.disconnect()
  chartObserver = null
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
 * 下面的 fallbackInsight 是本地规则兜底（按真实完成率分档），
 * 在 AI 返回前占位、网络失败时保底 ── */
const fallbackInsight = computed(() => {
  const t = total.value
  const rate = t ? done.value / t : 0
  if (t === 0) {
    return {
      headline: '从第一件任务开始吧',
      para: '还没有任务记录，点击右上角「新增任务」开始记录你的工作轨迹。',
      advice: '万事开头难，先建一个小目标！',
      source: 'RULES' as const,
    }
  }
  if (rate >= 0.6) {
    return {
      headline: '今日工作状态良好',
      para: '近期任务完成情况稳定，建议优先处理临近截止的任务，合理安排后续工作计划。',
      advice: '保持当前节奏，继续高效推进！',
      source: 'RULES' as const,
    }
  }
  if (rate >= 0.2) {
    return {
      headline: '任务推进中，注意节奏',
      para: '部分任务仍在进行中，建议聚焦高优先级事项，为临近截止的任务预留缓冲时间。',
      advice: '抓大放小，优先攻克高优任务！',
      source: 'RULES' as const,
    }
  }
  return {
    headline: '任务积压，需要加速',
    para: '待处理任务占比较高，建议今天先清理低优与过期事项，重新排定本周计划。',
    advice: '先清一批待办，再规划新任务！',
    source: 'RULES' as const,
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
    // 业务失败后端已降级 RULES 恒 200；此处多为网络异常，保留本地兜底
  }
}
/** 展示优先级：AI 洞察 > 本地规则兜底 */
const activeInsight = computed(() => aiInsight.value ?? fallbackInsight.value)

/** 洞察面板的关键数据条：全部由已加载任务派生（无新增接口），用于分层展示与重点着色 */
const insightStats = computed(() => {
  const t = total.value
  const rate = t ? Math.round((done.value / t) * 100) : 0
  const today = dateKey(new Date())
  const todayNew = tasks.value.filter((t2) => t2.createTime && dateKey(new Date(t2.createTime)) === today).length
  const keyCount = tasks.value.filter((t2) => t2.isKey === 1).length
  return [
    { label: '完成率', value: `${rate}%`, color: '#25B978' },
    { label: '今日新增', value: `${todayNew}`, color: '#3978F6' },
    { label: '重点任务', value: `${keyCount}`, color: '#FF8A3D' },
  ]
})

/* ── AI 报告中心入口（带 type 预选跳转报告页；文案只描述已有的日/周/月报能力） ── */
const reportTiles = [
  {
    label: '日报',
    sub: '今日完成与明日重点，精简输出',
    cta: '生成日报',
    type: 'DAILY',
    icon: Document,
    color: C.blue,
    soft: C.blueSoft,
    glow: 'rgba(57, 120, 246, 0.18)',
  },
  {
    label: '周报',
    sub: '本周完成情况与下周计划',
    cta: '生成周报',
    type: 'WEEKLY',
    icon: Calendar,
    color: C.teal,
    soft: '#8FF3EE',
    glow: 'rgba(32, 197, 193, 0.18)',
  },
  {
    label: '月报',
    sub: '整月成果、问题与后续规划',
    cta: '生成月报',
    type: 'MONTHLY',
    icon: TrendCharts,
    color: C.purple,
    soft: '#B9AEFF',
    glow: 'rgba(124, 108, 240, 0.18)',
  },
]

/* ── 微交互：卡片 3D 倾斜 + 页面光斑视差（rAF + reduced-motion） ── */
const pageEl = ref<HTMLDivElement>()
const tiltCleanup = new WeakMap<HTMLElement, () => void>()

const vTilt: Directive<HTMLElement, { max?: number } | undefined> = {
  mounted(el, binding) {
    if (prefersReducedMotion() || !window.matchMedia('(pointer: fine)').matches) return
    const max = binding.value?.max ?? 4
    let rect = el.getBoundingClientRect()
    let raf = 0
    let rx = 0
    let ry = 0
    const apply = () => {
      raf = 0
      el.style.setProperty('--rx', `${rx.toFixed(2)}deg`)
      el.style.setProperty('--ry', `${ry.toFixed(2)}deg`)
    }
    const refreshRect = () => {
      rect = el.getBoundingClientRect()
    }
    const onMove = (e: PointerEvent) => {
      if (e.pointerType === 'touch' || !rect.width || !rect.height) return
      const x = (e.clientX - rect.left) / rect.width
      const y = (e.clientY - rect.top) / rect.height
      rx = (0.5 - y) * max * 2
      ry = (x - 0.5) * max * 2
      el.style.setProperty('--mx', `${(x * 100).toFixed(1)}%`)
      el.style.setProperty('--my', `${(y * 100).toFixed(1)}%`)
      if (!raf) raf = requestAnimationFrame(apply)
    }
    const onLeave = () => {
      if (raf) cancelAnimationFrame(raf)
      raf = 0
      rx = 0
      ry = 0
      apply()
      el.style.setProperty('--mx', '50%')
      el.style.setProperty('--my', '50%')
    }
    el.addEventListener('pointerenter', refreshRect, { passive: true })
    el.addEventListener('pointermove', onMove, { passive: true })
    el.addEventListener('pointerleave', onLeave, { passive: true })
    tiltCleanup.set(el, () => {
      el.removeEventListener('pointerenter', refreshRect)
      el.removeEventListener('pointermove', onMove)
      el.removeEventListener('pointerleave', onLeave)
      if (raf) cancelAnimationFrame(raf)
    })
  },
  unmounted(el) {
    tiltCleanup.get(el)?.()
    tiltCleanup.delete(el)
  },
}

let pageRaf = 0
let nextPx = 0
let nextPy = 0
function onPageMove(e: PointerEvent): void {
  if (e.pointerType === 'touch' || prefersReducedMotion()) return
  const el = pageEl.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  if (!rect.width || !rect.height) return
  nextPx = (e.clientX - rect.left) / rect.width - 0.5
  nextPy = (e.clientY - rect.top) / rect.height - 0.5
  if (!pageRaf) {
    pageRaf = requestAnimationFrame(() => {
      pageRaf = 0
      el.style.setProperty('--px', nextPx.toFixed(3))
      el.style.setProperty('--py', nextPy.toFixed(3))
    })
  }
}
onMounted(() => {
  if (window.matchMedia('(pointer: fine)').matches && !prefersReducedMotion()) {
    pageEl.value?.addEventListener('pointermove', onPageMove, { passive: true })
  }
})
onBeforeUnmount(() => {
  pageEl.value?.removeEventListener('pointermove', onPageMove)
  if (pageRaf) cancelAnimationFrame(pageRaf)
})
</script>

<template>
  <div ref="pageEl" class="home-page" v-loading="loading">
    <!-- 环境光：缓慢漂浮的蓝色/青色光斑 + 极淡点阵 -->
    <div class="home-bg" aria-hidden="true">
      <span class="ambient ambient--blue" />
      <span class="ambient ambient--teal" />
      <span class="ambient ambient--amber" />
      <span class="home-bg__grid" />
    </div>

    <!-- 欢迎横幅 -->
    <section class="banner reveal" :style="{ '--i': 0 }">
      <svg
        class="banner__waves"
        viewBox="0 0 1440 190"
        preserveAspectRatio="none"
        aria-hidden="true"
      >
        <defs>
          <linearGradient id="bannerWaveA" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#DCE9FF" />
            <stop offset="1" stop-color="#EAF5FF" />
          </linearGradient>
          <linearGradient id="bannerWaveB" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#EDF4FF" />
            <stop offset="1" stop-color="#E7FBF8" />
          </linearGradient>
          <linearGradient id="bannerWaveC" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stop-color="#F7FBFF" stop-opacity="0.9" />
            <stop offset="1" stop-color="#EAF7FF" stop-opacity="0.4" />
          </linearGradient>
        </defs>
        <path
          d="M0 148 C 180 118, 320 112, 480 132 C 660 154, 800 150, 980 118 C 1140 90, 1280 92, 1440 116 L1440 190 L0 190 Z"
          fill="url(#bannerWaveA)"
        />
        <path
          d="M0 166 C 160 146, 300 142, 460 158 C 650 176, 820 172, 1000 146 C 1170 122, 1300 124, 1440 142 L1440 190 L0 190 Z"
          fill="url(#bannerWaveB)"
        />
        <path
          d="M0 180 C 220 164, 380 162, 560 174 C 760 188, 920 186, 1100 168 C 1240 154, 1350 156, 1440 166 L1440 190 L0 190 Z"
          fill="url(#bannerWaveC)"
        />
      </svg>
      <span class="banner__orb banner__orb--1" aria-hidden="true" />
      <span class="banner__orb banner__orb--2" aria-hidden="true" />
      <span class="banner__orb banner__orb--3" aria-hidden="true" />
      <span class="banner__ring banner__ring--lg" aria-hidden="true" />
      <span class="banner__ring banner__ring--sm" aria-hidden="true" />

      <div class="banner__left">
        <p class="banner__eyebrow">
          <span class="banner__eyebrow-dot" />
          WORKTRACE · WORKSPACE
        </p>
        <h1>
          <span class="banner__title-text">{{ greeting }}，欢迎回来</span>
          <span class="banner__spark" aria-hidden="true">✦</span>
        </h1>
        <p class="banner__sub">今天也要加油，完成更多有意义的工作</p>
      </div>

      <div class="banner__right">
        <div class="banner__date">
          <span class="banner__date-icon">
            <el-icon :size="17"><Calendar /></el-icon>
          </span>
          <div>
            <p class="date-main">{{ dateText }}</p>
            <p class="date-sub">
              {{ weekdayText }}<template v-if="lunarText"> · {{ lunarText }}</template>
            </p>
          </div>
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
    <section class="kpi-row" aria-label="任务统计">
      <article
        v-for="(k, i) in kpis"
        :key="k.label"
        v-tilt="{ max: 2.2 }"
        class="kpi reveal"
        :style="{
          '--i': i + 1,
          '--accent': k.color,
          '--accent-soft': k.soft,
          '--glow': k.glow,
        }"
      >
        <div class="kpi__icon-wrap">
          <span class="kpi__orb">
            <el-icon :size="22"><component :is="k.icon" /></el-icon>
          </span>
        </div>
        <div class="kpi__text">
          <p class="kpi__label">{{ k.label }}</p>
          <p class="kpi__value">{{ k.value }}</p>
          <p class="kpi__hint">{{ k.hint }}</p>
        </div>
        <div class="kpi__bars" aria-hidden="true">
          <span
            v-for="(h, j) in k.bars"
            :key="j"
            class="kpi__bar"
            :style="{ height: `${h}px`, '--bar-delay': `${j * 45}ms` }"
          />
        </div>
      </article>
    </section>

    <!-- 图表行：任务趋势 + 任务状态分布 -->
    <section class="charts-row">
      <div class="card chart-card trend reveal" :style="{ '--i': 5 }">
        <div class="card__head">
          <h2 class="card__title"><span class="title-bar" />任务走势</h2>
          <div class="range-pills" role="group" aria-label="时间范围">
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

      <div class="card chart-card donut reveal" :style="{ '--i': 6 }">
        <div class="card__head">
          <h2 class="card__title">
            <span class="title-bar title-bar--teal" />任务状态分布
          </h2>
        </div>
        <div class="donut__body">
          <div class="donut__chart-wrap">
            <div ref="donutEl" class="donut__chart" />
            <div class="donut__center" aria-label="总任务数">
              <strong>{{ total }}</strong>
              <span>总任务数</span>
            </div>
          </div>
          <ul class="donut__legend">
            <li v-for="s in statusStats" :key="s.name">
              <div class="donut__legend-row">
                <span
                  class="dot"
                  :style="{ background: s.color, boxShadow: `0 0 0 4px ${s.glow}` }"
                />
                <span class="name">{{ s.name }}</span>
                <span class="num">{{ s.value }}</span>
                <span class="pct"><b>{{ s.pct }}</b>%</span>
              </div>
              <span class="legend-track">
                <i
                  :style="{
                    width: `${s.pct}%`,
                    background: `linear-gradient(90deg, ${s.light}, ${s.color})`,
                  }"
                />
              </span>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <!-- 底部：重点任务 + AI 工作洞察 / AI 报告中心 -->
    <section class="bottom-row">
      <div class="card merged reveal" :style="{ '--i': 7 }">
        <div class="card__head">
          <h2 class="card__title"><span class="title-bar" />今日聚焦</h2>
          <RouterLink class="card__more" to="/tasks">查看全部 →</RouterLink>
        </div>
        <div class="merged__body">
          <div class="merged__col">
            <h3 class="merged__sub"><span class="title-bar title-bar--sm" />重点任务</h3>
            <ul v-if="keyTasks.length" class="today__list">
              <li v-for="row in keyTasks" :key="row.id" class="today__row">
                <button
                  type="button"
                  class="check"
                  :class="{ done: row.status === 2 }"
                  :aria-label="row.status === 2 ? '标记为待处理' : '标记为已完成'"
                  @click="toggleKeyRow(row)"
                >
                  <el-icon v-if="row.status === 2" :size="12"><Check /></el-icon>
                </button>
                <span class="title" @click="router.push('/tasks')">{{ row.title }}</span>
                <span class="tag" :class="STATUS_TAG[row.status]?.cls">
                  {{ STATUS_TAG[row.status]?.label ?? '待办' }}
                </span>
                <span class="date">{{ fmtMd(row.planDate) }}</span>
              </li>
            </ul>
            <div v-else class="empty-focus">
              <div class="empty-focus__art" aria-hidden="true">
                <span class="empty-focus__card">
                  <span class="empty-focus__check">
                    <el-icon :size="13"><Check /></el-icon>
                  </span>
                  <span class="empty-focus__line empty-focus__line--1" />
                  <span class="empty-focus__line empty-focus__line--2" />
                </span>
                <span class="empty-focus__orb empty-focus__orb--1" />
                <span class="empty-focus__orb empty-focus__orb--2" />
              </div>
              <p class="empty-focus__title">还没有重点任务</p>
              <p class="empty-focus__hint">去任务管理点「设重点」，让关键事项浮到首页</p>
              <RouterLink class="empty-focus__link" to="/tasks">去标记重点 →</RouterLink>
            </div>
          </div>

          <div class="merged__col merged__col--ai">
            <h3 class="merged__sub">
              <span class="ai-sub-icon"><el-icon :size="13"><MagicStick /></el-icon></span>
              AI 工作洞察
              <span class="pill">智能分析</span>
            </h3>
            <div class="ai-panel">
              <span class="ai-panel__glow" aria-hidden="true" />
              <!-- 第一部分：模块标识（图标 + 分析状态）；标题与「智能分析」标签在上方栏头 -->
              <div class="ai-panel__head">
                <span class="ai-orb" aria-hidden="true">
                  <span class="ai-orb__ring" />
                  <span class="ai-orb__core"><el-icon :size="19"><MagicStick /></el-icon></span>
                </span>
                <div class="ai-panel__meta">
                  <span class="ai-panel__eyebrow">AI WORK INSIGHT</span>
                  <span class="ai-panel__live">
                    <i />
                    {{ activeInsight.source === 'AI' ? 'AI 实时分析' : '智能规则分析' }}
                  </span>
                </div>
              </div>

              <!-- 第二部分：关键数据指标（全部由已加载任务派生，数字用主题色强调） -->
              <div class="ai-section">
                <p class="ai-section__label">关键数据<i aria-hidden="true" /></p>
                <div class="ai-stats" role="list">
                  <div
                    v-for="s in insightStats"
                    :key="s.label"
                    role="listitem"
                    class="ai-stat"
                    :style="{ '--stat': s.color }"
                  >
                    <strong>{{ s.value }}</strong>
                    <span>{{ s.label }}</span>
                  </div>
                </div>
              </div>

              <!-- 第三部分：AI 分析正文 -->
              <div class="ai-section ai-section--body">
                <p class="ai-section__label">AI 分析<i aria-hidden="true" /></p>
                <h4 class="ai-panel__headline">{{ activeInsight.headline }}</h4>
                <p class="ai-panel__para">{{ activeInsight.para }}</p>
              </div>

              <!-- 第四部分：AI 建议 -->
              <div class="ai-advice">
                <span class="ai-advice__icon"><el-icon :size="15"><Sunny /></el-icon></span>
                <div class="ai-advice__body">
                  <span class="ai-advice__label">AI 建议</span>
                  <span class="ai-advice__value">{{ activeInsight.advice }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="card reports reveal" :style="{ '--i': 8 }">
        <div class="card__head">
          <h2 class="card__title"><span class="title-bar title-bar--violet" />AI 报告中心</h2>
          <RouterLink class="card__more" to="/reports">更多 →</RouterLink>
        </div>
        <div class="reports__tiles">
          <div
            v-for="(t, i) in reportTiles"
            :key="t.type"
            v-tilt="{ max: 3 }"
            class="tile reveal"
            role="button"
            tabindex="0"
            :style="{
              '--i': 9 + i,
              '--accent': t.color,
              '--accent-soft': t.soft,
              '--glow': t.glow,
            }"
            @click="router.push(`/reports?type=${t.type}`)"
            @keyup.enter="router.push(`/reports?type=${t.type}`)"
            @keydown.space.prevent="router.push(`/reports?type=${t.type}`)"
          >
            <span class="tile__badge">
              <span class="tile__badge-orb">
                <el-icon :size="24"><component :is="t.icon" /></el-icon>
              </span>
            </span>
            <div class="tile__text">
              <p class="tile__name">{{ t.label }}</p>
              <p class="tile__sub">{{ t.sub }}</p>
            </div>
            <span class="tile__divider" aria-hidden="true" />
            <span class="tile__cta">{{ t.cta }}<i aria-hidden="true">→</i></span>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* ═══════════════════════════════════════════════════════════════
   WorkTrace Home · 3D Neumorphism / Glass / Spatial UI
   所有样式均为页面级 scoped，不污染其他路由
   ═══════════════════════════════════════════════════════════════ */

.home-page {
  --bg: #f2f5fb;
  --text: #24324a;
  --muted: #6b7a91; /* 次级文字：对比度提升一档，长文阅读更省力 */
  --primary: #3978f6;
  --cyan: #20c5c1;
  --green: #25b978;
  --orange: #ffa63d;
  --violet: #7c6cf0;
  --radius: 20px; /* 外层卡片统一圆角 */
  --radius-md: 16px; /* 卡片内嵌面板 / 入口卡统一圆角 */
  --btn-h: 42px; /* 主按钮统一高度 */
  --gap: 18px; /* 模块栅格统一间距 */
  /* 阴影规范：整体强度较上一版下调约 20%，并削弱卡片底部蓝色光晕 */
  --shadow-card:
    0 1px 0 rgba(255, 255, 255, 0.9) inset,
    inset 0 -1px 0 rgba(120, 168, 255, 0.08),
    inset 0 -20px 34px -32px rgba(57, 120, 246, 0.14),
    0 1px 2px rgba(36, 50, 74, 0.04),
    0 10px 22px -16px rgba(36, 50, 74, 0.26),
    0 26px 52px -36px rgba(57, 120, 246, 0.2);
  --shadow-hover:
    0 1px 0 rgba(255, 255, 255, 0.96) inset,
    inset 0 -20px 34px -32px rgba(57, 120, 246, 0.16),
    0 2px 4px rgba(36, 50, 74, 0.04),
    0 16px 30px -18px rgba(36, 50, 74, 0.3),
    0 34px 66px -40px rgba(57, 120, 246, 0.26);

  position: relative;
  isolation: isolate;
  display: grid;
  grid-template-rows: auto auto auto auto; /* 行高由内容决定，图表行不再被底部行撑高 */
  gap: var(--gap);
  width: 100%;
  flex: 1 0 auto;
  margin: 0;
  padding: 20px 24px 28px;
  box-sizing: border-box;
  color: var(--text);
  font-family:
    -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB',
    'Microsoft YaHei', sans-serif;
  background:
    radial-gradient(1100px 620px at 7% -8%, rgba(120, 168, 255, 0.2), transparent 62%),
    radial-gradient(900px 560px at 96% 2%, rgba(32, 197, 193, 0.13), transparent 62%),
    radial-gradient(820px 460px at 46% 44%, rgba(120, 168, 255, 0.06), transparent 70%),
    radial-gradient(720px 520px at 48% 108%, rgba(124, 108, 240, 0.05), transparent 64%),
    var(--bg);
}

.home-page > section {
  position: relative;
  z-index: 1;
}

/* ── 环境光与点阵 ── */
.home-bg {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}

.ambient {
  position: absolute;
  border-radius: 50%;
  filter: blur(70px);
  opacity: 0.42;
  transform: translate3d(calc(var(--px, 0) * 22px), calc(var(--py, 0) * 18px), 0);
  transition: transform 0.45s ease-out;
  will-change: transform, translate;
}

.ambient--blue {
  width: 380px;
  height: 380px;
  top: -110px;
  left: -70px;
  background: radial-gradient(circle, rgba(120, 168, 255, 0.34), transparent 68%);
  animation: blobFloat 18s ease-in-out infinite alternate;
}

.ambient--teal {
  width: 330px;
  height: 330px;
  top: 240px;
  right: -90px;
  background: radial-gradient(circle, rgba(32, 197, 193, 0.3), transparent 68%);
  animation: blobFloat 22s ease-in-out infinite alternate-reverse;
}

.ambient--amber {
  width: 280px;
  height: 280px;
  bottom: 3%;
  left: 42%;
  background: radial-gradient(circle, rgba(255, 166, 61, 0.13), transparent 70%);
  animation: blobFloat 26s ease-in-out infinite alternate;
}

.home-bg__grid {
  position: absolute;
  inset: 0;
  opacity: 0.4;
  background-image: radial-gradient(circle, rgba(57, 120, 246, 0.05) 1px, transparent 1px);
  background-size: 26px 26px;
  -webkit-mask-image: linear-gradient(to bottom, rgba(0, 0, 0, 0.65), transparent 72%);
  mask-image: linear-gradient(to bottom, rgba(0, 0, 0, 0.65), transparent 72%);
}

/* ── 入场动画：使用独立 translate 属性，避免覆盖卡片 hover 的 transform ── */
.reveal {
  opacity: 0;
  animation: reveal 0.7s cubic-bezier(0.2, 0.8, 0.2, 1) both;
  animation-delay: calc(var(--i, 0) * 70ms);
}

@keyframes reveal {
  from {
    opacity: 0;
    translate: 0 18px;
  }
  to {
    opacity: 1;
    translate: 0 0;
  }
}

/* ── 通用卡片：白色半透明 + 多层柔和阴影 + 白色高光边 ── */
.card {
  --lift: 0px;
  position: relative;
  border: 1px solid rgba(227, 235, 247, 0.9);
  border-radius: var(--radius);
  background:
    radial-gradient(520px 260px at 100% 0%, rgba(120, 168, 255, 0.07), transparent 70%),
    linear-gradient(145deg, rgba(255, 255, 255, 0.92) 0%, rgba(255, 255, 255, 0.68) 100%);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px) saturate(1.08);
  -webkit-backdrop-filter: blur(18px) saturate(1.08);
  transform: translate3d(0, var(--lift), 0);
  transition:
    transform 0.35s cubic-bezier(0.2, 0.8, 0.2, 1),
    box-shadow 0.35s ease,
    border-color 0.35s ease;
}

.card:hover {
  border-color: rgba(255, 255, 255, 0.9);
  box-shadow: var(--shadow-hover);
  will-change: transform;
}

.chart-card:hover,
.merged:hover,
.reports:hover {
  --lift: -4px;
}

.card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.card__title {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0;
  color: var(--text);
  font-size: 17px;
  font-weight: 750;
  letter-spacing: -0.015em;
}

.title-bar {
  width: 4px;
  height: 18px;
  border-radius: 999px;
  background: linear-gradient(180deg, #8fb8ff, #3978f6);
  box-shadow: 0 0 10px rgba(57, 120, 246, 0.3);
}

.title-bar--teal {
  background: linear-gradient(180deg, #8ff3ee, #20c5c1);
  box-shadow: 0 0 10px rgba(32, 197, 193, 0.28);
}

.title-bar--violet {
  background: linear-gradient(180deg, #c4bcff, #7c6cf0);
  box-shadow: 0 0 10px rgba(124, 108, 240, 0.28);
}

.title-bar--sm {
  width: 3px;
  height: 14px;
}

.card__more {
  padding: 4px 8px;
  border-radius: 999px;
  color: var(--muted);
  font-size: 13px;
  text-decoration: none;
  transition:
    color 0.2s ease,
    background 0.2s ease;
}

.card__more:hover {
  color: var(--primary);
  background: rgba(57, 120, 246, 0.07);
}

/* ── 欢迎横幅 ── */
.banner {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  min-height: 156px;
  padding: 26px 30px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: var(--radius);
  background:
    radial-gradient(620px 280px at 82% 18%, rgba(32, 197, 193, 0.1), transparent 62%),
    radial-gradient(720px 320px at 12% 0%, rgba(120, 168, 255, 0.18), transparent 64%),
    linear-gradient(115deg, #f8fbff 0%, #eaf2ff 48%, #e9f8f7 100%);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.9) inset,
    0 20px 42px -30px rgba(36, 50, 74, 0.24),
    0 10px 24px -20px rgba(57, 120, 246, 0.18);
}

.banner__waves {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  opacity: 0.95;
}

.banner__orb {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}

.banner__orb--1 {
  width: 78px;
  height: 78px;
  top: 18px;
  right: 27%;
  background: radial-gradient(
    circle at 30% 24%,
    rgba(255, 255, 255, 0.95),
    rgba(255, 255, 255, 0.16) 42%,
    rgba(120, 168, 255, 0.12) 72%
  );
  box-shadow:
    inset -6px -8px 16px rgba(57, 120, 246, 0.12),
    0 14px 28px -16px rgba(57, 120, 246, 0.35);
  animation: floatOrb 9s ease-in-out infinite;
}

.banner__orb--2 {
  width: 28px;
  height: 28px;
  bottom: 26px;
  left: 42%;
  background: radial-gradient(
    circle at 32% 28%,
    #fff,
    rgba(255, 255, 255, 0.35) 45%,
    rgba(32, 197, 193, 0.18)
  );
  box-shadow:
    inset -3px -4px 8px rgba(32, 197, 193, 0.18),
    0 8px 16px -8px rgba(32, 197, 193, 0.35);
  animation: floatOrb 7s ease-in-out infinite reverse;
}

.banner__orb--3 {
  width: 14px;
  height: 14px;
  top: 32px;
  left: 55%;
  background: radial-gradient(circle at 30% 30%, #fff, rgba(120, 168, 255, 0.35));
  box-shadow: 0 6px 12px -6px rgba(57, 120, 246, 0.4);
  animation: floatOrb 6s ease-in-out infinite;
}

.banner__left {
  position: relative;
  z-index: 2;
  max-width: 620px;
}

.banner__ring {
  position: absolute;
  border: 1px solid rgba(120, 168, 255, 0.24);
  border-radius: 50%;
  box-shadow:
    inset 0 0 30px rgba(255, 255, 255, 0.6),
    0 0 40px rgba(120, 168, 255, 0.12);
  pointer-events: none;
}

.banner__ring--lg {
  top: -78px;
  right: 23.5%;
  width: 220px;
  height: 220px;
}

.banner__ring--sm {
  right: 17%;
  bottom: -34px;
  width: 96px;
  height: 96px;
  border-color: rgba(32, 197, 193, 0.24);
  box-shadow: inset 0 0 24px rgba(255, 255, 255, 0.55);
}
.banner__eyebrow {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 8px;
  color: #3978f6;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  opacity: 0.85;
}

.banner__eyebrow-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: linear-gradient(135deg, #78a8ff, #3978f6);
  box-shadow:
    0 0 0 4px rgba(57, 120, 246, 0.1),
    0 0 10px rgba(57, 120, 246, 0.4);
}

.banner__left h1 {
  margin: 0 0 7px;
  font-size: 30px;
  font-weight: 800;
  line-height: 1.2;
  letter-spacing: -0.03em;
}

.banner__title-text {
  color: #24324a;
  background: linear-gradient(92deg, #24324a 0%, #2e5c9e 58%, #3978f6 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.banner__spark {
  display: inline-block;
  margin-left: 9px;
  color: #78a8ff;
  font-size: 18px;
  line-height: 1;
  text-shadow: 0 0 12px rgba(57, 120, 246, 0.35);
}

.banner__sub {
  margin: 0;
  color: #64748b;
  font-size: 14px;
  line-height: 1.6;
}

.banner__right {
  position: relative;
  z-index: 2;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 18px;
}

.banner__date {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 10px 15px 10px 11px;
  border: 1px solid rgba(255, 255, 255, 0.85);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.52);
  box-shadow:
    inset 0 1px 0 #fff,
    0 12px 26px -18px rgba(36, 50, 74, 0.28);
  backdrop-filter: blur(16px) saturate(1.2);
  -webkit-backdrop-filter: blur(16px) saturate(1.2);
}

.banner__date-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 11px;
  color: #3978f6;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.95), rgba(219, 234, 255, 0.85));
  box-shadow:
    inset 0 1px 0 #fff,
    0 6px 12px -8px rgba(57, 120, 246, 0.45);
}

.banner__date .date-main {
  margin: 0;
  color: #24324a;
  font-size: 14px;
  font-weight: 700;
  line-height: 1.3;
}

.banner__date .date-sub {
  margin: 2px 0 0;
  color: #6b7a91;
  font-size: 12px;
  line-height: 1.3;
}

.banner__actions {
  display: flex;
  gap: 10px;
}

.btn-grad.el-button {
  height: var(--btn-h);
  padding: 0 20px;
  border: 1px solid rgba(255, 255, 255, 0.22);
  border-radius: 13px;
  background: linear-gradient(135deg, #20c5c1 0%, #3978f6 100%);
  color: #fff;
  font-weight: 650;
  letter-spacing: 0.01em;
  box-shadow:
    0 10px 22px -10px rgba(57, 120, 246, 0.55),
    0 4px 10px -4px rgba(32, 197, 193, 0.35),
    inset 0 1px 0 rgba(255, 255, 255, 0.35),
    inset 0 -2px 4px rgba(20, 60, 120, 0.15);
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease,
    background 0.18s ease;
}

.btn-grad.el-button:hover,
.btn-grad.el-button:focus {
  color: #fff;
  background: linear-gradient(135deg, #2ad5d1 0%, #4a86fa 100%);
  transform: translateY(-1px);
  box-shadow:
    0 14px 28px -12px rgba(57, 120, 246, 0.65),
    0 6px 14px -6px rgba(32, 197, 193, 0.4),
    inset 0 1px 0 rgba(255, 255, 255, 0.4);
}

.btn-grad.el-button:active {
  transform: translateY(1px) scale(0.99);
  box-shadow:
    0 6px 14px -8px rgba(57, 120, 246, 0.45),
    inset 0 2px 5px rgba(20, 60, 120, 0.18);
}

.btn-ai.el-button {
  height: var(--btn-h);
  padding: 0 20px;
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.58);
  color: #3978f6;
  font-weight: 650;
  box-shadow:
    0 10px 22px -14px rgba(36, 50, 74, 0.28),
    inset 0 1px 0 #fff;
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease,
    background 0.18s ease;
}

.btn-ai.el-button:hover,
.btn-ai.el-button:focus {
  color: #3978f6;
  border-color: rgba(255, 255, 255, 1);
  background: rgba(255, 255, 255, 0.82);
  transform: translateY(-1px);
  box-shadow:
    0 14px 26px -16px rgba(36, 50, 74, 0.32),
    inset 0 1px 0 #fff;
}

.btn-ai.el-button:active {
  transform: translateY(1px) scale(0.99);
}

/* ── KPI 3D Floating Cards ── */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--gap);
}

.kpi {
  --rx: 0deg;
  --ry: 0deg;
  --lift: 0px;
  position: relative;
  display: flex;
  align-items: center;
  gap: 16px;
  min-height: 126px;
  padding: 20px;
  overflow: hidden;
  border: 1px solid rgba(227, 235, 247, 0.9);
  border-radius: var(--radius);
  background:
    radial-gradient(190px 130px at 100% 0%, var(--glow), transparent 72%),
    linear-gradient(150deg, rgba(255, 255, 255, 0.92), rgba(255, 255, 255, 0.66));
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(16px) saturate(1.08);
  -webkit-backdrop-filter: blur(16px) saturate(1.08);
  transform: perspective(1000px) translate3d(0, var(--lift), 0) rotateX(var(--rx))
    rotateY(var(--ry));
  transform-style: preserve-3d;
  transition:
    transform 0.32s cubic-bezier(0.2, 0.8, 0.2, 1),
    box-shadow 0.32s ease,
    border-color 0.32s ease;
}

.kpi::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: radial-gradient(
    190px circle at var(--mx, 50%) var(--my, 50%),
    rgba(255, 255, 255, 0.55),
    transparent 62%
  );
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.3s ease;
}

.kpi:hover {
  --lift: -5px;
  border-color: rgba(255, 255, 255, 0.95);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.92) inset,
    0 24px 46px -26px rgba(36, 50, 74, 0.3),
    0 12px 26px -18px var(--glow);
  will-change: transform;
}

.kpi:hover::before {
  opacity: 1;
}

.kpi__icon-wrap {
  position: relative;
  flex: none;
  display: grid;
  place-items: center;
  width: 58px;
  height: 58px;
  border-radius: 50%;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.96), rgba(232, 240, 252, 0.62));
  box-shadow:
    inset 0 1px 0 #fff,
    inset 0 -1px 0 rgba(120, 168, 255, 0.12),
    0 8px 18px -14px rgba(36, 50, 74, 0.24);
}

.kpi__icon-wrap::after {
  content: '';
  position: absolute;
  inset: 1px;
  border-radius: inherit;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.7), transparent 45%);
  pointer-events: none;
}

.kpi__orb {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  color: #fff;
  background: linear-gradient(145deg, var(--accent-soft) 0%, var(--accent) 62%, var(--accent) 100%);
  box-shadow:
    inset 0 1px 1px rgba(255, 255, 255, 0.72),
    inset 0 -3px 6px rgba(20, 40, 80, 0.16),
    0 6px 14px -8px var(--glow);
}

.kpi:hover .kpi__orb {
  animation: iconFloat 2.4s ease-in-out infinite;
}

.kpi__text {
  flex: 1;
  min-width: 0;
}

.kpi__label {
  margin: 0;
  color: #44546c;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.kpi__value {
  margin: 3px 0 4px;
  color: #1f2d43;
  font-size: 44px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.045em;
  font-variant-numeric: tabular-nums;
  text-shadow: 0 2px 10px rgba(36, 50, 74, 0.05);
}

.kpi__hint {
  margin: 0;
  color: #74839a;
  font-size: 12px;
  font-weight: 500;
}

.kpi__bars {
  flex: none;
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 48px;
  margin-left: auto;
  padding-bottom: 2px;
}

.kpi__bar {
  position: relative;
  width: 6px;
  border-radius: 4px 4px 3px 3px;
  background: linear-gradient(180deg, var(--accent-soft) 0%, var(--accent) 68%, var(--accent) 100%);
  box-shadow:
    inset 1px 0 0 rgba(255, 255, 255, 0.55),
    inset -3px 0 4px rgba(20, 40, 80, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.7),
    1px 4px 7px -3px var(--glow);
  transform-origin: bottom;
  transition: transform 0.35s cubic-bezier(0.2, 0.8, 0.2, 1);
  transition-delay: var(--bar-delay, 0ms);
}

.kpi__bar::after {
  content: '';
  position: absolute;
  top: 1px;
  left: 1px;
  right: 1px;
  height: 3px;
  border-radius: 2px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.75), rgba(255, 255, 255, 0.25));
}

.kpi:hover .kpi__bar {
  transform: scaleY(1.06);
}

/* ── 图表行 ── */
.charts-row {
  display: grid;
  grid-template-columns: 1.65fr 1fr;
  gap: var(--gap);
  min-height: 340px;
}

.trend,
.donut {
  display: flex;
  flex-direction: column;
  padding: 20px 22px 16px;
}

.trend {
  background:
    radial-gradient(600px 300px at 0% 0%, rgba(57, 120, 246, 0.07), transparent 62%),
    linear-gradient(145deg, rgba(255, 255, 255, 0.94), rgba(255, 255, 255, 0.74));
}

.donut {
  background:
    radial-gradient(520px 300px at 100% 0%, rgba(32, 197, 193, 0.08), transparent 62%),
    linear-gradient(145deg, rgba(255, 255, 255, 0.94), rgba(255, 255, 255, 0.74));
}

.range-pills {
  display: flex;
  gap: 3px;
  padding: 4px;
  border-radius: 12px;
  background: rgba(242, 245, 251, 0.9);
  box-shadow:
    inset 0 1px 2px rgba(36, 50, 74, 0.06),
    0 1px 0 rgba(255, 255, 255, 0.9);
}

.range-pills button {
  padding: 5px 13px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: #7a889c;
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    color 0.18s ease,
    background 0.18s ease,
    box-shadow 0.18s ease;
}

.range-pills button:hover {
  color: #3978f6;
  background: rgba(255, 255, 255, 0.7);
}

.range-pills button.active {
  color: #fff;
  background: linear-gradient(135deg, #78a8ff, #3978f6);
  box-shadow:
    0 4px 10px -4px rgba(57, 120, 246, 0.55),
    inset 0 1px 0 rgba(255, 255, 255, 0.35);
}

.trend__chart {
  flex: 1;
  min-height: 210px;
}

.donut__body {
  display: flex;
  flex: 1;
  align-items: stretch;
  gap: 10px;
  min-height: 210px;
}

.donut__chart-wrap {
  position: relative;
  flex: 0 0 54%;
  min-width: 0;
  min-height: 200px;
}

/* 环形边缘高光：只在圆环带内叠一层斜向玻璃反光，中心数字不受影响 */
.donut__chart-wrap::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  background: radial-gradient(
    ellipse 60% 50% at 32% 20%,
    rgba(255, 255, 255, 0.55),
    rgba(255, 255, 255, 0) 70%
  );
  -webkit-mask-image: radial-gradient(
    circle at 50% 50%,
    transparent 56%,
    #000 60%,
    #000 88%,
    transparent 93%
  );
  mask-image: radial-gradient(circle at 50% 50%, transparent 56%, #000 60%, #000 88%, transparent 93%);
}

.donut__chart {
  position: absolute;
  inset: 0;
  z-index: 0;
  width: 100%;
  height: 100%;
}

.donut__center {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  transform: translate(-50%, -50%);
  pointer-events: none;
}

.donut__center strong {
  color: #1f2d43;
  font-size: 46px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.05em;
  font-variant-numeric: tabular-nums;
  text-shadow: 0 4px 16px rgba(57, 120, 246, 0.14);
}

.donut__center span {
  color: #74839a;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.donut__legend {
  flex: 1;
  align-self: center;
  display: flex;
  flex-direction: column;
  gap: 15px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.donut__legend li {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.donut__legend-row {
  display: grid;
  grid-template-columns: 12px 1fr auto 52px;
  align-items: center;
  gap: 10px;
  font-size: 13.5px;
}

.donut__legend .dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
}

.donut__legend .name {
  color: #44546c;
  font-weight: 650;
}

.donut__legend .num {
  color: #1f2d43;
  font-size: 14.5px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}

.donut__legend .pct {
  color: #74839a;
  font-size: 12px;
  font-weight: 600;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.donut__legend .pct b {
  color: #44546c;
  font-size: 13.5px;
  font-weight: 750;
  margin-right: -1.5px;
}

.legend-track {
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(137, 151, 172, 0.14);
}

.legend-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  box-shadow: 0 0 8px rgba(57, 120, 246, 0.14);
  transition: width 0.6s cubic-bezier(0.2, 0.8, 0.2, 1);
}

/* ── 底部：重点任务 + AI 洞察 / 报告中心 ── */
.bottom-row {
  display: grid;
  grid-template-columns: 1.65fr 1fr;
  gap: var(--gap);
  min-height: 320px;
}

.merged,
.reports {
  display: flex;
  flex-direction: column;
  padding: 20px 22px;
}

.merged {
  background:
    radial-gradient(560px 300px at 0% 0%, rgba(120, 168, 255, 0.06), transparent 62%),
    linear-gradient(145deg, rgba(255, 255, 255, 0.94), rgba(255, 255, 255, 0.74));
}

.reports {
  background:
    radial-gradient(520px 300px at 100% 0%, rgba(124, 108, 240, 0.07), transparent 62%),
    linear-gradient(145deg, rgba(255, 255, 255, 0.94), rgba(255, 255, 255, 0.74));
}

.merged__body {
  display: flex;
  flex: 1;
  gap: 24px;
  min-height: 0;
}

.merged__col {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.merged__col + .merged__col {
  padding-left: 24px;
  border-left: 1px solid rgba(137, 151, 172, 0.14);
}

.merged__sub {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 12px;
  color: #24324a;
  font-size: 14px;
  font-weight: 750;
  letter-spacing: -0.01em;
}

.ai-sub-icon {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 7px;
  color: #fff;
  background: linear-gradient(145deg, #8ff3ee, #20c5c1);
  box-shadow:
    0 4px 10px -6px rgba(32, 197, 193, 0.4),
    inset 0 1px 0 rgba(255, 255, 255, 0.6);
}

.pill {
  padding: 3px 9px;
  border: 1px solid rgba(32, 197, 193, 0.2);
  border-radius: 999px;
  color: #168f8c;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
  background: rgba(255, 255, 255, 0.62);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

.today__list {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.today__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 12px;
  border: 1px solid transparent;
  border-radius: 14px;
  transition:
    background 0.25s ease,
    border-color 0.25s ease,
    box-shadow 0.25s ease,
    transform 0.25s ease;
}

.today__row:hover {
  border-color: rgba(255, 255, 255, 0.9);
  background: rgba(255, 255, 255, 0.66);
  box-shadow: 0 8px 18px -14px rgba(36, 50, 74, 0.22);
  transform: translateX(2px);
}

.check {
  display: grid;
  place-items: center;
  flex: none;
  width: 21px;
  height: 21px;
  padding: 0;
  border: 1.5px solid rgba(137, 151, 172, 0.42);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.7);
  color: #fff;
  cursor: pointer;
  box-shadow: inset 0 1px 0 #fff;
  transition:
    border-color 0.18s ease,
    background 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;
}

.check:hover {
  border-color: #20c5c1;
  box-shadow: 0 0 0 4px rgba(32, 197, 193, 0.14);
}

.check:active {
  transform: scale(0.88);
}

.check.done {
  border-color: transparent;
  background: linear-gradient(145deg, #8ff3ee, #20c5c1);
  box-shadow:
    0 4px 10px -6px rgba(32, 197, 193, 0.5),
    inset 0 1px 0 rgba(255, 255, 255, 0.6);
}

.today__row .title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: #24324a;
  font-size: 14.5px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: -0.005em;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.today__row .title:hover {
  color: #3978f6;
}

.tag {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.5;
}

.tag::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.75;
}

.tag--doing {
  border-color: rgba(57, 120, 246, 0.12);
  color: #3978f6;
  background: rgba(57, 120, 246, 0.08);
}

.tag--done {
  border-color: rgba(32, 197, 193, 0.12);
  color: #168f8c;
  background: rgba(32, 197, 193, 0.08);
}

.tag--pending {
  border-color: rgba(255, 166, 61, 0.14);
  color: #d9821f;
  background: rgba(255, 166, 61, 0.09);
}

.today__row .date {
  flex: none;
  width: 46px;
  color: #74839a;
  font-size: 12px;
  font-weight: 600;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

/* ── 空状态 3D 插画 ── */
.empty-focus {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  padding: 8px 0;
  text-align: center;
}

.empty-focus__art {
  position: relative;
  width: 108px;
  height: 82px;
  margin-bottom: 6px;
}

.empty-focus__card {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 76px;
  height: 58px;
  border-radius: 14px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.98), rgba(232, 241, 255, 0.9));
  box-shadow:
    0 14px 28px -18px rgba(57, 120, 246, 0.45),
    inset 0 1px 0 #fff;
  transform: translate(-50%, -50%) rotate(-4deg);
  animation: emptyFloat 4.5s ease-in-out infinite;
}

.empty-focus__check {
  position: absolute;
  top: -8px;
  right: -8px;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  color: #fff;
  background: linear-gradient(145deg, #8ff3ee, #20c5c1);
  box-shadow:
    0 6px 12px -6px rgba(32, 197, 193, 0.55),
    inset 0 1px 0 rgba(255, 255, 255, 0.6);
}

.empty-focus__line {
  position: absolute;
  left: 14px;
  height: 4px;
  border-radius: 999px;
  background: rgba(137, 151, 172, 0.18);
}

.empty-focus__line--1 {
  top: 22px;
  width: 34px;
}

.empty-focus__line--2 {
  top: 33px;
  width: 44px;
}

.empty-focus__orb {
  position: absolute;
  border-radius: 50%;
  background: radial-gradient(circle at 30% 30%, #fff, rgba(120, 168, 255, 0.3));
  box-shadow: 0 8px 16px -8px rgba(57, 120, 246, 0.4);
  animation: emptyFloat 5s ease-in-out infinite;
}

.empty-focus__orb--1 {
  width: 12px;
  height: 12px;
  right: 6px;
  bottom: 8px;
}

.empty-focus__orb--2 {
  width: 7px;
  height: 7px;
  left: 4px;
  top: 10px;
  animation-delay: 0.8s;
}

.empty-focus__title {
  margin: 2px 0 3px;
  color: #24324a;
  font-size: 13px;
  font-weight: 750;
}

.empty-focus__hint {
  margin: 0;
  color: #6b7a91;
  font-size: 12px;
  line-height: 1.6;
}

.empty-focus__link {
  margin-top: 8px;
  padding: 4px 10px;
  border-radius: 999px;
  color: #3978f6;
  font-size: 12px;
  font-weight: 650;
  text-decoration: none;
  background: rgba(57, 120, 246, 0.07);
  transition:
    background 0.2s ease,
    transform 0.2s ease;
}

.empty-focus__link:hover {
  background: rgba(57, 120, 246, 0.12);
  transform: translateY(-1px);
}

/* ── AI 工作洞察 ── */
.merged__col--ai {
  min-width: 0;
}

.ai-panel {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  padding: 20px;
  overflow: hidden;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background:
    linear-gradient(150deg, rgba(240, 255, 254, 0.84), rgba(255, 255, 255, 0.58) 48%, rgba(237, 245, 255, 0.8))
      padding-box,
    linear-gradient(135deg, rgba(32, 197, 193, 0.4), rgba(255, 255, 255, 0.7) 40%, rgba(57, 120, 246, 0.28))
      border-box;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.9),
    0 18px 36px -28px rgba(32, 197, 193, 0.3),
    0 8px 20px -18px rgba(57, 120, 246, 0.2);
  backdrop-filter: blur(18px) saturate(1.15);
  -webkit-backdrop-filter: blur(18px) saturate(1.15);
}

/* 静态柔光：不再做周期性明暗变化，避免与其它动画抢注意力 */
.ai-panel__glow {
  position: absolute;
  top: -80px;
  right: -60px;
  width: 200px;
  height: 200px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(32, 197, 193, 0.22), transparent 68%);
  filter: blur(8px);
  pointer-events: none;
}

.ai-panel__head {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 13px;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(137, 151, 172, 0.16);
}

/* 3D 发光晶体：多面渐变球体 + 高光 + 内圈；收敛外发光，避免过亮 */
.ai-orb {
  position: relative;
  display: grid;
  flex: none;
  place-items: center;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background:
    radial-gradient(circle at 30% 24%, rgba(255, 255, 255, 0.95), rgba(255, 255, 255, 0) 44%),
    conic-gradient(from 205deg at 50% 50%, #7cf0eb, #20c5c1 30%, #3978f6 66%, #a7ecff 100%);
  box-shadow:
    inset 0 2px 4px rgba(255, 255, 255, 0.7),
    inset 0 -8px 14px rgba(16, 64, 110, 0.28),
    0 10px 20px -14px rgba(32, 197, 193, 0.4),
    0 0 0 5px rgba(32, 197, 193, 0.05);
  animation: aiFloat 6s ease-in-out infinite;
}

.ai-orb__ring {
  position: absolute;
  inset: -6px;
  border: 1px solid rgba(32, 197, 193, 0.3);
  border-radius: 50%;
  box-shadow: 0 0 16px rgba(32, 197, 193, 0.16);
}

.ai-orb__core {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  color: #fff;
  background: rgba(255, 255, 255, 0.18);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.55),
    inset 0 -2px 6px rgba(16, 64, 110, 0.22);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

.ai-panel__meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.ai-panel__eyebrow {
  color: #0f9c99;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.14em;
}

.ai-panel__live {
  display: flex;
  align-items: center;
  gap: 5px;
  color: #6b7a91;
  font-size: 12px;
  font-weight: 600;
}

.ai-panel__live i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #25b978;
  box-shadow: 0 0 0 3px rgba(37, 185, 120, 0.12);
  animation: livePulse 2.8s ease-in-out infinite;
}

/* ── 分段标题：把面板切成「关键数据 / AI 分析 / AI 建议」三层结构 ── */
.ai-section {
  position: relative;
  z-index: 1;
}

.ai-panel__head + .ai-section {
  margin-top: 16px;
}

.ai-section--body {
  margin-top: 16px;
  margin-bottom: 16px;
}

.ai-section__label {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 9px;
  color: #74839a;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.ai-section__label i {
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, rgba(137, 151, 172, 0.3), rgba(137, 151, 172, 0));
}

.ai-panel__headline {
  position: relative;
  z-index: 1;
  margin: 0 0 8px;
  color: #0f9c99;
  font-size: 21px;
  font-weight: 800;
  line-height: 1.4;
  letter-spacing: -0.02em;
  text-shadow: 0 1px 14px rgba(32, 197, 193, 0.14);
}

/* 关键数据：三枚等宽玻璃 chip，顶部细色条 + 主题色数字做重点强调 */
.ai-stats {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 9px;
}

.ai-stat {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 12px 11px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.88);
  border-radius: 13px;
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.8), rgba(255, 255, 255, 0.5));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    0 5px 12px -9px rgba(36, 50, 74, 0.28);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

.ai-stat::before {
  content: '';
  position: absolute;
  top: 0;
  left: 12px;
  right: 12px;
  height: 2px;
  border-radius: 0 0 3px 3px;
  background: var(--stat, #3978f6);
  opacity: 0.7;
}

.ai-stat strong {
  color: var(--stat, #24324a);
  font-size: 20px;
  font-weight: 800;
  line-height: 1.15;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
}

.ai-stat span {
  color: #6b7a91;
  font-size: 12px;
  font-weight: 600;
}

.ai-panel__para {
  position: relative;
  z-index: 1;
  margin: 0;
  color: #47566d;
  font-size: 14px;
  line-height: 1.85;
}

/* AI 建议：与面板同源的渐变描边玻璃区块（第四部分） */
.ai-advice {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-top: auto;
  padding: 14px 15px;
  border: 1px solid transparent;
  border-radius: 14px;
  background:
    linear-gradient(150deg, rgba(255, 255, 255, 0.82), rgba(255, 255, 255, 0.56)) padding-box,
    linear-gradient(135deg, rgba(32, 197, 193, 0.5), rgba(255, 255, 255, 0.85) 45%, rgba(57, 120, 246, 0.32))
      border-box;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.95),
    0 12px 26px -20px rgba(36, 50, 74, 0.4);
  backdrop-filter: blur(14px) saturate(1.15);
  -webkit-backdrop-filter: blur(14px) saturate(1.15);
}

/* 3D 圆形底座（收敛外发光） */
.ai-advice__icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  color: #fff;
  background: linear-gradient(145deg, #8ff3ee, #20c5c1 62%, #16a5a2);
  box-shadow:
    inset 0 1px 1px rgba(255, 255, 255, 0.7),
    inset 0 -3px 6px rgba(12, 70, 90, 0.28),
    0 5px 10px -7px rgba(32, 197, 193, 0.45);
}

.ai-advice__body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.ai-advice__label {
  color: #0f9c99;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.ai-advice__value {
  color: #24324a;
  font-size: 13.5px;
  font-weight: 600;
  line-height: 1.65;
}

/* ── AI 报告中心：3D 悬浮入口 ── */
.reports__tiles {
  display: grid;
  flex: 1;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin: 0;
}

.tile {
  --rx: 0deg;
  --ry: 0deg;
  --lift: 0px;
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 0;
  padding: 20px 14px 18px;
  overflow: hidden;
  border: 1px solid rgba(227, 235, 247, 0.9);
  border-radius: var(--radius-md);
  background:
    radial-gradient(170px 120px at 50% 0%, var(--glow), transparent 70%),
    linear-gradient(155deg, rgba(255, 255, 255, 0.96), rgba(255, 255, 255, 0.7));
  box-shadow:
    0 14px 28px -24px rgba(36, 50, 74, 0.28),
    inset 0 1px 0 #fff;
  cursor: pointer;
  transform: perspective(900px) translate3d(0, var(--lift), 0) rotateX(var(--rx))
    rotateY(var(--ry));
  transition:
    transform 0.32s cubic-bezier(0.2, 0.8, 0.2, 1),
    box-shadow 0.32s ease,
    border-color 0.32s ease;
}

.tile::before {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(
    140px circle at var(--mx, 50%) var(--my, 50%),
    rgba(255, 255, 255, 0.5),
    transparent 60%
  );
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.3s ease;
}

.tile:hover {
  --lift: -5px;
  border-color: rgba(255, 255, 255, 0.95);
  box-shadow:
    0 22px 40px -26px rgba(36, 50, 74, 0.3),
    0 12px 24px -18px var(--glow),
    inset 0 1px 0 #fff;
  will-change: transform;
}

.tile:hover::before {
  opacity: 1;
}

/* 边缘高光：hover 时勾出一圈白色内高光，强化“悬浮卡片”质感 */
.tile::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.95),
    inset 0 0 0 1px rgba(255, 255, 255, 0.45),
    inset 0 -14px 24px -22px rgba(57, 120, 246, 0.5);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.3s ease;
}

.tile:hover::after {
  opacity: 1;
}

.tile__badge {
  position: relative;
  display: grid;
  place-items: center;
  width: 58px;
  height: 58px;
  border-radius: 50%;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.97), rgba(232, 240, 252, 0.66));
  box-shadow:
    inset 0 1px 0 #fff,
    inset 0 -2px 6px rgba(120, 168, 255, 0.14),
    0 10px 20px -14px rgba(36, 50, 74, 0.28);
}

.tile__badge-orb {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  color: #fff;
  background: linear-gradient(145deg, var(--accent-soft), var(--accent));
  box-shadow:
    inset 0 1px 1px rgba(255, 255, 255, 0.7),
    inset 0 -3px 6px rgba(20, 40, 80, 0.16),
    0 7px 14px -8px var(--glow);
}

.tile:hover .tile__badge-orb {
  animation: iconFloat 2.6s ease-in-out infinite;
}

.tile__text {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
}

.tile__name {
  margin: 0;
  color: #24324a;
  font-size: 15px;
  font-weight: 750;
  letter-spacing: -0.01em;
}

.tile__sub {
  margin: 0;
  color: #6b7a91;
  font-size: 12px;
  line-height: 1.55;
  text-align: center;
}

/* 分隔线：把入口卡切成“图标+说明 / 操作提示”两段，避免大片留白 */
.tile__divider {
  width: 100%;
  height: 1px;
  margin-top: 12px;
  background: linear-gradient(
    90deg,
    rgba(137, 151, 172, 0),
    rgba(137, 151, 172, 0.26),
    rgba(137, 151, 172, 0)
  );
}

/* 轻量“进入”提示：胶囊型 CTA，仅作视觉引导，跳转逻辑与原入口一致 */
.tile__cta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  padding: 6px 13px;
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 999px;
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.92), rgba(255, 255, 255, 0.6));
  box-shadow:
    inset 0 1px 0 #fff,
    0 6px 14px -10px var(--glow);
  transition:
    transform 0.25s ease,
    box-shadow 0.25s ease;
}

.tile__cta i {
  font-style: normal;
  transition: transform 0.25s ease;
}

.tile:hover .tile__cta {
  transform: translateY(-1px);
  border-color: rgba(255, 255, 255, 1);
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.98), rgba(255, 255, 255, 0.82));
  box-shadow:
    inset 0 1px 0 #fff,
    0 8px 18px -10px var(--glow),
    0 0 0 3px var(--glow);
}

.tile:hover .tile__cta i {
  transform: translateX(3px);
}

/* ── 键盘可见焦点 ── */
.tile:focus-visible,
.check:focus-visible,
.range-pills button:focus-visible,
.card__more:focus-visible,
.empty-focus__link:focus-visible {
  outline: 2px solid rgba(57, 120, 246, 0.55);
  outline-offset: 3px;
}

/* ── 动画 ── */
@keyframes blobFloat {
  from {
    translate: 0 0;
  }
  to {
    translate: 24px -18px;
  }
}

@keyframes floatOrb {
  0%,
  100% {
    transform: translate3d(0, 0, 0);
  }
  50% {
    transform: translate3d(0, -8px, 0);
  }
}

@keyframes iconFloat {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-4px);
  }
}

@keyframes emptyFloat {
  0%,
  100% {
    transform: translate(-50%, -50%) rotate(-4deg) translateY(0);
  }
  50% {
    transform: translate(-50%, -50%) rotate(-4deg) translateY(-5px);
  }
}

@keyframes aiFloat {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-4px);
  }
}

@keyframes livePulse {
  0%,
  100% {
    opacity: 0.65;
    transform: scale(0.9);
  }
  50% {
    opacity: 1;
    transform: scale(1.12);
  }
}

/* ── 响应式 ── */
@media (max-width: 1180px) {
  .home-page {
    grid-template-rows: auto auto auto auto;
  }

  .kpi-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .charts-row,
  .bottom-row {
    grid-template-columns: 1fr;
  }

  .bottom-row {
    min-height: 0;
  }
}

@media (max-width: 900px) {
  .banner {
    padding: 24px;
  }

  .banner__ring {
    display: none;
  }

  .banner__right {
    width: 100%;
    justify-content: space-between;
  }

  .merged__body {
    flex-direction: column;
    gap: 18px;
  }

  .merged__col + .merged__col {
    padding-top: 18px;
    padding-left: 0;
    border-top: 1px solid rgba(137, 151, 172, 0.14);
    border-left: 0;
  }

  .empty-focus {
    min-height: 170px;
  }
}

@media (max-width: 720px) {
  .banner {
    align-items: flex-start;
  }

  .banner__left h1 {
    font-size: 27px;
  }

  .banner__right {
    flex-direction: column;
    align-items: stretch;
    gap: 12px;
  }

  .banner__date {
    align-self: flex-start;
  }

  .banner__actions {
    width: 100%;
  }

  .banner__actions .el-button {
    flex: 1;
  }

  .donut__body {
    flex-direction: column;
  }

  .donut__chart-wrap {
    flex: 0 0 210px;
    width: 100%;
  }

  .donut__legend {
    width: 100%;
  }

  /* 小屏降低毛玻璃开销，保持同样的半透明层次 */
  .card,
  .kpi,
  .tile,
  .banner__date,
  .ai-panel {
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}

@media (max-width: 640px) {
  .home-page {
    gap: 14px;
    padding: 14px 12px 24px;
  }

  .banner {
    padding: 22px 18px;
  }

  .banner__left h1 {
    font-size: 25px;
  }

  .banner__sub {
    font-size: 13px;
  }

  .kpi-row {
    grid-template-columns: 1fr;
    gap: 14px;
  }

  .kpi {
    min-height: 112px;
    padding: 16px;
  }

  .kpi__value {
    font-size: 36px;
  }

  .trend,
  .donut,
  .merged,
  .reports {
    padding: 16px;
  }

  .reports__tiles {
    grid-template-columns: 1fr;
    gap: 10px;
  }

  .tile {
    flex-direction: row;
    justify-content: flex-start;
    gap: 12px;
    min-height: 76px;
    padding: 12px 14px;
  }

  .tile__text {
    align-items: flex-start;
  }

  .tile__sub {
    text-align: left;
  }

  /* 横排时 CTA 靠右，替代桌面端的角标提示 */
  .tile__cta {
    margin-top: 0;
    margin-left: auto;
  }

  .tile__divider {
    display: none;
  }

  .tile__badge {
    width: 52px;
    height: 52px;
  }

  .tile__badge-orb {
    width: 40px;
    height: 40px;
  }

  .ai-panel {
    padding: 15px;
  }

  .ai-panel__headline {
    font-size: 18px;
  }

  .merged__sub {
    font-size: 13px;
  }

  .today__row {
    gap: 9px;
    padding: 9px 6px;
  }

  .today__row .date {
    width: 38px;
  }
}

@media (max-width: 420px) {
  .banner__actions {
    flex-direction: column;
  }

  .kpi__bars {
    transform: scale(0.82);
    transform-origin: right bottom;
  }
}

/* ── 减少动态效果：尊重系统设置 ── */
@media (prefers-reduced-motion: reduce) {
  .reveal {
    opacity: 1;
    animation: none;
    translate: none;
  }

  .ambient,
  .banner__orb,
  .kpi__orb,
  .empty-focus__card,
  .empty-focus__orb,
  .ai-orb,
  .ai-panel__live i,
  .tile__badge-orb {
    animation: none !important;
  }

  .card,
  .kpi,
  .tile,
  .check,
  .kpi__bar,
  .legend-track i {
    transition: none !important;
  }

  .card,
  .kpi,
  .tile {
    transform: none !important;
  }
}
</style>
