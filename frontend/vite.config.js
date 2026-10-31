import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { '/api': { target: process.env.JAVA_API_PROXY_TARGET || 'http://127.0.0.1:8080', changeOrigin: true } },
  },
  build: {
    rollupOptions: { output: { manualChunks: { vue: ['vue'] } } },
  },
})
