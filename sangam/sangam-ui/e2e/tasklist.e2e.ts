// SPDX-License-Identifier: Apache-2.0
/**
 * M-048: the task user's core flow on a real server. The seeded task appears in the inbox, opens, is claimed and is
 * completed through the decision dialog; the server then has no open task for the instance. Each test starts its own
 * instance, so the order of specs never matters. Written, not run (test phase).
 */
import { expect, test } from './support/fixtures';
import { t } from './support/env';

test('a new task is listed in the inbox and opens its detail', async ({ signedIn: page, api }) => {
  const { taskId } = await api.newTask();
  await page.goto('/tasks');
  const link = page.locator(`a[href$="/tasks/${taskId}"]`).first();
  await expect(link).toBeVisible();
  await link.click();
  await expect(page).toHaveURL(new RegExp(`/tasks/${taskId}$`));
  await expect(page.getByText('Review the request').first()).toBeVisible();
});

test('claim, then complete through the decision dialog', async ({ signedIn: page, api }) => {
  const { taskId, processInstanceId } = await api.newTask();
  await page.goto(`/tasks/${taskId}`);
  await expect(page.getByRole('heading', { name: /Review the request/ })).toBeVisible();

  const claim = page.getByRole('button', { name: t('task.claim'), exact: true });
  if (await claim.isVisible()) {
    await claim.click();
    await expect(claim).toBeHidden();
  }

  await page.getByRole('group', { name: t('task.decisions') }).getByRole('button', { name: t('task.action.COMPLETE') }).click();
  const dialog = page.locator('dialog[open]');
  await dialog.getByRole('button', { name: t('common.confirm') }).click();
  await expect(page.getByText(t('task.doneTitle'))).toBeVisible();

  await expect
    .poll(async () => {
      const r = await api.ctx.get('tasks', { params: { processInstanceId } });
      const body = (await r.json()) as unknown[] | { items?: unknown[] };
      return (Array.isArray(body) ? body : (body.items ?? [])).length;
    })
    .toBe(0);
});

test('a task id that does not exist shows "not available", never a stack trace', async ({ signedIn: page }) => {
  await page.goto('/tasks/00000000-0000-0000-0000-000000000000');
  await expect(page.getByText(t('error.notAvailable'))).toBeVisible();
  await expect(page.getByText(/Exception|at org\.|stack/i)).toHaveCount(0);
});
