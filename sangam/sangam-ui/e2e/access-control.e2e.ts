// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: access control and error disclosure (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix rows and the clauses they give evidence for:
 * - D-10: access control over computer resources (DPDP Rules r.6(1)(b)). A signed-in user with no role and a data
 *   principal (citizen) do not get the compliance, audit, retention, operations or administration screens, and the
 *   server, which is the boundary, refuses the same data with 403; the compliance officer does get the compliance
 *   screen through the compliance.read permission alone (access.ts ROUTE_ACCESS, Permissions);
 * - W-10: errors carry no stack trace or internals, on the compliance, portal and privacy paths with ids that do not
 *   exist (OWASP WSTG error handling, the web application testing the CERT-In audit guidelines name).
 * Identities: support/identities.ts (each one checked through GET /me by global-setup before any spec runs).
 */
import { randomUUID } from 'node:crypto';
import type { APIResponse, Page } from '@playwright/test';
import { apiAs, expect, signInAs, test } from './support/fixtures';
import { t } from './support/env';

const INTERNALS = /\bat (?:org|java|jdk|sun|com)\.[\w.$]+\(|Exception\b|\.java:\d+|SQLState|org\.sangam\.|org\.springframework/;

async function denied(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole('heading', { name: t('error.notAvailable') }), path).toBeVisible();
}

async function expectNoInternals(res: APIResponse, what: string): Promise<void> {
  const body = await res.text();
  expect(body, what).not.toMatch(INTERNALS);
  expect(res.headers()['content-type'] ?? '', what).toMatch(/json/);
}

test('a signed-in user with no role gets neither the compliance screens nor their data (D-10)', async ({ page, seed }) => {
  const roleless = seed.identities.roleless;
  await signInAs(page, roleless.token);
  for (const path of ['/m/compliance', '/admin/retention', '/audit', '/ops', '/admin/users']) await denied(page, path);

  const ctx = await apiAs(roleless.token);
  try {
    for (const path of ['compliance/contacts', 'compliance/purposes', `compliance/consents?subject=${encodeURIComponent(seed.identities.citizen.userId)}`]) {
      const res = await ctx.get(path);
      expect(res.status(), path).toBe(403);
      await expectNoInternals(res, path);
    }
    const write = await ctx.post('compliance/contacts/DPO', { headers: { 'Idempotency-Key': randomUUID() }, data: { name: 'Not allowed', email: 'no@e2e.test' } });
    expect(write.status()).toBe(403);
  } finally {
    await ctx.dispose();
  }
});

test('a data principal gets the portal but not the operator or administration screens (D-10)', async ({ page, seed }) => {
  const citizen = seed.identities.citizen;
  await signInAs(page, citizen.token);
  await page.goto('/portal/grievances');
  await expect(page.getByRole('heading', { name: t('portal.grievancesTitle') })).toBeVisible();
  for (const path of ['/m/compliance', '/ops', '/admin/users', '/audit', '/admin/retention']) await denied(page, path);

  const ctx = await apiAs(citizen.token);
  try {
    for (const path of ['compliance/contacts', 'compliance/purposes']) {
      const res = await ctx.get(path);
      expect(res.status(), path).toBe(403);
    }
    // another data principal's consents: compliance.read is needed for any subject other than oneself
    const other = await ctx.get('compliance/consents', { params: { subject: seed.identities.roleless.userId } });
    expect(other.status()).toBe(403);
  } finally {
    await ctx.dispose();
  }
});

test('the compliance officer opens the compliance screen through its permission (D-10)', async ({ page, seed }) => {
  await signInAs(page, seed.identities.complianceOfficer.token);
  await page.goto('/m/compliance');
  await expect(page.getByRole('heading', { name: t('mod.compliance.title'), exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: t('error.notAvailable') })).toHaveCount(0);
});

test('errors for ids that do not exist carry no stack trace or internals (W-10)', async ({ api, seed }) => {
  const officer = await apiAs(seed.identities.complianceOfficer.token);
  const citizen = await apiAs(seed.identities.citizen.token);
  try {
    const probes: [string, Promise<APIResponse>][] = [
      ['rights request', officer.get('compliance/rights-requests/e2e-no-such-request')],
      ['breach', officer.get('compliance/breaches/e2e-no-such-breach')],
      ['incident', officer.get('compliance/incidents/e2e-no-such-incident')],
      ['notice', api.ctx.get('compliance/notices/e2e-no-such-notice')],
      ['portal application', citizen.get('portal/applications/e2e-no-such-application')],
    ];
    for (const [what, call] of probes) {
      const res = await call;
      expect(res.status(), what).toBeGreaterThanOrEqual(400);
      expect(res.status(), `${what}: a refusal, not a server error`).toBeLessThan(500);
      await expectNoInternals(res, what);
    }
  } finally {
    await officer.dispose();
    await citizen.dispose();
  }
});
