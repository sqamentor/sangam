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
 *   different person (owner decision 361; SANGAM.md X.2.3.5 #5);
 * - D-16 / D-17 end to end (owner decision 369 (g)): a dedicated test citizen of this spec's own (never the shared e2e
 *   citizen, whose data the other specs use) asks for erasure; the records officer's approval task of the installed
 *   erasure process is completed with APPROVE; the process root without a hold is erased (its variables are gone) and
 *   the held root is kept, the request closing PARTIALLY_RESOLVED with the refusal named LEGAL_HOLD (X.2.3.8 #4;
 *   LegalHoldErasureH2Test asserts the RETENTION_HELD audit on the held root's chain, not visible through the API);
 * - D-21: the acknowledgement carries the published DPO contact (s.8(9), r.9; owner decision 346);
 * - W-08 / D-11: the officer finds the request with the principal reference in a POST body, never in a URL (decision
 *   338), and that read of another principal's records is audited (r.6(1)(c), decision 348; not visible through the
 *   API, asserted by CompliancePrincipalReadsH2Test).
 * Server: ComplianceController.submitRightsRequest (signedIn), DataSubjectRightsService.submit / authenticate /
 * executeErasure, ComplianceWorkflowStarter, sangam-dsr-erasure (u_approve, s_erase), CompliancePersonalData,
 * ErasureService.
 */
import { randomUUID } from 'node:crypto';
import { request } from '@playwright/test';
import { apiAs, expect, test } from './support/fixtures';
import { API_BASE } from './support/env';
import { tenantOf } from './support/compliance';
import { createIdentity, IDENTITY_SCOPES } from './support/identities';

interface RightsRequest {
  requestId: string;
  principalRef: string;
  right: string;
  status: string;
  dueAt?: string;
  processInstanceId?: string;
  outcome?: string;
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

const items = <T>(body: unknown): T[] => (Array.isArray(body) ? (body as T[]) : (((body as { items?: T[] }).items) ?? []));

test('erasure end to end on a dedicated citizen: approved, the free root is erased and the held root is kept by name (D-16, D-17)', async ({ seed, api }) => {
  // a citizen of this spec only: erasing the shared e2e citizen would remove data the other specs read
  const own = await createIdentity(api.ctx, `e2e-citizen-erasure-${randomUUID().slice(0, 8)}`, IDENTITY_SCOPES.citizen);
  const citizen = await apiAs(own.token);
  const officer = await apiAs(seed.identities.complianceOfficer.token);
  try {
    // two process roots holding the citizen's data (the applicant variable CompliancePersonalData reads)
    const root = async (tag: string): Promise<string> => {
      const started = await api.ctx.post('process-instances', {
        headers: key(),
        data: { key: seed.definitionKey, businessKey: `E2E-ERASE-${tag}-${randomUUID()}`, variables: { sangamApplicantId: { type: 'String', value: own.userId } } },
      });
      expect(started.ok(), await started.text()).toBe(true);
      return ((await started.json()) as { id: string }).id;
    };
    const free = await root('FREE');
    const held = await root('HELD');
    const placed = await officer.post(`compliance/process-roots/${held}/legal-holds`, { headers: key(), data: { kind: 'LITIGATION', reference: 'E2E/ERASE/1', reason: 'case pending' } });
    expect(placed.status(), await placed.text()).toBe(201);
    const hold = (await placed.json()) as LegalHold;

    // the citizen asks for erasure; the erasure process of the installed compliance pack starts
    const res = await citizen.post('compliance/rights-requests', { headers: key(), data: { right: 'ERASURE', identifierType: 'SIGNED_IN', channel: 'PORTAL' } });
    expect(res.status(), await res.text()).toBe(201);
    const filed = (await res.json()) as RightsRequest;
    expect(filed.principalRef).toBe(own.userId);
    expect(filed.processInstanceId).toBeTruthy();

    // the records officer's approval task (u_approve), found by the request id (the process business key)
    let taskId: string | undefined;
    await expect
      .poll(async () => {
        const r = await api.ctx.get('tasks', { params: { businessKey: filed.requestId, taskDefinitionKey: 'u_approve' } });
        taskId = items<{ id: string }>(await r.json())[0]?.id;
        return taskId;
      }, { timeout: 30_000 })
      .toBeTruthy();
    const task = (await (await api.ctx.get(`tasks/${taskId}`)).json()) as { payloadHash: string; caseVersion?: number };
    const claim = await api.ctx.post(`tasks/${taskId}/claim`, { headers: key() });
    expect([204, 409], await claim.text()).toContain(claim.status());
    const done = await api.ctx.post(`tasks/${taskId}/complete`, {
      headers: key(),
      data: {
        payloadHash: task.payloadHash,
        caseVersion: task.caseVersion,
        variables: { decision: { type: 'String', value: 'APPROVE' }, decisionReason: { type: 'String', value: 'No retention obligation applies beyond the held case.' } },
      },
    });
    expect(done.ok(), await done.text()).toBe(true);

    // the erasure step runs: the request closes, partly resolved, naming the hold
    let closed: RightsRequest | undefined;
    await expect
      .poll(async () => {
        const r = await citizen.get(`compliance/rights-requests/${filed.requestId}`);
        closed = (await r.json()) as RightsRequest;
        return closed.status;
      }, { timeout: 60_000 })
      .toBe('PARTIALLY_RESOLVED');
    expect(closed?.outcome, 'one root erased').toContain('ERASED 1');
    expect(closed?.outcome).toContain(held);
    expect(closed?.outcome, 'the refusal names the hold').toContain('LEGAL_HOLD');
    expect(closed?.outcome).toContain(hold.holdId);

    // the free root's data is gone; the held root keeps the citizen's data
    const freeVars = await api.ctx.get(`process-instances/${free}/variables`);
    expect(freeVars.status(), await freeVars.text()).toBe(200);
    expect(Object.keys((await freeVars.json()) as Record<string, unknown>)).not.toContain('sangamApplicantId');
    const heldVars = await api.ctx.get(`process-instances/${held}/variables`);
    expect(heldVars.status(), await heldVars.text()).toBe(200);
    const kept = (await heldVars.json()) as Record<string, unknown>;
    expect(Object.keys(kept)).toContain('sangamApplicantId');
    expect(JSON.stringify(kept.sangamApplicantId)).toContain(own.userId);

    // the hold is released by a different person than the one who placed it (four eyes), leaving nothing held
    const released = await api.ctx.post(`compliance/process-roots/${held}/legal-holds/${hold.holdId}/release`, { headers: key(), data: { reason: 'e2e done' } });
    expect(released.status(), await released.text()).toBe(204);
  } finally {
    await Promise.all([citizen.dispose(), officer.dispose()]);
  }
});
