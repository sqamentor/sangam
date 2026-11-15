// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: erasure intake by the signed-in data principal, and the officer's audited look-up
 * (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix rows and the clauses they give evidence for:
 * - D-16 (intake only): a signed-in data principal asks for erasure (DPDP Act s.12(3)) and the request is received,
 *   with a due date and the erasure process started from the installed compliance pack (owner decisions 335, 336);
 *   an anonymous request, or one naming someone else, is refused with PRINCIPAL_NOT_AUTHENTICATED (DPDP Rules r.14(1):
 *   the means the pack names, here SIGNED_IN);
 * - D-17: a legal hold is placed on a process root holding the data principal's data through the API, listed, refused
 *   for a kind outside the list, an unknown root or a caller without compliance.modify, and released only by a
 *   different person (owner decision 361; SANGAM.md X.2.3.5 #5). That the hold overrides erasure (the held root is not
 *   erased, the refusal names LEGAL_HOLD) is asserted by LegalHoldErasureH2Test: executing an erasure here would erase
 *   the shared e2e citizen's data used by the other specs;
 * - D-21: the acknowledgement carries the published DPO contact (s.8(9), r.9; owner decision 346);
 * - W-08 / D-11: the officer finds the request with the principal reference in a POST body, never in a URL (decision
 *   338), and that read of another principal's records is audited (r.6(1)(c), decision 348; not visible through the
 *   API, asserted by CompliancePrincipalReadsH2Test).
 * Server: ComplianceController.submitRightsRequest (signedIn), DataSubjectRightsService.submit / authenticate,
 * ComplianceWorkflowStarter.
 */
import { randomUUID } from 'node:crypto';
import { request } from '@playwright/test';
import { apiAs, expect, test } from './support/fixtures';
import { API_BASE } from './support/env';
import { tenantOf } from './support/compliance';

interface RightsRequest {
  requestId: string;
  principalRef: string;
  right: string;
  status: string;
  dueAt?: string;
  processInstanceId?: string;
  contact?: { name?: string; email?: string } | null;
}

const key = () => ({ 'Idempotency-Key': randomUUID() });

test('a signed-in data principal asks for erasure; anonymous and impersonating requests are refused (D-16, D-17, D-21, W-08)', async ({ seed }) => {
  const citizen = await apiAs(seed.identities.citizen.token);
  const officer = await apiAs(seed.identities.complianceOfficer.token);
  const roleless = await apiAs(seed.identities.roleless.token);
  const anonymous = await request.newContext({ baseURL: `${API_BASE.replace(/\/$/, '')}/`, extraHTTPHeaders: { Accept: 'application/json, application/problem+json' } });
  try {
    const tenant = await tenantOf(officer);

    // no signed-in identity: refused by name, nothing received
    const anon = await anonymous.post('compliance/rights-requests', {
      headers: { ...key(), 'X-Sangam-Tenant': tenant },
      data: { right: 'ERASURE', identifierType: 'SIGNED_IN', principalRef: seed.identities.citizen.userId },
    });
    expect(anon.status(), await anon.text()).toBe(401);
    expect(((await anon.json()) as { code?: string }).code).toBe('PRINCIPAL_NOT_AUTHENTICATED');

    // signed in, but asking in another person's name
    const other = await citizen.post('compliance/rights-requests', {
      headers: key(),
      data: { right: 'ERASURE', identifierType: 'SIGNED_IN', identifierValue: seed.identities.roleless.userId },
    });
    expect(other.status(), await other.text()).toBe(401);
    expect(((await other.json()) as { code?: string }).code).toBe('PRINCIPAL_NOT_AUTHENTICATED');

    // the data principal's own request
    const res = await citizen.post('compliance/rights-requests', { headers: key(), data: { right: 'ERASURE', identifierType: 'SIGNED_IN', channel: 'PORTAL' } });
    expect(res.status(), await res.text()).toBe(201);
    const filed = (await res.json()) as RightsRequest;
    expect(filed.right).toBe('ERASURE');
    expect(filed.principalRef).toBe(seed.identities.citizen.userId);
    expect(filed.status).toBe('RECEIVED');
    expect(Date.parse(filed.dueAt ?? '')).toBeGreaterThan(Date.now());
    expect(filed.processInstanceId, 'the erasure process of the installed compliance pack').toBeTruthy();
    expect(filed.contact?.name).toBe(seed.compliance.dpo.name);
    expect(filed.contact?.email).toBe(seed.compliance.dpo.email);

    // the data principal reads it back, with the contact
    const own = await citizen.get(`compliance/rights-requests/${filed.requestId}`);
    expect(own.status(), await own.text()).toBe(200);
    expect(((await own.json()) as RightsRequest).contact?.name).toBe(seed.compliance.dpo.name);

    // the officer finds it with the reference in the body (decision 338)
    const found = await officer.post('compliance/rights-requests/search', { headers: key(), data: { subject: seed.identities.citizen.userId } });
    expect(found.status(), await found.text()).toBe(200);
    expect(((await found.json()) as RightsRequest[]).map((r) => r.requestId)).toContain(filed.requestId);
    expect(found.url()).not.toContain(encodeURIComponent(seed.identities.citizen.userId));
    const read = await officer.get(`compliance/rights-requests/${filed.requestId}`);
    expect(read.status(), await read.text()).toBe(200);

    // someone without compliance.read: another principal's request does not exist for them
    const hidden = await roleless.get(`compliance/rights-requests/${filed.requestId}`);
    expect([403, 404], await hidden.text()).toContain(hidden.status());
  } finally {
    await Promise.all([citizen.dispose(), officer.dispose(), roleless.dispose(), anonymous.dispose()]);
  }
});

interface LegalHold {
  holdId: string;
  rootId: string;
  reason?: string;
  heldBy?: string;
}

test('a legal hold is placed on a process root that holds the data principal\'s data, and released by a different person (D-17)', async ({ seed, api }) => {
  const officer = await apiAs(seed.identities.complianceOfficer.token);
  const roleless = await apiAs(seed.identities.roleless.token);
  try {
    // a process root holding the citizen's data: the applicant variable the server's personal-data port reads
    // (CompliancePersonalData, owner decision 361)
    const started = await api.ctx.post('process-instances', {
      headers: key(),
      data: { key: seed.definitionKey, businessKey: `E2E-HOLD-${randomUUID()}`, variables: { sangamApplicantId: { type: 'String', value: seed.identities.citizen.userId } } },
    });
    expect(started.ok(), await started.text()).toBe(true);
    const rootId = ((await started.json()) as { id: string }).id;

    // a kind outside the list, and an unknown root, are refused by name
    const badKind = await officer.post(`compliance/process-roots/${rootId}/legal-holds`, { headers: key(), data: { kind: 'rti', reason: 'reply pending' } });
    expect(badKind.status(), await badKind.text()).toBe(400);
    const unknown = await officer.post(`compliance/process-roots/no-such-root-${randomUUID()}/legal-holds`, { headers: key(), data: { kind: 'RTI', reason: 'reply pending' } });
    expect(unknown.status(), await unknown.text()).toBe(404);

    // someone without compliance.modify cannot hold
    const denied = await roleless.post(`compliance/process-roots/${rootId}/legal-holds`, { headers: key(), data: { kind: 'RTI', reason: 'reply pending' } });
    expect(denied.status(), await denied.text()).toBe(403);

    // the officer places the hold; it is listed
    const placed = await officer.post(`compliance/process-roots/${rootId}/legal-holds`, { headers: key(), data: { kind: 'RTI', reference: 'RTI/E2E/1', reason: 'reply pending' } });
    expect(placed.status(), await placed.text()).toBe(201);
    const hold = (await placed.json()) as LegalHold;
    expect(hold.rootId).toBe(rootId);
    expect(hold.reason).toBe('RTI RTI/E2E/1: reply pending');
    const listed = await officer.get(`compliance/process-roots/${rootId}/legal-holds`);
    expect(listed.status(), await listed.text()).toBe(200);
    expect(((await listed.json()) as LegalHold[]).map((h) => h.holdId)).toContain(hold.holdId);

    // the placer cannot release it (four eyes); a different person can
    const self = await officer.post(`compliance/process-roots/${rootId}/legal-holds/${hold.holdId}/release`, { headers: key(), data: { reason: 'reply sent' } });
    expect(self.status(), await self.text()).toBe(422);
    expect(((await self.json()) as { code?: string }).code).toBe('FOUR_EYES_REQUIRED');
    const released = await api.ctx.post(`compliance/process-roots/${rootId}/legal-holds/${hold.holdId}/release`, { headers: key(), data: { reason: 'reply sent' } });
    expect(released.status(), await released.text()).toBe(204);
    const after = await officer.get(`compliance/process-roots/${rootId}/legal-holds`);
    expect(((await after.json()) as LegalHold[]).map((h) => h.holdId)).not.toContain(hold.holdId);
  } finally {
    await Promise.all([officer.dispose(), roleless.dispose()]);
  }
});
