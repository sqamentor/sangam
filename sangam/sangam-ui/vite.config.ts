// SPDX-License-Identifier: Apache-2.0
import { loadEnv } from 'vite';
// defineConfig from vitest/config: the same function, typed for the `test` block below.
import { defineConfig } from 'vitest/config';
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
    // One setup file for every test file: what jsdom lacks is filled once (src/test/setup.ts), not per test file.
    // Vitest replaces every CSS import with an empty string unless test.css includes it, and that covers `?raw` too.
    // A `?raw` import asks for the file's text, not for a stylesheet to apply, so those are let through: a test that
    // checks a rule of the app stylesheet (BpmnDiagram.watermark.test.tsx, M-049) reads the real file.
    test: { setupFiles: ['src/test/setup.ts'], css: { include: [/\.css\?(?:.*&)?raw(?:&|$)/] } },
    server: {
      port: 5173,
      // M-047: under Vitest only (mode 'test'), the jsdom screen tests may read the REST contract (sangam-rest/api), to check every
      // call they see against it (src/test/screens.tsx contractOperations). The dev server keeps Vite's default
      // (this package only); nothing else outside the package is opened. UI-DEMO sweep S7: the policy editor's action list is
      // checked against the server's Permission enum, so that enum's source folder is readable under Vitest too.
      ...(mode === 'test'
        ? { fs: { allow: [root, resolve(root, '../sangam-rest/api'), resolve(root, '../sangam-security/src/main/java/org/sangam/security/policy')] } }
        : {}),
      proxy: {
        '/api': { target: backend, changeOrigin: true },
        '/engine-rest': { target: backend, changeOrigin: true },
      },
    },
    build: {
      outDir: 'dist',
      // CERT-In / DPDP matrix W-11: no source maps in the production build. dist/ is what the UI image serves
      // (ui.Dockerfile copies it to the nginx root), so a map here is the whole original source, comments included,
      // for anyone (OWASP WSTG information gathering, the web testing scope of the CERT-In audit guidelines). The
      // dev server (npm run dev) keeps its own maps for debugging. Checked by scripts/build-sourcemaps.test.mjs and,
      // on the image, by e2e/security-headers.e2e.ts.
      sourcemap: false,
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
