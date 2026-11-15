// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: data-principal rights and grievances (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix rows and the clauses they give evidence for:
 * - D-23 (partial): the operator's rights-request form offers exactly the rights of the India pack, ACCESS (s.11),
 *   CORRECTION, COMPLETION, UPDATING, ERASURE (s.12), GRIEVANCE (s.13), NOMINATION (s.14) of the DPDP Act, and so
 *   UPDATING, not the UPDATE the UI once kept on its own (fixed in this lane: compliance.tsx packCodeField). Submitting a
 *   request is not shown here: the standalone server wires no principal authenticator, so every submission is refused
 *   (ComplianceWiring, PRINCIPAL_NOT_AUTHENTICATED); that is an owner decision in the matrix;
 * - C-03 (partial): the contact form offers the pack's contact roles, among them the CERT-In point of contact
 *   (CERT-In Directions 28-04-2022 dir.(iii));
 * - D-22: a data principal files a grievance or data-rights request in the portal, gets a reference, sees the published
 *   reply period, and the reply is due within the pack's maximum (s.8(10), s.13; DPDP Rules r.14(3): 90 days).
 * The expected codes and days are read from the shipped India pack (support/compliance.ts indiaPack), never typed here.
 */
import { randomUUID } from 'node:crypto';
import type { Locator } from '@playwright/test';
import { apiAs, expect, signInAs, test } from './support/fixtures';
import { t } from './support/env';
import { indiaPack } from './support/compliance';

const DAY = 24 * 3600 * 1000;

async function optionValues(select: Locator): Promise<string[]> {
  const values = await select.locator('option').evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value));
  return values.filter((v) => v !== ''); // the "Choose…" placeholder
}

test('the rights-request and contact forms offer exactly the India pack codes (D-23, C-03)', async ({ page, seed }) => {
  const pack = indiaPack();
  await signInAs(page, seed.identities.complianceOfficer.token);
  await page.goto('/m/compliance');

  await page.getByRole('tab', { name: t('mod.compliance.rights') }).click();
  await page.getByRole('tabpanel').getByRole('button', { name: t('act.submitRightsRequest') }).click();
  const rights = page.getByRole('dialog');
  const right = rights.getByLabel(t('fld.right'));
  expect(await optionValues(right)).toEqual(pack.rights.map((r) => r.code));
  expect(await optionValues(right)).toContain('UPDATING');
  expect(await optionValues(right)).not.toContain('UPDATE');
  await expect(right.locator('option[value="UPDATING"]')).toHaveText(t('opt.UPDATING'));
  await rights.getByRole('button', { name: t('common.cancel') }).click();
  await expect(rights).toHaveCount(0);

  await page.getByRole('tab', { name: t('mod.compliance.contacts') }).click();
  await page.getByRole('tabpanel').getByRole('button', { name: t('act.setContact') }).click();
  const contact = page.getByRole('dialog');
  const role = contact.getByLabel(t('fld.role'));
  expect(await optionValues(role)).toEqual(pack.contactRoles.map((r) => r.code));
  await expect(role.locator('option[value="CERT_IN_POC"]')).toHaveText(t('opt.CERT_IN_POC'));
});

test('a data principal files a data-rights request in the portal and gets a reference and a due date within the pack maximum (D-22)', async ({ page, seed }) => {
  const grievance = indiaPack().rights.find((r) => r.code === 'GRIEVANCE');
  expect(grievance?.maxDays, 'the India pack GRIEVANCE right has a maximum').toBeGreaterThan(0);
  const subject = `E2E data request ${randomUUID().slice(0, 8)}`;
  await signInAs(page, seed.identities.citizen.token);
  await page.goto('/portal/grievances');
  await expect(page.getByRole('heading', { name: t('portal.grievancesTitle') })).toBeVisible();

  await page.getByLabel(t('portal.grievanceKind')).selectOption('DATA_RIGHTS');
  // the published reply period, shown before the request is sent
  await expect(page.getByText(t('portal.dataRightsHint'))).toBeVisible();
  await page.getByLabel(t('task.subject')).fill(subject);
  await page.getByLabel(t('portal.details')).fill('Please give me a summary of the personal data you hold about me.');
  await page.getByRole('button', { name: t('common.submit') }).click();

  // "Filed. Your reference is {{ref}}." with the reference captured (the en.json string, never retyped)
  const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const filed = new RegExp(t('portal.grievanceFiled', { ref: '\u0000' }).split('\u0000').map(escape).join('(\\S+)'));
  const notice = page.getByText(filed);
  await expect(notice).toBeVisible();
  const reference = filed.exec(await notice.innerText())?.[1] ?? '';
  expect(reference.length).toBeGreaterThan(0);
  await expect(page.getByRole('row').filter({ hasText: subject })).toContainText(reference);

  const citizen = await apiAs(seed.identities.citizen.token);
  try {
    const res = await citizen.get('portal/grievances');
    expect(res.status(), await res.text()).toBe(200);
    const rows = (await res.json()) as { reference: string; subject: string; filedAt: string; dueAt: string }[];
    const mine = rows.find((r) => r.reference === reference);
    expect(mine?.subject).toBe(subject);
    const days = (Date.parse(mine!.dueAt) - Date.parse(mine!.filedAt)) / DAY;
    expect(days).toBeGreaterThan(0);
    expect(days).toBeLessThanOrEqual(grievance!.maxDays!);
  } finally {
    await citizen.dispose();
  }
});
