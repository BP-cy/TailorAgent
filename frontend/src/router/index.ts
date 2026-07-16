import { createRouter, createWebHashHistory } from 'vue-router'

// 嵌入式 WebView 场景用 hash 模式最省事：刷新/深链不会 404，后端无需 fallback 路由。
// 所有路由组件使用动态 import() 懒加载：首屏只加载 MainPage，编辑器等按需加载。
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'main', component: () => import('../views/MainPage.vue') },
    // MD 文档阅读页：纯 HTML 渲染，无编辑元素。path 为知识库相对路径（含 /），用 catch-all 参数
    { path: '/reader/:path(.*)', name: 'reader', component: () => import('../views/ReaderView.vue') },
    // 富文本编辑页：带 path 为编辑已有文档，无 path 为新建
    { path: '/editor/:path(.*)?', name: 'editor', component: () => import('../views/EditorView.vue') },
    { path: '/about', name: 'about', component: () => import('../views/AboutView.vue') },
  ],
})