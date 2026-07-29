import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // target: 'http://localhost:8080',
        target: 'http://8.219.219.205:8080',
        changeOrigin: true
      }
    }
  }
})
