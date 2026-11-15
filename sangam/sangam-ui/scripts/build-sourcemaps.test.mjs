// SPDX-License-Identifier: Apache-2.0
// CERT-In / DPDP matrix W-11: production builds ship no source map. dist/ is copied as it is into the UI image's nginx
// root (deploy/docker/ui.Dockerfile) and dist-embed/sangam-elements.js is served by every host page, so a map in
// either is the original source for anyone. Both builds had `sourcemap: true`. The image itself is checked by
// e2e/security-headers.e2e.ts (no sourceMappingURL in /assets/*.js, no .map served).
import { describe, expect, it } from 'vitest';
import uiConfig from '../vite.config.ts';
import embedConfig from '../vite.embed.config.ts';

describe('production builds ship no source maps (W-11)', () => {
  it('the UI build (vite.config.ts) for production', async () => {
    const resolved = typeof uiConfig === 'function' ? await uiConfig({ mode: 'production', command: 'build' }) : uiConfig;
    expect(resolved.build?.outDir).toBe('dist');
    expect(resolved.build?.sourcemap).toBe(false);
  });

  it('the embeddable web components build (vite.embed.config.ts)', async () => {
    const resolved = typeof embedConfig === 'function' ? await embedConfig({ mode: 'production', command: 'build' }) : embedConfig;
    expect(resolved.build?.outDir).toBe('dist-embed');
    expect(resolved.build?.sourcemap).toBe(false);
  });
});
