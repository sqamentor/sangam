// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: registering a personal-data breach and a CERT-In incident (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix rows and the clauses they give evidence for:
 * - D-14: a breach is registered with its detailed-report deadline, the India pack's 72 hours from awareness (DPDP Act
 *   s.8(6); DPDP Rules r.7(2)), and opens the linked CERT-In incident; the processes come from the installed compliance
 *   pack (owner decision 336);
 * - C-02: a CERT-In incident is registered with a type from the Annexure I catalogue of the pack and is due within the
 *   pack's 6 hours (CERT-In Directions 28-04-2022 dir.(ii)); a free-text type is refused by name,
 *   INCIDENT_TYPE_NOT_IN_CATALOGUE, for the incident and for a breach alike (owner decision 344);
 * - C-03 (form): the officer's incident form offers exactly the catalogue codes of the chosen authority.
 * The codes and hours are read from the shipped India pack (support/compliance.ts indiaPack), never typed here.
 * Server: ComplianceController.registerBreach / registerIncident, BreachIncidentService.register,
 * IncidentReportingService.requireCatalogued.
 */
import { randomUUID } from 'node:crypto';
import type { Locator } from '@playwright/test';
import { apiAs, expect, signInAs, test } from './support/fixtures';
import { t } from './support/env';
import { indiaPack } from './support/compliance';

const HOUR = 3600 * 1000;
const key = () => ({ 'Idempotency-Key': randomUUID() });

interface CertIn {
  code: string;
  role: string;
  deadlineHours?: number;
  catalogue?: { code: string; label: string }[];
}

function certIn(): CertIn {
  const a = (indiaPack().authorities as CertIn[]).find((x) => x.role === 'INCIDENT');
  if (!a?.catalogue?.length || !a.deadlineHours) throw new Error('the India pack names no INCIDENT authority with a catalogue and a deadline');
  return a;
}

async function optionValues(select: Locator): Promise<string[]> {
  const values = await select.locator('option').evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value));
  return values.filter((v) => v !== '');
}

test('a breach is registered with its 72-hour deadline and a catalogued incident type; free text is refused (D-14, C-02)', async ({ seed }) => {
  const pack = indiaPack();
  const authority = certIn();
  const type = authority.catalogue!.find((c) => c.code === 'iii')?.code ?? authority.catalogue![0].code;
  const officer = await apiAs(seed.identities.complianceOfficer.token);
  try {
    const awareAt = new Date(Date.now() - 60_000).toISOString();
    const facts = { description: `E2E breach ${randomUUID().slice(0, 8)}`, nature: 'unauthorised access', extent: 'one record', timing: awareAt, location: 'e2e', likelyImpact: 'low' };

    // a free-text type: refused before anything is written
    const free = await officer.post('compliance/breaches', { headers: key(), data: { facts, awareAt, incidentType: 'someone hacked us' } });
    expect(free.status(), await free.text()).toBe(422);
    const refusal = await free.text();
    expect((JSON.parse(refusal) as { code?: string }).code).toBe('INCIDENT_TYPE_NOT_IN_CATALOGUE');
    expect(refusal, 'the refused value is not echoed').not.toContain('someone hacked us');

    const res = await officer.post('compliance/breaches', { headers: key(), data: { facts, awareAt, incidentType: type } });
    expect(res.status(), await res.text()).toBe(201);
    const breach = (await res.json()) as { breachId: string; status: string; awareAt: string; reportDueAt: string; incidentIds: string[] };
    expect(breach.status).toBe('REGISTERED');
    expect(Date.parse(breach.reportDueAt) - Date.parse(breach.awareAt)).toBe(pack.breach.detailedReportHours * HOUR);
    expect(breach.incidentIds.length, 'the linked CERT-In incident').toBe(1);

    const incident = await officer.get(`compliance/incidents/${breach.incidentIds[0]}`);
    expect(incident.status(), await incident.text()).toBe(200);
    const linked = (await incident.json()) as { authority: string; type: string; breachId: string };
    expect(linked).toMatchObject({ authority: authority.code, type, breachId: breach.breachId });
    const again = await officer.get(`compliance/breaches/${breach.breachId}`);
    expect(again.status(), await again.text()).toBe(200);
  } finally {
    await officer.dispose();
  }
});

test('a CERT-In incident takes only an Annexure I code and is due within the pack deadline (C-02)', async ({ seed }) => {
  const authority = certIn();
  const officer = await apiAs(seed.identities.complianceOfficer.token);
  try {
    const occurredAt = new Date(Date.now() - 5 * 60_000).toISOString();
    const free = await officer.post('compliance/incidents', { headers: key(), data: { authority: authority.code, type: 'ransomware, probably', occurredAt, description: 'e2e' } });
    expect(free.status(), await free.text()).toBe(422);
    expect(((await free.json()) as { code?: string }).code).toBe('INCIDENT_TYPE_NOT_IN_CATALOGUE');

    const code = authority.catalogue!.find((c) => c.code === 'x')?.code ?? authority.catalogue![0].code;
    const res = await officer.post('compliance/incidents', { headers: key(), data: { authority: authority.code, type: code, occurredAt, noticedAt: occurredAt, description: 'e2e incident' } });
    expect(res.status(), await res.text()).toBe(201);
    const incident = (await res.json()) as { incidentId: string; type: string; noticedAt: string; dueAt: string; status: string };
    expect(incident.type).toBe(code);
    expect(Date.parse(incident.dueAt) - Date.parse(incident.noticedAt)).toBeLessThanOrEqual(authority.deadlineHours! * HOUR);
  } finally {
    await officer.dispose();
  }
});

test('the incident form offers exactly the catalogue of the chosen authority (C-02)', async ({ page, seed }) => {
  const authority = certIn();
  await signInAs(page, seed.identities.complianceOfficer.token);
  await page.goto('/m/compliance');
  await page.getByRole('tab', { name: t('mod.compliance.breaches') }).click();
  await page.getByRole('tabpanel').getByRole('button', { name: t('act.registerIncident') }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel(t('fld.authority')).selectOption(authority.code);
  expect(await optionValues(dialog.getByLabel(t('fld.incidentType')))).toEqual(authority.catalogue!.map((c) => c.code));
});
