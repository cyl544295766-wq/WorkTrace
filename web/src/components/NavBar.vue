<script setup lang="ts">
import { RouterLink, useRouter } from 'vue-router'
import { SwitchButton, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

function logout(): void {
  userStore.clear()
  router.push('/login')
}
</script>

<template>
  <header class="navbar">
    <div class="navbar__left">
      <RouterLink class="navbar__brand" to="/">
        <span class="navbar__logo" aria-hidden="true">W</span>
        <span class="navbar__brand-text">WorkTrace</span>
      </RouterLink>
      <!-- 浅蓝胶囊导航：当前项为浅蓝胶囊，hover 柔和过渡（首页必须 exact-active，否则常亮） -->
      <nav class="navbar__menu" aria-label="主导航">
        <RouterLink class="navbar__item" to="/">首页</RouterLink>
        <RouterLink class="navbar__item" to="/tasks">任务管理</RouterLink>
        <RouterLink class="navbar__item" to="/reports">AI 报告</RouterLink>
      </nav>
    </div>

    <div class="navbar__right">
      <span class="navbar__user">
        <span class="navbar__avatar" aria-hidden="true">
          <el-icon :size="15"><User /></el-icon>
        </span>
        <span class="navbar__name">{{
          userStore.user?.nickname || userStore.user?.username
        }}</span>
      </span>
      <span class="navbar__divider" aria-hidden="true" />
      <button type="button" class="navbar__logout" @click="logout">
        <el-icon :size="14"><SwitchButton /></el-icon>
        <span>退出登录</span>
      </button>
    </div>
  </header>
</template>

<style scoped>
.navbar {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 24px;
  min-height: 58px;
  padding: 8px 24px;
  border-bottom: 1px solid rgba(227, 235, 247, 0.9);
  background: rgba(255, 255, 255, 0.84);
  box-shadow:
    inset 0 -1px 0 rgba(120, 168, 255, 0.08),
    0 10px 24px -22px rgba(36, 50, 74, 0.5);
  backdrop-filter: blur(14px) saturate(1.2);
  -webkit-backdrop-filter: blur(14px) saturate(1.2);
}

.navbar__left {
  display: flex;
  align-items: center;
  gap: 22px;
}

/* ── 品牌：立体渐变方标 + 字标 ── */
.navbar__brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: #24324a;
  font-size: 17px;
  font-weight: 800;
  letter-spacing: -0.02em;
  text-decoration: none;
}

.navbar__logo {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 9px;
  color: #fff;
  font-size: 14px;
  font-weight: 800;
  line-height: 1;
  background: linear-gradient(145deg, #78a8ff 0%, #3978f6 70%);
  box-shadow:
    inset 0 1px 1px rgba(255, 255, 255, 0.65),
    inset 0 -2px 4px rgba(20, 60, 120, 0.28),
    0 6px 14px -8px rgba(57, 120, 246, 0.7);
}

/* ── 菜单：文字层级 14/600，当前项浅蓝胶囊 ── */
.navbar__menu {
  display: flex;
  gap: 4px;
}

.navbar__item {
  padding: 7px 16px;
  border-radius: 999px;
  color: #5b6b82;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.4;
  text-decoration: none;
  transition:
    background 0.22s ease,
    color 0.22s ease,
    box-shadow 0.22s ease;
}

.navbar__item:hover {
  background: rgba(57, 120, 246, 0.07);
  color: #2f6fe4;
}

/* 必须用 exact：首页 to="/" 会被普通 active 视为所有路由的祖先而常亮 */
.navbar__item.router-link-exact-active {
  background: linear-gradient(135deg, #e8f2ff 0%, #ddebff 100%);
  color: #2f6fe4;
  font-weight: 700;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.9),
    0 4px 10px -6px rgba(57, 120, 246, 0.45);
}

/* ── 用户区：头像 + 名字 + 分隔线 + 胶囊退出按钮 ── */
.navbar__right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.navbar__user {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 10px 4px 4px;
  border-radius: 999px;
  transition: background 0.22s ease;
}

.navbar__user:hover {
  background: rgba(57, 120, 246, 0.06);
}

.navbar__avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  color: #fff;
  background: linear-gradient(145deg, #78a8ff, #3978f6);
  box-shadow:
    inset 0 1px 1px rgba(255, 255, 255, 0.65),
    inset 0 -2px 4px rgba(20, 60, 120, 0.25),
    0 5px 12px -7px rgba(57, 120, 246, 0.7);
}

.navbar__name {
  max-width: 140px;
  overflow: hidden;
  color: #24324a;
  font-size: 14px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.navbar__divider {
  width: 1px;
  height: 20px;
  background: rgba(137, 151, 172, 0.24);
}

.navbar__logout {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 14px;
  border: 1px solid rgba(227, 235, 247, 0.9);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.8);
  color: #5b6b82;
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.22s ease,
    color 0.22s ease,
    border-color 0.22s ease,
    box-shadow 0.22s ease,
    transform 0.15s ease;
}

.navbar__logout:hover {
  border-color: rgba(57, 120, 246, 0.3);
  background: #fff;
  color: #2f6fe4;
  box-shadow: 0 6px 14px -10px rgba(57, 120, 246, 0.7);
}

.navbar__logout:active {
  transform: translateY(1px) scale(0.98);
}

.navbar__logout:focus-visible,
.navbar__item:focus-visible,
.navbar__brand:focus-visible {
  outline: 2px solid rgba(57, 120, 246, 0.5);
  outline-offset: 2px;
}

@media (max-width: 640px) {
  .navbar {
    padding: 8px 14px;
    gap: 6px 12px;
  }

  .navbar__left {
    gap: 12px;
  }

  .navbar__brand-text {
    display: none;
  }

  .navbar__item {
    padding: 6px 12px;
    font-size: 13.5px;
  }

  .navbar__name {
    display: none;
  }

  .navbar__divider {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .navbar__item,
  .navbar__user,
  .navbar__logout {
    transition: none;
  }
}
</style>
