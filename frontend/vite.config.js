import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Dev server on 5174 (5173 belongs to another local project).
// The /api proxy forwards to api-service, so the browser sees ONE origin —
// no CORS config needed anywhere. SSE streams through the proxy too.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5174,
    strictPort: true, // never silently hop ports — fail loudly if 5174 is taken
    proxy: {
      '/api': { target: 'http://localhost:8082', changeOrigin: true },
    },
  },
})
