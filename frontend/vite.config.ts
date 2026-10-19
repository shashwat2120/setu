import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// Backend module path prefixes, proxied to the real Spring Boot backend during dev so the browser never
// needs CORS and every request looks same-origin. See docs/frontend-dev-bridge.md for why this points at
// LiveDevBridge (port 8089) rather than a normally-run backend on this machine.
const BACKEND_PREFIXES = [
  '/auth', '/identity', '/institution', '/catalog', '/enrollment', '/directory', '/guardian-access',
  '/attendance', '/staff-attendance', '/learning', '/assessment', '/risk', '/finance', '/career', '/ai', '/governance', '/record',
  '/notifications', '/actuator', '/v3/api-docs', '/swagger-ui', '/admin',
]

// Three ways to run it:
//   npm run dev         the single-process backend on the Windows development bridge (port 8089), the default.
//   npm run dev:stack   the containerised services (docker-compose.services.yml): every request, the live attendance board's WebSocket
//                       included, goes through the gateway on 8080 (it relays /ws to the Attendance service). Its address is in this file.
//   BACKEND_URL=... WS_URL=... PORT=... npm run dev     anything else; a real environment variable beats a file. WS_URL is only needed
//                       to send the WebSocket somewhere other than BACKEND_URL.
// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // the addresses of the containerised stack (not secrets: ports on this machine); a real environment variable beats them
  const stack = mode === 'stack' ? { BACKEND_URL: 'http://127.0.0.1:8080', PORT: '5180' } : {}
  const env = { ...stack, ...loadEnv(mode, process.cwd(), ''), ...process.env }
  const backend = env.BACKEND_URL ?? 'http://127.0.0.1:8089'
  return {
    plugins: [react()],
    server: {
      port: env.PORT ? Number(env.PORT) : 5180,
      strictPort: false,
      proxy: {
        ...Object.fromEntries(
          BACKEND_PREFIXES.map((prefix) => [prefix, { target: backend, changeOrigin: true }]),
        ),
        // STOMP-over-WebSocket (the live attendance board) — needs ws:true so Vite upgrades the connection instead of
        // proxying it as plain HTTP. Note: LiveDevBridge (the Windows-Selector-bug workaround this dev setup
        // otherwise runs against) is a plain HTTP/1.1 request/response bridge with NO WebSocket upgrade handling
        // — see LiveDevBridge.java's own javadoc. This entry is correct for a normally-run backend, for the containerised
        // stack (WS_URL), or the real deployed app; it cannot be exercised live against LiveDevBridge in this environment.
        '/ws': { target: env.WS_URL ?? backend, changeOrigin: true, ws: true },
      },
    },
  }
})
