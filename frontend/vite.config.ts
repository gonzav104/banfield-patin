import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  // changeOrigin stays false on purpose: the backend then sees the browser's own Host, so POSTs are same-origin
  // (no CORS involved), exactly like behind a production reverse proxy. Cookies and CSRF work unchanged.
  server: { port: 5173, strictPort: true, proxy: { '/api': { target: process.env.BACKEND_URL ?? 'http://127.0.0.1:8080', changeOrigin: false } } },
  test: { environment: 'jsdom', globals: true, setupFiles: ['./src/test/setup.ts'], include: ['src/**/*.test.{ts,tsx}'], restoreMocks: true },
});
