// SPDX-License-Identifier: Apache-2.0
/**
 * Citizen-portal e2e helpers (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md, rows D-01, D-03, D-04, D-05, D-15, D-24):
 * - registerPortalService: a portal service in the services registry (POST /admin/services, then activate; the
 *   portal lists the entries with portal: true, OperatonPortalAdapter.serviceEntries) that starts the seeded
 *   sangamE2eSimple process and names the published e2e consent notice (noticeKey, owner decision 339). It has no
 *   formKey, so the apply form is the notice and the consent only. Each spec registers its own, so specs never share
 *   applications;
 * - applyAsCitizen: POST /portal/applications as an identity, with the consent the portal form sends.
 * All calls carry an Idempotency-Key (R41).
 */
import { randomUUID } from 'node:crypto';
import type { APIRequestContext, APIResponse } from '@playwright/test';
import { E2E_NOTICE } from './compliance';

export const E2E_PROCESS = 'sangamE2eSimple';

export interface PortalConsentBody {
  noticeKey: string;
  noticeVersion?: number;
  language?: string;
  purposeCodes: string[];
  ageDeclaration?: 'ADULT' | 'CHILD';
  guardianVerificationId?: string;
}

export interface PortalNoticeBody {
  noticeKey: string;
  version: number;
  language: string;
  text: string;
  contentHash: string;
  purposes: { code: string; name: string; description?: string }[];
  childAgeYears?: number;
}

const key = () => ({ 'Idempotency-Key': randomUUID() });

async function must(what: string, res: APIResponse, status: number[]): Promise<unknown> {
  const text = await res.text();
  if (!status.includes(res.status())) throw new Error(`${what} answered ${res.status()} ${text}`);
  return text ? (JSON.parse(text) as unknown) : null;
}

/** Registers and activates a portal service on the e2e notice; returns its key. */
export async function registerPortalService(admin: APIRequestContext, name: string, noticeKey: string | null = E2E_NOTICE): Promise<string> {
  const serviceKey = `e2e-svc-${randomUUID().replace(/-/g, '').slice(0, 10)}`;
  const created = (await must(
    'POST /admin/services',
    await admin.post('admin/services', {
      headers: key(),
      data: { key: serviceKey, name, portal: true, processKey: E2E_PROCESS, fee: '0', ...(noticeKey ? { noticeKey } : {}) },
    }),
    [201],
  )) as { id?: string };
  if (!created.id) throw new Error('POST /admin/services returned no id');
  await must(`POST /admin/services/${created.id}/activate`, await admin.post(`admin/services/${created.id}/activate`, { headers: key() }), [200]);
  return serviceKey;
}

/** GET /portal/services/{key}/notice as the caller. */
export async function portalNotice(ctx: APIRequestContext, serviceKey: string): Promise<PortalNoticeBody> {
  return (await must(`GET /portal/services/by-key/notice?key=${serviceKey}`, await ctx.get('portal/services/by-key/notice', { params: { key: serviceKey, lang: 'en' } }), [200])) as PortalNoticeBody;
}

/** POST /portal/applications (final submit) with `consent`; the raw response, so a spec can assert a refusal. */
export async function applyAsCitizen(ctx: APIRequestContext, serviceKey: string, consent?: PortalConsentBody): Promise<APIResponse> {
  return ctx.post('portal/applications', { headers: key(), data: { serviceKey, variables: {}, submit: true, ...(consent ? { consent } : {}) } });
}
