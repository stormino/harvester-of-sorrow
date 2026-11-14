import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Dev server proxies the API to a locally running backend (override with HOS_BACKEND).
// SSE (/api/v1/events) is streamed through unbuffered; the backend sends no compression for it.
const backend = process.env.HOS_BACKEND ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: true, timeout: 0, proxyTimeout: 0 },
      '/v3/api-docs': { target: backend, changeOrigin: true },
      '/swagger-ui': { target: backend, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
