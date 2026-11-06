<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
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

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleLogin(): Promise<void> {
  if (!formRef.value) return
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
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-head">
        <h1>WorkTrace</h1>
        <p>任务管理 · 登录以继续</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" label-position="top" @submit.prevent>
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            :prefix-icon="User"
            autocomplete="username"
            clearable
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
            @keyup.enter="handleLogin"
          />
        </el-form-item>

        <el-button
          type="primary"
          class="login-btn"
          size="large"
          native-type="button"
          :loading="loading"
          @click="handleLogin"
        >
          {{ loading ? '登录中…' : '登 录' }}
        </el-button>
      </el-form>

      <p class="login-hint">演示账号：admin / admin123</p>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(circle at 50% 0%, var(--el-color-primary-light-9), transparent 55%),
    var(--el-bg-color-page);
}

.login-card {
  width: min(100%, 400px);
  padding: 8px 4px;
  border-radius: 16px;
}

.login-head {
  margin-bottom: 20px;
  text-align: center;
}

.login-head h1 {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 0.5px;
  background: linear-gradient(90deg, var(--el-color-primary), var(--el-color-primary-light-3));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.login-head p {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.login-btn {
  width: 100%;
  margin-top: 4px;
}

.login-hint {
  margin: 16px 0 0;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  text-align: center;
}
</style>
