// SPDX-License-Identifier: Apache-2.0
/**
 * M-043 end to end: the library build (dist-embed/sangam-elements.js, `npm run build:embed` first) inside a host page
 * that is not the Sangam UI. The host page and the module are served by Playwright routes, so no extra server is needed.
 * - same origin: the host page lives on the UI's origin and uses apiBase /api/v1;
 * - cross origin: the host page lives on SANGAM_E2E_CORS_ORIGIN, which the CI job lists in the server's
 *   sangam.security.cors.allowed-origins; that test is skipped, with the reason, when the variable is not set.
 * The task form completes a claimed task and emits sangam-task-completed; the diagram keeps the bpmn.io watermark
 * visible inside its shadow root (LIC-8). Written, not run (test phase).
 */
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Page } from '@playwright/test';
import { expect, test } from './support/fixtures';
import { API_BASE, BASE_URL, UI_ROOT, devToken, t } from './support/env';
import { expectWatermarkVisible } from './support/watermark';

const MODULE = join(UI_ROOT, 'dist-embed', 'sangam-elements.js');

function hostPage(apiBase: string, taskId: string, definitionId?: string): string {
  const cfg = JSON.stringify({ apiBase, token: devToken() });
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Host application</title></head>
<body>
<h1>Host application</h1>
<sangam-task-form task-id="${taskId}"></sangam-task-form>
${definitionId ? `<sangam-process-diagram process-definition-id="${definitionId}" label="Embedded diagram"></sangam-process-diagram>` : ''}
<script type="module">
  import { configure } from './sangam-elements.js';
  const cfg = ${cfg};
  window.__events = [];
  for (const n of ['sangam-task-completed', 'sangam-error', 'sangam-element-selected']) {
    document.addEventListener(n, (e) => window.__events.push({ type: n, detail: e.detail }));
  }
  configure({ apiBase: cfg.apiBase, getAccessToken: () => cfg.token });
</script>
</body></html>`;
}

async function serveHost(page: Page, origin: string, html: string): Promise<string> {
  await page.route(`${origin}/__e2e/embed-host.html`, (r) => r.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: html }));
  await page.route(`${origin}/__e2e/sangam-elements.js`, (r) =>
    r.fulfill({ status: 200, contentType: 'text/javascript; charset=utf-8', body: readFileSync(MODULE) }),
  );
  return `${origin}/__e2e/embed-host.html`;
}

async function completeThroughEmbed(page: Page, url: string, taskId: string): Promise<void> {
  await page.goto(url);
  const form = page.locator('sangam-task-form');
  await expect(form.getByRole('textbox').first()).toBeVisible();
  await form.getByRole('button', { name: t('common.submit') }).click();
  await expect(form.getByText(t('task.doneTitle'))).toBeVisible();
  const events = await page.evaluate(() => (window as unknown as { __events: { type: string; detail: { taskId?: string } }[] }).__events);
  expect(events.filter((e) => e.type === 'sangam-error')).toEqual([]);
  expect(events.find((e) => e.type === 'sangam-task-completed')?.detail.taskId).toBe(taskId);
  expect(await page.evaluate(() => document.documentElement.lang)).toBe('en');
}

test.beforeAll(() => {
  if (!existsSync(MODULE)) throw new Error(`${MODULE} is missing: run npm run build:embed before the e2e suite`);
});

test('same origin: the task form completes a claimed task and the diagram keeps the watermark', async ({ page, api, seed }) => {
  const { taskId } = await api.newTask();
  const claimed = await api.ctx.post(`tasks/${taskId}/claim`, { headers: { 'Idempotency-Key': randomUUID() }, data: {} });
  expect(claimed.ok(), await claimed.text()).toBe(true);

  const origin = new URL(BASE_URL).origin;
  const url = await serveHost(page, origin, hostPage('/api/v1', taskId, seed.definitionId));
  await completeThroughEmbed(page, url, taskId);

  const diagram = page.locator('sangam-process-diagram');
  await expect(diagram.locator('[data-element-id="Review"]')).toBeVisible();
  await expectWatermarkVisible(diagram);
  await diagram.locator('[data-element-id="Review"]').click();
  await expect
    .poll(() => page.evaluate(() => (window as unknown as { __events: { type: string; detail: { activityId?: string } }[] }).__events.find((e) => e.type === 'sangam-element-selected')?.detail.activityId))
    .toBe('Review');
});

test('cross origin: the same page on another origin reaches the API through CORS', async ({ page, api }) => {
  const hostOrigin = process.env.SANGAM_E2E_CORS_ORIGIN;
  test.skip(!hostOrigin, 'SANGAM_E2E_CORS_ORIGIN is not set: the server has no CORS origin for an embedding host in this run');
  const { taskId } = await api.newTask();
  const claimed = await api.ctx.post(`tasks/${taskId}/claim`, { headers: { 'Idempotency-Key': randomUUID() }, data: {} });
  expect(claimed.ok(), await claimed.text()).toBe(true);

  const absoluteApi = new URL(API_BASE).href.replace(/\/$/, '');
  const url = await serveHost(page, new URL(hostOrigin!).origin, hostPage(absoluteApi, taskId));
  await completeThroughEmbed(page, url, taskId);
});
