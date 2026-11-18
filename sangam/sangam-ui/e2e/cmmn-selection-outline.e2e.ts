// SPDX-License-Identifier: Apache-2.0
/**
 * UP-L6b (diagram-js 15): the selection module no longer draws an outline (diagram-js 15.0.0 breaking change), so the
 * CMMN modeler adds the outline module. A selected plan item must stay visibly outlined in the real browser (jsdom has
 * no SVG geometry, so this cannot be a unit test). The package is a fresh draft holding the grievance pack's case file.
 */
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { expect, signInAs, test } from './support/fixtures';
import { apiAs, createIdentity, meOf } from './support/identities';

const CASE = readFileSync(
  fileURLToPath(new URL('../../sangam-packs/src/main/resources/packs/grievance/sangam-grievance-case.cmmn', import.meta.url)),
  'utf-8',
);

test('a selected CMMN plan item is outlined on the canvas', async ({ page, api }) => {
  // The modeller screens need the MODELLER role (App.tsx MODEL), which the development token does not carry: the spec
  // signs in as its own model author (group sangam-model-author -> MODELLER, OperatonWorkspaceAdapter.roles).
  const author = await createIdentity(api.ctx, `e2e-cmmn-author-${randomUUID().slice(0, 8)}`, ['sangam-model-author']);
  expect((await meOf(author.token)).roles).toContain('MODELLER');
  const as = await apiAs(author.token);
  let id = '';
  try {
    const created = await as.post('packages', { data: { key: `e2e-cmmn-${randomUUID().slice(0, 8)}`, name: 'E2E CMMN outline' } });
    expect(created.ok(), await created.text()).toBe(true);
    id = ((await created.json()) as { id: string }).id;
    const current = await as.get(`packages/${encodeURIComponent(id)}`);
    const etag = current.headers()['etag'];
    expect(etag).toBeTruthy();
    const saved = await as.put(`packages/${encodeURIComponent(id)}`, {
      headers: { 'If-Match': etag },
      data: { name: 'E2E CMMN outline', resources: { 'grievance.cmmn': CASE } },
    });
    expect(saved.ok(), await saved.text()).toBe(true);
  } finally {
    await as.dispose();
  }

  await signInAs(page, author.token);
  await page.goto(`/modeller/${encodeURIComponent(id)}`);
  // The plan outline button is named by its kind and its name ("Human task Examine grievance", CmmnDiagramEditor.tsx).
  const pick = page.getByRole('button', { name: /^Human task Examine grievance$/ });
  await pick.click();
  await expect(pick).toHaveAttribute('aria-pressed', 'true');
  const shape = page.locator('.cmmn-host [data-element-id="grvc_pi_examine"]');
  await expect(shape).toHaveClass(/selected/);
  await expect(shape.locator('.djs-outline')).toHaveCount(1);
});
