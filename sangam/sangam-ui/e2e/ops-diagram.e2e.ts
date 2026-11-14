// SPDX-License-Identifier: Apache-2.0
/**
 * M-048: the operations view of the seeded definition and the bpmn.io watermark (LIC-8). The watermark is checked on the
 * rendered page by geometry, as RM-47 asks: `.bjs-powered-by` is visible, has a non-zero box inside the canvas, and is
 * the top-most element at its own centre (nothing covers it). Written, not run (test phase).
 */
import { expect, test } from './support/fixtures';
import { expectWatermarkVisible } from './support/watermark';

test('the definition diagram renders with the bpmn.io watermark visible and uncovered (LIC-8, RM-47)', async ({ signedIn: page, seed }) => {
  await page.goto(`/ops/processes/${seed.definitionId}`);
  const figure = page.locator('figure.diagram').first();
  await expect(figure.locator('[data-element-id="Review"]')).toBeVisible();
  await expectWatermarkVisible(figure);
});

test('the instance diagram shows the token on the waiting user task', async ({ signedIn: page, seed }) => {
  await page.goto(`/ops/instances/${seed.processInstanceId}`);
  const figure = page.locator('figure.diagram').first();
  await expect(figure.locator('[data-element-id="Review"]')).toBeVisible();
  await expect(figure.locator('.djs-overlay .sg-badge').first()).toHaveText('1');
});
