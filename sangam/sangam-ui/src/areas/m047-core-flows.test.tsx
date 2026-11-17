// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-047 (component tests, finish, W3-J): the task-user core flows. TaskDetail decisions (approve with the declaration,
 * reject with a reason code, the stale banner on 409, the maker-checker block, a hidden task), the start catalogue
 * for a case, the case workspace (plan-item command, reactivation, comments) and MyWork (leave request,
 * notifications). Each test drives the screen against a mocked API client and asserts the exact contract call
 * (path, body) the screen sends through src/api/endpoints.ts, and that a refusal is shown by ProblemAlert.
 * Written, not run in this phase (house rule: tests run after Wave 5).
 */
import type { ReactElement } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18n from 'i18next';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), page: vi.fn(), upload: vi.fn(), request: vi.fn(), download: vi.fn() }));
const auth = vi.hoisted(() => ({
  state: {
    me: {
      userId: 'demo',
      displayName: 'Demo',
      tenantId: 't1',
      roles: ['TASK_USER'],
      seats: [{ seatId: 's1', seatName: 'Section officer', designation: 'SO', orgUnitName: 'Unit', kind: 'SUBSTANTIVE', validFrom: '2026-01-01' }],
    },
    seat: null as unknown,
    mode: 'dev',
    hasRole: (..._r: string[]) => false,
  },
}));

vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return {
    ...real,
    http: { ...real.http, get: api.get, post: api.post, put: api.put, del: api.del, page: api.page, upload: api.upload },
    request: api.request,
    download: api.download,
  };
});
vi.mock('../auth/AuthProvider', () => ({ useAuth: () => auth.state }));
vi.mock('../components/BpmnDiagram', () => ({ BpmnDiagram: (p: { title: string }) => <div data-testid="bpmn">{p.title}</div> }));
vi.mock('../plugins/host', () => ({ PluginSlot: () => null, usePlugins: () => [] }));

import { ApiError } from '../api/client';
import { initI18n } from '../i18n';
import { TaskDetail } from './tasklist/TaskDetail';
import { CaseWorkspace, StartCatalog } from './tasklist/StartAndCases';
import { MyDelegations, Notifications } from './tasklist/MyWork';

const T = (k: string, o?: Record<string, unknown>) => i18n.t(k, o) as string;
const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
/** A field label: the translated text, optionally followed by the required marker (ui.tsx FieldWrap:187-196). */
const L = (k: string) => new RegExp(`^${esc(T(k))}(\\s*\\*.*)?$`);
const problem = (status: number, code?: string) => new ApiError({ status, title: 't', code });
const KEY = expect.any(String);
const EMPTY_PAGE = { items: [], page: 0, size: 25, total: 0, capped: false };

let routes: Record<string, unknown> = {};

beforeAll(() => {
  initI18n('en');
  // jsdom has <dialog> but not its modal API.
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
    return typeof v === 'function' ? (v as () => unknown)() : (v ?? []);
  });
  api.page.mockResolvedValue(EMPTY_PAGE);
  api.post.mockResolvedValue({});
  auth.state.hasRole = () => false;
  auth.state.seat = null;
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

/** The open dialog (a closed <dialog> stays in the DOM). */
function dialog(): HTMLElement {
  const d = screen.getAllByRole('dialog', { hidden: true }).find((x) => x.hasAttribute('open'));
  if (!d) throw new Error('no open dialog');
  return d;
}
const confirmIn = (d: HTMLElement) => within(d).getByRole('button', { name: T('common.confirm') }) as HTMLButtonElement;

// ====================================================================== TaskDetail decisions

/**
 * Evidence: TaskDetail.tsx:110-121 (openDecision), :332-361 (decision bar, SoD disable), :474-506 (DecisionDialog
 * submit body and disabled rules), :80-91 (stale handling), :124-131 (hidden task); endpoints.ts:104-119 (complete →
 * CompleteTaskRequest {payloadHash, caseVersion, decision, remarks, ...}).
 */
describe('M-047 TaskDetail decisions', () => {
  const task = (extra: Record<string, unknown> = {}) => ({
    id: 't1',
    name: 'Approve bill',
    reference: 'REF-1',
    section: 'MY_SEAT',
    assignee: 'demo',
    payloadHash: 'abc123',
    version: 3,
    allowedActions: ['approve', 'reject'],
    rejectReasons: [{ code: 'R1', label: 'Incomplete' }],
    returnTargets: [],
    variables: {},
    ...extra,
  });
  const open = () => show(<TaskDetail />, '/tasks/t1', '/tasks/:id');

  it('approve: confirm stays disabled until the declaration is ticked, then sends payloadHash and caseVersion', async () => {
    routes['/tasks/t1'] = task();
    api.post.mockResolvedValue({ state: 'COMPLETED', reference: 'REF-1' });
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('task.action.APPROVE') }));
    const d = dialog();
    expect(confirmIn(d).disabled).toBe(true);
    fireEvent.click(within(d).getByLabelText(T('task.declaration')));
    expect(confirmIn(d).disabled).toBe(false);
    fireEvent.click(confirmIn(d));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/tasks/t1/complete', expect.objectContaining({ payloadHash: 'abc123', caseVersion: 3, decision: 'APPROVE', variables: {} }), KEY),
    );
    expect(await screen.findByRole('heading', { name: T('task.doneTitle') })).toBeTruthy();
  });

  it('reject: needs a reason code and remarks, and sends both', async () => {
    routes['/tasks/t1'] = task();
    api.post.mockResolvedValue({ state: 'REJECTED' });
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('task.action.REJECT') }));
    const d = dialog();
    expect(confirmIn(d).disabled).toBe(true);
    fireEvent.change(within(d).getByLabelText(L('task.reasonCode')), { target: { value: 'R1' } });
    expect(confirmIn(d).disabled).toBe(true);
    fireEvent.change(within(d).getByLabelText(L('task.remarks')), { target: { value: 'Pages missing' } });
    fireEvent.click(confirmIn(d));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/tasks/t1/complete', expect.objectContaining({ decision: 'REJECT', reasonCode: 'R1', remarks: 'Pages missing', payloadHash: 'abc123', caseVersion: 3 }), KEY),
    );
  });

  it('409 FORM_VERSION_STALE: the stale banner shows the old and new version and asks for the field diff', async () => {
    let current = task();
    routes['/tasks/t1'] = () => current;
    routes['/tasks/t1/diff'] = [];
    api.post.mockImplementation(async () => {
      current = task({ version: 4, payloadHash: 'def456' });
      throw problem(409, 'FORM_VERSION_STALE');
    });
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('task.action.APPROVE') }));
    const d = dialog();
    fireEvent.click(within(d).getByLabelText(T('task.declaration')));
    fireEvent.click(confirmIn(d));
    expect(await screen.findByText(T('task.staleBanner', { from: 3, to: 4 }))).toBeTruthy();
    expect(api.get).toHaveBeenCalledWith('/tasks/t1/diff', { from: 3, to: 4 });
  });

  it('maker-checker: approve is disabled and the reason is shown', async () => {
    routes['/tasks/t1'] = task({ sod: { allowed: false, reasons: ['SAME_MAKER'] } });
    open();
    const approve = (await screen.findByRole('button', { name: T('task.action.APPROVE') })) as HTMLButtonElement;
    expect(approve.disabled).toBe(true);
    expect(screen.getByText(T('task.sodDisabled'))).toBeTruthy();
  });

  it('a missing task (404) reads "could not find" and links back to the inbox', async () => {
    routes['/tasks/t1'] = problem(404);
    open();
    expect(await screen.findByText(T('error.notFound'))).toBeTruthy();
    expect(screen.getByRole('link', { name: T('task.backToInbox') })).toBeTruthy();
    expect(api.post).not.toHaveBeenCalled();
  });
});

// ====================================================================== start catalogue and case workspace

/** Evidence: StartAndCases.tsx:54-104 (StartForm), endpoints.ts:306-307 (cases.start → POST /case-instances). */
describe('M-047 start a case from the catalogue', () => {
  beforeEach(() => {
    routes['/start-catalogue'] = [{ key: 'grant', name: 'Grant case', kind: 'CASE' }];
  });

  it('sends {key, businessKey, variables} to POST /case-instances', async () => {
    api.post.mockResolvedValue({ id: 'c9' });
    show(<StartCatalog />);
    fireEvent.click(await screen.findByRole('button', { name: T('start.begin') }));
    fireEvent.change(screen.getByLabelText(L('start.businessKey')), { target: { value: 'BK-9' } });
    fireEvent.click(screen.getByRole('button', { name: T('start.submit') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/case-instances', { key: 'grant', businessKey: 'BK-9', variables: {} }, KEY));
  });

  it('a refusal is shown in words', async () => {
    api.post.mockRejectedValue(problem(422));
    show(<StartCatalog />);
    fireEvent.click(await screen.findByRole('button', { name: T('start.begin') }));
    fireEvent.click(screen.getByRole('button', { name: T('start.submit') }));
    expect(await screen.findByText(T('error.validation'))).toBeTruthy();
  });
});

/**
 * Evidence: StartAndCases.tsx:189-339 (CaseWorkspace: plan-item commands, reactivation, comments);
 * endpoints.ts:309-314 (planItemAction → CaseExecutionCommand {action, reason}; reactivate {reason}; addComment {text}).
 */
describe('M-047 case workspace', () => {
  const kase = (state: string) => ({
    id: 'c1',
    businessKey: 'CASE-1',
    definitionKey: 'grant',
    state,
    created: '2026-11-01T10:00:00Z',
    planItems: [{ id: 'p1', name: 'Site visit', definitionId: 'visit', type: 'HUMAN_TASK', state: 'ENABLED' }],
  });
  const open = () => show(<CaseWorkspace />, '/cases/c1', '/cases/:id');

  it('manual start of an enabled plan item sends the CaseExecutionCommand with the reason', async () => {
    routes['/case-instances/c1'] = kase('ACTIVE');
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('case.start') }));
    const d = dialog();
    fireEvent.change(within(d).getByLabelText(L('common.reason')), { target: { value: 'Visit is due now' } });
    fireEvent.click(confirmIn(d));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/case-instances/c1/executions/p1/commands', { action: 'manualStart', reason: 'Visit is due now' }));
  });

  it('a closed case is reactivated with a reason (FC-59)', async () => {
    routes['/case-instances/c1'] = kase('COMPLETED');
    api.post.mockResolvedValue({ id: 'c2' });
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('case.reactivate') }));
    const d = dialog();
    fireEvent.change(within(d).getByLabelText(L('common.reason')), { target: { value: 'New evidence came in' } });
    fireEvent.click(confirmIn(d));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/case-instances/c1/reactivation', { reason: 'New evidence came in' }, KEY));
  });

  it('a comment is posted as {text}', async () => {
    routes['/case-instances/c1'] = kase('ACTIVE');
    open();
    fireEvent.click(await screen.findByRole('tab', { name: T('task.tab.comments') }));
    fireEvent.change(screen.getByLabelText(T('task.addComment')), { target: { value: 'Looks fine' } });
    fireEvent.click(screen.getByRole('button', { name: T('task.postComment') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/case-instances/c1/comments', { text: 'Looks fine' }));
  });

  it('a refused plan-item command keeps the dialog open with the refusal', async () => {
    routes['/case-instances/c1'] = kase('ACTIVE');
    api.post.mockRejectedValue(problem(409));
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('case.start') }));
    const d = dialog();
    fireEvent.change(within(d).getByLabelText(L('common.reason')), { target: { value: 'Visit is due now' } });
    fireEvent.click(confirmIn(d));
    expect(await within(d).findByText(T('error.conflict'))).toBeTruthy();
  });
});

// ====================================================================== MyWork

/** Evidence: MyWork.tsx:20-101 (leave request form), :103-132 (notifications); endpoints.ts:64-70. */
describe('M-047 MyWork', () => {
  it('a leave request sends the substantive seat, ISO dates and the reason', async () => {
    routes['/me/delegations'] = [];
    show(<MyDelegations />);
    const submit = (await screen.findByRole('button', { name: T('my.submitLeave') })) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(L('admin.seat')), { target: { value: 's1' } });
    fireEvent.change(screen.getByLabelText(L('admin.validFrom')), { target: { value: '2026-12-01' } });
    fireEvent.change(screen.getByLabelText(L('admin.validTo')), { target: { value: '2026-12-05' } });
    fireEvent.change(screen.getByLabelText(L('common.reason')), { target: { value: 'Medical leave' } });
    expect(submit.disabled).toBe(false);
    fireEvent.click(submit);
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/me/leave-requests', {
        seatId: 's1',
        substituteSeatId: undefined,
        from: '2026-12-01T00:00:00.000Z',
        to: '2026-12-05T00:00:00.000Z',
        reason: 'Medical leave',
      }),
    );
  });

  it('a refused leave request is shown in words', async () => {
    routes['/me/delegations'] = [];
    api.post.mockRejectedValue(problem(403));
    show(<MyDelegations />);
    fireEvent.change(await screen.findByLabelText(L('admin.seat')), { target: { value: 's1' } });
    fireEvent.change(screen.getByLabelText(L('admin.validFrom')), { target: { value: '2026-12-01' } });
    fireEvent.change(screen.getByLabelText(L('admin.validTo')), { target: { value: '2026-12-05' } });
    fireEvent.change(screen.getByLabelText(L('common.reason')), { target: { value: 'Medical leave' } });
    fireEvent.click(screen.getByRole('button', { name: T('my.submitLeave') }));
    expect(await screen.findByText(T('error.notAvailable'))).toBeTruthy();
  });

  it('an unread notification is marked read through its own route', async () => {
    api.page.mockResolvedValue({ items: [{ id: 'n1', title: 'File moved', at: '2026-11-12T10:00:00Z', read: false }], page: 0, size: 25, total: 1, capped: false });
    api.post.mockResolvedValue(undefined);
    show(<Notifications />);
    fireEvent.click(await screen.findByRole('button', { name: T('notif.markRead') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/me/notifications/n1/read'));
    expect(api.page).toHaveBeenCalledWith('/me/notifications', { page: 0, size: 25 });
  });
});
