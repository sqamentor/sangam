// SPDX-License-Identifier: Apache-2.0
// M-043: library build of the embeddable web components <sangam-task-form> and <sangam-process-diagram>.
// `npm run build:embed` writes dist-embed/sangam-elements.js, one self-contained ES module (React, i18n, bpmn-js and the
// Sangam styles included), a separate artefact from the UI image. bpmn-js is bundled unmodified and its watermark stays
// visible (LIC-8). No remote origin is referenced (air-gapped, R56). See README.md "Embeddable web components".
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  // The host page decides where the file is served from; asset URLs are resolved relative to the module.
  base: './',
  publicDir: false,
  define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  build: {
    outDir: 'dist-embed',
    emptyOutDir: true,
    sourcemap: true,
    // Everything inline: the module must work from any path on the host without extra files.
    assetsInlineLimit: Number.MAX_SAFE_INTEGER,
    cssCodeSplit: false,
    lib: {
      entry: 'src/embed/index.ts',
      formats: ['es'],
      fileName: () => 'sangam-elements.js',
    },
    // M-117 (vite 8): build.rollupOptions became build.rolldownOptions.
    rolldownOptions: {
      // Bundled, not external: the host application need not provide React or bpmn-js.
      external: [],
    },
  },
});
