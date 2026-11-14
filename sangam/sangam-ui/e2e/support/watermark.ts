// SPDX-License-Identifier: Apache-2.0
// M-048 / RM-47: the bpmn.io watermark (LIC-8) checked by rendered geometry, shared by the diagram and embed specs.
import { expect, type Locator } from '@playwright/test';

/** Asserts the watermark inside `scope` is visible, sized, inside its canvas and not covered by another element. */
export async function expectWatermarkVisible(scope: Locator): Promise<void> {
  const mark = scope.locator('.bjs-powered-by').first();
  await expect(mark).toBeVisible();
  const box = await mark.boundingBox();
  expect(box, 'watermark has a layout box').not.toBeNull();
  expect(box!.width).toBeGreaterThan(0);
  expect(box!.height).toBeGreaterThan(0);
  const canvas = await scope.locator('.bjs-container').first().boundingBox();
  expect(canvas).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(canvas!.x - 1);
  expect(box!.y).toBeGreaterThanOrEqual(canvas!.y - 1);
  expect(box!.x + box!.width).toBeLessThanOrEqual(canvas!.x + canvas!.width + 1);
  expect(box!.y + box!.height).toBeLessThanOrEqual(canvas!.y + canvas!.height + 1);
  // elementFromPoint, resolved through open shadow roots, must land on the watermark or inside it.
  const onTop = await mark.evaluate((el, p) => {
    let root: Document | ShadowRoot = el.ownerDocument;
    let hit = root.elementFromPoint(p.x, p.y);
    while (hit && hit.shadowRoot) {
      root = hit.shadowRoot;
      const inner = root.elementFromPoint(p.x, p.y);
      if (!inner || inner === hit) break;
      hit = inner;
    }
    return !!hit && (hit === el || el.contains(hit));
  }, { x: box!.x + box!.width / 2, y: box!.y + box!.height / 2 });
  expect(onTop, 'nothing covers the bpmn.io watermark').toBe(true);
}
