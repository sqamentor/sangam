// Scratch only: the pre-M-117 vite.embed.config.ts, run with vite 6.4.5 to compare outputs.
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
const ui = 'D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui';
export default defineConfig({
  root: ui,
  plugins: [react()],
  base: './',
  publicDir: false,
  define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  build: {
    outDir: process.env.OUT,
    emptyOutDir: true,
    sourcemap: true,
    assetsInlineLimit: Number.MAX_SAFE_INTEGER,
    cssCodeSplit: false,
    lib: { entry: ui + '/src/embed/index.ts', formats: ['es'], fileName: () => 'sangam-elements.js' },
    rollupOptions: { external: [] },
  },
});
