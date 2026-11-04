// SPDX-License-Identifier: Apache-2.0
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Everything is bundled locally (fonts, diagram assets, locales): the built app makes no CDN call (air-gapped, R56).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backend = env.SANGAM_BACKEND ?? 'http://localhost:8080';
  return {
    plugins: [react()],
    base: env.SANGAM_UI_BASE ?? '/',
    server: {
      port: 5173,
      proxy: {
        '/api': { target: backend, changeOrigin: true },
        '/engine-rest': { target: backend, changeOrigin: true },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: true,
      chunkSizeWarningLimit: 2500,
      // Inline nothing from remote origins; keep assets as hashed files under /assets.
      assetsInlineLimit: 4096,
      rollupOptions: {
        output: {
          manualChunks: {
            react: ['react', 'react-dom', 'react-router-dom'],
            i18n: ['i18next', 'react-i18next'],
          },
        },
      },
    },
  };
});
