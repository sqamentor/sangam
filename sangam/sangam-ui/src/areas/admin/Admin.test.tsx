// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-047 admin area (Admin.tsx): every screen. The API client (src/api/client.ts http.*) is mocked; each test asserts what
 * the screen renders from API data, the exact request it sends (path, body, explicit primitives, reason), what it gates
 * (four eyes, required fields, impact preview) and how a refusal is shown (ProblemAlert by status and code). Every call
 * the tests see is checked against the REST contract (sangam-api.yaml) at the end, with the known gaps named.
 */
import { useEffect } from 'react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), page: vi.fn(), upload: vi.fn() }));
const auth = vi.hoisted(() => ({ userId: 'demo', roles: [] as string[] }));

vi.mock('../../api/client', async (orig) => {
  const real = await orig<typeof import('../../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post, put: api.put, del: api.del, page: api.page, upload: api.upload } };
});
vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ me: { userId: auth.userId, displayName: 'Demo', tenantId: 't1', roles: auth.roles, seats: [] }, seat: null, mode: 'dev', hasRole: (r: string) => auth.roles.includes(r) }),
}));
// The DMN editor (dmn-js) does not run in jsdom: a stand-in hands the screen an editor handle and reports a change.
vi.mock('../../components/Editors', () => ({
  EMPTY_DMN: '<dmn-empty/>',
  DmnEditor: ({ xml, onReady, onChange }: { xml: string; onReady: (h: { getContent: () => Promise<string> }) => void; onChange?: () => void }) => {
    useEffect(() => onReady({ getContent: async () => `${xml}<!-- edited -->` }), [onReady, xml]);
    return (
      <button type="button" data-testid="dmn" onClick={() => onChange?.()}>
        {xml}
      </button>
    );
  },
}));

import apiYaml from '../../../../sangam-rest/api/sangam-api.yaml?raw';
import { initI18n } from '../../i18n';
import { EMPTY_PAGE, KEY, L, T, buttonIn, contractOperations, dialog, noDialog, outsideContract, pageOf, problem, reasonAndConfirm, recordCalls, rowWith, show, type } from '../../test/screens';
import {
  AgentDefinitions,
  Assignments,
  ConversationalChannels,
  DelegationTableEditor,
  DelegationTables,
  Groups,
  IdentityProviders,
  ModuleSecurityDefaults,
  OrgHierarchy,
  Policies,
  RetentionClasses,
  RoutingStrategies,
  Seats,
  ServicePrincipals,
  ServiceRegistry,
  SlaModels,
  SodExceptions,
  Templates,
  Tenants,
  Users,
} from './Admin';

let routes: Record<string, unknown> = {};
let pages: Record<string, unknown> = {};
const seen = new Set<string>();

beforeAll(() => initI18n('en'));

beforeEach(() => {
  routes = {};
  pages = {};
  auth.userId = 'demo';
  auth.roles = [];
  for (const f of Object.values(api)) f.mockReset();
  const answer = (table: Record<string, unknown>, path: string, fallback: unknown) => {
    const v = table[path];
    if (v instanceof Error) throw v;
    return v ?? fallback;
  };
  api.get.mockImplementation(async (path: string) => answer(routes, path, []));
  api.page.mockImplementation(async (path: string) => answer(pages, path, EMPTY_PAGE));
  api.post.mockResolvedValue({});
  api.put.mockResolvedValue({});
  api.del.mockResolvedValue(undefined);
});
afterEach(() => {
  recordCalls(seen, api);
  cleanup();
});

/**
 * Every call these tests saw is an operation of the contract, except the named gaps: the Tenants, Seats, Users and
 * Groups screens are generic CrudPage screens whose edit and row actions call routes the contract does not have
 * (sangam-api.yaml has only GET /admin/users, GET /admin/groups, no tenant or seat actions). Reported (M-047 report,
 * TESTING-GAP-REGISTER); a new drift, or a gap that is closed, fails this check.
 */
const KNOWN_GAPS = ['POST /admin/seats/s1/deactivate', 'POST /admin/tenants/t-a/validate', 'POST /admin/tenants/t-b/activate', 'POST /admin/users', 'POST /admin/users/u1/disable'];
afterAll(() => {
  expect(outsideContract(seen, contractOperations(apiYaml))).toEqual(KNOWN_GAPS);
});

const lastCall = (fn: typeof api.post, path: string) => [...fn.mock.calls].reverse().find((c) => c[0] === path);

// ====================================================================== A01 tenants (CrudPage)

/** Evidence: Admin.tsx:45-76 (Tenants), CrudPage.tsx:66-209 (list, row actions, editor validation, reason dialog). */
describe('A01 tenants', () => {
  const tenants = [
    { id: 't-a', name: 'Alpha', status: 'ACTIVE', hostingProfile: 'SHARED', languages: ['en', 'hi'] },
    { id: 't-b', name: 'Beta', status: 'DRAFT', hostingProfile: 'DEDICATED_DB', languages: ['en'] },
  ];

  it('lists tenants with status, hosting and languages, and offers activate or suspend by status', async () => {
    pages['/admin/tenants'] = pageOf(tenants);
    show(<Tenants />);
    expect(await screen.findByText('Alpha')).toBeTruthy();
    expect(api.page).toHaveBeenCalledWith('/admin/tenants', { page: 0, size: 25, q: '', sort: undefined });
    const a = rowWith('Alpha');
    expect(within(a).getByText('en, hi')).toBeTruthy();
    expect(within(a).getByRole('button', { name: T('admin.suspendTenant') })).toBeTruthy();
    expect(within(a).queryByRole('button', { name: T('admin.activate') })).toBeNull();
    const b = rowWith('Beta');
    expect(within(b).getByRole('button', { name: T('admin.activate') })).toBeTruthy();
    expect(within(b).queryByRole('button', { name: T('admin.suspendTenant') })).toBeNull();
  });

  it('validate runs at once; activate asks for a reason and sends it', async () => {
    pages['/admin/tenants'] = pageOf(tenants);
    show(<Tenants />);
    fireEvent.click(within(rowWith((await screen.findByText('Alpha')).textContent!)).getByRole('button', { name: T('admin.validate') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/tenants/t-a/validate', {}));
    fireEvent.click(within(rowWith('Beta')).getByRole('button', { name: T('admin.activate') }));
    reasonAndConfirm('Go-live approved by the board');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/tenants/t-b/activate', { reason: 'Go-live approved by the board' }));
  });

  it('a new tenant needs an id of the allowed form and a name; nothing is sent until then', async () => {
    show(<Tenants />);
    fireEvent.click(await screen.findByRole('button', { name: T('common.new') }));
    const d = dialog();
    fireEvent.click(buttonIn(d, 'common.save'));
    expect(within(d).getAllByText(T('form.required')).length).toBe(2);
    type(d, 'admin.tenantId', 'Bad Id');
    type(d, 'common.name', 'Gamma');
    fireEvent.click(buttonIn(d, 'common.save'));
    expect(within(d).getByText(T('form.pattern'))).toBeTruthy();
    expect(api.post).not.toHaveBeenCalled();
    api.post.mockResolvedValueOnce({ id: 'gamma' });
    type(d, 'admin.tenantId', 'gamma');
    fireEvent.click(buttonIn(d, 'common.save'));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/tenants', { status: 'DRAFT', languages: ['en', 'hi'], id: 'gamma', name: 'Gamma' }));
    await waitFor(noDialog);
  });

  it('an edit is a PUT of the row; a stale revision (409) is shown in the editor', async () => {
    pages['/admin/tenants'] = pageOf(tenants);
    api.put.mockRejectedValueOnce(problem(409, 'REVISION_CONFLICT', 'Changed by someone else'));
    show(<Tenants />);
    fireEvent.click(within(rowWith(await screen.findByText('Beta').then((e) => e.textContent!))).getByRole('button', { name: T('common.edit') }));
    const d = dialog();
    type(d, 'common.name', 'Beta two');
    fireEvent.click(buttonIn(d, 'common.save'));
    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/admin/tenants/t-b', expect.objectContaining({ id: 't-b', name: 'Beta two', status: 'DRAFT' })));
    expect(await within(d).findByText(T('error.conflict'))).toBeTruthy();
    expect(within(d).getByText('Changed by someone else')).toBeTruthy();
  });
});

// ====================================================================== A02 org hierarchy

/** Evidence: Admin.tsx:134-297 (tree, detail, add child, move), endpoints.ts:570-578 (orgTree, saveOrgUnit, moveOrgUnit, importOrg). */
describe('A02 org hierarchy', () => {
  const units = [
    { id: 'm1', name: 'Ministry of Finance', level: 'MINISTRY', validFrom: '2026-01-01', active: true },
    { id: 'd1', name: 'Treasury', level: 'DEPARTMENT', parentId: 'm1', validFrom: '2026-01-01', active: true },
  ];

  it('builds the tree from the flat list, expands with the keyboard and shows the seats of a selected unit', async () => {
    pages['/admin/org-units'] = pageOf(units);
    pages['/admin/seats'] = pageOf([{ id: 's1', designation: 'Treasury Officer', orgUnitId: 'd1', active: true }]);
    show(<OrgHierarchy />);
    const root = await screen.findByText('Ministry of Finance');
    expect(api.page).toHaveBeenCalledWith('/admin/org-units', { activeOn: undefined, includeInactive: true, size: 500 });
    expect(screen.queryByText('Treasury')).toBeNull();
    fireEvent.keyDown(root, { key: 'ArrowRight' });
    fireEvent.click(await screen.findByText('Treasury'));
    expect(await screen.findByText(`Treasury Officer — ${T('admin.vacant')}`)).toBeTruthy();
    expect(api.page).toHaveBeenCalledWith('/admin/seats', { page: 0, size: 25, orgUnitId: 'd1' });
  });

  it('add child proposes the level below the selected unit and posts it under it', async () => {
    pages['/admin/org-units'] = pageOf(units);
    api.post.mockResolvedValueOnce({ id: 'x1' });
    show(<OrgHierarchy />);
    fireEvent.click(await screen.findByText('Ministry of Finance'));
    fireEvent.click(screen.getByRole('button', { name: T('admin.addChild') }));
    const d = dialog();
    expect((within(d).getByLabelText(L('admin.level')) as HTMLSelectElement).value).toBe('DEPARTMENT');
    const save = buttonIn(d, 'common.save');
    expect(save.disabled).toBe(true);
    type(d, 'common.name', 'Budget Directorate');
    fireEvent.click(save);
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/admin/org-units', expect.objectContaining({ parentId: 'm1', level: 'DEPARTMENT', name: 'Budget Directorate', active: true })),
    );
  });

  it('move is effective-dated and carries the reason', async () => {
    pages['/admin/org-units'] = pageOf(units);
    show(<OrgHierarchy />);
    fireEvent.click(await screen.findByText('Ministry of Finance'));
    fireEvent.click(screen.getByRole('button', { name: T('admin.move') }));
    const d = dialog();
    type(d, 'admin.newParent', 'root-2');
    type(d, 'admin.effectiveFrom', '2026-12-01');
    reasonAndConfirm('Reorganisation order 12');
    await waitFor(() => expect(api.put).toHaveBeenCalledWith(`/admin/org-units/m1?reason=${encodeURIComponent('Reorganisation order 12')}`, { parentId: 'root-2', validFrom: '2026-12-01' }));
  });

  it('a CSV import reports created, updated and the rows in error', async () => {
    pages['/admin/org-units'] = pageOf(units);
    api.upload.mockResolvedValueOnce({ created: 2, updated: 1, errors: ['row 4: unknown parent'] });
    const { container } = show(<OrgHierarchy />);
    await screen.findByText('Ministry of Finance');
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(['id,name'], 'units.csv', { type: 'text/csv' })] } });
    expect(await screen.findByText(T('admin.importResult', { c: 2, u: 1 }))).toBeTruthy();
    expect(screen.getByText('row 4: unknown parent')).toBeTruthy();
    expect(api.upload.mock.calls[0][0]).toBe('/admin/org-units/import');
    expect((api.upload.mock.calls[0][1] as FormData).get('file')).toBeInstanceOf(File);
  });
});

// ====================================================================== seats (CrudPage)

/** Evidence: Admin.tsx:301-325. */
describe('seats', () => {
  it('a vacant seat says so and only an active seat can be deactivated, with a reason', async () => {
    pages['/admin/seats'] = pageOf([
      { id: 's1', designation: 'Section Officer', orgUnitName: 'Treasury', active: true },
      { id: 's2', designation: 'Clerk', orgUnitName: 'Treasury', holderName: 'Asha', active: false },
    ]);
    show(<Seats />);
    const s1 = rowWith(await screen.findByText('Section Officer').then((e) => e.textContent!));
    expect(within(s1).getByText(T('admin.vacant'))).toBeTruthy();
    expect(within(rowWith('Clerk')).queryByRole('button', { name: T('admin.deactivate') })).toBeNull();
    fireEvent.click(within(s1).getByRole('button', { name: T('admin.deactivate') }));
    reasonAndConfirm('Post abolished by order 7');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/seats/s1/deactivate', { reason: 'Post abolished by order 7' }));
  });
});

// ====================================================================== A03 assignments

/** Evidence: Admin.tsx:329-512 (filters, impact preview gate, save, relieve, approve/reject), endpoints.ts:580-585. */
describe('A03 assignments', () => {
  it('filters by kind and date (activeOn) and offers approve / reject only on pending rows', async () => {
    pages['/admin/assignments'] = pageOf([
      { id: 'a1', personId: 'p1', personName: 'Ravi', seatId: 's1', seatName: 'SO', kind: 'ADDITIONAL_CHARGE', validFrom: '2026-01-01', status: 'PENDING' },
      { id: 'a2', personId: 'p2', personName: 'Meena', seatId: 's2', seatName: 'AO', kind: 'SUBSTANTIVE', validFrom: '2026-01-01', status: 'ACTIVE' },
    ]);
    show(<Assignments />);
    const pending = rowWith(await screen.findByText('Ravi').then((e) => e.textContent!));
    expect(within(pending).getByText(T('seat.kind.ADDITIONAL_CHARGE'))).toBeTruthy();
    expect(within(pending).getByRole('button', { name: T('admin.approve') })).toBeTruthy();
    expect(within(rowWith('Meena')).queryByRole('button', { name: T('admin.approve') })).toBeNull();
    fireEvent.change(screen.getByLabelText(L('admin.kind')), { target: { value: 'LEAVE_SUBSTITUTE' } });
    fireEvent.change(screen.getByLabelText(L('admin.asOf')), { target: { value: '2026-11-15' } });
    await waitFor(() =>
      expect(api.page).toHaveBeenLastCalledWith('/admin/assignments', { page: 0, size: 25, kind: 'LEAVE_SUBSTITUTE', q: '', activeOn: '2026-11-15', asOf: undefined }),
    );
  });

  it('save needs a fresh impact preview of what is saved: a change of kind or dates after the preview asks for it again', async () => {
    api.post.mockImplementation(async (path: string) =>
      path === '/admin/assignments/impact' ? { openTasks: 3, sodConflicts: [{ taskId: 'task-9', reason: 'maker of bill 9' }], summary: ['Holds two seats'] } : { id: 'a9' },
    );
    show(<Assignments />);
    fireEvent.click(await screen.findByRole('button', { name: T('admin.newAssignment') }));
    const d = dialog();
    const preview = buttonIn(d, 'admin.previewImpact');
    const save = buttonIn(d, 'common.save');
    expect(preview.disabled).toBe(true);
    fireEvent.change(within(d).getByLabelText(L('admin.kind')), { target: { value: 'ADDITIONAL_CHARGE' } });
    type(d, 'admin.personId', 'p1');
    type(d, 'admin.seatId', 's1');
    type(d, 'admin.validTo', '2026-12-31');
    type(d, 'common.reason', 'Officer on leave');
    expect(within(d).getByText(T('admin.actedForHint'))).toBeTruthy();
    expect(save.disabled).toBe(true);
    fireEvent.click(preview);
    expect(await within(d).findByText('Holds two seats')).toBeTruthy();
    expect(within(d).getByText(T('admin.openTasksAffected', { n: 3 }))).toBeTruthy();
    expect(within(d).getByRole('link', { name: 'task-9' }).getAttribute('href')).toBe('/tasks/task-9');
    expect(lastCall(api.post, '/admin/assignments/impact')?.[1]).toEqual({ kind: 'ADDITIONAL_CHARGE', personId: 'p1', seatId: 's1', validFrom: expect.any(String), validTo: '2026-12-31', reason: 'Officer on leave' });
    expect(save.disabled).toBe(false);
    // the period changes after the preview: the preview no longer describes what would be saved
    type(d, 'admin.validTo', '2027-03-31');
    expect(save.disabled).toBe(true);
    expect(within(d).queryByText('Holds two seats')).toBeNull();
    fireEvent.click(preview);
    await within(d).findByText('Holds two seats');
    fireEvent.click(save);
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/assignments', expect.objectContaining({ kind: 'ADDITIONAL_CHARGE', validTo: '2027-03-31', reason: 'Officer on leave' }), KEY));
  });

  it('a change of kind after the preview also asks for it again', async () => {
    api.post.mockResolvedValue({ openTasks: 0, sodConflicts: [], summary: ['One seat'] });
    show(<Assignments />);
    fireEvent.click(await screen.findByRole('button', { name: T('admin.newAssignment') }));
    const d = dialog();
    type(d, 'admin.personId', 'p1');
    type(d, 'admin.seatId', 's1');
    type(d, 'common.reason', 'New posting');
    fireEvent.click(buttonIn(d, 'admin.previewImpact'));
    await within(d).findByText('One seat');
    expect(buttonIn(d, 'common.save').disabled).toBe(false);
    fireEvent.change(within(d).getByLabelText(L('admin.kind')), { target: { value: 'LEAVE_SUBSTITUTE' } });
    expect(buttonIn(d, 'common.save').disabled).toBe(true);
  });

  it('relieve ends on the chosen date; approve sends the reason; a self-approval refusal is shown', async () => {
    pages['/admin/assignments'] = pageOf([
      { id: 'a1', personId: 'p1', personName: 'Ravi', seatId: 's1', kind: 'ADDITIONAL_CHARGE', validFrom: '2026-01-01', status: 'PENDING' },
      { id: 'a2', personId: 'p2', personName: 'Meena', seatId: 's2', kind: 'SUBSTANTIVE', validFrom: '2026-01-01', status: 'ACTIVE' },
    ]);
    show(<Assignments />);
    fireEvent.click(within(rowWith(await screen.findByText('Meena').then((e) => e.textContent!))).getByRole('button', { name: T('admin.relieve') }));
    type(dialog(), 'admin.validTo', '2026-11-30');
    reasonAndConfirm('Transferred out');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/assignments/a2/end', { validTo: '2026-11-30', endDate: '2026-11-30', reason: 'Transferred out' }));
    api.post.mockRejectedValueOnce(problem(403, 'SOD_SELF_APPROVAL', 'You requested this posting'));
    fireEvent.click(within(rowWith('Ravi')).getByRole('button', { name: T('admin.approve') }));
    expect(within(dialog()).getByText(T('admin.noSelfApproval'))).toBeTruthy();
    const d = reasonAndConfirm('Checked the order');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/assignments/a1/approve', { reason: 'Checked the order' }));
    expect(await within(d).findByText('You requested this posting')).toBeTruthy();
  });
});

// ====================================================================== A04 delegation-of-powers tables

/** Evidence: Admin.tsx:516-688 (list, new, editor: save, check, submit, four eyes), endpoints.ts:587-593. */
describe('A04 delegation tables', () => {
  const table = (extra: Record<string, unknown> = {}) => ({
    id: 'pt1',
    key: 'powers-1',
    name: 'Sanction powers',
    version: 2,
    state: 'DRAFT',
    author: 'demo',
    dmnXml: '<dmn/>',
    testCases: [{ id: 'tc1', inputs: { amount: '100' }, expected: { level: 'SO' } }],
    ...extra,
  });
  const openEditor = () => show(<DelegationTableEditor />, '/admin/delegations/pt1', '/admin/delegations/:id');

  it('lists the tables and a new table is created with the empty DMN, then opened', async () => {
    pages['/admin/power-tables'] = pageOf([table({ state: 'ACTIVE', approver: 'boss' })]);
    api.post.mockResolvedValueOnce({ id: 'pt9' });
    show(<DelegationTables />, '/admin/delegations', '/admin/delegations');
    expect((await screen.findByRole('link', { name: 'Sanction powers' })).getAttribute('href')).toBe('/admin/delegations/pt1');
    expect(within(rowWith('Sanction powers')).getByText('boss')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: T('common.new') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/power-tables', { key: expect.stringMatching(/^powers-\d+$/), name: T('admin.newTable'), dmnXml: '<dmn-empty/>' }));
    expect(await screen.findByTestId('elsewhere')).toBeTruthy();
  });

  it('save sends the editor XML and the edited test cases; submit is blocked while unsaved', async () => {
    routes['/admin/power-tables/pt1'] = table();
    api.put.mockImplementation(async (_p: string, body: Record<string, unknown>) => body);
    openEditor();
    const save = (await screen.findByRole('button', { name: T('common.save') })) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.click(screen.getByTestId('dmn'));
    expect((screen.getByRole('button', { name: T('admin.submitForApproval') }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(L('admin.testCasesJson')), { target: { value: '[{"id":"tc2","inputs":{"amount":"5000"},"expected":{"level":"AO"}}]' } });
    fireEvent.click(save);
    await waitFor(() =>
      expect(api.put).toHaveBeenCalledWith(
        '/admin/power-tables/pt1',
        expect.objectContaining({ id: 'pt1', dmnXml: '<dmn/><!-- edited -->', testCases: [{ id: 'tc2', inputs: { amount: '5000' }, expected: { level: 'AO' } }] }),
      ),
    );
    await waitFor(() => expect((screen.getByRole('button', { name: T('admin.submitForApproval') }) as HTMLButtonElement).disabled).toBe(false));
  });

  it('test cases that are not JSON, or not a list of {id, inputs, expected}, are refused by name and nothing is saved', async () => {
    routes['/admin/power-tables/pt1'] = table();
    openEditor();
    const save = (await screen.findByRole('button', { name: T('common.save') })) as HTMLButtonElement;
    fireEvent.click(screen.getByTestId('dmn'));
    fireEvent.change(screen.getByLabelText(L('admin.testCasesJson')), { target: { value: '[{"id":"tc2",' } });
    fireEvent.click(save);
    expect(await screen.findByText(T('mod.common.invalidJson'))).toBeTruthy();
    fireEvent.change(screen.getByLabelText(L('admin.testCasesJson')), { target: { value: '[{"id":"tc2","inputs":"5000"}]' } });
    fireEvent.click(save);
    expect(await screen.findByText(T('form.jsonListOfObjects'))).toBeTruthy();
    expect(api.put).not.toHaveBeenCalled();
  });
  it('the check shows gaps, overlaps and each test case result', async () => {
    routes['/admin/power-tables/pt1'] = table();
    api.post.mockResolvedValueOnce({ hitPolicy: 'UNIQUE', gaps: ['amount > 1 crore'], overlaps: ['rule 2 and 3'], tests: [{ id: 'tc1', passed: false, actual: { level: 'AO' } }] });
    openEditor();
    fireEvent.click(await screen.findByRole('button', { name: T('admin.checkTable') }));
    expect(await screen.findByText('amount > 1 crore')).toBeTruthy();
    expect(api.post).toHaveBeenCalledWith('/admin/power-tables/pt1/check');
    expect(screen.getByText('rule 2 and 3')).toBeTruthy();
    expect(screen.getByText('UNIQUE')).toBeTruthy();
    expect(screen.getByText('{"level":"AO"}')).toBeTruthy();
    expect(screen.queryByText(T('admin.noGaps'))).toBeNull();
  });

  it('four eyes: the author of a table in review cannot approve it', async () => {
    routes['/admin/power-tables/pt1'] = table({ state: 'IN_REVIEW', author: 'demo' });
    openEditor();
    expect(await screen.findByText(T('admin.authorCannotApprove'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: T('admin.approve') })).toBeNull();
    expect(screen.queryByRole('button', { name: T('common.save') })).toBeNull();
    expect((screen.getByLabelText(L('admin.testCasesJson')) as HTMLTextAreaElement).disabled).toBe(true);
  });

  it('another reviewer approves with an effective date (sent as an instant) and a reason', async () => {
    routes['/admin/power-tables/pt1'] = table({ state: 'IN_REVIEW', author: 'maker' });
    api.post.mockResolvedValueOnce(table({ state: 'APPROVED', author: 'maker', approver: 'demo' }));
    openEditor();
    fireEvent.click(await screen.findByRole('button', { name: T('admin.approve') }));
    type(dialog(), 'admin.effectiveFrom', '2026-12-01T09:30');
    reasonAndConfirm('Matches the finance rules');
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/admin/power-tables/pt1/approve', { reason: 'Matches the finance rules', effectiveFrom: new Date('2026-12-01T09:30').toISOString() }),
    );
    await waitFor(noDialog);
    expect(screen.getAllByText(T('enum.APPROVED')).length).toBeGreaterThan(0);
  });

  it('a table that cannot be read is shown with a retry', async () => {
    routes['/admin/power-tables/pt1'] = problem(404, 'NOT_FOUND');
    openEditor();
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('button', { name: T('common.retry') })).toBeTruthy();
  });
});

// ====================================================================== A08 policies

/** Evidence: Admin.tsx:695-851 (filters, explain, editor, delete), endpoints.ts:597-607 (edit = create + delete old). */
describe('A08 policies', () => {
  const pol = { id: 'po1', effect: 'DENY', principalType: 'GROUP', principalId: 'clerks', resourceType: 'PROCESS_INSTANCE', resourceId: '*', permissions: ['DELETE_INSTANCE'], policyVersion: 7 };

  it('lists policies (deny wins is stated) and filters by effect and resource type', async () => {
    pages['/admin/policies'] = pageOf([pol]);
    show(<Policies />);
    expect(await screen.findByText('GROUP:clerks')).toBeTruthy();
    expect(screen.getByText(T('admin.denyWins'))).toBeTruthy();
    expect(within(rowWith('GROUP:clerks')).getByText('PROCESS_INSTANCE/*')).toBeTruthy();
    fireEvent.change(screen.getByLabelText(L('admin.effect')), { target: { value: 'DENY' } });
    await waitFor(() => expect(api.page).toHaveBeenLastCalledWith('/admin/policies', { page: 0, size: 25, effect: 'DENY', resourceType: undefined }));
  });

  it('explain sends the question and shows the decision with the matched policies', async () => {
    api.post.mockResolvedValueOnce({ allowed: false, matched: [{ policyId: 'po1', effect: 'DENY', scope: 'tenant' }], policyVersion: 7 });
    show(<Policies />);
    const run = (await screen.findByRole('button', { name: T('admin.explainRun') })) as HTMLButtonElement;
    expect(run.disabled).toBe(true);
    const section = run.closest('section') as HTMLElement;
    type(section, 'admin.principal', 'ravi');
    fireEvent.change(within(section).getByLabelText(L('admin.action')), { target: { value: 'DELETE_INSTANCE' } });
    fireEvent.click(run);
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/policies/explain', { principalId: 'ravi', action: 'DELETE_INSTANCE', resourceType: 'PROCESS_INSTANCE', resourceId: '*' }));
    expect(await screen.findByText(T('admin.denied'))).toBeTruthy();
    expect(screen.getByText(/po1 · tenant/)).toBeTruthy();
  });

  it('an edit creates the new version and removes the old one, so history stays', async () => {
    pages['/admin/policies'] = pageOf([pol]);
    api.post.mockResolvedValueOnce({ ...pol, id: 'po2' });
    show(<Policies />);
    fireEvent.click(within(rowWith(await screen.findByText('GROUP:clerks').then((e) => e.textContent!))).getByRole('button', { name: T('common.edit') }));
    const d = dialog();
    fireEvent.click(within(d).getByLabelText('READ'));
    fireEvent.click(buttonIn(d, 'common.save'));
    await waitFor(() => expect(api.del).toHaveBeenCalledWith('/admin/policies/po1', { reason: 'replaced by po2' }));
    expect(api.post).toHaveBeenCalledWith('/admin/policies', { ...pol, id: undefined, permissions: ['DELETE_INSTANCE', 'READ'] });
  });

  it('delete asks for a reason', async () => {
    pages['/admin/policies'] = pageOf([pol]);
    show(<Policies />);
    fireEvent.click(within(rowWith(await screen.findByText('GROUP:clerks').then((e) => e.textContent!))).getByRole('button', { name: T('common.delete') }));
    reasonAndConfirm('Superseded by the new rules');
    await waitFor(() => expect(api.del).toHaveBeenCalledWith('/admin/policies/po1', { reason: 'Superseded by the new rules' }));
  });
});

// ====================================================================== SoD exceptions

/** Evidence: Admin.tsx:853-951, endpoints.ts:608-610. */
describe('SoD exceptions', () => {
  it('a request needs person, expiry and reason, and sends the expiry as an instant', async () => {
    show(<SodExceptions />);
    fireEvent.click(await screen.findByRole('button', { name: T('admin.requestException') }));
    const d = dialog();
    const submit = buttonIn(d, 'common.submit');
    expect(submit.disabled).toBe(true);
    fireEvent.change(within(d).getByLabelText(L('admin.rule')), { target: { value: 'RECON_RESOLVER' } });
    type(d, 'admin.personId', 'p7');
    type(d, 'admin.expires', '2026-12-31T18:00');
    type(d, 'common.reason', 'Only trained resolver this month');
    expect(within(d).getByText(T('admin.higherAuthority'))).toBeTruthy();
    fireEvent.click(submit);
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/admin/sod-exceptions', { rule: 'RECON_RESOLVER', personId: 'p7', reason: 'Only trained resolver this month', expiresAt: new Date('2026-12-31T18:00').toISOString() }),
    );
  });

  it('only a pending exception is decided, with a reason', async () => {
    pages['/admin/sod-exceptions'] = pageOf([
      { id: 'x1', rule: 'MAKER_CHECKER', personId: 'p1', personName: 'Ravi', reason: 'r', expiresAt: '2026-12-01T00:00:00Z', status: 'PENDING' },
      { id: 'x2', rule: 'SAME_SEAT', personId: 'p2', personName: 'Meena', reason: 'r', expiresAt: '2026-12-01T00:00:00Z', status: 'APPROVED' },
    ]);
    show(<SodExceptions />);
    const r1 = rowWith(await screen.findByText('Ravi').then((e) => e.textContent!));
    expect(within(r1).getByText(T('sod.rule.MAKER_CHECKER'))).toBeTruthy();
    expect(within(rowWith('Meena')).queryByRole('button', { name: T('admin.reject') })).toBeNull();
    fireEvent.click(within(r1).getByRole('button', { name: T('admin.reject') }));
    reasonAndConfirm('No business need shown');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/sod-exceptions/x1/reject', { reason: 'No business need shown' }));
  });
});

// ====================================================================== users and groups

/** Evidence: Admin.tsx:955-1026, endpoints.ts:611-613. */
describe('users and groups', () => {
  it('users: a new user starts active and LOCAL; disable is offered on active users only', async () => {
    pages['/admin/users'] = pageOf([
      { id: 'u1', username: 'ravi', displayName: 'Ravi', active: true, source: 'LDAP' },
      { id: 'u2', username: 'old', displayName: 'Old', active: false, source: 'LOCAL' },
    ]);
    show(<Users />);
    const r1 = rowWith(await screen.findByText('ravi').then((e) => e.textContent!));
    expect(within(rowWith('old')).queryByRole('button', { name: T('admin.disable') })).toBeNull();
    fireEvent.click(within(r1).getByRole('button', { name: T('admin.disable') }));
    reasonAndConfirm('Left the department');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/users/u1/disable', { reason: 'Left the department' }));
    fireEvent.click(screen.getByRole('button', { name: T('common.new') }));
    const d = dialog();
    expect((within(d).getByLabelText(L('admin.activeFlag')) as HTMLInputElement).checked).toBe(true);
    type(d, 'admin.username', 'asha');
    type(d, 'common.name', 'Asha');
    fireEvent.click(buttonIn(d, 'common.save'));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/users', { active: true, source: 'LOCAL', username: 'asha', displayName: 'Asha' }));
  });

  it('groups: members of the typed group are listed, added and removed', async () => {
    routes['/admin/groups/g1/members'] = [{ id: 'u1', username: 'ravi', displayName: 'Ravi', active: true }];
    show(<Groups />);
    const section = (await screen.findByRole('heading', { name: T('admin.membership') })).closest('section') as HTMLElement;
    expect((within(section).getByRole('button', { name: T('admin.addMember') }) as HTMLButtonElement).disabled).toBe(true);
    type(section, 'admin.groupId', 'g1');
    expect(await within(section).findByText(/Ravi \(ravi\)/)).toBeTruthy();
    type(section, 'admin.personId', 'u5');
    fireEvent.click(within(section).getByRole('button', { name: T('admin.addMember') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/groups/g1/members/u5'));
    fireEvent.click(within(section).getByRole('button', { name: T('common.remove') }));
    await waitFor(() => expect(api.del).toHaveBeenCalledWith('/admin/groups/g1/members/u1'));
  });
});

// ====================================================================== A05 identity providers

/** Evidence: Admin.tsx:1031-1151 (idpTimeoutProblem, save with explicit primitives, test, LDAP sync), endpoints.ts:614-618. */
describe('A05 identity providers', () => {
  const ldap = { id: 'i1', name: 'State LDAP', protocol: 'LDAP', attributeMapping: { username: 'uid' }, mfaRequired: false, sessionTimeoutMin: 20, status: 'TESTED' };

  it('a new provider is sent with the mapping as an object and mfaRequired / sessionTimeoutMin as explicit values', async () => {
    show(<IdentityProviders />);
    fireEvent.click(await screen.findByRole('button', { name: T('common.new') }));
    const d = dialog();
    type(d, 'common.name', 'Jan Parichay');
    type(d, 'admin.issuer', 'https://idp.example.gov.in');
    fireEvent.click(within(d).getByLabelText(L('admin.mfa')));
    type(d, 'admin.sessionTimeout', '45');
    fireEvent.click(buttonIn(d, 'common.save'));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/admin/identity-providers', {
        protocol: 'OIDC',
        name: 'Jan Parichay',
        issuer: 'https://idp.example.gov.in',
        attributeMapping: { username: 'preferred_username', tenant: 'tenant' },
        mfaRequired: false,
        sessionTimeoutMin: 45,
      }),
    );
  });

  it('an empty or fractional session timeout is refused by name before any call', async () => {
    show(<IdentityProviders />);
    fireEvent.click(await screen.findByRole('button', { name: T('common.new') }));
    const d = dialog();
    type(d, 'admin.sessionTimeout', '');
    fireEvent.click(buttonIn(d, 'common.save'));
    expect(within(d).getByText(T('form.required'))).toBeTruthy();
    type(d, 'admin.sessionTimeout', '12.5');
    expect(within(d).getByText(T('form.wholeNumber'))).toBeTruthy();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('an attribute mapping that is not valid JSON is refused, never sent as an empty mapping', async () => {
    routes['/admin/identity-providers'] = [ldap];
    show(<IdentityProviders />);
    fireEvent.click(within(rowWith(await screen.findByText('State LDAP').then((e) => e.textContent!))).getByRole('button', { name: T('common.edit') }));
    const d = dialog();
    expect((within(d).getByLabelText(L('admin.attributeMapping')) as HTMLTextAreaElement).value).toBe('{"username":"uid"}');
    type(d, 'admin.attributeMapping', '{"username": "uid",');
    fireEvent.click(buttonIn(d, 'common.save'));
    expect(within(d).getByText(T('mod.common.invalidJson'))).toBeTruthy();
    expect(api.put).not.toHaveBeenCalled();
    type(d, 'admin.attributeMapping', '{"username": "uid", "tenant": "o"}');
    fireEvent.click(buttonIn(d, 'common.save'));
    await waitFor(() =>
      expect(api.put).toHaveBeenCalledWith('/admin/identity-providers/i1', { ...ldap, attributeMapping: { username: 'uid', tenant: 'o' }, mfaRequired: false, sessionTimeoutMin: 20 }),
    );
  });

  it('a test login reports the server message; an LDAP provider can be synced', async () => {
    routes['/admin/identity-providers'] = [ldap, { ...ldap, id: 'i2', name: 'Citizen OIDC', protocol: 'OIDC' }];
    api.post.mockImplementation(async (path: string) => (path.endsWith('/test') ? { ok: true, message: 'Login worked for test user' } : { users: 120, groups: 8 }));
    show(<IdentityProviders />);
    const r1 = rowWith(await screen.findByText('State LDAP').then((e) => e.textContent!));
    expect(within(rowWith('Citizen OIDC')).queryByRole('button', { name: T('admin.syncNow') })).toBeNull();
    fireEvent.click(within(r1).getByRole('button', { name: T('admin.testLogin') }));
    expect(await screen.findByText('Login worked for test user')).toBeTruthy();
    expect(api.post).toHaveBeenCalledWith('/admin/identity-providers/i1/test');
    fireEvent.click(within(rowWith('State LDAP')).getByRole('button', { name: T('admin.syncNow') }));
    expect(await screen.findByText(T('admin.synced', { u: 120, g: 8 }))).toBeTruthy();
    expect(api.post).toHaveBeenCalledWith('/admin/identity-providers/i1/sync');
  });
});

// ====================================================================== I03 service principals

/** Evidence: Admin.tsx:1153-1271 (create with lists, secret shown once, rotate, revoke), endpoints.ts:619-622. */
describe('I03 service principals', () => {
  it('a new principal sends scopes and topics as lists and the expiry as an instant; the secret is shown once', async () => {
    api.post.mockResolvedValueOnce({ id: 'sp1', name: 'pfms-bridge', scopes: [], topics: [], status: 'PENDING', secret: 's3cr3t-once' });
    show(<ServicePrincipals />);
    fireEvent.click(await screen.findByRole('button', { name: T('common.new') }));
    const d = dialog();
    type(d, 'common.name', 'pfms-bridge');
    type(d, 'admin.scopes', 'ops.read, ops.write ,');
    type(d, 'ops.topics', 'payments');
    type(d, 'admin.expires', '2027-03-31');
    fireEvent.click(buttonIn(d, 'common.save'));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/admin/service-principals', { name: 'pfms-bridge', scopes: ['ops.read', 'ops.write'], topics: ['payments'], expiresAt: new Date('2027-03-31').toISOString() }),
    );
    expect(await screen.findByText(T('admin.secretOnce'))).toBeTruthy();
    expect(screen.getByText('s3cr3t-once')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: T('common.close') }));
    expect(screen.queryByText('s3cr3t-once')).toBeNull();
  });

  it('rotate and revoke only on active principals; revoke asks for a reason', async () => {
    routes['/admin/service-principals'] = [
      { id: 'sp1', name: 'pfms-bridge', scopes: ['ops.read'], topics: [], status: 'ACTIVE' },
      { id: 'sp2', name: 'old-bridge', scopes: [], topics: [], status: 'REVOKED' },
    ];
    api.post.mockResolvedValueOnce({ secret: 'rotated-secret' });
    show(<ServicePrincipals />);
    const r1 = rowWith(await screen.findByText('pfms-bridge').then((e) => e.textContent!));
    expect(within(rowWith('old-bridge')).queryByRole('button', { name: T('admin.rotate') })).toBeNull();
    fireEvent.click(within(r1).getByRole('button', { name: T('admin.rotate') }));
    expect(await screen.findByText('rotated-secret')).toBeTruthy();
    expect(api.post).toHaveBeenCalledWith('/admin/service-principals/sp1/rotate');
    fireEvent.click(within(rowWith('pfms-bridge')).getByRole('button', { name: T('admin.revoke') }));
    reasonAndConfirm('Key exposed in a log');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/service-principals/sp1/revoke', { reason: 'Key exposed in a log' }));
  });
});

// ====================================================================== registry screens (CrudPage over RegistryController)

/**
 * Evidence: Admin.tsx:1275-1475; gen_openapi.py REG (GET/POST /admin/{kind}, PUT/DELETE ?reason /admin/{kind}/{id},
 * POST /admin/{kind}/{id}/{action} with action activate | retire | test); RegistryController.java:78 maps only those.
 */
describe('registry screens', () => {
  const screens = [
    { name: 'service registry', ui: <ServiceRegistry />, path: '/admin/services', title: 'admin.servicesTitle' },
    { name: 'SLA models', ui: <SlaModels />, path: '/admin/sla-models', title: 'admin.slaTitle' },
    { name: 'routing strategies', ui: <RoutingStrategies />, path: '/admin/routing-strategies', title: 'admin.routingTitle' },
    { name: 'templates', ui: <Templates />, path: '/admin/templates', title: 'admin.templatesTitle' },
    { name: 'conversational channels', ui: <ConversationalChannels />, path: '/admin/conversation-channels', title: 'admin.channelsTitle' },
    { name: 'agent definitions', ui: <AgentDefinitions />, path: '/admin/agents', title: 'admin.agentsTitle' },
    { name: 'retention classes', ui: <RetentionClasses />, path: '/admin/retention-classes', title: 'admin.retentionTitle' },
  ];

  it.each(screens)('$name: lists its entries, refuses a new entry without the required fields and deletes with a reason when allowed', async ({ ui, path, title }) => {
    pages[path] = pageOf([{ id: 'e1', key: 'entry-one' }]);
    show(ui);
    expect(await screen.findByRole('heading', { name: T(title) })).toBeTruthy();
    expect(await screen.findByText('entry-one')).toBeTruthy();
    expect(api.page).toHaveBeenCalledWith(path, { page: 0, size: 25, q: '', sort: undefined });
    fireEvent.click(screen.getByRole('button', { name: T('common.new') }));
    const d = dialog();
    fireEvent.click(buttonIn(d, 'common.save'));
    expect(within(d).getAllByText(T('form.required')).length).toBeGreaterThan(0);
    expect(api.post).not.toHaveBeenCalled();
    fireEvent.click(buttonIn(d, 'common.cancel'));
    const del = within(rowWith('entry-one')).queryByRole('button', { name: T('common.delete') });
    if (path === '/admin/retention-classes') {
      expect(del).toBeNull();
      return;
    }
    fireEvent.click(del!);
    reasonAndConfirm('Entry no longer used');
    await waitFor(() => expect(api.del).toHaveBeenCalledWith(`${path}/e1`, { reason: 'Entry no longer used' }));
  });

  it('service registry: a new service sends its connector, endpoint and capability class', async () => {
    show(<ServiceRegistry />);
    fireEvent.click(await screen.findByRole('button', { name: T('common.new') }));
    const d = dialog();
    type(d, 'ops.key', 'pfms');
    type(d, 'common.name', 'PFMS');
    fireEvent.change(within(d).getByLabelText(L('admin.connector')), { target: { value: 'PFMS' } });
    type(d, 'admin.endpoint', 'https://pfms.example.gov.in/api');
    fireEvent.click(within(d).getByLabelText(T('recon.classLabel.b')));
    fireEvent.click(buttonIn(d, 'common.save'));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/services', { key: 'pfms', name: 'PFMS', connector: 'PFMS', endpoint: 'https://pfms.example.gov.in/api', capabilityClass: 'b' }));
  });

  it('routing strategies: the claim limit is a number and below its minimum is refused', async () => {
    show(<RoutingStrategies />);
    fireEvent.click(await screen.findByRole('button', { name: T('common.new') }));
    const d = dialog();
    type(d, 'ops.key', 'rr');
    fireEvent.change(within(d).getByLabelText(L('admin.strategy')), { target: { value: 'ROUND_ROBIN' } });
    type(d, 'admin.claimLimit', '0');
    fireEvent.click(buttonIn(d, 'common.save'));
    expect(within(d).getByText(T('form.min', { n: 1 }))).toBeTruthy();
    expect(api.post).not.toHaveBeenCalled();
    type(d, 'admin.claimLimit', '3');
    fireEvent.click(buttonIn(d, 'common.save'));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/routing-strategies', { key: 'rr', strategy: 'ROUND_ROBIN', claimLimit: 3 }));
  });

  it('templates: test send runs the registry "test" action and retire asks for a reason', async () => {
    pages['/admin/templates'] = pageOf([{ id: 'tp1', key: 'sms-otp', kind: 'SMS', version: 2 }]);
    show(<Templates />);
    const r = rowWith(await screen.findByText('sms-otp').then((e) => e.textContent!));
    fireEvent.click(within(r).getByRole('button', { name: T('admin.testSend') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/templates/tp1/test', {}));
    fireEvent.click(within(rowWith('sms-otp')).getByRole('button', { name: T('admin.retire') }));
    reasonAndConfirm('Replaced by version 3');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/templates/tp1/retire', { reason: 'Replaced by version 3' }));
  });

  it('templates: every row action is one the registry route accepts (activate, retire, test)', async () => {
    pages['/admin/templates'] = pageOf([{ id: 'tp1', key: 'sms-otp' }]);
    show(<Templates />);
    const r = rowWith(await screen.findByText('sms-otp').then((e) => e.textContent!));
    fireEvent.click(within(r).getByRole('button', { name: T('admin.testSend') }));
    await waitFor(() => expect(api.post).toHaveBeenCalled());
    for (const [p] of api.post.mock.calls) expect(String(p)).toMatch(/^\/admin\/templates\/tp1\/(activate|retire|test)$/);
  });

  it('a refused list is shown with a retry', async () => {
    pages['/admin/agents'] = problem(403, 'FORBIDDEN');
    show(<AgentDefinitions />);
    expect(await screen.findByText(T('error.notAvailable'))).toBeTruthy();
    expect(screen.getByRole('button', { name: T('common.retry') })).toBeTruthy();
  });
});

// ====================================================================== M-032 module security defaults

/** Evidence: Admin.tsx:1492-1524 (POST /admin/security/module-defaults {selfService}); also in w2c-screens.test.tsx. */
describe('M-032 module security defaults', () => {
  it('a server without sangam-security answers 501 and the refusal is shown by name with its detail', async () => {
    api.post.mockRejectedValueOnce(problem(501, 'CAPABILITY_NOT_INSTALLED', 'sangam-security is not installed'));
    show(<ModuleSecurityDefaults />);
    fireEvent.click(screen.getByLabelText(L('secdef.selfService')));
    fireEvent.click(screen.getByRole('button', { name: T('secdef.installButton') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/admin/security/module-defaults', { selfService: false }, KEY));
    const alert = await screen.findByRole('alert');
    // named as a module this server does not have, not as "something went wrong"
    expect(within(alert).getByText(T('error.notInstalled'))).toBeTruthy();
    expect(within(alert).getByText('sangam-security is not installed')).toBeTruthy();
  });
});
