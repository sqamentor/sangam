// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: a child's application on the citizen portal (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix rows and the clauses they give evidence for:
 * - D-24: before processing a child's personal data the fiduciary obtains the verifiable consent of a parent (DPDP Act
 *   s.9(1); DPDP Rules r.10). The portal form asks for the applicant's age when the jurisdiction pack has a child-age
 *   limit (IN: 18, limits.childAgeYears); a declared child applies only with a parent verification that the existing
 *   guardian verifier accepts (GuardianConsentVerifier.requireValid, owner decision 343). Fail closed: a declared child
 *   without a valid verification gets 403 GUARDIAN_CONSENT_REQUIRED and no application, a missing declaration 422
 *   AGE_DECLARATION_REQUIRED.
 * The happy child path needs a parent verification, which only an integrator-bound AdultIdentityVerifier creates
 * (decision 68; none in the test-mode server), so this spec proves the refusals; the accepted child path is proven by
 * PortalComplianceAdapterH2Test.
 * Server: PortalController.apply, PortalComplianceAdapter.recordConsent / childConsent; UI: Portal.tsx PortalApply.
 */
import { randomUUID } from 'node:crypto';
import { apiAs, expect, signInAs, test } from './support/fixtures';
import { t } from './support/env';
import { indiaPack } from './support/compliance';
import { applyAsCitizen, portalNotice, registerPortalService } from './support/portal';

interface AppList {
  items?: { serviceKey?: string }[];
}

test('the portal asks the age, and a declared child cannot submit without a parent verification (D-24)', async ({ page, api: admin, seed }) => {
  expect(indiaPack().limits.childAgeYears?.value, 'the India pack has a child-age limit').toBe(18);
  const serviceKey = await registerPortalService(admin.ctx, 'E2E child service');
  const citizenApi = await apiAs(seed.identities.citizen.token);
  try {
    const notice = await portalNotice(citizenApi, serviceKey);
    expect(notice.childAgeYears).toBe(18);
    const purpose = notice.purposes.find((p) => p.code === seed.compliance.purposeCode)!;

    await signInAs(page, seed.identities.citizen.token);
    await page.goto(`/portal/apply/${encodeURIComponent(serviceKey)}`);
    await page.getByLabel(t('portal.consentPurpose', { purpose: purpose.name })).check();
    const submit = page.getByRole('button', { name: t('portal.submit') });
    // the age is a required answer: without it the form does not submit
    await expect(submit).toBeDisabled();
    await page.getByLabel(t('portal.ageDeclaration')).selectOption('CHILD');
    await expect(page.getByText(t('portal.guardianNotice'))).toBeVisible();
    await expect(submit).toBeDisabled();
    await page.getByLabel(t('portal.guardianVerification')).fill(`e2e-no-such-verification-${randomUUID()}`);
    await expect(submit).toBeEnabled();
    await submit.click();
    // refused by the server: the page stays on the form and shows the problem
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`/portal/apply/${serviceKey}$`));
  } finally {
    await citizenApi.dispose();
  }
});

test('fail closed: a child without a valid verification and a missing age are refused by name, and nothing starts (D-24)', async ({ api: admin, seed }) => {
  const serviceKey = await registerPortalService(admin.ctx, 'E2E child refusal service');
  const citizenApi = await apiAs(seed.identities.citizen.token);
  try {
    const base = { noticeKey: seed.compliance.noticeKey, noticeVersion: seed.compliance.noticeVersion, language: 'en', purposeCodes: [seed.compliance.purposeCode] };

    const noVerification = await applyAsCitizen(citizenApi, serviceKey, { ...base, ageDeclaration: 'CHILD' });
    const a = await noVerification.text();
    expect(noVerification.status(), a).toBe(403);
    expect(a).toContain('GUARDIAN_CONSENT_REQUIRED');

    const unknown = await applyAsCitizen(citizenApi, serviceKey, { ...base, ageDeclaration: 'CHILD', guardianVerificationId: `e2e-${randomUUID()}` });
    const b = await unknown.text();
    expect(unknown.status(), b).toBe(403);
    expect(b).toContain('GUARDIAN_CONSENT_REQUIRED');

    const noAge = await applyAsCitizen(citizenApi, serviceKey, base);
    const c = await noAge.text();
    expect(noAge.status(), c).toBe(422);
    expect(c).toContain('AGE_DECLARATION_REQUIRED');

    const mine = (await (await citizenApi.get('portal/applications')).json()) as AppList | { serviceKey?: string }[];
    const apps = Array.isArray(mine) ? mine : (mine.items ?? []);
    expect(apps.filter((x) => x.serviceKey === serviceKey)).toHaveLength(0);
  } finally {
    await citizenApi.dispose();
  }
});
