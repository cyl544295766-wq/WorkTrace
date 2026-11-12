<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
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
</script>

<template>
  <div class="login-page">
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
          <el-link type="primary" :underline="false" @click="mode = 'login'">去登录</el-link>
        </template>
        <template v-else>
          没有账号？
          <el-link type="primary" :underline="false" @click="mode = 'register'">去注册</el-link>
        </template>
      </p>

      <p class="login-hint">演示账号：admin / admin123</p>
    </el-card>
  </div>
</template>

<style scoped>
/* ═══════ 第一阶段：整体布局 + 背景视觉（浅蓝灰渐变 + 柔和蓝色光晕） ═══════ */
.login-page {
  position: relative;
  min-height: 100vh;
  /* flex + margin auto 居中：内容高于视口时也不会被裁切（无异常垂直溢出） */
  display: flex;
  padding: clamp(24px, 6vh, 56px) 24px;
  /* 光晕装饰禁止产生横向滚动条 */
  overflow-x: hidden;
  background:
    /* 柔和蓝色光晕 · 左上 */
    radial-gradient(900px 540px at 12% -10%, rgba(96, 145, 240, 0.18), transparent 62%),
    /* 柔和蓝紫光晕 · 右下（空间层次） */
    radial-gradient(860px 520px at 90% 110%, rgba(133, 150, 245, 0.15), transparent 64%),
    /* 中部极淡冷光，让卡片周围更通透 */
    radial-gradient(760px 460px at 50% 44%, rgba(219, 232, 252, 0.55), transparent 72%),
    /* 基底：浅蓝灰渐变 */
    linear-gradient(165deg, #f3f7fd 0%, #e9eef9 52%, #edf2fb 100%);
}

/* 登录区域：水平垂直居中 + 舒适留白（卡片本体样式不变） */
.login-card {
  margin: auto;
}

/* ═══════ 第二阶段：登录卡片容器视觉升级（背景与布局保持不变） ═══════ */
.login-card {
  width: min(100%, 400px);
  /* 内距交由 el-card__body 统一控制，保证左右对齐与呼吸空间 */
  padding: 0;
  border-radius: 16px;
  /* 近纯白面板 + 细腻浅蓝灰边框（与背景同色系，层次清晰） */
  background: #fff;
  border: 1px solid #e9eef6;
  /* 多层低透明度灰蓝阴影：贴地层 → 中层 → 远层，自然浮现、无重黑投影 */
  box-shadow:
    0 1px 2px rgba(37, 60, 112, 0.04),
    0 10px 28px -12px rgba(37, 60, 112, 0.1),
    0 28px 60px -28px rgba(37, 60, 112, 0.16);
}

/* 舒适内距：上 34 / 左右 34 / 下 28（内容区统一，无多余空白） */
.login-card :deep(.el-card__body) {
  padding: 34px 34px 28px;
}

/* 品牌区 → 表单 → 底部辅助信息的节奏微调（仅间距，不改内容） */
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

.login-switch {
  margin: 18px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  text-align: center;
}

.login-hint {
  margin: 14px 0 0;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  text-align: center;
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
</style>
