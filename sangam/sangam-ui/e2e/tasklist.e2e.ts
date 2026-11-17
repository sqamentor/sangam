// SPDX-License-Identifier: Apache-2.0
/**
 * M-048: the task user's core flow on a real server. The seeded task appears in the inbox, opens, is claimed and is
 * completed through the decision dialog; the server then has no open task for the instance. Each test starts its own
 * instance, so the order of specs never matters. Written, not run (test phase).
 */
import { expect, test } from './support/fixtures';
import { t } from './support/env';

test('a new task is listed in the inbox and opens its detail', async ({ signedIn: page, api }) => {
  const { taskId, businessKey } = await api.newTask();
  await page.goto('/tasks');
  // The e2e task has no assignee and no candidate group, so it is in the team list (POOL: unassigned tasks the caller
  // may see), not under "Mine" (assigned to the caller or the caller's seat), which the inbox opens on (TaskInbox.tsx,
  // endpoints.ts tasks.list: MY_SEAT is mine=true). The search narrows the list to this task's business key.
  await page.getByRole('tab', { name: t('task.section.POOL') }).click();
  await page.getByRole('searchbox', { name: t('task.search') }).fill(businessKey);
  await page.getByRole('searchbox', { name: t('task.search') }).press('Enter');
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

test('a task id that does not exist shows "could not find", never a stack trace', async ({ signedIn: page }) => {
  await page.goto('/tasks/00000000-0000-0000-0000-000000000000');
  // decision 397 (UX-SPEC 6.3): a 404 says "We could not find this" (error.notFound); "You do not have access to
  // this" (error.notAvailable) is the 403 (components/ui.tsx problemKind, problem.test.ts)
  await expect(page.getByText(t('error.notFound'))).toBeVisible();
  // the page content, not the menu (its "Duty conflict exceptions" link is a label, not a trace)
  await expect(page.getByRole('main').getByText(/Exception|at org\.|stack/i)).toHaveCount(0);
});
