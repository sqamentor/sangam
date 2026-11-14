// SPDX-License-Identifier: Apache-2.0
/**
 * M-048 with M-046: axe-core on the real, styled screens (WCAG 2.0/2.1 A and AA, the GIGW 3.0 / IS 17802 target). Unlike
 * the jsdom component check (src/test/axe.ts), colour contrast is included here because the browser computes layout and
 * colours. A screen fails on any "serious" or "critical" violation, the same threshold as the component tests.
 * axe-core is the dev dependency already in node_modules (4.14.0, MPL-2.0), injected from disk, never from a CDN; the
 * page's CSP (script-src 'self') is bypassed for this injection only. Written, not run (test phase).
 */
import { join } from 'node:path';
import type { Page } from '@playwright/test';
import { expect, test } from './support/fixtures';
import { UI_ROOT } from './support/env';

test.use({ bypassCSP: true });

const AXE = join(UI_ROOT, 'node_modules', 'axe-core', 'axe.min.js');
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

async function blockingIssues(page: Page): Promise<string[]> {
  await page.addScriptTag({ path: AXE });
  return page.evaluate(async (tags) => {
    const w = window as unknown as {
      axe: { run: (ctx: Document, o: unknown) => Promise<{ violations: { id: string; impact?: string; help: string; nodes: { target: string[] }[] }[] }> };
    };
    const r = await w.axe.run(document, { runOnly: { type: 'tag', values: tags }, resultTypes: ['violations'] });
    return r.violations
      .filter((v) => v.impact === 'serious' || v.impact === 'critical')
      .map((v) => `${v.id} (${v.impact}): ${v.help} at ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
  }, TAGS);
}

test('sign-in screen', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  expect(await blockingIssues(page)).toEqual([]);
});

for (const [name, path] of [
  ['inbox', '/tasks'],
  ['operations dashboard', '/ops'],
  ['process definitions', '/ops/processes'],
  ['modeller packages', '/modeller'],
  ['audit search', '/audit'],
] as const) {
  test(`${name} (${path})`, async ({ signedIn: page }) => {
    await page.goto(path);
    await page.waitForLoadState('networkidle');
    expect(await blockingIssues(page)).toEqual([]);
  });
}

test('task detail with its form', async ({ signedIn: page, seed }) => {
  await page.goto(`/tasks/${seed.taskId}`);
  await page.waitForLoadState('networkidle');
  expect(await blockingIssues(page)).toEqual([]);
});
