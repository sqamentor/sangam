// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e: the notice of a personal-data breach in the affected citizen's portal account
 * (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md).
 *
 * Matrix rows and the clauses they give evidence for:
 * - D-15: each affected data principal is told of the breach without delay, with the description, the likely
 *   consequences, the mitigation, the safety steps they can take and the business contact (DPDP Act s.8(6); DPDP Rules
 *   r.7(1)(a)-(e)). Besides the integrator's delivery (PrincipalNotifier), the notice is a portal notification of that
 *   principal carrying the five items under the jurisdiction pack's labels and citations (owner decision 347); no other
 *   citizen sees it.
 * Flow: the compliance officer registers the breach (POST /compliance/breaches) naming the principal, the process
 * sangam-breach-incident reaches "Approve notice to affected data subjects" (u_notice), the approver completes it
 * with the notice text (subjectNotice), breach.notifySubjects delivers it and BreachIncidentService's subject-notice
 * listener writes the portal notification (PortalComplianceAdapter, REST_DOCUMENT kind portal:notice, encrypted).
 * The process must be deployed on the test-mode server (owner decision 336: from the installed compliance pack). The
 * test asserts that first, so a server without it fails here by name; it is never skipped.
 * UI: PortalPrivacy.tsx PortalNotices.
 */
import { randomUUID } from 'node:crypto';
import { apiAs, expect, signInAs, test } from './support/fixtures';
import { t } from './support/env';
import { indiaPack } from './support/compliance';
import { IDENTITY_SCOPES, createIdentity } from './support/identities';

interface PackItem {
  key: string;
  label: string;
  citation?: string;
}

interface Notification {
  id: string;
  kind: string;
  reference: string;
  at: string;
  subject?: string;
  items: { key: string; label: string; citation?: string; text: string }[];
}

const subjectItems = (): PackItem[] => (indiaPack() as unknown as { breach: { subjectItems: PackItem[] } }).breach.subjectItems;

const items = <T>(body: unknown): T[] => (Array.isArray(body) ? (body as T[]) : ((body as { items?: T[] }).items ?? []));

test('a fresh citizen has no notices, and the notices page says so (D-15)', async ({ page, api: admin }) => {
  const citizen = await createIdentity(admin.ctx, `e2e-citizen-notices-${randomUUID().slice(0, 8)}`, IDENTITY_SCOPES.citizen);
  const citizenApi = await apiAs(citizen.token);
  try {
    const res = await citizenApi.get('portal/notifications');
    expect(res.status(), await res.text()).toBe(200);
    expect(items(await res.json())).toEqual([]);
  } finally {
    await citizenApi.dispose();
  }
  await signInAs(page, citizen.token);
  await page.goto('/portal');
  // decision 397: the citizen's menu entry is "Letters and notices" (nav.ts services.notices, /portal/notices)
  await page.getByRole('navigation', { name: t('nav.primary') }).getByRole('link', { name: t('menu.services.notices.label'), exact: true }).click();
  await expect(page.getByText(t('portal.noNotices'))).toBeVisible();
});

test('the breach notice reaches the affected citizen\'s portal with the five r.7(1) items, and no one else (D-15)', async ({ page, api: admin, seed }) => {
  const defs = await admin.ctx.get('process-definitions', { params: { key: 'sangam-breach-incident', latest: true } });
  expect(defs.status(), await defs.text()).toBe(200);
  expect(items(await defs.json()), 'sangam-breach-incident is deployed from the installed compliance pack (owner decision 336)').not.toHaveLength(0);

  const pack = subjectItems();
  expect(pack.map((i) => i.key)).toEqual(['description', 'consequences', 'mitigation', 'safetySteps', 'contact']);
  const affected = await createIdentity(admin.ctx, `e2e-citizen-breach-${randomUUID().slice(0, 8)}`, IDENTITY_SCOPES.citizen);
  const bystander = await createIdentity(admin.ctx, `e2e-citizen-bystander-${randomUUID().slice(0, 8)}`, IDENTITY_SCOPES.citizen);

  const officerApi = await apiAs(seed.identities.complianceOfficer.token);
  const affectedApi = await apiAs(affected.token);
  const bystanderApi = await apiAs(bystander.token);
  try {
    const registered = await officerApi.post('compliance/breaches', {
      headers: { 'Idempotency-Key': randomUUID() },
      data: { facts: { description: 'E2E breach of the application store' }, awareAt: new Date().toISOString(), subjects: [affected.userId] },
    });
    expect(registered.status(), await registered.text()).toBe(201);
    const breachId = ((await registered.json()) as { breachId: string }).breachId;

    // the approval task of the subject notice (u_notice), found by the breach id (the process business key)
    let taskId: string | undefined;
    await expect
      .poll(async () => {
        const r = await admin.ctx.get('tasks', { params: { businessKey: breachId, taskDefinitionKey: 'u_notice' } });
        taskId = items<{ id: string }>(await r.json())[0]?.id;
        return taskId;
      }, { timeout: 30_000 })
      .toBeTruthy();
    const task = (await (await admin.ctx.get(`tasks/${taskId}`)).json()) as { payloadHash: string; caseVersion?: number };
    const claim = await admin.ctx.post(`tasks/${taskId}/claim`, { headers: { 'Idempotency-Key': randomUUID() } });
    expect([204, 409], await claim.text()).toContain(claim.status());
    const text: Record<string, string> = {
      description: 'Application records were read by an unauthorised party.',
      consequences: 'Your name and mobile number may be misused.',
      mitigation: 'Access was revoked and the store was secured.',
      safetySteps: 'Beware of calls asking for one-time passwords.',
      contact: 'dpo@e2e.test',
    };
    const done = await admin.ctx.post(`tasks/${taskId}/complete`, {
      headers: { 'Idempotency-Key': randomUUID() },
      data: { payloadHash: task.payloadHash, caseVersion: task.caseVersion, decision: 'APPROVED', variables: { subjectNotice: { type: 'Json', value: text } } },
    });
    expect(done.ok(), await done.text()).toBe(true);

    // the affected citizen's portal account: one notice, the five items in the pack's order with its labels and citations
    let notice: Notification | undefined;
    await expect
      .poll(async () => {
        const r = await affectedApi.get('portal/notifications');
        notice = items<Notification>(await r.json()).find((n) => n.reference === breachId);
        return notice;
      }, { timeout: 30_000 })
      .toBeTruthy();
    expect(notice!.items.map((i) => i.key)).toEqual(pack.map((i) => i.key));
    for (const [i, p] of pack.entries()) {
      expect(notice!.items[i].label).toBe(p.label);
      expect(notice!.items[i].citation).toBe(p.citation);
      expect(notice!.items[i].text).toBe(text[p.key]);
    }

    // another citizen sees nothing of it
    const other = await bystanderApi.get('portal/notifications');
    expect(items<Notification>(await other.json()).filter((n) => n.reference === breachId)).toEqual([]);

    await signInAs(page, affected.token);
    await page.goto('/portal/notices');
    for (const p of pack) await expect(page.locator('dl.notice-items dt').filter({ hasText: p.label }).first()).toBeVisible();
    await expect(page.getByText(text.safetySteps)).toBeVisible();
  } finally {
    await officerApi.dispose();
    await affectedApi.dispose();
    await bystanderApi.dispose();
  }
});
