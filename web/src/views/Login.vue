<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormItemRule, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { ApiError, httpPost } from '@/api/request'
import { useUserStore } from '@/stores/user'

/** 后端 /api/auth/login 返回的用户（Result.data） */
interface LoginResult {
  id: number
  username: string
  nickname: string
}

/** request.ts 认为“会话类”的错误码（401=未登录/凭据错误），其在 /login 页不自动弹提示，此处兜底 */
const SESSION_CODES = new Set([401])

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

/** 登录 / 注册同页双模式（不加路由） */
const mode = ref<'login' | 'register'>('login')

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ username: '', password: '', confirm: '', nickname: '' })

const rules = computed<FormRules>(() => {
  const usernameRules: FormItemRule[] = [{ required: true, message: '请输入用户名', trigger: 'blur' }]
  const passwordRules: FormItemRule[] = [{ required: true, message: '请输入密码', trigger: 'blur' }]
  const base: FormRules = { username: usernameRules, password: passwordRules }
  if (mode.value === 'register') {
    usernameRules.push({
      pattern: /^[a-zA-Z0-9_]{3,32}$/,
      message: '用户名为 3-32 位字母、数字或下划线',
      trigger: 'blur',
    })
    passwordRules.push({ min: 6, max: 32, message: '密码长度 6-32 位', trigger: 'blur' })
    base.confirm = [
      { required: true, message: '请再次输入密码', trigger: 'blur' },
      {
        validator: (_rule: unknown, value: unknown, callback: (err?: Error) => void) => {
          if (value === form.password) callback()
          else callback(new Error('两次输入的密码不一致'))
        },
        trigger: 'blur',
      },
    ]
  }
  return base
})

// 切模式：清密码与校验残留
watch(mode, () => {
  form.password = ''
  form.confirm = ''
  formRef.value?.clearValidate()
})

async function handleLogin(): Promise<void> {
  if (!formRef.value || loading.value) return // 加载中防重复提交（含 Enter 键路径）
  try {
    await formRef.value.validate()
  } catch {
    return // el-form 已就地标红，无需再弹提示
  }

  loading.value = true
  try {
    const user = await httpPost<LoginResult>('/auth/login', {
      username: form.username,
      password: form.password,
    })
    // 会话方案：token 留空，登录态由 Cookie 携带；仅把用户信息入 Pinia 并持久化
    userStore.setUser('', {
      id: user.id,
      username: user.username,
      nickname: user.nickname,
      roles: [],
    })
    ElMessage.success(`欢迎，${user.nickname || user.username}`)
    // 回跳到守卫记录的原路径（?redirect=），否则默认进首页
    const redirect = route.query.redirect
    await router.push(typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/')
  } catch (e) {
    // 会话/凭据类错误 request.ts 在登录页不弹；其余错误它已 toast，避免重复
    if (e instanceof ApiError && SESSION_CODES.has(e.code)) {
      ElMessage.error(e.message || '用户名或密码错误')
    } else if (!(e instanceof ApiError)) {
      ElMessage.error(e instanceof Error ? e.message : '登录失败，请重试')
    }
  } finally {
    loading.value = false
  }
}

/** 注册：后端 BCrypt 落库并自动登录（写好会话），体验与登录一致 */
async function handleRegister(): Promise<void> {
  if (!formRef.value || loading.value) return // 加载中防重复提交
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    const user = await httpPost<LoginResult>('/auth/register', {
      username: form.username,
      password: form.password,
      nickname: form.nickname.trim(),
    })
    userStore.setUser('', {
      id: user.id,
      username: user.username,
      nickname: user.nickname,
      roles: [],
    })
    ElMessage.success(`注册成功，欢迎 ${user.nickname || user.username}`)
    const redirect = route.query.redirect
    await router.push(typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/')
  } catch (e) {
    // 业务失败（用户名已存在等）request.ts 已统一 toast；此处只兜非 ApiError 意外
    if (!(e instanceof ApiError)) {
      ElMessage.error(e instanceof Error ? e.message : '注册失败，请重试')
    }
  } finally {
    loading.value = false
  }
}

/** 表单提交：按模式分发 */
function handleSubmit(): void {
  if (mode.value === 'register') void handleRegister()
  else void handleLogin()
}

/** 已显示错误的字段在输入时即时重校验（及时清除错误；从未提交过的字段不提前报错） */
function revalidateIfError(prop: 'username' | 'password'): void {
  const field = formRef.value?.fields.find((f) => f.prop === prop)
  if (field?.validateState === 'error') void formRef.value?.validateField(prop)
}

/* ═══════ 第八/九阶段：鼠标视差 + 动态光线（rAF + lerp，仅桌面精确指针开启） ═══════ */
const glassLayer = ref<HTMLDivElement>()

let rafId = 0
let rafActive = false
let targetX = 0
let targetY = 0
let curX = 0
let curY = 0
/** 指针在场系数：1=页面内 · 0=离开 —— 离开时局部高光随 near 平滑熄灭 */
let presence = 0
let targetPresence = 0
let cardEls: HTMLElement[] = []
let cardRects: Array<{ cx: number; cy: number; w: number; h: number }> = []

/** 缓存卡片几何中心（仅 mount/resize 时读取布局；视差位移极小，缓存值足够，逐帧零重排） */
function cacheCardRects(): void {
  cardRects = cardEls.map((el) => {
    const r = el.getBoundingClientRect()
    return { cx: r.left + r.width / 2, cy: r.top + r.height / 2, w: r.width || 1, h: r.height || 1 }
  })
}

/** 动态光线：高光位置 --lx/--ly 随指针相对卡片中心偏移；靠近程度 --near 0~1 控制光泽强度 */
function applyLight(i: number, px: number, py: number): void {
  const el = cardEls[i]
  const c = cardRects[i]
  if (!el || !c) return
  const dx = px - c.cx
  const dy = py - c.cy
  const near = Math.max(0, 1 - Math.hypot(dx, dy) / 460) * presence
  const clamp = (v: number, m: number) => Math.max(-m, Math.min(m, v))
  el.style.setProperty('--lx', `${clamp((dx / c.w) * 80, 60).toFixed(1)}%`)
  el.style.setProperty('--ly', `${clamp((dy / c.h) * 80, 55).toFixed(1)}%`)
  el.style.setProperty('--near', near.toFixed(3))
}

/** 视差循环：目标值逐帧插值 —— 移动跟手、停止渐回、离页复位共用同一通道 */
function parallaxTick(): void {
  curX += (targetX - curX) * 0.08
  curY += (targetY - curY) * 0.08
  presence += (targetPresence - presence) * 0.08
  const el = glassLayer.value
  if (el) {
    el.style.setProperty('--mx', curX.toFixed(4))
    el.style.setProperty('--my', curY.toFixed(4))
  }
  // 光线位置与视差共用同一平滑通道：插值坐标反算像素位置，高光移动自然无跳变
  const px = ((curX + 1) / 2) * window.innerWidth
  const py = ((curY + 1) / 2) * window.innerHeight
  for (let i = 0; i < cardEls.length; i++) applyLight(i, px, py)
  // 收敛后停止循环，下一次 mousemove 再唤醒（避免常驻空转）
  if (
    Math.abs(targetX - curX) < 0.0006 &&
    Math.abs(targetY - curY) < 0.0006 &&
    Math.abs(targetPresence - presence) < 0.0006
  ) {
    curX = targetX
    curY = targetY
    rafActive = false
    return
  }
  rafId = requestAnimationFrame(parallaxTick)
}

function startParallax(): void {
  if (!rafActive) {
    rafActive = true
    rafId = requestAnimationFrame(parallaxTick)
  }
}

function handleMouseMove(e: MouseEvent): void {
  targetX = (e.clientX / window.innerWidth) * 2 - 1
  targetY = (e.clientY / window.innerHeight) * 2 - 1
  targetPresence = 1
  startParallax()
}

function handleMouseLeave(): void {
  targetX = 0
  targetY = 0
  targetPresence = 0
  startParallax()
}

function handleResize(): void {
  cacheCardRects()
}

/** 装饰性演示数字递增（0 → data-count）：rAF 驱动、ease-out cubic、后台标签自动暂停；
    仅为视觉演示元素，非真实业务数据；reduced-motion 时直接输出终值 */
function runCounters(reducedMotion: boolean): void {
  const nums = document.querySelectorAll<HTMLElement>('[data-count]')
  if (reducedMotion) {
    nums.forEach((el) => { el.textContent = el.dataset.count ?? '0' })
    return
  }
  const DURATION = 1400
  const DELAY = 900
  nums.forEach((el) => {
    const target = Number(el.dataset.count || 0)
    const start = performance.now() + DELAY
    const step = (now: number): void => {
      const p = Math.min(1, Math.max(0, (now - start) / DURATION))
      const eased = 1 - Math.pow(1 - p, 3)
      el.textContent = String(Math.round(target * eased))
      if (p < 1) requestAnimationFrame(step)
    }
    requestAnimationFrame(step)
  })
}

onMounted(() => {
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  runCounters(reducedMotion)
  // 仅精确指针（桌面）且未开启"减少动态效果"时启用视差；触屏/减弱动效只保留 CSS 浮动与静态光泽
  const finePointer = window.matchMedia('(pointer: fine)')
  if (!finePointer.matches || reducedMotion) return
  const layer = glassLayer.value
  if (layer) {
    cardEls = Array.from(layer.querySelectorAll<HTMLElement>('.glass-card'))
    cacheCardRects()
  }
  window.addEventListener('mousemove', handleMouseMove, { passive: true })
  document.documentElement.addEventListener('mouseleave', handleMouseLeave)
  window.addEventListener('resize', handleResize, { passive: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', handleMouseMove)
  document.documentElement.removeEventListener('mouseleave', handleMouseLeave)
  window.removeEventListener('resize', handleResize)
  cancelAnimationFrame(rafId)
  rafActive = false
})
</script>

<template>
  <div class="login-page">
    <!-- 3D 玻璃悬浮装饰卡片：纯装饰 · pointer-events:none · 位于登录卡后方（z-index 0 < 卡片 1） -->
    <div ref="glassLayer" class="glass-layer" aria-hidden="true">
      <!-- 共享渐变定义（各卡片 SVG 引用） -->
      <svg width="0" height="0" style="position: absolute">
        <defs>
          <linearGradient id="wtGlassG" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#5aa2f7" />
            <stop offset="1" stop-color="#2f6bf0" />
          </linearGradient>
        </defs>
      </svg>

      <!-- ① 任务进度：圆环（远景：translateZ 负值 + 轻景深柔化 + 低悬浮） -->
      <div class="glass-card gc-progress" style="--dx: 22px; --dy: 14px; --rfy: 5.5deg; --rfx: -4.5deg; --tz: -18px; --lift: 0.9; --fa: 4px; --fb: 5px; --fdur: 6.8s; --fdel: 0s">
        <div class="glass-inner">
          <svg class="gc-ring" viewBox="0 0 60 60">
            <circle cx="30" cy="30" r="24" fill="none" stroke="rgba(255, 255, 255, 0.6)" stroke-width="6" />
            <!-- 进度弧：dashoffset 151(0%) → 42.3(72%)，CSS 动画平滑增长 -->
            <circle class="ring-fill" cx="30" cy="30" r="24" fill="none" stroke="url(#wtGlassG)" stroke-width="6" stroke-linecap="round" stroke-dasharray="151" transform="rotate(-90 30 30)" />
            <!-- 达标高光：一次柔和蓝色脉冲（仅 opacity） -->
            <circle class="ring-glow" cx="30" cy="30" r="24" fill="none" stroke="url(#wtGlassG)" stroke-width="7" stroke-linecap="round" stroke-dasharray="151" stroke-dashoffset="42.3" transform="rotate(-90 30 30)" />
          </svg>
          <p class="gc-label">本周进度</p>
          <p class="gc-value"><b class="gc-num" data-count="72">0</b>%</p>
        </div>
      </div>

      <!-- ② 任务完成：立体勾选（前景：translateZ 正值 + 大视差幅度 + 高悬浮） -->
      <div class="glass-card gc-done" style="--dx: 34px; --dy: 20px; --rfy: 8deg; --rfx: -6deg; --tz: 26px; --lift: 1.15; --fa: 5px; --fb: 6.5px; --fdur: 8.2s; --fdel: 0.6s">
        <div class="glass-inner">
          <span class="gc-check">
            <svg viewBox="0 0 24 24"><path d="M5 12.5 10 17.5 19 7.5" fill="none" stroke="url(#wtGlassG)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" /></svg>
          </span>
          <p class="gc-label">今日已完成</p>
          <p class="gc-value"><b class="gc-num" data-count="12">0</b> 项</p>
        </div>
      </div>

      <!-- ③ 数据统计：柱状图（最远层：最大负 translateZ + 最小视差） -->
      <div class="glass-card gc-chart" style="--dx: 18px; --dy: 12px; --rfy: 4.5deg; --rfx: -3.5deg; --tz: -26px; --lift: 0.8; --fa: 4.5px; --fb: 5.5px; --fdur: 7.4s; --fdel: 1.1s">
        <div class="glass-inner">
          <div class="gc-bars">
            <i style="--h: 42%; --i: 0"></i><i style="--h: 70%; --i: 1"></i><i style="--h: 55%; --i: 2"></i><i style="--h: 88%; --i: 3"></i>
          </div>
          <p class="gc-label">任务统计</p>
        </div>
      </div>

      <!-- ④ 待办清单：状态行（中景） -->
      <div class="glass-card gc-todo" style="--dx: 14px; --dy: 10px; --rfy: 3.5deg; --rfx: -2.5deg; --tz: -8px; --lift: 1; --fa: 5px; --fb: 6px; --fdur: 9s; --fdel: 1.6s">
        <div class="glass-inner">
          <div class="gc-row" style="--i: 0"><span class="gc-dot is-done"></span><span class="gc-line" style="--w: 64%"></span></div>
          <div class="gc-row" style="--i: 1"><span class="gc-dot is-done"></span><span class="gc-line" style="--w: 46%"></span></div>
          <div class="gc-row" style="--i: 2"><span class="gc-dot"></span><span class="gc-line" style="--w: 72%"></span></div>
          <p class="gc-label">待办清单</p>
        </div>
      </div>
    </div>

    <el-card class="login-card" shadow="always">
      <div class="login-head">
        <!-- 品牌 Logo：任务清单行 + 勾选轨迹（扁平 SVG，零依赖） -->
        <span class="brand-logo" aria-hidden="true">
          <svg viewBox="0 0 48 48">
            <defs>
              <linearGradient id="wtBrandG" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stop-color="#5aa2f7" />
                <stop offset="1" stop-color="#2f6bf0" />
              </linearGradient>
            </defs>
            <rect x="1.5" y="1.5" width="45" height="45" rx="13.5" fill="url(#wtBrandG)" />
            <path d="M13 16.5h9.5" stroke="#cfe4ff" stroke-width="3.2" stroke-linecap="round" />
            <path d="M14 27.5 20 33.5 34.5 17.5" fill="none" stroke="#ffffff" stroke-width="4.4" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </span>
        <h1>WorkTrace</h1>
        <p class="brand-sub">让工作有序，让协作更高效</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" label-position="top" @submit.prevent>
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            :prefix-icon="User"
            autocomplete="username"
            clearable
            @input="revalidateIfError('username')"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            show-password
            autocomplete="current-password"
            @input="revalidateIfError('password')"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <!-- 注册专属：确认密码 + 昵称（可选） -->
        <el-form-item v-if="mode === 'register'" label="确认密码" prop="confirm">
          <el-input
            v-model="form.confirm"
            type="password"
            placeholder="再次输入密码"
            :prefix-icon="Lock"
            show-password
            autocomplete="new-password"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-form-item v-if="mode === 'register'" label="昵称（可选）" prop="nickname">
          <el-input
            v-model="form.nickname"
            placeholder="不填则与用户名相同"
            :prefix-icon="User"
            maxlength="16"
            clearable
          />
        </el-form-item>

        <el-button
          type="primary"
          class="login-btn"
          size="large"
          native-type="button"
          :loading="loading"
          @click="handleSubmit"
        >
          {{ loading ? (mode === 'register' ? '注册中…' : '登录中…') : mode === 'register' ? '注 册' : '登 录' }}
        </el-button>
      </el-form>

      <p class="login-switch">
        <template v-if="mode === 'register'">
          已有账号？
          <el-link type="primary" underline="never" @click="mode = 'login'">去登录</el-link>
        </template>
        <template v-else>
          没有账号？
          <el-link type="primary" underline="never" @click="mode = 'register'">去注册</el-link>
        </template>
      </p>

      </el-card>
  </div>
</template>

<style scoped>
/* ═══════ 第一阶段：整体布局 + 3D 背景视觉（蓝白科技风本地背景图 + 轻微白色遮罩） ═══════ */
.login-page {
  position: relative;
  /* border-box：min-height 100dvh 含 padding，避免移动端出现 48px 多余垂直滚动 */
  box-sizing: border-box;
  /* 100dvh 兜底：移动端浏览器动态工具栏收展时不会产生多余垂直滚动 */
  min-height: 100vh;
  min-height: 100dvh;
  /* flex + margin auto 居中：内容高于视口时自然增长（允许垂直滚动，无横向滚动条） */
  display: flex;
  padding: clamp(24px, 6vh, 56px) 24px;
  overflow-x: hidden;
  /* 3D 背景图：本地静态资源 · cover 适配窗口 · 居中 · 不拉伸变形；
     第一层为极轻白色遮罩（提升卡片文字对比度，不破坏 3D 质感），直接作为
     背景图层实现 —— 始终位于内容之下，无层叠/遮挡问题 */
  background:
    linear-gradient(rgba(255, 255, 255, 0.14), rgba(255, 255, 255, 0.14)),
    url('../assets/login-bg.png') center center / cover no-repeat;
  background-color: #eaf1fb;
}

/* ═══════ 第二阶段：登录卡片容器视觉（居中规则并入本块，消除重复定义） ═══════ */
.login-card {
  margin: auto;
  /* 层叠保护：登录卡永远位于玻璃装饰层之上（装饰层 pointer-events:none，不影响点击） */
  position: relative;
  z-index: 1;
  width: min(100%, 400px);
  /* 内距交由 el-card__body 统一控制，保证左右对齐与呼吸空间 */
  padding: 0;
  border-radius: 16px;
  /* 近纯白面板 + 细腻浅蓝灰边框（与背景同色系，层次清晰） */
  background: #fff;
  border: 1px solid #e9eef6;
  /* 三层低透明度蓝调阴影（与 3D 蓝白背景同色系）：贴地层 → 中层 → 远层，
     自然融入背景、无重黑投影 */
  box-shadow:
    0 1px 2px rgba(48, 76, 140, 0.05),
    0 12px 32px -14px rgba(48, 76, 140, 0.12),
    0 32px 68px -32px rgba(48, 76, 140, 0.17),
    /* 轻微蓝色环境反光：与 3D 玻璃卡片的光影语言统一 */
    0 4px 16px -8px rgba(90, 140, 240, 0.1);
}

/* 舒适内距：上 34 / 左右 34 / 下 26（底部仅注册入口一行，收紧更协调） */
.login-card :deep(.el-card__body) {
  padding: 34px 34px 26px;
}

/* ═══════ 第三阶段：品牌 Logo 与标题区域 ═══════ */
.login-head {
  margin-bottom: 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

/* Logo：48px 扁平方块 + 柔和品牌蓝投影，Hover 轻微上浮放大 */
.brand-logo {
  width: 48px;
  height: 48px;
  display: block;
  margin-bottom: 14px;
  filter: drop-shadow(0 8px 18px rgba(47, 107, 240, 0.26));
  transition: transform 0.2s ease, filter 0.2s ease;
  animation: brandIn 0.5s ease-out 0.05s both;
}

.brand-logo:hover {
  transform: translateY(-2px) scale(1.04);
  filter: drop-shadow(0 12px 22px rgba(47, 107, 240, 0.34));
}

.brand-logo:active {
  transform: translateY(0) scale(0.99);
}

.brand-logo svg {
  display: block;
  width: 100%;
  height: 100%;
}

/* 品牌名：26px / 700 / 深蓝灰纯色（无渐变文字） */
.login-head h1 {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 0.3px;
  color: #24344d;
  animation: brandIn 0.5s ease-out 0.12s both;
}

/* 产品定位副标题：柔和蓝灰 13.5px */
.brand-sub {
  margin: 7px 0 0;
  font-size: 13.5px;
  letter-spacing: 0.02em;
  color: #7b8aa6;
  animation: brandIn 0.5s ease-out 0.19s both;
}

@keyframes brandIn {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (prefers-reduced-motion: reduce) {
  .brand-logo,
  .login-head h1,
  .brand-sub {
    animation: none;
  }
  .brand-logo {
    transition: none;
  }
}

/* ═══════ 第五阶段：登录按钮视觉升级与交互体验 ═══════ */
/* 44px / 圆角 8 —— 与输入框完全同规范；轻微品牌蓝纵向渐变（克制） */
.login-card .login-btn {
  width: 100%;
  height: 44px;
  margin-top: 4px;
  padding: 0 16px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(180deg, #4b95ec 0%, #3988e8 62%, #3580e2 100%);
  box-shadow: 0 1px 2px rgba(31, 82, 168, 0.16);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 2px;
  color: #fff;
  transition:
    filter 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;
}

/* Hover：轻微加深 + 上浮 1px + 柔和蓝投影（无放大/弹跳/闪烁） */
.login-card .login-btn:not(:disabled):hover {
  filter: brightness(0.94);
  transform: translateY(-1px);
  box-shadow:
    0 6px 16px -6px rgba(47, 110, 229, 0.55),
    0 1px 2px rgba(31, 82, 168, 0.16);
}

/* Active：轻微下沉反馈，切换更快 */
.login-card .login-btn:not(:disabled):active {
  transition-duration: 0.06s;
  transform: translateY(0);
  filter: brightness(0.9);
  box-shadow: 0 1px 3px rgba(31, 82, 168, 0.3);
}

/* 键盘焦点：清晰但不刺眼的品牌蓝光环 */
.login-card .login-btn:focus-visible {
  outline: none;
  box-shadow:
    0 1px 2px rgba(31, 82, 168, 0.16),
    0 0 0 3px rgba(57, 136, 232, 0.24);
}

/* 加载 / 禁用态：明确但柔和（去悬停动效，轻微去饱和，尺寸不变） */
.login-card .login-btn.is-loading,
.login-card .login-btn:disabled {
  filter: saturate(0.8);
  opacity: 0.82;
  cursor: not-allowed;
  transform: none;
  box-shadow: 0 1px 2px rgba(31, 82, 168, 0.14);
}

.login-card .login-btn.is-loading:hover,
.login-card .login-btn:disabled:hover {
  filter: saturate(0.8);
  transform: none;
  box-shadow: 0 1px 2px rgba(31, 82, 168, 0.14);
}

/* 内部 spinner：白色、与文字基线协调 */
.login-card .login-btn .el-icon.is-loading {
  color: #fff;
  margin-right: 2px;
}

@media (prefers-reduced-motion: reduce) {
  .login-card .login-btn {
    transition: none;
  }
}

/* ═══════ 第六阶段：注册入口与底部布局（无演示账号，保持简洁专业） ═══════ */
.login-switch {
  margin: 20px 0 0;
  color: #7b8aa6;
  font-size: 13px;
  letter-spacing: 0.02em;
  text-align: center;
}

/* 「去注册 / 去登录」：品牌蓝 + 600 字重，悬停轻微加深（复用现有 el-link 跳转逻辑） */
.login-switch .el-link {
  margin-left: 2px;
  font-weight: 600;
  color: #3988e8;
  transition: color 0.18s ease;
}

.login-switch .el-link:hover {
  color: #2a6fd0;
}

/* ═══════ 第四阶段：输入框视觉与表单交互 ═══════ */
/* 统一输入框规范：44px 高 · 圆角 8 · 浅灰蓝边框 · 纯白底 */
.login-card :deep(.el-input) {
  --el-input-height: 44px;
}

.login-card :deep(.el-input__wrapper) {
  padding: 1px 12px;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 0 0 1px #dfe7f2 inset;
  transition: box-shadow 0.18s ease;
}

.login-card :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #c6d4e8 inset;
}

/* 聚焦：品牌蓝边框 + 柔和蓝色外发光（:focus-within 纯 CSS，不依赖组件 JS 类） */
.login-card :deep(.el-input__wrapper:focus-within) {
  box-shadow: 0 0 0 1.5px #3988e8 inset, 0 0 0 3px rgba(57, 136, 232, 0.16);
}

/* 输入文字深蓝灰 · 占位符柔和灰蓝 */
.login-card :deep(.el-input__inner) {
  color: #24344d;
  font-size: 14.5px;
}

.login-card :deep(.el-input__inner::placeholder) {
  color: #96a7bf;
}

/* 图标统一：16px · 默认灰蓝 · 聚焦同步品牌蓝 · 与文字 8px 间距 · 平滑过渡 */
.login-card :deep(.el-input__prefix) {
  display: inline-flex;
  align-items: center;
  margin-right: 8px;
}

.login-card :deep(.el-input__suffix) {
  display: inline-flex;
  align-items: center;
  margin-left: 8px;
}

.login-card :deep(.el-input__icon) {
  font-size: 16px;
  color: #93a5bd;
  transition: color 0.18s ease, transform 0.18s ease;
}

.login-card :deep(.el-input__wrapper:focus-within .el-input__icon) {
  color: #3988e8;
}

/* 密码眼睛（及清除按钮）：轻微缩放反馈 */
.login-card :deep(.el-input__suffix .el-input__icon:hover) {
  transform: scale(1.08);
  color: #5b7ea8;
}

/* 表单标签：统一字号字重 · 柔和必填星号 · 与输入框 7px 间距 */
.login-card :deep(.el-form-item__label) {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
  color: #45546f;
  margin-bottom: 7px;
  padding-bottom: 0;
}

.login-card :deep(.el-form-item__label::before) {
  color: #e8756f;
  font-size: 12px;
  margin-right: 4px;
}

/* 表单项垂直节奏统一；错误文字绝对定位（不引起布局跳动） */
.login-card :deep(.el-form-item) {
  margin-bottom: 22px;
}

.login-card :deep(.el-form-item__error) {
  top: calc(100% + 5px);
  font-size: 12px;
  color: #e06c6c;
}

/* 输入错误态：柔和红边框 + 极淡红光（覆盖聚焦蓝） */
.login-card :deep(.el-form-item.is-error .el-input__wrapper) {
  box-shadow: 0 0 0 1.5px #e88a8a inset, 0 0 0 3px rgba(230, 110, 110, 0.1);
}

/* ═══════ 第六阶段：响应式适配 ═══════ */
/* 手机端（≤480px）：卡片 100%-32px · 上限 420px · 内距收紧 · 背景焦点右移
   —— 竖屏 cover 横向裁切大，把可见窗右移让右上角 3D 勾选元素可见（呼应品牌 Logo），
   内容高于视口时 flex 容器自然增长、允许垂直滚动 */
@media (max-width: 480px) {
  .login-page {
    padding: 24px 16px;
    /* 两层背景分别定位：遮罩层居中 · 背景图焦点 88% 50% */
    background-position: center, 88% 50%;
  }

  .login-card {
    width: 100%;
    max-width: 420px;
  }

  .login-card :deep(.el-card__body) {
    padding: 28px 22px 22px;
  }
}

/* ═══════ 第八阶段：3D 玻璃悬浮卡片 + 鼠标视差 ═══════ */
/* 装饰层：铺满背景 · 不响应任何鼠标事件（登录表单零干扰） · 承载 JS 视差变量 --mx/--my */
.glass-layer {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
  --mx: 0;
  --my: 0;
}

/* 外层：JS 视差变换（perspective + rotateX/rotateY + 视差位移 + translateZ 纵深）
   幅度由每张卡的内联 --dx/--dy/--rfx/--rfy/--tz 决定 —— 前后景远近分层 */
.glass-card {
  position: absolute;
  transform:
    perspective(900px)
    rotateX(calc(var(--my) * var(--rfx, -5deg)))
    rotateY(calc(var(--mx) * var(--rfy, 6deg)))
    translate3d(calc(var(--mx) * var(--dx, 24px)), calc(var(--my) * var(--dy, 14px)), var(--tz, 0px))
    scale(calc(var(--gs, 1) * (1 + var(--near, 0) * 0.045)));
  will-change: transform;
}

/* 内层：玻璃本体 —— 三层渐变模拟光线折射（顶部光窗 → 对角光线 → 通透基底），
   避免沦为"普通白矩形"；缓慢浮动（与外层视差分离，互不冲突） */
.glass-inner {
  position: relative;
  min-width: 126px;
  padding: 16px 18px 14px;
  border-radius: 18px;
  text-align: center;
  background:
    radial-gradient(130% 70% at 20% -8%, rgba(255, 255, 255, 0.4), transparent 56%),
    linear-gradient(155deg, rgba(255, 255, 255, 0.32), rgba(234, 241, 252, 0.14) 52%, rgba(214, 228, 250, 0.24)),
    rgba(255, 255, 255, 0.14);
  -webkit-backdrop-filter: blur(12px) saturate(1.3) brightness(1.03);
  backdrop-filter: blur(12px) saturate(1.3) brightness(1.03);
  border: 1px solid rgba(255, 255, 255, 0.55);
  box-shadow:
    /* 细腻边缘高光：顶部白光 + 底部蓝色环境反射 + 内侧极淡蓝晕 */
    inset 0 1px 0 rgba(255, 255, 255, 0.78),
    inset 0 -1px 0 rgba(122, 162, 240, 0.16),
    inset 0 0 12px rgba(90, 162, 247, 0.07),
    /* 多层蓝色环境阴影：范围/扩散随内联 --lift（悬浮高度）变化，各卡参数略有差异 */
    0 14px calc(30px * var(--lift, 1)) -10px rgba(48, 76, 140, 0.2),
    0 32px calc(62px * var(--lift, 1)) -26px rgba(48, 76, 140, 0.24);
  animation: glassFloat var(--fdur, 7.5s) ease-in-out var(--fdel, 0s) infinite alternate;
}

/* 玻璃光窗：常驻顶部光泽 + 跟随鼠标的高光（--lx/--ly 为指针相对卡中心偏移，
   --near 为靠近程度 0~1，均由视差循环写入）· 平滑无闪烁 */
.glass-inner::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background:
    radial-gradient(54% 44% at calc(50% + var(--lx, 0%)) calc(26% + var(--ly, 0%)), rgba(255, 255, 255, 0.46), transparent 72%),
    radial-gradient(120% 60% at 24% 0%, rgba(255, 255, 255, 0.3), transparent 58%);
  opacity: calc(0.55 + var(--near, 0) * 0.4);
  pointer-events: none;
}

/* 浮动时的贴地投影：同周期呼吸（仅 opacity，避免逐帧绘制 box-shadow） */
.glass-inner::after {
  content: '';
  position: absolute;
  inset: 30% 8% -14%;
  z-index: -1;
  border-radius: 50%;
  background: rgba(48, 76, 140, 0.3);
  filter: blur(20px);
  opacity: 0.75;
  animation: glassShadow var(--fdur, 7.5s) ease-in-out var(--fdel, 0s) infinite alternate;
}

@keyframes glassFloat {
  from { transform: translateY(calc(var(--fa, 5px) * -1)); }
  to { transform: translateY(var(--fb, 6px)); }
}

@keyframes glassShadow {
  from { opacity: 0.55; transform: translateY(2px) scale(0.98); }
  to { opacity: 0.85; transform: translateY(0) scale(1); }
}

/* ── 四张卡片内容样式（蓝白科技风 · 蓝色渐变图标与数据） ── */
.gc-ring {
  display: block;
  width: 52px;
  height: 52px;
  margin: 0 auto;
}

.gc-check {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  margin: 0 auto;
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.55);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8), 0 6px 14px -6px rgba(47, 110, 229, 0.4);
}

.gc-check svg {
  display: block;
  width: 26px;
  height: 26px;
}

.gc-bars {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 7px;
  height: 46px;
  padding: 0 4px;
}

.gc-bars i {
  width: 8px;
  height: var(--h, 50%);
  border-radius: 3px 3px 2px 2px;
  background: linear-gradient(180deg, #5aa2f7, #2f6bf0);
  box-shadow: 0 2px 6px -2px rgba(47, 110, 229, 0.4);
}

.gc-bars i:first-child {
  background: linear-gradient(180deg, #b9d4fb, #6b9df3);
}

.gc-row {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 7px 0;
}

.gc-dot {
  width: 9px;
  height: 9px;
  flex: none;
  border-radius: 50%;
  border: 1.6px solid #9db9e8;
  background: rgba(255, 255, 255, 0.4);
}

.gc-dot.is-done {
  border-color: transparent;
  background: linear-gradient(180deg, #5aa2f7, #2f6bf0);
}

.gc-line {
  width: var(--w, 60%);
  height: 6px;
  border-radius: 3px;
  background: rgba(90, 162, 247, 0.4);
}

.gc-row:first-child .gc-line {
  background: linear-gradient(90deg, #5aa2f7, #2f6bf0);
}

.gc-label {
  margin: 9px 0 0;
  font-size: 11px;
  letter-spacing: 0.03em;
  color: #5b6d8d;
}

.gc-value {
  margin: 2px 0 0;
  font-size: 16px;
  font-weight: 700;
  background: linear-gradient(90deg, #2f6bf0, #5aa2f7);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

/* ── 桌面端摆放：四角分布，绝不进入中央登录卡区域 ── */
.gc-progress { left: 6.5%; top: 17%; }
.gc-done { right: 6.5%; top: 21%; }
.gc-chart { left: 8.5%; bottom: 16%; }
.gc-todo { right: 8%; bottom: 19%; }

/* ── 平板端：减数量（隐藏待办卡）、减幅度（缩放+位移折半） ── */
@media (max-width: 900px) {
  .glass-card { --gs: 0.82; }
  .gc-todo { display: none; }
  .gc-progress { left: 2.5%; top: 9%; }
  .gc-done { right: 2.5%; top: 13%; }
  .gc-chart { left: 4%; bottom: 11%; }
}

/* ── 手机端：禁用鼠标跟随（JS 按 pointer:fine 判定）；仅保留 1 张轻浮动小卡，表单优先 ── */
@media (max-width: 480px) {
  .glass-card { --gs: 0.7; }
  .gc-progress,
  .gc-chart { display: none; }
  .gc-done { right: 5%; top: 2%; }
}

/* 矮屏手机（如 375x667）：卡片上下空间不足，全部隐藏装饰，登录表单完全优先 */
@media (max-width: 480px) and (max-height: 700px) {
  .glass-card { display: none; }
}

/* ── 减少动态效果：关闭浮动/光泽/投影呼吸（视差已在 JS 侧跳过） ── */
@media (prefers-reduced-motion: reduce) {
  .glass-inner,
  .glass-inner::before,
  .glass-inner::after {
    animation: none;
  }
  .glass-card {
    transform: none;
    will-change: auto;
  }
}

/* ═══════ 第十阶段：卡片微交互与数据展示动画（全部 one-shot，仅 transform/opacity） ═══════ */
/* 统一缓动曲线 */
.glass-layer {
  --ease-out: cubic-bezier(0.22, 1, 0.36, 1);
}

/* ① 进度环：dashoffset 151(0%) → 42.3(72%) 平滑增长，与数字递增同步（0.9s 起 / 1.4s 时长） */
.ring-fill {
  stroke-dasharray: 151;
  stroke-dashoffset: 151;
  animation: ringGrow 1.4s var(--ease-out) 0.9s forwards;
}

@keyframes ringGrow {
  to { stroke-dashoffset: 42.3; }
}

/* 达标高光：整环一次柔和蓝色脉冲（仅 opacity，不闪烁不旋转） */
.ring-glow {
  opacity: 0;
  animation: ringGlow 0.9s ease 2.25s 1;
}

@keyframes ringGlow {
  0% { opacity: 0; }
  40% { opacity: 0.45; }
  100% { opacity: 0; }
}

/* ② 完成勾选：轻微弹性进入（温和 back-out，无夸张弹跳）+ 一次蓝色光效扩散 */
.gc-check {
  position: relative;
  animation: checkPop 0.55s cubic-bezier(0.34, 1.4, 0.64, 1) 1s both;
}

@keyframes checkPop {
  from { transform: scale(0.55); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}

.gc-check::after {
  content: '';
  position: absolute;
  inset: -3px;
  border-radius: 16px;
  pointer-events: none;
  animation: checkGlow 0.95s ease 1.4s 1 both;
}

@keyframes checkGlow {
  0% { box-shadow: 0 0 0 0 rgba(90, 150, 240, 0.32); opacity: 1; }
  100% { box-shadow: 0 0 0 14px rgba(90, 150, 240, 0); opacity: 0; }
}

/* ③ 柱状图：自底部依次升起（scaleY 0→1，0.15s 错落延迟） */
.gc-bars i {
  transform: scaleY(0);
  transform-origin: bottom;
  animation: barGrow 0.75s var(--ease-out) forwards;
  animation-delay: calc(1.05s + var(--i, 0) * 0.15s);
}

@keyframes barGrow {
  to { transform: scaleY(1); }
}

/* ④ 待办清单：行依次淡入上移；状态圆点轻微呼吸 */
.gc-row {
  animation: rowIn 0.5s var(--ease-out) both;
  animation-delay: calc(1s + var(--i, 0) * 0.16s);
}

@keyframes rowIn {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

.gc-dot {
  animation: dotBreath 3.2s ease-in-out var(--bdel, 0s) infinite alternate;
}

.gc-row:nth-child(2) .gc-dot {
  animation-delay: 0.7s;
}

.gc-row:nth-child(3) .gc-dot {
  animation-delay: 1.4s;
}

@keyframes dotBreath {
  from { transform: scale(0.9); opacity: 0.85; }
  to { transform: scale(1.12); opacity: 1; }
}

/* 计数数字：宽度稳定占位，递增时不抖动卡片布局 */
.gc-num {
  font: inherit;
  display: inline-block;
  min-width: 1.4em;
}

/* 减少动态效果：所有数据/微交互动画直接呈现终值终态 */
@media (prefers-reduced-motion: reduce) {
  .ring-fill {
    animation: none;
    stroke-dashoffset: 42.3;
  }
  .ring-glow,
  .gc-check::after {
    animation: none;
    opacity: 0;
  }
  .gc-check {
    animation: none;
    transform: none;
    opacity: 1;
  }
  .gc-bars i {
    animation: none;
    transform: none;
  }
  .gc-row {
    animation: none;
    opacity: 1;
    transform: none;
  }
  .gc-dot {
    animation: none;
  }
}
</style>
