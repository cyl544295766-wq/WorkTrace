import { createApp } from 'vue'
import App from './App.vue'
import { createPinia } from 'pinia'

import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css' // 默认主题（全量样式，组件与 ElMessage 等函数式组件共用）
import 'element-plus/theme-chalk/dark/css-vars.css' // 暗黑主题变量（激活方式：html 挂 dark class）
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import * as Icons from '@element-plus/icons-vue'

const app = createApp(App)

// 必须先装 pinia：request.ts 的拦截器里会用到 useUserStore()
app.use(createPinia())

// 全量注册 Element Plus（含 ElMessage/ElMessageBox 等函数式组件的上下文）
app.use(ElementPlus, { locale: zhCn })

// 全局注册全部图标：模板里可直接用 <ElIconXxx />，或 :icon="Search"
for (const [name, component] of Object.entries(Icons)) {
  app.component(`ElIcon${name}`, component)
}

app.mount('#app')
