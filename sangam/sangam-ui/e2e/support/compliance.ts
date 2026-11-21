// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e compliance baseline (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md, prerequisites), published by
 * global-setup through the product API as the development administrator, on the India jurisdiction pack:
 * - the tenant's jurisdiction must be IN. The CI job sets it on the test-mode server (ui.yml "Start the stack",
 *   SPRING_APPLICATION_JSON sangam.compliance.*; ComplianceConfigProvider). Anything else stops the run here: every
 *   CERT-In / DPDP spec is about the India pack, and a run on another pack would prove nothing;
 * - the Data Protection Officer contact (POST /compliance/contacts/by-role?role=DPO; ContactDirectory: IN requires residency), which
 *   the privacy policy publishes (DPDP s.8(9), r.9) and notice publishing requires;
 * - one active consent purpose with itemised personal data (PUT /compliance/purposes/by-code?code=; LegitimateUseRegistry);
 * - a published consent notice for that purpose (POST /compliance/notices/by-key/publish?key=; ConsentNoticeService.publish,
 *   which also needs the withdraw, rights and complaint links of the server configuration).
 */
import { createHash, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { APIRequestContext, APIResponse } from '@playwright/test';
import { UI_ROOT, type ComplianceSeed } from './env';

export const E2E_PURPOSE = 'E2E_SERVICE';
export const E2E_NOTICE = 'e2e-service-notice';
export const E2E_DPO = { name: 'E2E Data Protection Officer', email: 'dpo@e2e.test' };

/**
 * The withdraw, rights and Board-complaint links the CI job gives the test-mode server (ui.yml "Start the stack",
 * SPRING_APPLICATION_JSON sangam.compliance.links). The server prints them into every notice and the privacy policy
 * (ConsentNoticeService linkLine); a change to one side without the other fails the notice specs, loudly.
 */
export const E2E_LINKS = {
  withdrawConsent: 'http://127.0.0.1:8080/portal',
  rightsPortal: 'http://127.0.0.1:8080/portal/grievances',
  authorityComplaint: 'https://www.dpb.gov.in/',
} as const;

/** The India jurisdiction pack the server ships (sangam-compliance resources), read by the specs as data. */
export interface IndiaPack {
  code: string;
  homeCountry: string;
  rights: { code: string; maxDays?: number; defaultDays?: number }[];
  contactRoles: { code: string }[];
  /** The pack's configuration defaults (string values), e.g. rights.otp.issue-max-per-identifier. */
  defaults?: Record<string, string>;
  authorities: { code: string; role: string; deadlineHours?: number; warnHours?: number }[];
  breach: { detailedReportHours: number };
  limits: Record<string, { value: number }>;
  noticeLint: string[];
}

export function indiaPack(): IndiaPack {
  const file = join(UI_ROOT, '..', 'sangam-compliance', 'src', 'main', 'resources', 'compliance', 'jurisdictions', 'IN', 'pack.json');
  return JSON.parse(readFileSync(file, 'utf8')) as IndiaPack;
}

/** SHA-256 hex of a UTF-8 text, as the server's content and report hashes (Sql.hashHex). */
export function sha256Hex(text: string): string {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

/** A purpose code no earlier run or other spec uses. */
export function uniqueCode(prefix: string): string {
  return `${prefix}_${randomUUID().replace(/-/g, '').slice(0, 10).toUpperCase()}`;
}

/** PUT /compliance/purposes/by-code?code=: a consent purpose shaped like the seeded one, with the given English text. */
export async function definePurpose(admin: APIRequestContext, code: string, text: { name: string; description: string }, active: boolean): Promise<void> {
  await must(
    `PUT /compliance/purposes/by-code?code=${code}`,
    await admin.put('compliance/purposes/by-code', {
      params: { code },
      headers: key(),
      data: {
        names: { en: text.name },
        descriptions: { en: text.description },
        dataItems: [{ code: 'name', names: { en: 'Full name' }, sensitive: false }],
        basis: 'CONSENT',
        // an explicit attestation, never defaulted (ComplianceDefinePurposeRequest): a consent basis needs no Second
        // Schedule standards, and the e2e purpose claims none
        standardsAttested: false,
        retention: 'P400D',
        active,
      },
    }),
    [200],
  );
}

/** The tenant of an API client's identity (GET /me tenantId). */
export async function tenantOf(ctx: APIRequestContext): Promise<string> {
  const me = (await must('GET /me', await ctx.get('me'), [200])) as { tenantId?: string };
  if (!me.tenantId) throw new Error('GET /me returned no tenantId');
  return me.tenantId;
}

async function must(what: string, res: APIResponse, status: number[]): Promise<unknown> {
  if (!status.includes(res.status())) throw new Error(`seed: ${what} answered ${res.status()} ${await res.text()}`);
  const text = await res.text();
  return text ? (JSON.parse(text) as unknown) : null;
}

const key = () => ({ 'Idempotency-Key': randomUUID() });

export async function seedCompliance(admin: APIRequestContext): Promise<ComplianceSeed> {
  const j = (await must('GET /compliance/jurisdiction', await admin.get('compliance/jurisdiction'), [200])) as { code?: string };
  if (j.code !== 'IN') {
    throw new Error(`seed: the tenant's jurisdiction is ${j.code ?? 'not set'}, the CERT-In / DPDP specs need IN (set sangam.compliance.jurisdictions on the test-mode server)`);
  }
  await must(
    'POST /compliance/contacts/by-role?role=DPO',
    await admin.post('compliance/contacts/by-role', {
      params: { role: 'DPO' },
      headers: key(),
      data: { name: E2E_DPO.name, designation: 'Data Protection Officer', organisation: 'Sangam e2e', email: E2E_DPO.email, phone: '+91 11 0000 0000', address: 'New Delhi, India', resident: true },
    }),
    [200, 204],
  );
  await must(
    `PUT /compliance/purposes/by-code?code=${E2E_PURPOSE}`,
    await admin.put('compliance/purposes/by-code', {
      params: { code: E2E_PURPOSE },
      headers: key(),
      data: {
        names: { en: 'E2E service delivery' },
        descriptions: { en: 'Processing an application for the e2e test service and telling you its outcome.' },
        dataItems: [
          { code: 'name', names: { en: 'Full name' }, sensitive: false },
          { code: 'mobile', names: { en: 'Mobile number' }, sensitive: false },
        ],
        basis: 'CONSENT',
        standardsAttested: false, // explicit, see definePurpose
        retention: 'P400D',
        active: true,
      },
    }),
    [200],
  );
  const published = (await must(
    `POST /compliance/notices/by-key/publish?key=${E2E_NOTICE}`,
    await admin.post('compliance/notices/by-key/publish', { params: { key: E2E_NOTICE }, headers: key(), data: { purposes: [E2E_PURPOSE] } }),
    [200, 201],
  )) as { version?: number };
  if (typeof published.version !== 'number') throw new Error('seed: the notice publication returned no version');
  return { jurisdiction: j.code, purposeCode: E2E_PURPOSE, noticeKey: E2E_NOTICE, noticeVersion: published.version, dpo: E2E_DPO };
}
