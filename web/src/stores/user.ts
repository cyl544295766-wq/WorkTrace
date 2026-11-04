import { defineStore } from 'pinia'

/** 当前登录用户信息（按后端返回自行增减字段） */
export interface UserInfo {
  id: number
  username: string
  nickname: string
  roles: string[]
}

interface UserState {
  token: string // Token 方案下保存；纯 Session 方案留空（Cookie 自动携带）
  user: UserInfo | null
}

/** localStorage 持久化 key */
const STORAGE_KEY = 'worktrace:user'

const EMPTY_STATE: UserState = { token: '', user: null }

/** 启动时从 localStorage 恢复，解析失败则忽略（相当于未登录） */
function loadPersisted(): UserState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...EMPTY_STATE }
    const saved = JSON.parse(raw) as Partial<UserState>
    return {
      token: saved.token ?? '',
      user: saved.user ?? null,
    }
  } catch {
    return { ...EMPTY_STATE }
  }
}

export const useUserStore = defineStore('user', {
  state: (): UserState => loadPersisted(),

  getters: {
    isLoggedIn: (s) => Boolean(s.user),
    isAdmin: (s) => s.user?.roles?.includes('ADMIN') ?? false,
    hasRole: (s) => (role: string) => s.user?.roles?.includes(role) ?? false,
  },

  actions: {
    /** 登录成功后写入用户信息与可选 token，并持久化 */
    setUser(token: string, user: UserInfo) {
      this.token = token
      this.user = user
      this.persist()
    },

    /** 仅更新 token（纯 Session 场景不需要调用） */
    setToken(token: string) {
      this.token = token
      this.persist()
    },

    /** 退出登录 / 会话失效：清空并清除持久化 */
    clear() {
      this.token = ''
      this.user = null
      localStorage.removeItem(STORAGE_KEY)
    },

    persist() {
      localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({ token: this.token, user: this.user }),
      )
    },
  },
})
