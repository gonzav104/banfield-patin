import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  // changeOrigin stays false on purpose: the backend then sees the browser's own Host, so POSTs are same-origin
  // (no CORS involved), exactly like behind a production reverse proxy. Cookies and CSRF work unchanged.
  // Referrer-Policy as an HTTP header, not only the <meta>: the dev server injects its scripts BEFORE the meta tag, so
  // those first requests would otherwise carry the full page URL (a legacy invitation link includes the token) as Referer.
  // Production hosting must send the same header (docs/SEGURIDAD.md, section 7).
  preview: { headers: { 'Referrer-Policy': 'no-referrer' } },
  server: { headers: { 'Referrer-Policy': 'no-referrer' }, port: 5173, strictPort: true, proxy: { '/api': { target: process.env.BACKEND_URL ?? 'http://127.0.0.1:8080', changeOrigin: false } } },
  test: { environment: 'jsdom', globals: true, setupFiles: ['./src/test/setup.ts'], include: ['src/**/*.test.{ts,tsx}'], restoreMocks: true },
});
