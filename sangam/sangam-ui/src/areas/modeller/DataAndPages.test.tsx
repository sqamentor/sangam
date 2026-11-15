// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-047 modeller area, DataAndPages.tsx: the data dictionary (data objects and their records), the page builder and
 * the page runtime. (Record creation itself is covered by RecordCreate.test.tsx.) The API client (src/api/client.ts
 * http.*) is mocked; each test asserts what the screen renders from API data, the exact request it sends (explicit
 * primitives included) and how a refusal is shown. Every call is checked against the REST contract at the end.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), page: vi.fn(), upload: vi.fn() }));
const auth = vi.hoisted(() => ({ roles: [] as string[] }));

vi.mock('../../api/client', async (orig) => {
  const real = await orig<typeof import('../../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post, put: api.put, del: api.del, page: api.page, upload: api.upload } };
});
vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ me: { userId: 'demo', tenantId: 't1', roles: auth.roles, seats: [] }, seat: null, mode: 'dev', hasRole: (r: string) => auth.roles.includes(r) }),
}));

import apiYaml from '../../../../sangam-rest/api/sangam-api.yaml?raw';
import { initI18n } from '../../i18n';
import { EMPTY_PAGE, KEY, L, T, contractOperations, outsideContract, pageOf, problem, recordCalls, rowWith, show, type } from '../../test/screens';
import { DataObjects, PageBuilder, PageRuntime } from './DataAndPages';

let routes: Record<string, unknown> = {};
let pages: Record<string, unknown> = {};
const seen = new Set<string>();

beforeAll(() => initI18n('en'));
beforeEach(() => {
  routes = {};
  pages = {};
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
});
afterEach(() => {
  recordCalls(seen, api);
  cleanup();
});
afterAll(() => expect(outsideContract(seen, contractOperations(apiYaml))).toEqual([]));

// ====================================================================== data objects

/** Evidence: DataAndPages.tsx:62-234; endpoints.ts dataObjects.list / save / records. */
describe('data objects', () => {
  const applicant = { id: 'do1', key: 'applicant', name: 'Applicant', backing: 'DB', version: 2, fields: [{ name: 'id', type: 'string', required: true }, { name: 'aadhaar', type: 'string', sensitive: true }] };

  it('lists the dictionary with the fields, sensitive ones marked; no records are read until one is browsed', async () => {
    routes['/data-objects'] = [applicant];
    show(<DataObjects />);
    expect(await screen.findByText('id:string, aadhaar:string (S)')).toBeTruthy();
    expect(api.page).not.toHaveBeenCalled();
    pages['/data-objects/applicant/records'] = pageOf([{ id: 'r1', name: 'Asha' }]);
    fireEvent.click(within(rowWith('Applicant')).getByRole('button', { name: T('data.browse') }));
    expect(await screen.findByText('Asha')).toBeTruthy();
    expect(api.page).toHaveBeenCalledWith('/data-objects/applicant/records', { page: 0, size: 25 });
  });

  it('a new object is posted with each required and sensitive box as an explicit boolean', async () => {
    show(<DataObjects />);
    fireEvent.click(await screen.findByRole('button', { name: T('common.new') }));
    type(document.body, 'ops.key', 'office');
    fireEvent.click(screen.getByRole('button', { name: T('data.addField') }));
    // the object's name box first, then one name box per field row
    const names = screen.getAllByLabelText(L('common.name'));
    expect(names).toHaveLength(3);
    fireEvent.change(names[0], { target: { value: 'Office' } });
    fireEvent.change(names[2], { target: { value: 'district' } });
    fireEvent.click(screen.getByRole('button', { name: T('common.save') }));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/data-objects', {
        key: 'office',
        name: 'Office',
        backing: 'DB',
        fields: [
          { name: 'id', type: 'string', required: true, sensitive: false },
          { name: 'district', type: 'string', required: false, sensitive: false },
        ],
      }),
    );
  });

  it('an edited object is put under its id; a refusal is shown by name', async () => {
    routes['/data-objects'] = [applicant];
    api.put.mockRejectedValueOnce(problem(409, 'DATA_OBJECT_VERSION_CONFLICT'));
    show(<DataObjects />);
    fireEvent.click(within(rowWith(await screen.findByText('Applicant').then((e) => e.textContent!))).getByRole('button', { name: T('common.edit') }));
    fireEvent.click(screen.getByRole('button', { name: T('common.save') }));
    await waitFor(() =>
      expect(api.put).toHaveBeenCalledWith('/data-objects/do1', {
        ...applicant,
        fields: [
          { name: 'id', type: 'string', required: true, sensitive: false },
          { name: 'aadhaar', type: 'string', required: false, sensitive: true },
        ],
      }),
    );
    expect(await screen.findByText(T('error.conflict'))).toBeTruthy();
  });

  it('creating a record is offered only to a record writer (decision 284)', async () => {
    routes['/data-objects'] = [applicant];
    show(<DataObjects />);
    fireEvent.click(within(rowWith(await screen.findByText('Applicant').then((e) => e.textContent!))).getByRole('button', { name: T('data.browse') }));
    await screen.findByText(T('data.records', { key: 'applicant' }));
    expect(screen.queryByRole('button', { name: T('data.createRecord') })).toBeNull();
  });
});

// ====================================================================== page builder

/** Evidence: DataAndPages.tsx:238-350; endpoints.ts pages.list / get / save. */
describe('page builder', () => {
  const page = { id: 'pg1', key: 'district-dash', title: 'District dashboard', version: 2, state: 'ACTIVE', blocks: [{ id: 'b1', kind: 'heading', title: 'Pending work' }] };

  it('lists pages with a link to each; a new page with a list block is posted', async () => {
    routes['/pages'] = [{ id: 'pg1', key: 'district-dash', title: 'District dashboard', version: 2, state: 'ACTIVE' }];
    show(<PageBuilder />);
    expect((await screen.findByRole('link', { name: 'District dashboard' })).getAttribute('href')).toBe('/pages/district-dash');
    fireEvent.click(screen.getByRole('button', { name: T('common.new') }));
    type(document.body, 'ops.key', 'office-list');
    type(document.body, 'pages.pageTitle', 'Offices');
    fireEvent.change(screen.getByLabelText(L('pages.blockKind')), { target: { value: 'list' } });
    type(document.body, 'pages.query', 'offices-by-district');
    type(document.body, 'pages.columns', 'name, district');
    fireEvent.click(screen.getByRole('button', { name: T('common.save') }));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/pages', { key: 'office-list', title: 'Offices', blocks: [{ id: 'b1', kind: 'list', title: '', query: 'offices-by-district', columns: ['name', 'district'] }] }),
    );
  });

  it('an existing page is loaded for edit and put under its key', async () => {
    routes['/pages'] = [{ id: 'pg1', key: 'district-dash', title: 'District dashboard', version: 2, state: 'ACTIVE' }];
    routes['/pages/district-dash'] = page;
    show(<PageBuilder />);
    fireEvent.click(within(rowWith(await screen.findByRole('link', { name: 'District dashboard' }).then((e) => e.textContent!))).getByRole('button', { name: T('common.edit') }));
    expect(((await screen.findByLabelText(L('pages.blockTitle'))) as HTMLInputElement).value).toBe('Pending work');
    fireEvent.click(screen.getByRole('button', { name: T('common.save') }));
    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/pages/district-dash', page));
  });

  it('a refused page read for edit is shown, not swallowed', async () => {
    routes['/pages'] = [{ id: 'pg1', key: 'district-dash', title: 'District dashboard', version: 2, state: 'ACTIVE' }];
    routes['/pages/district-dash'] = problem(403, 'LOWCODE_READ_DENIED');
    show(<PageBuilder />);
    fireEvent.click(within(rowWith(await screen.findByRole('link', { name: 'District dashboard' }).then((e) => e.textContent!))).getByRole('button', { name: T('common.edit') }));
    expect(await screen.findByText(T('error.notAvailable'))).toBeTruthy();
  });
});

// ====================================================================== page runtime

/** Evidence: DataAndPages.tsx:352-427; endpoints.ts pages.get / query, processes.start. */
describe('page runtime', () => {
  const page = {
    id: 'pg1',
    key: 'district-dash',
    title: 'District dashboard',
    blocks: [
      { id: 'b1', kind: 'heading', title: 'Pending work' },
      { id: 'b2', kind: 'kpi', title: 'Open grievances', query: 'open-count' },
      { id: 'b3', kind: 'list', title: 'Offices', query: 'offices', columns: ['name'] },
      { id: 'b4', kind: 'action', title: 'Start an inspection', processKey: 'inspection' },
    ],
  };

  it('renders the blocks from their named queries; starting the process needs the confirm box', async () => {
    routes['/pages/district-dash'] = page;
    pages['/queries/open-count'] = pageOf([{ n: 42 }]);
    pages['/queries/offices'] = pageOf([{ id: 'o1', name: 'Ajmer', district: 'Ajmer' }]);
    api.post.mockResolvedValueOnce({ id: 'pi7' });
    show(<PageRuntime />, '/pages/district-dash', '/pages/:key');
    expect(await screen.findByRole('heading', { name: 'Pending work' })).toBeTruthy();
    expect(await screen.findByText('42')).toBeTruthy();
    expect(await screen.findByText('Ajmer')).toBeTruthy();
    expect(api.page).toHaveBeenCalledWith('/queries/open-count', { size: 1 });
    const start = screen.getByRole('button', { name: 'Start an inspection' }) as HTMLButtonElement;
    expect(start.disabled).toBe(true);
    fireEvent.click(screen.getByLabelText(L('pages.confirmStart')));
    fireEvent.click(start);
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/process-instances', { key: 'inspection', variables: {} }, KEY));
  });

  it('a missing page is shown by name', async () => {
    routes['/pages/district-dash'] = problem(404, 'PAGE_NOT_FOUND');
    show(<PageRuntime />, '/pages/district-dash', '/pages/:key');
    expect(await screen.findByText(T('error.notAvailable'))).toBeTruthy();
  });
});
