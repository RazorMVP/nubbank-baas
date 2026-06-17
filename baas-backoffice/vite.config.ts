/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'node:path';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  // Dev proxy: when VITE_API_BASE_URL is empty the app makes same-origin
  // (relative) requests; this forwards /baas/** to a local baas-engine so the
  // browser never hits cross-origin CORS (the engine has no CORS config in 1C).
  // Override the engine origin with VITE_ENGINE_ORIGIN (default :8080).
  // See docs/backoffice-local-dev.md.
  const engineOrigin = env.VITE_ENGINE_ORIGIN || 'http://localhost:8080';

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: { '@': path.resolve(__dirname, './src') },
    },
    server: {
      port: 3001,
      proxy: {
        '/baas': { target: engineOrigin, changeOrigin: true },
      },
    },
    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: ['./src/test-setup.ts'],
      css: true,
      exclude: ['**/node_modules/**', '**/e2e/**'],
    },
  };
});
