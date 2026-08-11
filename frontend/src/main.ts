import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'

/**
 * Vue 应用启动入口：Pinia -> 路由 -> Element Plus 及图标注册。
 * 说明：
 * - 图标组件全量注册，模板中可直接使用 icon 标签
 * - 任何挂载前初始化逻辑建议放在 App.vue 或插件层，不建议放在入口文件里
 */
const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
app.mount('#app')
