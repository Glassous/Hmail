import { defineConfig, type ProxyOptions } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
const proxy: Record<string, ProxyOptions> = {
  '/api': {
    target: 'http://127.0.0.1:8184',
    changeOrigin: false,
    timeout: 600000,
    proxyTimeout: 600000,
    configure(server) {
      server.on('proxyReq', (outgoing, incoming) => {
        outgoing.setHeader('X-Real-IP', incoming.socket.remoteAddress || '127.0.0.1')
      })
    },
  },
}
const headers = {
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'X-Frame-Options': 'SAMEORIGIN',
}
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: { proxy, headers },
  preview: { proxy, headers: { ...headers, 'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https: http:; frame-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'self'" } },
})
