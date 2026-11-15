// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-047 modeller area, Modeller.tsx: the package list, the package workspace (save with If-Match and the edit-lease
 * fence, validate, the lifecycle transitions, comments), the review queue (reviewer is not the author) and the import
 * assessment. The API client (src/api/client.ts http.* and raw) is mocked; the bpmn.io editors, the lease banner and
 * the presence bar are stand-ins. Each test asserts what the screen renders from API data, the exact request it sends
 * and how a refusal is shown. Every call is checked against the REST contract at the end.
 */
import { useEffect } from 'react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), page: vi.fn(), upload: vi.fn(), getTagged: vi.fn(), raw: vi.fn() }));
const env = vi.hoisted(() => ({ userId: 'demo', lease: undefined as undefined | { mine: boolean; fence?: number } }));

vi.mock('../../api/client', async (orig) => {
  const real = await orig<typeof import('../../api/client')>();
  return {
    ...real,
    raw: api.raw,
    http: { ...real.http, get: api.get, post: api.post, put: api.put, del: api.del, page: api.page, upload: api.upload, getTagged: api.getTagged },
  };
});
vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ me: { userId: env.userId, tenantId: 't1', roles: [], seats: [] }, seat: null, mode: 'dev', hasRole: () => false }) }));
vi.mock('../../components/Editors', () => {
  type P = { xml?: string; schema?: string; onReady: (h: { getContent: () => Promise<string> }) => void; onChange: () => void };
  const Stand = (p: P) => {
    useEffect(() => p.onReady({ getContent: async () => `${p.xml ?? p.schema}<!-- edited -->` }), []); // eslint-disable-line react-hooks/exhaustive-deps
    return (
      <button type="button" onClick={p.onChange}>
        change the model
      </button>
    );
  };
  return { BpmnEditor: Stand, DmnEditor: Stand, FormBuilder: Stand, CmmnEditor: Stand, EMPTY_BPMN: '<bpmn-empty/>', EMPTY_DMN: '<dmn-empty/>', EMPTY_FORM: '{}' };
});
vi.mock('../../modules/platform', () => ({
  LeaseBanner: (p: { onState?: (s: { mine: boolean; fence?: number }) => void }) => {
    useEffect(() => {
      if (env.lease) p.onState?.(env.lease);
    }, []); // eslint-disable-line react-hooks/exhaustive-deps
    return null;
  },
  BulkTestReport: () => null,
  FeelPlayground: () => null,
  TokenWalk: () => null,
}));
vi.mock('../../modules/presence', () => ({ ModelPresenceBar: () => null }));

import apiYaml from '../../../../sangam-rest/api/sangam-api.yaml?raw';
import { initI18n } from '../../i18n';
import { EMPTY_PAGE, KEY, L, T, buttonIn, contractOperations, dialog, outsideContract, pageOf, problem, reasonAndConfirm, recordCalls, rowWith, show, type } from '../../test/screens';
import { ImportAssessment, PackageWorkspace, Packages, ReviewQueue } from './Modeller';

let routes: Record<string, unknown> = {};
let pages: Record<string, unknown> = {};
const seen = new Set<string>();

beforeAll(() => initI18n('en'));
beforeEach(() => {
  routes = {};
  pages = {};
  env.userId = 'demo';
  env.lease = undefined;
  for (const f of Object.values(api)) f.mockReset();
  const answer = (table: Record<string, unknown>, path: string, fallback: unknown) => {
    const v = table[path];
    if (v instanceof Error) throw v;
    return v ?? fallback;
  };
  api.get.mockImplementation(async (path: string) => answer(routes, path, []));
  api.getTagged.mockImplementation(async (path: string) => ({ body: answer(routes, path, {}), etag: '"e1"' }));
  api.page.mockImplementation(async (path: string) => answer(pages, path, EMPTY_PAGE));
  api.post.mockResolvedValue({});
  api.put.mockResolvedValue({});
  api.upload.mockResolvedValue([]);
});
afterEach(() => {
  recordCalls(seen, api);
  for (const [m, path] of api.raw.mock.calls as [string, string][]) seen.add(`${m} ${path.split('?')[0]}`);
  cleanup();
});
afterAll(() => expect(outsideContract(seen, contractOperations(apiYaml))).toEqual([]));

const draft = { id: 'p1', key: 'grievance', name: 'Grievance', revision: 3, state: 'DRAFT', author: 'meena', resources: { 'grievance.bpmn': '<bpmn/>', 'fee.dmn': '<dmn/>' }, packageHash: 'abc123' };
const rawAnswer = (body: unknown) => ({ status: 200, headers: { get: (h: string) => (h === 'ETag' ? '"e2"' : null) }, json: async () => body });

// ====================================================================== packages

/** Evidence: Modeller.tsx:37-106; endpoints.ts packages.list / create. */
describe('packages', () => {
  it('lists packages with their state and links; the state filter reaches the contract', async () => {
    pages['/packages'] = pageOf([draft]);
    show(<Packages />);
    expect((await screen.findByRole('link', { name: 'Grievance' })).getAttribute('href')).toBe('/modeller/p1');
    expect(within(rowWith('grievance')).getByText('2')).toBeTruthy();
    fireEvent.change(screen.getByLabelText(L('admin.status')), { target: { value: 'IN_REVIEW' } });
    await waitFor(() => expect(api.page).toHaveBeenLastCalledWith('/packages', { page: 0, size: 25, state: 'IN_REVIEW' }));
  });

  it('a new package needs a key and a name; it is created and opened', async () => {
    api.post.mockResolvedValueOnce({ id: 'p9', key: 'pension', name: 'Pension', state: 'DRAFT', resources: {} });
    show(<Packages />, '/modeller', '/modeller');
    fireEvent.click(screen.getByRole('button', { name: T('modeller.newPackage') }));
    const d = dialog();
    expect((buttonIn(d, 'common.save') as HTMLButtonElement).disabled).toBe(true);
    type(d, 'ops.key', 'pension');
    type(d, 'common.name', 'Pension');
    fireEvent.click(buttonIn(d, 'common.save'));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/packages', { key: 'pension', name: 'Pension' }));
    expect(await screen.findByTestId('elsewhere')).toBeTruthy();
  });

  it('a refused create is shown by name', async () => {
    api.post.mockRejectedValueOnce(problem(409, 'PACKAGE_KEY_TAKEN'));
    show(<Packages />);
    fireEvent.click(screen.getByRole('button', { name: T('modeller.newPackage') }));
    const d = dialog();
    type(d, 'ops.key', 'grievance');
    type(d, 'common.name', 'Grievance');
    fireEvent.click(buttonIn(d, 'common.save'));
    expect(await screen.findByText(T('error.conflict'))).toBeTruthy();
  });
});

// ====================================================================== package workspace

/** Evidence: Modeller.tsx:123-385, 566-603; endpoints.ts:395-408 (saveResources with If-Match and the fence), transitions. */
describe('package workspace', () => {
  const open = (p: Record<string, unknown> = draft) => {
    routes['/packages/p1'] = p;
    return show(<PackageWorkspace />, '/modeller/p1', '/modeller/:id');
  };

  it('a save sends every resource by file name with the ETag as If-Match and the lease fence', async () => {
    env.lease = { mine: true, fence: 7 };
    api.raw.mockResolvedValueOnce(rawAnswer({ ...draft, revision: 4, resources: { 'grievance.bpmn': '<bpmn/><!-- edited -->', 'fee.dmn': '<dmn/>' } }));
    open();
    expect(await screen.findByText('Grievance · v3')).toBeTruthy();
    const save = screen.getByRole('button', { name: T('common.save') }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'change the model' }));
    expect(screen.getByText(T('modeller.unsaved'))).toBeTruthy();
    expect((screen.getByRole('button', { name: T('modeller.submitReview') }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(save);
    await waitFor(() =>
      expect(api.raw).toHaveBeenCalledWith('PUT', '/packages/p1', {
        body: { name: 'Grievance', resources: { 'grievance.bpmn': '<bpmn/><!-- edited -->', 'fee.dmn': '<dmn/>' } },
        headers: { 'If-Match': '"e1"', 'X-Sangam-Edit-Fence': '7' },
      }),
    );
    expect(await screen.findByText('Grievance · v4')).toBeTruthy();
    expect(screen.queryByText(T('modeller.unsaved'))).toBeNull();
  });

  it('while someone else holds the edit lease the save is not offered', async () => {
    env.lease = { mine: false };
    open();
    fireEvent.click(await screen.findByRole('button', { name: 'change the model' }));
    expect((screen.getByRole('button', { name: T('common.save') }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('a stale save (409) is shown by name and the model stays unsaved', async () => {
    api.raw.mockRejectedValueOnce(problem(409, 'STALE_PACKAGE'));
    open();
    fireEvent.click(await screen.findByRole('button', { name: 'change the model' }));
    fireEvent.click(screen.getByRole('button', { name: T('common.save') }));
    expect(await screen.findByText(T('error.conflict'))).toBeTruthy();
    expect(screen.getByText(T('modeller.unsaved'))).toBeTruthy();
  });

  it('validate shows the findings on the validation tab', async () => {
    api.post.mockResolvedValueOnce([{ severity: 'ERROR', artifact: 'grievance.bpmn', elementId: 'Task_1', code: 'UNSUPPORTED_CONSTRUCT', message: 'Compensation is not supported' }]);
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('modeller.validate') }));
    expect(await screen.findByText('Compensation is not supported')).toBeTruthy();
    expect(api.post).toHaveBeenCalledWith('/packages/p1/validate');
    expect(within(rowWith('Compensation is not supported')).getByText('UNSUPPORTED_CONSTRUCT')).toBeTruthy();
  });

  it('submit for review sends the transition with the reason under an idempotency key', async () => {
    api.post.mockResolvedValueOnce({ ...draft, state: 'IN_REVIEW' });
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('modeller.submitReview') }));
    expect(within(dialog()).getByText(T('modeller.submitHint'))).toBeTruthy();
    reasonAndConfirm('Ready for the fee rule review');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/packages/p1/transitions', { action: 'submit', reason: 'Ready for the fee rule review', environment: undefined, activateAt: undefined }, KEY));
    expect(await screen.findByText(T('modeller.readOnly'))).toBeTruthy();
  });

  it('an approved package is deployed by someone other than its author, to the tenant typed', async () => {
    env.userId = 'meena';
    open({ ...draft, state: 'APPROVED' });
    expect(await screen.findByText(T('modeller.readOnly'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: T('modeller.deploy') })).toBeNull();
    cleanup();
    env.userId = 'ravi';
    api.post.mockResolvedValueOnce({ ...draft, state: 'DEPLOYED' });
    open({ ...draft, state: 'APPROVED' });
    fireEvent.click(await screen.findByRole('button', { name: T('modeller.deploy') }));
    type(dialog(), 'modeller.targetTenant', 'rajasthan');
    reasonAndConfirm('Go-live for the district');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/packages/p1/transitions', { action: 'deploy', reason: 'Go-live for the district', environment: 'rajasthan', activateAt: undefined }, KEY));
  });

  it('a deployed package can be retired, nothing else', async () => {
    open({ ...draft, state: 'DEPLOYED' });
    expect(await screen.findByRole('button', { name: T('modeller.retire') })).toBeTruthy();
    expect(screen.queryByRole('button', { name: T('modeller.submitReview') })).toBeNull();
    expect(screen.queryByRole('button', { name: T('modeller.addArtifact') })).toBeNull();
  });

  it('a new artifact is added with an empty model under its file name', async () => {
    api.raw.mockResolvedValueOnce(rawAnswer(draft));
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('modeller.addArtifact') }));
    const d = dialog();
    type(d, 'common.name', 'fees');
    fireEvent.change(within(d).getByLabelText(L('admin.kind')), { target: { value: 'DMN' } });
    fireEvent.click(buttonIn(d, 'common.save'));
    await waitFor(() =>
      expect(api.raw).toHaveBeenCalledWith('PUT', '/packages/p1', {
        body: { name: 'Grievance', resources: { 'grievance.bpmn': '<bpmn/>', 'fee.dmn': '<dmn/>', 'fees.dmn': '<dmn-empty/>' } },
        headers: { 'If-Match': '"e1"' },
      }),
    );
  });

  it('comments are listed and a new one is posted with its element', async () => {
    routes['/packages/p1/comments'] = [{ id: 'c1', author: 'ravi', text: 'Check the fee table', createdAt: '2026-11-10T08:00:00Z' }];
    open();
    fireEvent.click(await screen.findByRole('tab', { name: T('task.tab.comments') }));
    expect(await screen.findByText('Check the fee table')).toBeTruthy();
    type(document.body, 'modeller.element', 'Task_Fee');
    type(document.body, 'task.addComment', 'Fixed the rounding');
    fireEvent.click(screen.getByRole('button', { name: T('task.postComment') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/packages/p1/comments', { text: 'Fixed the rounding', elementId: 'Task_Fee' }));
  });

  it('a missing package is shown by name', async () => {
    routes['/packages/p1'] = problem(404, 'PACKAGE_NOT_FOUND');
    show(<PackageWorkspace />, '/modeller/p1', '/modeller/:id');
    expect(await screen.findByText(T('error.notAvailable'))).toBeTruthy();
  });
});

// ====================================================================== review queue

/** Evidence: Modeller.tsx:605-679 (A07, K5: the reviewer is not the author). */
describe('review queue', () => {
  const inReview = { ...draft, state: 'IN_REVIEW' };

  it('the author cannot approve or reject their own package', async () => {
    env.userId = 'meena';
    pages['/packages'] = pageOf([inReview]);
    show(<ReviewQueue />);
    fireEvent.click(await screen.findByRole('button', { name: 'Grievance v3' }));
    expect(api.page).toHaveBeenCalledWith('/packages', { page: 0, size: 25, state: 'IN_REVIEW' });
    expect(screen.getByText(T('modeller.selfReview'))).toBeTruthy();
    expect((screen.getByRole('button', { name: T('admin.approve') }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: T('admin.reject') }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('another reviewer approves with an effective-from instant', async () => {
    env.userId = 'ravi';
    pages['/packages'] = pageOf([inReview]);
    api.post.mockResolvedValueOnce({ ...draft, state: 'APPROVED' });
    show(<ReviewQueue />);
    fireEvent.click(await screen.findByRole('button', { name: 'Grievance v3' }));
    fireEvent.click(screen.getByRole('button', { name: T('admin.approve') }));
    type(dialog(), 'admin.effectiveFrom', '2026-12-01T00:00');
    reasonAndConfirm('Fee rule checked against the order');
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith(
        '/packages/p1/transitions',
        { action: 'approve', reason: 'Fee rule checked against the order', environment: undefined, activateAt: new Date('2026-12-01T00:00').toISOString() },
        KEY,
      ),
    );
  });

  it('none waiting says so', async () => {
    show(<ReviewQueue />);
    expect(await screen.findByText(T('modeller.reviewEmpty'))).toBeTruthy();
  });
});

// ====================================================================== import assessment

/** Evidence: Modeller.tsx:681-727; endpoints.ts packages.assess (multipart files). */
describe('import assessment', () => {
  it('uploads the chosen files and shows each construct with a count per classification', async () => {
    api.upload.mockResolvedValueOnce([
      { file: 'legacy.bpmn', element: 'Task_1', construct: 'userTask', classification: 'SUPPORTED' },
      { file: 'legacy.bpmn', element: 'Comp_1', construct: 'compensation', classification: 'REJECTED', note: 'Not supported' },
    ]);
    show(<ImportAssessment />);
    const file = new File(['<definitions/>'], 'legacy.bpmn', { type: 'application/xml' });
    fireEvent.change(screen.getByLabelText(T('modeller.assessFiles')), { target: { files: [file] } });
    expect(await screen.findByText('compensation')).toBeTruthy();
    const [path, form] = api.upload.mock.calls[0] as [string, FormData];
    expect(path).toBe('/packages/assessment');
    expect((form.getAll('files') as File[]).map((f) => f.name)).toEqual(['legacy.bpmn']);
    expect(screen.getByText(`${T('enum.SUPPORTED')}: 1 · ${T('enum.MAPPED')}: 0 · ${T('enum.PATTERN')}: 0 · ${T('enum.REJECTED')}: 1`)).toBeTruthy();
  });

  it('a refused assessment is shown by name', async () => {
    api.upload.mockRejectedValueOnce(problem(422, 'NOT_A_MODEL'));
    show(<ImportAssessment />);
    fireEvent.change(screen.getByLabelText(T('modeller.assessFiles')), { target: { files: [new File(['x'], 'x.txt')] } });
    expect(await screen.findByText(T('error.validation'))).toBeTruthy();
  });
});
