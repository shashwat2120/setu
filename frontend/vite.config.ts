import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Backend module path prefixes, proxied to the real Spring Boot backend during dev so the browser never
// needs CORS and every request looks same-origin. See docs/frontend-dev-bridge.md for why this points at
// LiveDevBridge (port 8089) rather than a normally-run backend on this machine.
const BACKEND_PREFIXES = [
  '/auth', '/identity', '/institution', '/catalog', '/enrollment', '/directory', '/guardian-access',
  '/attendance', '/staff-attendance', '/learning', '/assessment', '/risk', '/finance', '/career', '/ai', '/governance', '/record',
  '/actuator', '/v3/api-docs', '/swagger-ui', '/admin',
]

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: process.env.PORT ? Number(process.env.PORT) : 5180,
    strictPort: false,
    proxy: {
      ...Object.fromEntries(
        BACKEND_PREFIXES.map((prefix) => [
          prefix,
          { target: process.env.BACKEND_URL ?? 'http://127.0.0.1:8089', changeOrigin: true },
        ]),
      ),
      // STOMP-over-WebSocket (notification module) — needs ws:true so Vite upgrades the connection instead of
      // proxying it as plain HTTP. Note: LiveDevBridge (the Windows-Selector-bug workaround this dev setup
      // otherwise runs against) is a plain HTTP/1.1 request/response bridge with NO WebSocket upgrade handling
      // — see LiveDevBridge.java's own javadoc. This entry is correct for a normally-run backend or the real
      // deployed app; it cannot be exercised live against LiveDevBridge in this environment.
      '/ws': { target: process.env.BACKEND_URL ?? 'http://127.0.0.1:8089', changeOrigin: true, ws: true },
    },
  },
})
