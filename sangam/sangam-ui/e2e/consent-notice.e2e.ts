// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: the consent notice and the public privacy policy (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix rows and the clauses they give evidence for:
 * - D-01 (partial: the published notice and the public policy; the portal form still shows a fixed string, see the
 *   matrix): notice with itemised personal data, the purposes, and the links to withdraw, exercise rights and complain to
 *   the Board (DPDP Act s.5(1); DPDP Rules r.3(a)-(c));
 * - D-21: the Data Protection Officer's business contact is published (s.8(9); r.9), here on the public policy page,
 *   signed out;
 * - D-28: a notice whose purpose text waives a right ("irrevocable", the India pack's noticeLint list) is refused and is
 *   never published (s.6(2)).
 * Server side: ConsentNoticeService.publish / privacyPolicy (links, contact block, lint, content hash = SHA-256 of the
 * text); UI: PublicPrivacy and NoticeView (Portal.tsx, compliance.tsx), rendered as text. The baseline (DPO contact,
 * the E2E_SERVICE purpose and its notice) is published by global-setup (support/compliance.ts).
 */
import { randomUUID } from 'node:crypto';
import { expect, test } from './support/fixtures';
import { API_BASE, t } from './support/env';
import { E2E_LINKS, definePurpose, indiaPack, sha256Hex, tenantOf, uniqueCode } from './support/compliance';

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
