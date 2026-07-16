import { createApp } from 'vue'
import { createPinia } from 'pinia'
// 离线自托管字体（绝不走 CDN）：Material Symbols 图标 + 正文/标题/等宽字体
import 'material-symbols/outlined.css'
import '@fontsource/inter/400.css'
import '@fontsource/inter/500.css'
import '@fontsource/inter/600.css'
import '@fontsource/manrope/700.css'
import '@fontsource/manrope/800.css'
import '@fontsource/jetbrains-mono/400.css'

import './assets/main.css' // Tailwind + Material 令牌
import App from './App.vue'
import { router } from './router'

createApp(App).use(createPinia()).use(router).mount('#app')