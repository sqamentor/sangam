// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: retention floors and the compliance evidence report (docs/compliance/
 * CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix rows and the clauses they give evidence for:
 * - C-04: logs kept within Indian jurisdiction and provided on order (CERT-In Directions 28-04-2022 dir.(iv)): the
 *   tenant runs on the India pack (home country IN) with its log-retention floors, and the evidence report an officer
 *   hands over carries a hash that is the SHA-256 of exactly the report served (ComplianceEvidenceReportGenerator:
 *   hash = SHA-256 of `json`, also in the X-Sangam-Report-Hash header, stored in CMP_REPORT and audited);
 * - D-12: logs and personal data are kept for at least one year (DPDP Rules r.6(1)(e), r.8(3)): the effective floors
 *   are those of the pack, 365 days.
 * The evidence tab of /m/compliance shows the same jurisdiction answer to the compliance officer. The report is fetched
 * through the API with an explicit period: the UI's "Generate report" sends the period as optional dates while the
 * server requires both instants (ComplianceController.report), which is listed for the owner, not covered here.
 */
import { apiAs, expect, signInAs, test } from './support/fixtures';
import { t } from './support/env';
import { indiaPack, sha256Hex } from './support/compliance';

interface Jurisdiction {
  code: string;
  homeCountry: string;
  /** GET /compliance/jurisdiction sends each limit as its number (ComplianceApiImpl.jurisdiction: JurisdictionPack.limits()
   *  is Map<String, Integer>); the pack file keeps {value, citation}, the citation stays server side. */
  limits: Record<string, number>;
}

test('the tenant runs on the India pack with its one-year retention floors (C-04, D-12)', async ({ page, seed }) => {
  const pack = indiaPack();
  const officer = await apiAs(seed.identities.complianceOfficer.token);
  try {
    const res = await officer.get('compliance/jurisdiction');
    expect(res.status(), await res.text()).toBe(200);
    const j = (await res.json()) as Jurisdiction;
    expect(j.code).toBe('IN');
    expect(j.homeCountry).toBe(pack.homeCountry);
    expect(j.limits.logRetentionMinDays).toBe(pack.limits.logRetentionMinDays.value);
    expect(j.limits.logRetentionMinDays).toBeGreaterThanOrEqual(365);
    expect(j.limits.minimumRetentionDays).toBe(pack.limits.minimumRetentionDays.value);
    expect(j.limits.minimumRetentionDays).toBeGreaterThanOrEqual(365);
    expect(j.limits.incidentLogRetentionDays).toBe(pack.limits.incidentLogRetentionDays.value);
    expect(j.limits.incidentLogRetentionDays).toBeGreaterThanOrEqual(180);
  } finally {
    await officer.dispose();
  }

  await signInAs(page, seed.identities.complianceOfficer.token);
  await page.goto('/m/compliance');
  await page.getByRole('tab', { name: t('mod.compliance.evidence'), exact: true }).click();
  const panel = page.getByRole('tabpanel');
  await expect(panel.getByRole('row').filter({ hasText: 'homeCountry' })).toContainText(pack.homeCountry);
});

test('the evidence report carries the SHA-256 of exactly what is served (C-04)', async ({ seed }) => {
  const officer = await apiAs(seed.identities.complianceOfficer.token);
  try {
    const to = new Date();
    const from = new Date(to.getTime() - 24 * 3600 * 1000);
    const res = await officer.get('compliance/reports', { params: { from: from.toISOString(), to: to.toISOString(), format: 'json', verifyChains: true } });
    expect(res.status(), await res.text()).toBe(200);
    const report = (await res.json()) as { reportId?: string; hash?: string; json?: string; content?: Record<string, unknown> };
    expect(report.reportId).toBeTruthy();
    expect(report.hash).toMatch(/^[0-9a-fA-F]{64}$/);
    expect(res.headers()['x-sangam-report-hash']).toBe(report.hash);
    expect(sha256Hex(report.json ?? '').toLowerCase()).toBe(report.hash!.toLowerCase());
    // the report covers the consents of the period (the register global-setup and the consent specs wrote to)
    expect(report.content).toHaveProperty('consents');
  } finally {
    await officer.dispose();
  }
});
