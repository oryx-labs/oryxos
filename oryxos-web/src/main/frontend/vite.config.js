import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 管理台由 Spring 托在 /admin 子路径；产物直接落进 oryxos-web 的 static/admin，随 fat JAR 分发。
// dev 时把 /api 代理到本地 serve 的 8080，便于热更调试（发布形态不经代理）。
export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  build: {
    outDir: '../resources/static/admin',
    emptyOutDir: true,
  },
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    restoreMocks: true,
  },
})
