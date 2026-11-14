// SPDX-License-Identifier: Apache-2.0
/**
 * M-048: the application shell. Dev sign-in, navigation, the air-gapped rule (no request leaves the origin, R56) and
 * the fonts of owner decisions 120/121: the deploy/fonts Noto TTFs are served from /fonts/noto/ byte-identical to the
 * approved files, Devanagari text renders in Noto Sans Devanagari, and Sakal Bharati / @fontsource are never fetched.
 * Written, not run (test phase).
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from './support/fixtures';
import { UI_ROOT, devToken, t } from './support/env';

const FONTS_README = join(UI_ROOT, '..', 'deploy', 'fonts', 'README.md');

/** File → SHA-256 from deploy/fonts/README.md (the same table scripts/deploy-fonts.mjs checks at build time). */
function approvedHashes(): Map<string, string> {
  const out = new Map<string, string>();
  for (const line of readFileSync(FONTS_README, 'utf8').split(/\r?\n/)) {
    const m = /^\|\s*`([^`]+)`\s*\|.*\|\s*([0-9A-Fa-f]{64})\s*\|\s*$/.exec(line.trim());
    if (m) out.set(m[1], m[2].toUpperCase());
  }
  return out;
}

test('signing in with the dev token opens the work area', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: t('auth.title') })).toBeVisible();
  await page.getByLabel(t('auth.devToken')).fill(devToken());
  await page.getByRole('button', { name: t('auth.signIn') }).click();
  await expect(page.getByRole('link', { name: t('nav.inbox') })).toBeVisible();
});

test('no request leaves the origin (air-gapped, R56)', async ({ signedIn: page, offOrigin, seed }) => {
  for (const path of ['/', '/tasks', `/tasks/${seed.taskId}`, '/ops', `/ops/processes/${seed.definitionId}`]) {
    await page.goto(path);
    await page.waitForLoadState('networkidle');
  }
  expect(offOrigin).toEqual([]);
});

test.describe('fonts (owner decisions 120, 121)', () => {
  for (const name of ['NotoSans-Regular.ttf', 'NotoSansDevanagari-Regular.ttf', 'OFL-NotoSans.txt', 'OFL-NotoSansDevanagari.txt']) {
    test(`/fonts/noto/${name} is the approved file`, async ({ request }) => {
      const res = await request.get(`/fonts/noto/${name}`);
      expect(res.status()).toBe(200);
      if (name.endsWith('.ttf')) expect(res.headers()['content-type']).toMatch(/font\/ttf|application\/x-font-ttf|font\/sfnt/);
      const sha = createHash('sha256').update(await res.body()).digest('hex').toUpperCase();
      expect(sha).toBe(approvedHashes().get(name));
    });
  }

  test('Devanagari text renders in Noto Sans Devanagari; Sakal Bharati and @fontsource are never requested', async ({ signedIn: page }) => {
    const fontRequests: string[] = [];
    page.on('request', (r) => {
      if (r.resourceType() === 'font') fontRequests.push(new URL(r.url()).pathname);
    });
    await page.goto('/tasks');
    const result = await page.evaluate(async () => {
      const p = document.createElement('p');
      p.lang = 'hi';
      p.textContent = 'संगम कार्यप्रवाह';
      document.body.append(p);
      await document.fonts.load("16px 'Noto Sans Devanagari'", 'संगम');
      await document.fonts.ready;
      return { family: getComputedStyle(p).fontFamily, loaded: document.fonts.check("16px 'Noto Sans Devanagari'", 'संगम') };
    });
    expect(result.family.replace(/["']/g, '')).toMatch(/^Noto Sans Devanagari\b/);
    expect(result.loaded).toBe(true);
    expect(fontRequests).toContain('/fonts/noto/NotoSansDevanagari-Regular.ttf');
    expect(fontRequests.filter((p) => /sakal|fontsource|\.woff2$/i.test(p))).toEqual([]);
  });
});
