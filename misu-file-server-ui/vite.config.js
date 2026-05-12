import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  base: "/",
  plugins: [
    vue(),
    vueDevTools(),
  ],
  server: {
    port: 5173,
    host:'0.0.0.0',// 开放本机地址访问
  },
  build: {
    // foliate-js (vendored under src/lib/) uses top-level await; needs ES2022+.
    target: 'esnext',
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  }
})
