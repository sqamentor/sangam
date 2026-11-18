// SPDX-License-Identifier: Apache-2.0
/**
 * M-048 with M-046: axe-core on the real, styled screens (WCAG 2.0/2.1 A and AA, the GIGW 3.0 / IS 17802 target). Unlike
 * the jsdom component check (src/test/axe.ts), colour contrast is included here because the browser computes layout and
 * colours. A screen fails on any "serious" or "critical" violation, the same threshold as the component tests.
 * axe-core is the dev dependency already in node_modules (4.14.0, MPL-2.0), injected from disk, never from a CDN; the
 * page's CSP (script-src 'self') is bypassed for this injection only. Written, not run (test phase).
 */
import { randomUUID } from 'node:crypto';
import { join } from 'node:path';
import type { Page } from '@playwright/test';
import { expect, signInAs, test } from './support/fixtures';
import { UI_ROOT, devToken, t } from './support/env';
import { createIdentity } from './support/identities';

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

// Each screen runs as a user its route guard admits (App.tsx), and the check fails on the "You do not have access to
// this" page (error.notAvailable): that page has no violations of its own, so before this the modeller entry passed
// without ever opening the modeller (the development token has no MODELLER role). The development token's groups give
// TASK_USER and OPERATOR; the modeller screens run as a model author (sangam-model-author -> MODELLER).
for (const [name, path, scopes] of [
  ['inbox', '/tasks', null],
  ['operations dashboard', '/ops', null],
  ['process definitions', '/ops/processes', null],
  ['modeller packages', '/modeller', ['sangam-model-author']],
  ['audit search', '/audit', null],
] as const) {
  test(`${name} (${path})`, async ({ page, api }) => {
    if (scopes) {
      const user = await createIdentity(api.ctx, `e2e-a11y-${randomUUID().slice(0, 8)}`, scopes);
      await signInAs(page, user.token);
    } else {
      await signInAs(page, devToken());
    }
    await page.goto(path);
    await page.waitForLoadState('networkidle');
    await expect(page.getByText(t('error.notAvailable'))).toHaveCount(0);
    expect(await blockingIssues(page)).toEqual([]);
  });
}

test('task detail with its form', async ({ signedIn: page, seed }) => {
  await page.goto(`/tasks/${seed.taskId}`);
  await page.waitForLoadState('networkidle');
  expect(await blockingIssues(page)).toEqual([]);
});
