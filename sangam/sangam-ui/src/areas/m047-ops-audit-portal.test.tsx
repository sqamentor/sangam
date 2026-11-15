// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-047 (component tests, finish, W3-J): operator, auditor, analyst and citizen core flows. Audit chain verification,
 * the reconciliation console (list query, resolve with evidence, separation of duties), batch approval and the
 * migration wizard, the analytics dashboard queries and export, and the citizen portal (apply with consent, appeal,
 * withdraw, grievance). The API client is mocked; each test asserts the exact contract call the screen sends through
 * src/api/endpoints.ts and that a refusal is shown by ProblemAlert. Written, not run in this phase.
 */
import type { ReactElement } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18n from 'i18next';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), page: vi.fn(), upload: vi.fn(), request: vi.fn(), download: vi.fn() }));

vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return {
    ...real,
    http: { ...real.http, get: api.get, post: api.post, put: api.put, del: api.del, page: api.page, upload: api.upload },
    request: api.request,
    download: api.download,
  };
});
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ me: { userId: 'demo', displayName: 'Demo', tenantId: 't1', roles: ['OPERATOR'], seats: [] }, seat: null, mode: 'dev', hasRole: () => true }),
}));
vi.mock('../components/BpmnDiagram', () => ({ BpmnDiagram: (p: { title: string }) => <div data-testid="bpmn">{p.title}</div> }));
vi.mock('../plugins/host', () => ({ PluginSlot: () => null, usePlugins: () => [] }));

import { ApiError } from '../api/client';
import { initI18n } from '../i18n';
import type { AuditEvent } from '../api/types';
import { Timeline, verifyLinkage } from './audit/Audit';
import { Reconciliation, ReconciliationDetail } from './ops/IncidentsRecon';
import { BatchDetail, Migration } from './ops/BatchMigration';
import { AnalyticsDashboard } from './analytics/Analytics';
import { PortalApplication, PortalApply, PortalGrievances, PortalHome } from './portal/Portal';

const T = (k: string, o?: Record<string, unknown>) => i18n.t(k, o) as string;
const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
/** A field label: the translated text, optionally followed by the required marker (ui.tsx FieldWrap:187-196). */
const L = (k: string) => new RegExp(`^${esc(T(k))}(\\s*\\*.*)?$`);
const problem = (status: number, code?: string) => new ApiError({ status, title: 't', code });
const KEY = expect.any(String);
const EMPTY_PAGE = { items: [], page: 0, size: 25, total: 0, capped: false };
const DAY = /^\d{4}-\d{2}-\d{2}$/;

let routes: Record<string, unknown> = {};

beforeAll(() => {
  initI18n('en');
  const proto = HTMLDialogElement.prototype as unknown as { showModal?: () => void; close?: () => void };
  proto.showModal = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  proto.close = function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
});

beforeEach(() => {
  routes = {};
  for (const f of Object.values(api)) f.mockReset();
  api.get.mockImplementation(async (path: string) => {
    const v = routes[path];
    if (v instanceof Error) throw v;
    return v ?? [];
  });
  api.page.mockResolvedValue(EMPTY_PAGE);
  api.post.mockResolvedValue({});
  api.download.mockResolvedValue(undefined);
});
afterEach(cleanup);

function show(ui: ReactElement, path = '/', route = '*') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={route} element={ui} />
      </Routes>
    </MemoryRouter>,
  );
}

function dialog(): HTMLElement {
  const d = screen.getAllByRole('dialog', { hidden: true }).find((x) => x.hasAttribute('open'));
  if (!d) throw new Error('no open dialog');
  return d;
}
const confirmIn = (d: HTMLElement) => within(d).getByRole('button', { name: T('common.confirm') }) as HTMLButtonElement;
function reasonAndConfirm(reason: string) {
  const d = dialog();
  fireEvent.change(within(d).getByLabelText(L('common.reason')), { target: { value: reason } });
  fireEvent.click(confirmIn(d));
  return d;
}

// ====================================================================== audit verify

/**
 * Evidence: Audit.tsx:19-25 (verifyLinkage), :27-111 (Timeline: verify button, linkage notice, verify notice);
 * endpoints.ts:590-592 (timeline GET /audit/{bk}/timeline, verify GET /audit/verify ?businessKey); adapters.ts:456 (normVerify).
 */
describe('M-047 audit verify', () => {
  const ev = (seq: number, hash: string, prevHash: string): AuditEvent => ({ seq, id: `e${seq}`, time: '2026-11-12T10:00:00Z', type: 'TASK_COMPLETED', businessKey: 'BK-1', actor: 'demo', hash, prevHash });
  const open = () => show(<Timeline />, '/audit/timeline/BK-1', '/audit/timeline/:businessKey');

  it('linkage check of a loaded page finds the first broken link', () => {
    expect(verifyLinkage([ev(1, 'h1', 'g'), ev(2, 'h2', 'h1'), ev(3, 'h3', 'h2')])).toEqual({ ok: true });
    expect(verifyLinkage([ev(2, 'h2', 'xx'), ev(1, 'h1', 'g')])).toEqual({ ok: false, brokenAt: 2 });
  });

  it('reads the timeline with history and shows a broken link of the loaded page', async () => {
    api.page.mockResolvedValue({ items: [ev(1, 'h1', 'g'), ev(2, 'h2', 'zz')], page: 0, size: 50, total: 2, capped: false });
    open();
    expect(await screen.findByText(T('audit.linkBroken', { seq: 2 }))).toBeTruthy();
    expect(api.page).toHaveBeenCalledWith('/audit/BK-1/timeline', { page: 0, size: 50, includeHistory: true, type: undefined });
  });

  it('verify asks the server for the chain of the business key and reports a valid chain', async () => {
    routes['/audit/verify'] = { valid: true, checked: 12, headHash: 'abcdef' };
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('audit.verify') }));
    expect(await screen.findByText(T('audit.chainValid'))).toBeTruthy();
    expect(api.get).toHaveBeenCalledWith('/audit/verify', { businessKey: 'BK-1' });
  });

  it('a broken chain names the first broken entry', async () => {
    routes['/audit/verify'] = { valid: false, checked: 5, firstBrokenSeq: 3 };
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('audit.verify') }));
    expect(await screen.findByText(T('audit.chainBroken'))).toBeTruthy();
    expect(screen.getByText(new RegExp(esc(T('audit.firstBroken', { seq: 3 }))))).toBeTruthy();
  });

  it('a refused verify is shown as "not available"', async () => {
    routes['/audit/verify'] = problem(403);
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('audit.verify') }));
    expect(await screen.findByText(T('error.notAvailable'))).toBeTruthy();
  });
});

// ====================================================================== reconciliation

/**
 * Evidence: IncidentsRecon.tsx:225-275 (list: open items, sort, mine), :279-437 (detail: SoD, evidence rule, resolve);
 * endpoints.ts:21-26 (sortParams), :419-437 (reconciliation list and ReconciliationResolveRequest {action, evidence, result, reason}).
 */
describe('M-047 reconciliation', () => {
  const item = (extra: Record<string, unknown> = {}) => ({
    id: 'r1',
    operationId: 'op-1',
    kind: 'UNKNOWN_OUTCOME',
    capabilityClass: 'b',
    owner: 'demo',
    initiator: 'maker',
    attempts: [],
    evidence: [],
    lookupAvailable: false,
    ...extra,
  });
  const openDetail = () => show(<ReconciliationDetail />, '/ops/reconciliation/r1', '/ops/reconciliation/:id');

  it('lists open items, oldest first, and narrows to mine', async () => {
    show(<Reconciliation />);
    await waitFor(() =>
      expect(api.page).toHaveBeenCalledWith('/ops/reconciliation', expect.objectContaining({ state: 'OPEN', sortBy: 'ageSec', sortOrder: 'desc', owner: undefined })),
    );
    fireEvent.click(screen.getByLabelText(T('recon.mine')));
    await waitFor(() => expect(api.page).toHaveBeenCalledWith('/ops/reconciliation', expect.objectContaining({ owner: 'demo' })));
  });

  it('mark failed needs evidence, then sends the evidence note and the reason', async () => {
    routes['/ops/reconciliation/r1'] = item();
    api.post.mockResolvedValue({ state: 'RESOLVED' });
    openDetail();
    const fail = (await screen.findByRole('button', { name: T('recon.markFailed') })) as HTMLButtonElement;
    expect(fail.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(T('recon.evidenceNote')), { target: { value: 'Bank statement shows no credit' } });
    expect(fail.disabled).toBe(false);
    fireEvent.click(fail);
    reasonAndConfirm('Statement confirms failure');
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith(
        '/ops/reconciliation/r1/resolve',
        { action: 'MARK_FAILED', evidence: 'Bank statement shows no credit', result: undefined, reason: 'Statement confirms failure' },
        KEY,
      ),
    );
  });

  it('the initiator may not resolve (separation of duties)', async () => {
    routes['/ops/reconciliation/r1'] = item({ initiator: 'demo' });
    openDetail();
    expect(await screen.findByText(T('recon.sodConflict'))).toBeTruthy();
    for (const k of ['recon.confirmSuccess', 'recon.retrySame', 'recon.markFailed']) expect((screen.getByRole('button', { name: T(k) }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('a refused resolve stays in the dialog with the refusal', async () => {
    routes['/ops/reconciliation/r1'] = item();
    api.post.mockRejectedValue(problem(409));
    openDetail();
    fireEvent.click(await screen.findByRole('button', { name: T('recon.retrySame') }));
    const d = reasonAndConfirm('Retry with the same id');
    expect(await within(d).findByText(T('error.conflict'))).toBeTruthy();
  });
});

// ====================================================================== batches and migration

/** Evidence: BatchMigration.tsx:72-186 (BatchDetail actions); endpoints.ts:396-407 (BatchControlRequest {action, reason}). */
describe('M-047 batch approval', () => {
  const open = () => show(<BatchDetail />, '/ops/batches/b1', '/ops/batches/:id');

  it('a batch awaiting approval is approved with a reason (four eyes) and its failed items are listed', async () => {
    routes['/ops/batches/b1'] = { id: 'b1', type: 'MIGRATE', status: 'AWAITING_APPROVAL', total: 10 };
    api.post.mockResolvedValue({ id: 'b1', type: 'MIGRATE', status: 'QUEUED', total: 10 });
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('batch.approve') }));
    reasonAndConfirm('Plan checked by me');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/ops/batches/b1/control', { action: 'APPROVE', reason: 'Plan checked by me' }));
    expect(api.page).toHaveBeenCalledWith('/ops/batches/b1/items', { page: 0, size: 25, state: 'FAILED' });
  });

  it('reject is a CANCEL with the reviewer reason', async () => {
    routes['/ops/batches/b1'] = { id: 'b1', type: 'MIGRATE', status: 'AWAITING_APPROVAL', total: 10 };
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('batch.reject') }));
    reasonAndConfirm('Wrong target version');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/ops/batches/b1/control', { action: 'CANCEL', reason: 'Wrong target version' }));
  });

  it('a refused control is shown in the dialog', async () => {
    routes['/ops/batches/b1'] = { id: 'b1', type: 'MIGRATE', status: 'RUNNING', total: 10 };
    api.post.mockRejectedValue(problem(403));
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('batch.pause') }));
    const d = reasonAndConfirm('Pause for the night');
    expect(await within(d).findByText(T('error.notAvailable'))).toBeTruthy();
  });
});

/** Evidence: BatchMigration.tsx:188-366 (Migration); endpoints.ts:450-484 (plan, validate, count, execute). */
describe('M-047 migration', () => {
  it('generate → validate → dry run → execute sends the plan, the R19 filter and the reason', async () => {
    api.page.mockResolvedValue({
      items: [
        { id: 'd1', key: 'p', version: 1 },
        { id: 'd2', key: 'p', version: 2 },
      ],
      page: 0,
      size: 200,
      total: 2,
      capped: false,
    });
    const plan = { sourceDefinitionId: 'd1', targetDefinitionId: 'd2', instructions: [{ sourceActivityIds: ['a'], targetActivityIds: ['a'] }] };
    api.post.mockImplementation(async (path: string, body: Record<string, unknown>) => {
      if (path === '/ops/migrations/plan') return body.mapEqualActivities ? plan : { ...plan, valid: true, validationErrors: [] };
      if (path === '/ops/batches/preview') return { count: 4 };
      if (path === '/ops/migrations/execute') return { id: 'b7', type: 'MIGRATE', state: 'QUEUED' };
      return {};
    });
    show(<Migration />, '/ops/migration', '/ops/migration');
    // both selects list the definitions (any other definition may be a target), so the option is looked up in the source select
    await within(screen.getByLabelText(T('mig.source'))).findByRole('option', { name: 'p v1' });
    fireEvent.change(screen.getByLabelText(T('mig.source')), { target: { value: 'd1' } });
    fireEvent.change(screen.getByLabelText(T('mig.target')), { target: { value: 'd2' } });
    fireEvent.click(screen.getByRole('button', { name: T('mig.generate') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/ops/migrations/plan', { sourceDefinitionId: 'd1', targetDefinitionId: 'd2', mapEqualActivities: true }));
    fireEvent.click(await screen.findByRole('button', { name: T('mig.validate') }));
    expect(await screen.findByText(T('mig.valid'))).toBeTruthy();
    // MigrationPlanRequest.mapEqualActivities and MigrationInstruction.updateEventTrigger are primitives (Jackson 3): always sent
    const sentPlan = { ...plan, mapEqualActivities: false, instructions: [{ sourceActivityIds: ['a'], targetActivityIds: ['a'], updateEventTrigger: false }] };
    expect(api.post).toHaveBeenCalledWith('/ops/migrations/plan', sentPlan);
    fireEvent.click(screen.getByRole('button', { name: T('mig.dryRun') }));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/ops/batches/preview', {
        type: 'MIGRATE',
        filter: expect.objectContaining({ processDefinitionId: 'd1' }),
        params: { targetDefinitionId: { type: 'String', value: 'd2' } },
        reason: 'preview',
      }),
    );
    const exec = screen.getByRole('button', { name: T('mig.execute') }) as HTMLButtonElement;
    await waitFor(() => expect(exec.disabled).toBe(false));
    fireEvent.click(exec);
    reasonAndConfirm('Move to version two');
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith(
        '/ops/migrations/execute',
        // MigrationExecuteRequest.skipCustomListeners / skipIoMappings and the nested plan's primitives are sent explicitly
        expect.objectContaining({ plan: sentPlan, skipCustomListeners: false, skipIoMappings: false, reason: 'Move to version two', filter: expect.objectContaining({ processDefinitionId: 'd1' }) }),
        KEY,
      ),
    );
  });
});

// ====================================================================== analytics

/** Evidence: Analytics.tsx:18-129 (filters, dashboard, export); endpoints.ts:562-583 (summary, throughput, sla, export). */
describe('M-047 analytics dashboard', () => {
  it('reads summary, throughput and SLA with the date range and bucket, and re-reads on a bucket change', async () => {
    show(<AnalyticsDashboard />);
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith('/analytics/summary', { processDefinitionKey: undefined, from: expect.stringMatching(DAY), to: expect.stringMatching(DAY) }),
    );
    expect(api.get).toHaveBeenCalledWith('/analytics/throughput', expect.objectContaining({ bucket: 'day', from: expect.stringMatching(DAY) }));
    expect(api.get).toHaveBeenCalledWith('/analytics/sla', expect.objectContaining({ bucket: 'day' }));
    fireEvent.change(screen.getByLabelText(T('analytics.bucket')), { target: { value: 'week' } });
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/analytics/throughput', expect.objectContaining({ bucket: 'week' })));
  });

  it('XES export downloads the event log for the same range', async () => {
    show(<AnalyticsDashboard />);
    fireEvent.click(screen.getByRole('button', { name: T('analytics.exportXes') }));
    await waitFor(() =>
      expect(api.download).toHaveBeenCalledWith('/analytics/export/xes', { processDefinitionKey: undefined, from: expect.stringMatching(DAY), to: expect.stringMatching(DAY) }, 'sangam-log.xes'),
    );
  });

  it('a refused summary is shown as "not available"', async () => {
    routes['/analytics/summary'] = problem(403);
    show(<AnalyticsDashboard />);
    expect(await screen.findByText(T('error.notAvailable'))).toBeTruthy();
  });
});

// ====================================================================== citizen portal

/** Evidence: Portal.tsx:20-121 (catalogue, apply with consent), :152-306 (withdraw, appeal), :308-358 (grievances); endpoints.ts:644-661. */
describe('M-047 citizen portal', () => {
  const service = { key: 'cert', name: 'Certificate', formKey: 'f1', fee: 0 };
  const form = { key: 'f1', components: [{ key: 'applicantName', label: 'Applicant name', type: 'textfield', validate: { required: true } }] };
  const openApply = () => show(<PortalApply />, '/portal/apply/cert', '/portal/apply/:serviceKey');

  it('submit needs consent and the required fields, then sends the typed variables with submit=true', async () => {
    routes['/portal/services'] = [service];
    routes['/forms/f1'] = form;
    api.post.mockResolvedValue({ id: 'a1', reference: 'APP-1' });
    openApply();
    const submit = (await screen.findByRole('button', { name: T('portal.submit') })) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    fireEvent.click(screen.getByLabelText(T('portal.consent')));
    // validateForm runs synchronously before any call (Portal.tsx:78-83): an empty required field sends nothing
    fireEvent.click(submit);
    expect(api.post).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText(/Applicant name/), { target: { value: 'Asha' } });
    fireEvent.click(submit);
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/portal/applications', { serviceKey: 'cert', variables: { applicantName: { type: 'String', value: 'Asha' } }, submit: true }, KEY),
    );
  });

  it('save draft sends submit=false without the consent', async () => {
    routes['/portal/services'] = [service];
    routes['/forms/f1'] = form;
    api.post.mockResolvedValue({ id: 'a1', reference: 'APP-1' });
    openApply();
    fireEvent.click(await screen.findByRole('button', { name: T('portal.saveDraft') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/portal/applications', { serviceKey: 'cert', variables: {}, submit: false }, KEY));
  });

  it('a rejected application can be appealed with grounds before the deadline', async () => {
    routes['/portal/applications/a1'] = { id: 'a1', reference: 'APP-1', serviceName: 'Certificate', status: 'REJECTED', appealDeadline: '2999-01-01', clarifications: [] };
    show(<PortalApplication />, '/portal/applications/a1', '/portal/applications/:id');
    fireEvent.click(await screen.findByRole('button', { name: T('portal.fileAppeal') }));
    const d = dialog();
    fireEvent.change(within(d).getByLabelText(L('portal.grounds')), { target: { value: 'The record was correct' } });
    reasonAndConfirm('Appeal against the rejection');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/portal/applications/a1/appeal', { grounds: 'The record was correct' }, KEY));
  });

  it('a submitted application is withdrawn with a reason', async () => {
    routes['/portal/applications/a1'] = { id: 'a1', reference: 'APP-1', serviceName: 'Certificate', status: 'SUBMITTED', clarifications: [] };
    show(<PortalApplication />, '/portal/applications/a1', '/portal/applications/:id');
    fireEvent.click(await screen.findByRole('button', { name: T('portal.withdraw') }));
    reasonAndConfirm('No longer needed');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/portal/applications/a1/withdraw', { reason: 'No longer needed' }, KEY));
  });

  it('a grievance is filed and its reference is shown', async () => {
    routes['/portal/grievances'] = [];
    api.post.mockResolvedValue({ reference: 'G-7' });
    show(<PortalGrievances />);
    fireEvent.change(await screen.findByLabelText(L('task.subject')), { target: { value: 'Delay' } });
    fireEvent.change(screen.getByLabelText(L('portal.details')), { target: { value: 'No reply for a month' } });
    fireEvent.click(screen.getByRole('button', { name: T('common.submit') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/portal/grievances', { subject: 'Delay', text: 'No reply for a month', kind: 'GRIEVANCE' }, KEY));
    expect(await screen.findByText(T('portal.grievanceFiled', { ref: 'G-7' }))).toBeTruthy();
  });

  it('a refused catalogue is shown as "not available"', async () => {
    routes['/portal/services'] = problem(403);
    show(<PortalHome />);
    expect(await screen.findByText(T('error.notAvailable'))).toBeTruthy();
  });
});
