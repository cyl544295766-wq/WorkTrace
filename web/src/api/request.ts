import axios, {
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosError,
} from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

/** 后端统一响应结构：{ code, message, data, traceId, ts }，code=0 表示成功 */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
  traceId?: string
  ts?: number
}

/** 业务错误：拦截器抛出，调用方可 catch(e => e.code / e.message) */
export class ApiError extends Error {
  code: number
  traceId?: string
  constructor(code: number, message: string, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

const request: AxiosInstance = axios.create({
  // 请求前缀：dev 环境由 .env.development 提供 VITE_API_BASE=/api
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15_000,
  withCredentials: true, // Session 方案：始终携带会话 Cookie
})

/* ── 请求拦截器：统一附加登录态 ─────────────────────────────
 * 在回调内才取 store（而非模块顶层），
 * 避免 pinia 尚未安装时 request.ts 被 import 导致报错。
 */
request.interceptors.request.use((config) => {
  const { token } = useUserStore()
  if (token) {
    config.headers.Authorization = `Bearer ${token}` // Token 方案；Session 方案由 Cookie 自动携带
  }
  return config
})

/* ── 统一错误提示：同一时刻只弹一条，避免并发请求弹一串 ── */
let messageVisible = false
function toastError(message: string) {
  if (messageVisible) return
  messageVisible = true
  ElMessage.error(message || '请求失败，请稍后重试')
  setTimeout(() => {
    messageVisible = false
  }, 3000)
}

/* ── 认证/会话失效：清空登录态并回登录页 ── */
function handleAuthExpired(message: string) {
  const userStore = useUserStore()
  if (userStore.isLoggedIn) userStore.clear()
  if (!window.location.pathname.startsWith('/login')) {
    toastError(message || '登录已过期，请重新登录')
    window.location.href = '/login'
  }
}

const AUTH_BIZ_CODES = new Set([20001, 20002, 20003]) // 会话无/过期/强制下线（2xxxx 段）

request.interceptors.response.use(
  (response) => {
    // 文件流（下载/导出）不拆包，原样返回
    if (response.config.responseType === 'blob') return response

    const res = response.data as ApiResult
    if (res.code === 0) {
      // 成功：直接把业务 data 抛给调用方
      return res.data as never
    }
    // 业务失败：统一提示
    if (AUTH_BIZ_CODES.has(res.code)) {
      handleAuthExpired(res.message) // 会话过期：清理 + 跳登录
    } else {
      toastError(res.message)
    }
    return Promise.reject(new ApiError(res.code, res.message, res.traceId))
  },
  (error: AxiosError<ApiResult>) => {
    // HTTP 层失败：网络异常 / 超时 / 4xx-5xx
    const status = error.response?.status
    if (status === 401) {
      handleAuthExpired('登录已过期，请重新登录')
    } else if (status === 403) {
      toastError('没有操作权限')
    } else if (error.code === 'ECONNABORTED') {
      toastError('请求超时，请稍后重试')
    } else if (!error.response) {
      toastError('网络异常，请检查网络连接')
    } else {
      toastError(error.response.data?.message || `请求失败（${status}）`)
    }
    return Promise.reject(
      new ApiError(
        status ?? -1,
        error.response?.data?.message || error.message,
        error.response?.headers?.['x-trace-id'],
      ),
    )
  },
)

/* ── 便捷调用：泛型默认拿到拆包后的业务 data ──
 * const user = await httpGet<UserInfo>('/user/me', { id: 1 })
 */
export function httpGet<T>(url: string, params?: AxiosRequestConfig['params']) {
  return request.get<unknown, T>(url, { params })
}
export function httpPost<T>(url: string, data?: AxiosRequestConfig['data']) {
  return request.post<unknown, T>(url, data)
}
export function httpPut<T>(url: string, data?: AxiosRequestConfig['data']) {
  return request.put<unknown, T>(url, data)
}
export function httpDelete<T>(url: string, params?: AxiosRequestConfig['params']) {
  return request.delete<unknown, T>(url, { params })
}

export default request
