// SPDX-License-Identifier: Apache-2.0
/**
 * UP-L6b (diagram-js 15): the selection module no longer draws an outline (diagram-js 15.0.0 breaking change), so the
 * CMMN modeler adds the outline module. A selected plan item must stay visibly outlined in the real browser (jsdom has
 * no SVG geometry, so this cannot be a unit test). The package is a fresh draft holding the grievance pack's case file.
 */
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { expect, test } from './support/fixtures';

const CASE = readFileSync(
  fileURLToPath(new URL('../../sangam-packs/src/main/resources/packs/grievance/sangam-grievance-case.cmmn', import.meta.url)),
  'utf-8',
);

test('a selected CMMN plan item is outlined on the canvas', async ({ signedIn: page, api }) => {
  const created = await api.ctx.post('packages', { data: { key: `e2e-cmmn-${randomUUID().slice(0, 8)}`, name: 'E2E CMMN outline' } });
  expect(created.ok(), await created.text()).toBe(true);
  const id = ((await created.json()) as { id: string }).id;
  const current = await api.ctx.get(`packages/${encodeURIComponent(id)}`);
  const etag = current.headers()['etag'];
  expect(etag).toBeTruthy();
  const saved = await api.ctx.put(`packages/${encodeURIComponent(id)}`, {
    headers: { 'If-Match': etag },
    data: { name: 'E2E CMMN outline', resources: { 'grievance.cmmn': CASE } },
  });
  expect(saved.ok(), await saved.text()).toBe(true);

  await page.goto(`/modeller/${encodeURIComponent(id)}`);
  const pick = page.getByRole('button', { name: 'Examine grievance', exact: true });
  await pick.click();
  await expect(pick).toHaveAttribute('aria-pressed', 'true');
  const shape = page.locator('.cmmn-host [data-element-id="grvc_pi_examine"]');
  await expect(shape).toHaveClass(/selected/);
  await expect(shape.locator('.djs-outline')).toHaveCount(1);
});
