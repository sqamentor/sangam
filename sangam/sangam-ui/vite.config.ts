// SPDX-License-Identifier: Apache-2.0
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
// Owner decisions 120/121: the approved deploy/fonts Noto TTFs, hash-checked, become dist/fonts/noto/ (FONTS.md).
import deployFonts from './scripts/deploy-fonts.mjs';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));

// Everything is bundled locally (fonts, diagram assets, locales): the built app makes no CDN call (air-gapped, R56).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backend = env.SANGAM_BACKEND ?? 'http://localhost:8080';
  return {
    plugins: [react(), deployFonts()],
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
      // M-117 (vite 8): rolldown replaces rollup and esbuild; build.rollupOptions became build.rolldownOptions.
      rolldownOptions: {
        // Decision 162: teams.html is the optional Microsoft Teams app; built only when the page is present, and the
        // console (index.html) never imports from src/teams, so the SDK stays out of the console bundle.
        input: {
          main: resolve(root, 'index.html'),
          ...(existsSync(resolve(root, 'teams.html')) ? { teams: resolve(root, 'teams.html') } : {}),
        },
        output: {
          // Rolldown has no object form of manualChunks. The same two vendor chunks as before, now as code-splitting
          // groups: each lists the packages and their runtime-only dependencies that the old object form pulled in.
          codeSplitting: {
            groups: [
              {
                name: 'react',
                test: /[\\/]node_modules[\\/](react|react-dom|scheduler|react-router|react-router-dom|cookie|set-cookie-parser)[\\/]/,
                priority: 20,
              },
              {
                name: 'i18n',
                test: /[\\/]node_modules[\\/](i18next|react-i18next|html-parse-stringify|void-elements)[\\/]/,
                priority: 10,
              },
            ],
          },
        },
      },
    },
  };
});
