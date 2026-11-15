// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e compliance baseline (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md, prerequisites), published by
 * global-setup through the product API as the development administrator, on the India jurisdiction pack:
 * - the tenant's jurisdiction must be IN. The CI job sets it on the test-mode server (ui.yml "Start the stack",
 *   SPRING_APPLICATION_JSON sangam.compliance.*; ComplianceConfigProvider). Anything else stops the run here: every
 *   CERT-In / DPDP spec is about the India pack, and a run on another pack would prove nothing;
 * - the Data Protection Officer contact (POST /compliance/contacts/DPO; ContactDirectory: IN requires residency), which
 *   the privacy policy publishes (DPDP s.8(9), r.9) and notice publishing requires;
 * - one active consent purpose with itemised personal data (PUT /compliance/purposes/{code}; LegitimateUseRegistry);
 * - a published consent notice for that purpose (POST /compliance/notices/{key}/publish; ConsentNoticeService.publish,
 *   which also needs the withdraw, rights and complaint links of the server configuration).
 */
import { randomUUID } from 'node:crypto';
import type { APIRequestContext, APIResponse } from '@playwright/test';
import type { ComplianceSeed } from './env';

export const E2E_PURPOSE = 'E2E_SERVICE';
export const E2E_NOTICE = 'e2e-service-notice';
export const E2E_DPO = { name: 'E2E Data Protection Officer', email: 'dpo@e2e.test' };

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
    'POST /compliance/contacts/DPO',
    await admin.post('compliance/contacts/DPO', {
      headers: key(),
      data: { name: E2E_DPO.name, designation: 'Data Protection Officer', organisation: 'Sangam e2e', email: E2E_DPO.email, phone: '+91 11 0000 0000', address: 'New Delhi, India', resident: true },
    }),
    [200, 204],
  );
  await must(
    `PUT /compliance/purposes/${E2E_PURPOSE}`,
    await admin.put(`compliance/purposes/${E2E_PURPOSE}`, {
      headers: key(),
      data: {
        names: { en: 'E2E service delivery' },
        descriptions: { en: 'Processing an application for the e2e test service and telling you its outcome.' },
        dataItems: [
          { code: 'name', names: { en: 'Full name' }, sensitive: false },
          { code: 'mobile', names: { en: 'Mobile number' }, sensitive: false },
        ],
        basis: 'CONSENT',
        retention: 'P400D',
        active: true,
      },
    }),
    [200],
  );
  const published = (await must(
    `POST /compliance/notices/${E2E_NOTICE}/publish`,
    await admin.post(`compliance/notices/${E2E_NOTICE}/publish`, { headers: key(), data: { purposes: [E2E_PURPOSE] } }),
    [200, 201],
  )) as { version?: number };
  if (typeof published.version !== 'number') throw new Error('seed: the notice publication returned no version');
  return { jurisdiction: j.code, purposeCode: E2E_PURPOSE, noticeKey: E2E_NOTICE, noticeVersion: published.version, dpo: E2E_DPO };
}
