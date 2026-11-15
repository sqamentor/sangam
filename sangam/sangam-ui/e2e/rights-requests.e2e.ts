// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: data-principal rights and grievances (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix rows and the clauses they give evidence for:
 * - D-23 (partial): the operator's rights-request form offers exactly the rights of the India pack, ACCESS (s.11),
 *   CORRECTION, COMPLETION, UPDATING, ERASURE (s.12), GRIEVANCE (s.13), NOMINATION (s.14) of the DPDP Act, and so
 *   UPDATING, not the UPDATE the UI once kept on its own (fixed in this lane: compliance.tsx packCodeField). Submitting a
 *   request by the signed-in data principal (owner decision 335) is shown in erasure-legal-hold.e2e.ts;
 * - C-03 (partial): the contact form offers the pack's contact roles, among them the CERT-In point of contact
 *   (CERT-In Directions 28-04-2022 dir.(iii));
 * - D-22: a data principal files a grievance or data-rights request in the portal, gets a reference, sees the published
 *   reply period, and the reply is due within the pack's maximum (s.8(10), s.13; DPDP Rules r.14(3): 90 days).
 * - D-23 (OTP, owner decision 360): an OTP request needs a verified compliance-owned one-time code; an unaccepted
 *   identifier type, an unknown challenge and an unverified one are refused by name; the code is never in a response.
 * The expected codes and days are read from the shipped India pack (support/compliance.ts indiaPack), never typed here.
 */
import { randomUUID } from 'node:crypto';
import { request, type Locator } from '@playwright/test';
import { apiAs, expect, signInAs, test } from './support/fixtures';
import { API_BASE, t } from './support/env';
import { indiaPack, tenantOf } from './support/compliance';

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

test('an OTP rights request needs a verified one-time code (D-23, owner decision 360)', async ({ seed }) => {
  const officer = await apiAs(seed.identities.complianceOfficer.token);
  const anonymous = await request.newContext({ baseURL: `${API_BASE.replace(/\/$/, '')}/`, extraHTTPHeaders: { Accept: 'application/json, application/problem+json' } });
  try {
    const tenant = await tenantOf(officer);
    const headers = () => ({ 'Idempotency-Key': randomUUID(), 'X-Sangam-Tenant': tenant });
    const email = `e2e-otp-${randomUUID()}@example.test`;

    // an identifier type the tenant does not accept is refused by name (IN pack: MOBILE, EMAIL)
    const type = await anonymous.post('compliance/rights-requests/otp-challenges', { headers: headers(), data: { identifierType: 'AADHAAR', identifierValue: '0000' } });
    expect(type.status(), await type.text()).toBe(400);

    // a code for an unknown challenge is refused without saying whether it exists
    const unknown = await anonymous.post(`compliance/rights-requests/otp-challenges/${randomUUID()}/verify`, { headers: headers(), data: { code: '000000' } });
    expect(unknown.status(), await unknown.text()).toBe(401);
    expect(((await unknown.json()) as { code?: string }).code).toBe('OTP_INVALID');

    // a submission naming a challenge that was never verified does not authenticate
    const submit = await anonymous.post('compliance/rights-requests', {
      headers: headers(),
      data: { right: 'ACCESS', identifierType: 'EMAIL', identifierValue: email, proof: { challengeId: randomUUID() } },
    });
    expect(submit.status(), await submit.text()).toBe(401);
    expect(((await submit.json()) as { code?: string }).code).toBe('PRINCIPAL_NOT_AUTHENTICATED');

    // a code is issued when the server has a notifier, else refused by name (the code itself is only sent to the identifier)
    const issued = await anonymous.post('compliance/rights-requests/otp-challenges', { headers: headers(), data: { identifierType: 'EMAIL', identifierValue: email } });
    expect([201, 409], await issued.text()).toContain(issued.status());
    if (issued.status() === 201) {
      const ch = (await issued.json()) as { challengeId: string; expiresAt: string; code?: string };
      expect(ch.code, 'the code is never in the response').toBeUndefined();
      expect(Date.parse(ch.expiresAt)).toBeGreaterThan(Date.now());
      const wrong = await anonymous.post(`compliance/rights-requests/otp-challenges/${ch.challengeId}/verify`, { headers: headers(), data: { code: 'not-a-code' } });
      expect(wrong.status(), await wrong.text()).toBe(401);
      expect(((await wrong.json()) as { code?: string }).code).toBe('OTP_INVALID');
    } else {
      expect(((await issued.json()) as { code?: string }).code).toBe('OTP_NOT_CONFIGURED');
    }
  } finally {
    await Promise.all([officer.dispose(), anonymous.dispose()]);
  }
});
