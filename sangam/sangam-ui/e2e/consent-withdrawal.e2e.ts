// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: recording and withdrawing a consent in the consent register, operator path
 * (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix rows and the clauses they give evidence for:
 * - D-04 (operator / assisted path here; the citizen path is in consent-notice.e2e.ts): the fiduciary can prove the
 *   consent, as a register row with the notice key and version, the purposes and the time it was given (DPDP Act
 *   s.6(10));
 * - D-05: a consent can be withdrawn, and the register then shows WITHDRAWN and the time (s.6(4); DPDP Rules
 *   r.3(c)(i)). Operator path, and the citizen's own "My consents" page (owner decision 340): one click per purpose or
 *   for the whole consent, no reason asked, through the owner-withdraw endpoint POST /compliance/consents/{id}/withdraw,
 *   as easy as giving it on the portal form.
 * Done in the UI by the compliance officer (support/identities.ts: sangam-dpo, compliance.read/modify/admin), on the
 * notice global-setup published. Server: ConsentService.give / withdraw (the consent row and its purposes);
 * ComplianceController (recording or withdrawing for someone else needs compliance.modify). Citizen path:
 * PortalPrivacy.tsx PortalConsents, on a consent given through the portal service of support/portal.ts.
 */
import { randomUUID } from 'node:crypto';
import { apiAs, expect, signInAs, test } from './support/fixtures';
import { t } from './support/env';
import { IDENTITY_SCOPES, createIdentity } from './support/identities';
import { applyAsCitizen, registerPortalService } from './support/portal';

interface Consent {
  consentId: string;
  principalRef: string;
  noticeKey: string;
  noticeVersion: number;
  status: string;
  givenAt?: string;
  withdrawnAt?: string | null;
  activePurposes?: string[];
}

test('the compliance officer records a consent on the published notice and withdraws it (D-04, D-05)', async ({ page, seed }) => {
  const officer = seed.identities.complianceOfficer;
  // an opaque reference, never a name, e-mail or phone number (W-08: the register filter is a query parameter today)
  const principal = `e2e-principal-${randomUUID()}`;
  await signInAs(page, officer.token);
  await page.goto('/m/compliance');
  await page.getByRole('tab', { name: t('mod.compliance.consents') }).click();
  const panel = page.getByRole('tabpanel');

  // give: the "Record consent" dialog (compliance.tsx consents.create)
  await panel.getByRole('button', { name: t('act.recordConsent') }).click();
  const give = page.getByRole('dialog');
  await give.getByLabel(t('fld.principalRef')).fill(principal);
  await give.getByLabel(t('fld.noticeKey')).fill(seed.compliance.noticeKey);
  await give.getByLabel(t('fld.noticeVersion')).fill(String(seed.compliance.noticeVersion));
  await give.getByLabel(t('fld.purposeCodes')).fill(JSON.stringify([seed.compliance.purposeCode]));
  await give.getByLabel(t('fld.affirmativeAction')).check();
  await give.getByLabel(t('fld.channel')).fill('e2e-assisted');
  const before = Date.now();
  await give.getByRole('button', { name: t('common.confirm') }).click();
  await expect(give).toHaveCount(0);

  // the register, filtered by the data principal
  await panel.getByLabel(t('fld.principalRef')).fill(principal);
  await panel.getByRole('button', { name: t('common.search') }).click();
  const row = panel.getByRole('row').filter({ hasText: seed.compliance.noticeKey });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText(seed.compliance.purposeCode);
  await expect(row).toContainText(t('enum.ACTIVE'));

  const officerApi = await apiAs(officer.token);
  try {
    const list = async () => {
      const res = await officerApi.get('compliance/consents', { params: { subject: principal } });
      expect(res.status(), await res.text()).toBe(200);
      const body = (await res.json()) as Consent[] | { items?: Consent[] };
      return Array.isArray(body) ? body : (body.items ?? []);
    };
    const [given] = await list();
    expect(given.principalRef).toBe(principal);
    expect(given.noticeKey).toBe(seed.compliance.noticeKey);
    expect(given.noticeVersion).toBe(seed.compliance.noticeVersion);
    expect(given.activePurposes).toEqual([seed.compliance.purposeCode]);
    expect(Date.parse(given.givenAt ?? '')).toBeGreaterThan(before - 5 * 60_000);
    expect(given.withdrawnAt ?? null).toBeNull();

    // withdraw: select the row, "Withdraw consent", a reason (compliance.tsx consents.actions)
    await row.getByRole('button').first().click();
    await panel.getByRole('button', { name: t('act.withdrawConsent') }).click();
    const withdraw = page.getByRole('dialog');
    await withdraw.getByLabel(t('common.reason')).fill('Data principal asked at the counter (e2e)');
    await withdraw.getByRole('button', { name: t('common.confirm') }).click();
    await expect(withdraw).toHaveCount(0);
    await expect(panel.getByRole('row').filter({ hasText: seed.compliance.noticeKey })).toContainText(t('enum.WITHDRAWN'));

    const [after] = await list();
    expect(after.consentId).toBe(given.consentId);
    expect(after.status).toBe('WITHDRAWN');
    expect(Date.parse(after.withdrawnAt ?? '')).toBeGreaterThanOrEqual(Date.parse(given.givenAt ?? ''));
    expect(after.activePurposes ?? []).toEqual([]);
  } finally {
    await officerApi.dispose();
  }
});

test('citizen: a consent given on the portal is withdrawn from "My consents" with one click, no reason asked (D-05)', async ({ page, api: admin, seed }) => {
  // a citizen of its own, so the page lists only this consent whatever other specs gave
  const citizen = await createIdentity(admin.ctx, `e2e-citizen-withdraw-${randomUUID().slice(0, 8)}`, IDENTITY_SCOPES.citizen);
  const serviceKey = await registerPortalService(admin.ctx, 'E2E withdrawal service');
  const citizenApi = await apiAs(citizen.token);
  try {
    const applied = await applyAsCitizen(citizenApi, serviceKey, {
      noticeKey: seed.compliance.noticeKey,
      noticeVersion: seed.compliance.noticeVersion,
      language: 'en',
      purposeCodes: [seed.compliance.purposeCode],
      ageDeclaration: 'ADULT',
    });
    expect(applied.status(), await applied.text()).toBe(201);
    const list = async () => {
      const res = await citizenApi.get('compliance/consents');
      expect(res.status(), await res.text()).toBe(200);
      const body = (await res.json()) as Consent[] | { items?: Consent[] };
      return Array.isArray(body) ? body : (body.items ?? []);
    };
    const [given] = await list();
    expect(given.status).toBe('ACTIVE');
    expect(given.activePurposes).toEqual([seed.compliance.purposeCode]);

    await signInAs(page, citizen.token);
    await page.goto('/portal');
    await page.getByRole('link', { name: t('nav.myConsents') }).click();
    await expect(page.getByRole('heading', { name: t('portal.myConsents') })).toBeVisible();
    // one click: the purpose's own button, no dialog and no reason (as easy as the one box that gave it)
    await page.getByRole('button', { name: t('portal.withdrawPurpose', { purpose: 'E2E service delivery' }) }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page.getByText(t('portal.consentNothingActive'))).toBeVisible();

    const [after] = await list();
    expect(after.consentId).toBe(given.consentId);
    expect(after.status).toBe('WITHDRAWN');
    expect(after.activePurposes ?? []).toEqual([]);
    expect(Date.parse(after.withdrawnAt ?? '')).toBeGreaterThanOrEqual(Date.parse(given.givenAt ?? ''));
  } finally {
    await citizenApi.dispose();
  }
});
