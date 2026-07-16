import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      // 与从旧项目移植的组件保持一致：用 @ 指向 src
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    // 开发联调：把 /api 请求代理到固定端口运行的 Spring Boot（profile=dev）
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      // 图片等媒体也代理到后端，开发期与打包期路径一致
      '/media': 'http://127.0.0.1:8080',
    },
  },
  build: {
    // 构建产物直接输出到后端静态资源目录，随后被打进 Spring Boot jar 由其托管
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
})