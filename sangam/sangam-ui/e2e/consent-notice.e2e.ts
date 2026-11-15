// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: the consent notice and the public privacy policy (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix rows and the clauses they give evidence for:
 * - D-01: notice with itemised personal data, the purposes, and the links to withdraw, exercise rights and complain to
 *   the Board (DPDP Act s.5(1); DPDP Rules r.3(a)-(c)); on the citizen path the portal form shows the service's
 *   published notice and the submission names its key and version (owner decision 339);
 * - D-03 (citizen path): one checkbox per purpose, nothing pre-ticked, consent only for the ticked purposes
 *   (s.6(1), decision 341); whether "Save draft" may send personal data before consent stays open for counsel;
 * - D-04 (citizen path): the consent is recorded in the register with the notice key and version and the portal
 *   channel, in the same transaction as the application; a notice that is not the current published one is refused
 *   by name (409 NOTICE_NOT_CURRENT);
 * - D-21: the Data Protection Officer's business contact is published (s.8(9); r.9), here on the public policy page,
 *   signed out;
 * - D-28: a notice whose purpose text waives a right ("irrevocable", the India pack's noticeLint list) is refused and is
 *   never published (s.6(2)).
 * Server side: ConsentNoticeService.publish / privacyPolicy (links, contact block, lint, content hash = SHA-256 of the
 * text); UI: PublicPrivacy and NoticeView (Portal.tsx, compliance.tsx), rendered as text. The baseline (DPO contact,
 * the E2E_SERVICE purpose and its notice) is published by global-setup (support/compliance.ts). Citizen path: the
 * portal service of support/portal.ts; server PortalController.apply + PortalComplianceAdapter.recordConsent.
 */
import { randomUUID } from 'node:crypto';
import { apiAs, expect, signInAs, test } from './support/fixtures';
import { API_BASE, t } from './support/env';
import { E2E_LINKS, definePurpose, indiaPack, sha256Hex, tenantOf, uniqueCode } from './support/compliance';
import { applyAsCitizen, portalNotice, registerPortalService } from './support/portal';

interface Rendered {
  noticeKey: string;
  version: number;
  purposeCodes?: string[];
  text?: string;
  contentHash?: string;
}

const apiUrl = (path: string) => `${API_BASE.replace(/\/$/, '')}/${path}`;

test('signed out, the public privacy policy shows the rights links, the DPO contact, its version and hash (D-01, D-21)', async ({ page, api: admin, seed, request }) => {
  const tenant = await tenantOf(admin.ctx);

  // the policy is public: no Authorization header at all (security: [] in sangam-api.yaml)
  const res = await request.get(apiUrl('compliance/privacy-policy'), { params: { tenant, lang: 'en' } });
  expect(res.status(), await res.text()).toBe(200);
  const policy = (await res.json()) as Rendered;
  const text = policy.text ?? '';
  for (const link of Object.values(E2E_LINKS)) expect(text).toContain(link);
  expect(text).toContain(seed.compliance.dpo.name);
  expect(text).toContain(seed.compliance.dpo.email);
  expect(policy.purposeCodes).toContain(seed.compliance.purposeCode);
  // the content hash is the SHA-256 of exactly the text that is served (evidence the page shows what was hashed)
  expect(policy.contentHash?.toLowerCase()).toBe(sha256Hex(text));

  await page.goto(`/privacy/${encodeURIComponent(tenant)}`);
  await expect(page.getByRole('heading', { name: t('privacy.publicTitle') })).toBeVisible();
  // signed out: the public page, not the sign-in screen
  await expect(page.getByRole('heading', { name: t('auth.title') })).toHaveCount(0);
  const body = page.locator('.notice-text');
  for (const link of Object.values(E2E_LINKS)) await expect(body).toContainText(link);
  await expect(body).toContainText(seed.compliance.dpo.name);
  await expect(body).toContainText(seed.compliance.dpo.email);
  const value = (label: string) => page.locator('dl.kv > div').filter({ has: page.locator('dt', { hasText: label }) }).locator('dd');
  await expect(value(t('privacy.version'))).toHaveText(/^\d+$/);
  await expect(value(t('privacy.contentHash'))).toHaveText(/^[0-9a-fA-F]{64}$/);
});

test('the published consent notice itemises the data and the purpose and carries the three links and the DPO (D-01, D-21)', async ({ api: admin, seed }) => {
  const res = await admin.ctx.get(`compliance/notices/${encodeURIComponent(seed.compliance.noticeKey)}`, {
    params: { version: seed.compliance.noticeVersion, lang: 'en' },
  });
  expect(res.status(), await res.text()).toBe(200);
  const notice = (await res.json()) as Rendered;
  expect(notice.version).toBe(seed.compliance.noticeVersion);
  expect(notice.purposeCodes).toEqual([seed.compliance.purposeCode]);
  const text = notice.text ?? '';
  // itemised personal data and the purpose (the seeded E2E_SERVICE definition, support/compliance.ts)
  for (const item of ['Full name', 'Mobile number', 'E2E service delivery']) expect(text).toContain(item);
  for (const link of Object.values(E2E_LINKS)) expect(text).toContain(link);
  expect(text).toContain(seed.compliance.dpo.email);
  expect(notice.contentHash?.toLowerCase()).toBe(sha256Hex(text));
});

test('a notice whose purpose text waives a right is refused and not published (D-28)', async ({ api: admin }) => {
  const phrase = indiaPack().noticeLint.find((p) => p === 'irrevocable');
  expect(phrase, 'the India pack lint list names "irrevocable"').toBeDefined();
  const code = uniqueCode('E2E_LINT');
  const noticeKey = `e2e-lint-${code.toLowerCase()}`;
  // active, so that the refusal can only come from the lint and not from "purpose is inactive"
  await definePurpose(admin.ctx, code, { name: 'E2E lint check', description: `By applying you agree that this consent is ${phrase}.` }, true);
  try {
    const res = await admin.ctx.post(`compliance/notices/${noticeKey}/publish`, { headers: { 'Idempotency-Key': randomUUID() }, data: { purposes: [code] } });
    const problem = await res.text();
    expect(res.status(), problem).toBe(422);
    expect(problem).toContain('NOTICE_INVALID');
    expect(problem).toContain(phrase!);
    const after = await admin.ctx.get(`compliance/notices/${noticeKey}`);
    expect(after.status(), await after.text()).toBe(404);
  } finally {
    // the purpose would otherwise stay on the tenant's public privacy policy
    await definePurpose(admin.ctx, code, { name: 'E2E lint check', description: 'Retired by the e2e suite.' }, false);
  }
});

interface RegisterRow {
  consentId: string;
  principalRef: string;
  noticeKey: string;
  noticeVersion: number;
  status: string;
  activePurposes?: string[];
}

const rows = (body: unknown): RegisterRow[] => (Array.isArray(body) ? (body as RegisterRow[]) : ((body as { items?: RegisterRow[] }).items ?? []));

test('citizen: the portal form shows the published notice, one box per purpose, and the consent is recorded on its version (D-01, D-03, D-04)', async ({ page, api: admin, seed }) => {
  const citizen = seed.identities.citizen;
  const serviceKey = await registerPortalService(admin.ctx, 'E2E consent notice service');
  const citizenApi = await apiAs(citizen.token);
  try {
    const before = rows(await (await citizenApi.get('compliance/consents')).json()).map((c) => c.consentId);
    // the notice the portal serves is the published one (decision 339), with the purpose named
    const notice = await portalNotice(citizenApi, serviceKey);
    expect(notice.noticeKey).toBe(seed.compliance.noticeKey);
    expect(notice.version).toBe(seed.compliance.noticeVersion);
    expect(notice.contentHash.toLowerCase()).toBe(sha256Hex(notice.text));
    const purpose = notice.purposes.find((p) => p.code === seed.compliance.purposeCode);
    expect(purpose, JSON.stringify(notice.purposes)).toBeDefined();

    await signInAs(page, citizen.token);
    await page.goto(`/portal/apply/${encodeURIComponent(serviceKey)}`);
    await expect(page.locator('.notice-text')).toContainText(E2E_LINKS.withdrawConsent);
    // decision 341: one box per purpose, nothing pre-ticked, no bundled "I agree" box
    const box = page.getByLabel(t('portal.consentPurpose', { purpose: purpose!.name }));
    await expect(box).not.toBeChecked();
    await expect(page.getByLabel(t('portal.consent'))).toHaveCount(0);
    const submit = page.getByRole('button', { name: t('portal.submit') });
    await expect(submit).toBeDisabled();
    await box.check();
    if (notice.childAgeYears) await page.getByLabel(t('portal.ageDeclaration')).selectOption('ADULT');
    await expect(submit).toBeEnabled();
    await submit.click();
    await expect(page).toHaveURL(/\/portal\/applications\/[^/]+$/);

    const after = rows(await (await citizenApi.get('compliance/consents')).json());
    const given = after.filter((c) => !before.includes(c.consentId));
    expect(given, JSON.stringify(after)).toHaveLength(1);
    expect(given[0].principalRef).toBe(citizen.userId);
    expect(given[0].noticeKey).toBe(seed.compliance.noticeKey);
    expect(given[0].noticeVersion).toBe(seed.compliance.noticeVersion);
    expect(given[0].activePurposes).toEqual([seed.compliance.purposeCode]);
  } finally {
    await citizenApi.dispose();
  }
});

test('citizen: a notice version that is not the current published one is refused by name and starts nothing (D-04)', async ({ api: admin, seed }) => {
  const serviceKey = await registerPortalService(admin.ctx, 'E2E stale notice service');
  const citizenApi = await apiAs(seed.identities.citizen.token);
  try {
    const consent = { noticeKey: seed.compliance.noticeKey, language: 'en', purposeCodes: [seed.compliance.purposeCode], ageDeclaration: 'ADULT' as const };
    const stale = await applyAsCitizen(citizenApi, serviceKey, { ...consent, noticeVersion: seed.compliance.noticeVersion + 1000 });
    const body = await stale.text();
    expect(stale.status(), body).toBe(409);
    expect(body).toContain('NOTICE_NOT_CURRENT');
    const other = await applyAsCitizen(citizenApi, serviceKey, { ...consent, noticeKey: 'e2e-not-this-notice', noticeVersion: seed.compliance.noticeVersion });
    expect(other.status(), await other.text()).toBe(409);
    // no consent at all on a noticed service: refused, never an application without consent
    const none = await applyAsCitizen(citizenApi, serviceKey);
    const noneBody = await none.text();
    expect(none.status(), noneBody).toBe(403);
    expect(noneBody).toContain('CONSENT_REQUIRED');
    const mine = await citizenApi.get('portal/applications');
    const apps = (await mine.json()) as { items?: { serviceKey?: string }[] } | { serviceKey?: string }[];
    const list = Array.isArray(apps) ? apps : (apps.items ?? []);
    expect(list.filter((a) => a.serviceKey === serviceKey)).toHaveLength(0);
  } finally {
    await citizenApi.dispose();
  }
});
