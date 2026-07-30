import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// Vite 配置
// 开发时前端 5173,后端 8080,通过 proxy 转发避免跨域
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  // 构建产物输出到后端 static 目录,可由 Spring Boot 直接托管
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  }
})
