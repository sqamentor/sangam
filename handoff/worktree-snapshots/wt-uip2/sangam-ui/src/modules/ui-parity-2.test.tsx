// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * UI-PARITY-2 rows (TESTING-GAP-REGISTER.md, table "UI-parity rows"): P13 activity-tree selection filters the
 * variables, incidents and history tabs; P14 tree quick filter by state; P35 inbox filter sidebar; P45 inbox card
 * variables; P49 per-permission toggles; the duplicate "Deployment resources" tab label; P02 overlays and thumbnails;
 * P59 welcome links and plugin points. Each test renders the screen against a mocked API and asserts the exact call
 * (path and query) the row depends on. The server side of the new filters is SangamUiParityApiTest (sangam-server, h2).
 */
import type { ReactElement } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18n from 'i18next';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), page: vi.fn(), request: vi.fn(), download: vi.fn() }));
const plug = vi.hoisted(() => ({ run: vi.fn(), overlays: [] as unknown[], actions: [] as unknown[] }));
const cfg = vi.hoisted(() => ({ links: [] as { url: string; label: { en: string; hi: string } }[] }));

vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post, put: api.put, del: api.del, page: api.page }, request: api.request, download: api.download };
});
vi.mock('../config', async (orig) => {
  const real = await orig<typeof import('../config')>();
  return { ...real, config: () => ({ ...real.config(), welcomeLinks: cfg.links }) };
});
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({
    me: { userId: 'demo', displayName: 'Demo', tenantId: 't1', roles: ['PLATFORM_ADMIN', 'PLATFORM_OPERATOR'], seats: [] },
    seat: { seatId: 's1', seatName: 'Clerk' },
    mode: 'dev',
    hasRole: () => true,
  }),
}));
vi.mock('../components/BpmnDiagram', () => ({
  BpmnDiagram: (p: { title: string; compact?: boolean; badges?: { text: string; href?: string }[] }) => (
    <div data-testid={p.compact ? 'thumb' : 'bpmn'} title={p.title}>
      <ul aria-label="badges">{(p.badges ?? []).map((b) => <li key={b.text}>{b.href ? `${b.text}|${b.href}` : b.text}</li>)}</ul>
    </div>
  ),
}));
vi.mock('../plugins/host', () => ({
  PluginSlot: (p: { point: string; ctx?: Record<string, unknown> }) => <div data-testid={`slot-${p.point}`} data-ctx={JSON.stringify(p.ctx ?? {})} />,
  usePlugins: (point: string) => (point === 'diagram.overlay' ? plug.overlays : point === 'process-instance.action' ? plug.actions : NONE),
}));
const NONE: unknown[] = [];

import { initI18n } from '../i18n';
import { EngineAdminPage, OperatonOpsPage, Welcome, ENGINE_PERMISSIONS } from './operaton';
import { InstanceDetail } from '../areas/ops/InstanceDetail';
import { DefinitionDetail, ProcessDefinitions } from '../areas/ops/Processes';
import { TaskInbox, parseCardVariables } from '../areas/tasklist/TaskInbox';
import { api as contract, historyActivityFilter } from '../api/endpoints';

const T = (k: string, o?: Record<string, unknown>) => i18n.t(k, o) as string;
const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const L = (k: string) => new RegExp(`^${esc(T(k))}(\\s*\\*.*)?$`);
const EMPTY = { items: [], page: 0, size: 25, total: 0, capped: false };
const pageOf = (items: unknown[]) => ({ items, page: 0, size: 25, total: items.length, capped: false });

let routes: Record<string, unknown> = {};
let pages: Record<string, unknown[]> = {};
function answer(path: string): unknown {
  const k = Object.keys(routes).sort((a, b) => b.length - a.length).find((x) => path === x || path.startsWith(x));
  return k === undefined ? [] : routes[k];
}

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
  pages = {};
  cfg.links = [];
  plug.overlays = NONE;
  plug.actions = NONE;
  for (const m of [...Object.values(api), plug.run]) m.mockReset();
  api.get.mockImplementation(async (path: string) => answer(path));
  api.page.mockImplementation(async (path: string) => (pages[path] ? pageOf(pages[path]) : EMPTY));
  api.request.mockResolvedValue(undefined);
  api.post.mockResolvedValue({ id: 'new-1' });
  window.localStorage.clear();
  void i18n.changeLanguage('en');
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

const tab = async (label: string) => fireEvent.click(await screen.findByRole('tab', { name: T(label) }));
/** Last call of a mocked method for one path. */
const lastCall = (m: typeof api.get, path: string) => [...m.mock.calls].reverse().find((c) => c[0] === path);

// ====================================================================== P13 / P14 instance view

describe('P13/P14 process instance: activity tree selection and state filter', () => {
  function instance() {
    routes = {
      '/process-instances/pi1/activity-tree': { id: 'pi1', activityId: 'leave', activityName: 'Leave', activityType: 'processDefinition', children: [{ id: 'a1', activityId: 'review', activityName: 'Review', activityType: 'userTask', children: [] }, { id: 'a2', activityId: 'pay', activityName: 'Pay', activityType: 'serviceTask', children: [] }] },
      '/process-instances/pi1/links': [],
      '/process-instances/pi1/variables': {},
      '/process-instances/pi1': { id: 'pi1', state: 'ACTIVE', definitionId: 'd1', definitionKey: 'leave' },
      '/process-definitions/d1/xml': '<xml/>',
    };
    show(<InstanceDetail />, '/ops/instances/pi1', '/ops/instances/:id');
  }
  const pick = async (name: string) => fireEvent.click(await screen.findByRole('button', { name }));

  it('P13 a picked activity narrows the variables tab to its scope; picking it again clears the filter', async () => {
    instance();
    await pick('Review');
    expect(screen.getByRole('button', { name: 'Review' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByText(T('ops.selectedActivity', { name: 'Review', id: 'a1' }))).toBeTruthy();
    await tab('ops.variables');
    await waitFor(() => expect(lastCall(api.get, '/process-instances/pi1/variables')?.[1]).toEqual({ activityInstanceId: 'a1' }));
    await tab('ops.activityTree');
    await pick('Review');
    await tab('ops.variables');
    await waitFor(() => expect(lastCall(api.get, '/process-instances/pi1/variables')?.[1]).toBeUndefined());
    expect(screen.queryByText(T('ops.selectedActivity', { name: 'Review', id: 'a1' }))).toBeNull();
  });

  it('P13 the process-instance root is not a filter', async () => {
    instance();
    await pick('Leave');
    expect(screen.queryByText(T('ops.selectedActivity', { name: 'Leave', id: 'pi1' }))).toBeNull();
    await tab('ops.variables');
    await waitFor(() => expect(lastCall(api.get, '/process-instances/pi1/variables')?.[1]).toBeUndefined());
  });

  it('P13 incidents tab: the instance incidents, narrowed to the picked activity', async () => {
    pages['/history/incidents'] = [{ id: 'i1', type: 'failedJob', message: 'boom', activityId: 'pay', state: 'OPEN' }];
    instance();
    await tab('nav.incidents');
    expect(await screen.findByText('boom')).toBeTruthy();
    expect(lastCall(api.page, '/history/incidents')?.[1]).toEqual({ page: 0, size: 25, processInstanceId: 'pi1', activityId: undefined });
    await tab('ops.activityTree');
    await pick('Pay');
    await tab('nav.incidents');
    await waitFor(() => expect(lastCall(api.page, '/history/incidents')?.[1]).toEqual({ page: 0, size: 25, processInstanceId: 'pi1', activityId: 'pay' }));
  });

  it('P13 history tabs narrow by what each log API filters on; the operation log says it is not narrowed', async () => {
    instance();
    await pick('Review');
    const expected: [string, string, Record<string, unknown>][] = [
      ['ops.log.variables', '/history/variables', { activityInstanceId: 'a1' }],
      ['ops.log.decisions', '/history/decisions', { activityInstanceId: 'a1' }],
      ['ops.log.external-tasks', '/history/external-task-log', { activityInstanceId: 'a1' }],
      ['ops.log.jobs', '/history/job-log', { activityId: 'review' }],
      ['ops.log.user-tasks', '/history/tasks', { taskDefinitionKey: 'review' }],
    ];
    for (const [label, path, filter] of expected) {
      await tab(label);
      await waitFor(() => expect(lastCall(api.page, path)?.[1]).toEqual({ page: 0, size: 25, processInstanceId: 'pi1', ...filter }));
    }
    await tab('ops.log.audit');
    await waitFor(() => expect(lastCall(api.page, '/history/operation-log')?.[1]).toEqual({ page: 0, size: 25, processInstanceId: 'pi1' }));
    expect(screen.getByText(T('ops.logNotFiltered'))).toBeTruthy();
  });

  it('P13 contract helper: no log gets a filter its API refuses', () => {
    const at = { activityInstanceId: 'a1', activityId: 'review', label: 'Review' };
    expect(historyActivityFilter('audit', at)).toEqual({});
    expect(historyActivityFilter('called', at)).toEqual({});
    expect(historyActivityFilter('variables', at)).toEqual({ activityInstanceId: 'a1' });
  });

  it('P14 quick filter by state lists historic activity instances of that state, searchable and selectable', async () => {
    api.page.mockImplementation(async (path: string, q?: Record<string, unknown>) =>
      path === '/history/activity-instances' && q?.state === 'canceled'
        ? pageOf([{ id: 'h1', activityId: 'pay', activityName: 'Pay', activityType: 'serviceTask', canceled: true }, { id: 'h2', activityId: 'notify', activityName: 'Notify', activityType: 'sendTask', canceled: true }])
        : EMPTY,
    );
    instance();
    await screen.findByRole('button', { name: 'Review' });
    fireEvent.change(screen.getByLabelText(L('ops.treeState')), { target: { value: 'canceled' } });
    await screen.findByRole('button', { name: 'Pay' });
    expect(lastCall(api.page, '/history/activity-instances')?.[1]).toEqual({ page: 0, size: 25, processInstanceId: 'pi1', state: 'canceled', activityInstanceId: undefined });
    expect(screen.queryByRole('button', { name: 'Review' })).toBeNull();
    fireEvent.change(screen.getByLabelText(L('common.search')), { target: { value: 'noti' } });
    expect(screen.queryByRole('button', { name: 'Pay' })).toBeNull();
    await pick('Notify');
    await tab('ops.variables');
    await waitFor(() => expect(lastCall(api.get, '/process-instances/pi1/variables')?.[1]).toEqual({ activityInstanceId: 'h2' }));
    await tab('ops.activityTree');
    for (const s of ['running', 'completed'] as const) {
      fireEvent.change(screen.getByLabelText(L('ops.treeState')), { target: { value: s } });
      await waitFor(() => expect(lastCall(api.page, '/history/activity-instances')?.[1]).toEqual(expect.objectContaining({ state: s })));
    }
    fireEvent.change(screen.getByLabelText(L('ops.treeState')), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText(L('common.search')), { target: { value: '' } });
    expect(await screen.findByRole('button', { name: 'Review' })).toBeTruthy();
  });

  it('P59 plugin points on the instance page: the instance tab slot and the instance actions', async () => {
    plug.actions = [{ pluginId: 'p1', contribution: { point: 'process-instance.action', id: 'x', label: { en: 'Escalate', hi: 'Escalate' }, run: plug.run } }];
    instance();
    fireEvent.click(await screen.findByRole('button', { name: 'Escalate' }));
    expect(plug.run).toHaveBeenCalledWith(expect.objectContaining({ processInstanceId: 'pi1' }));
    expect(JSON.parse(screen.getByTestId('slot-process-instance.tab').getAttribute('data-ctx')!)).toEqual({ processInstanceId: 'pi1' });
  });
});

// ====================================================================== P35 / P45 inbox

describe('P35/P45 inbox filter sidebar and card variables', () => {
  const tasks = [{ id: 't1', name: 'Pay claim', businessKey: 'CL-1', variables: { amount: { type: 'Long', value: 1200 }, district: { type: 'String', value: 'Jaipur' } } }];

  async function saveFilter(name: string, vars: string) {
    fireEvent.click(await screen.findByRole('button', { name: T('task.saveFilter') }));
    const side = screen.getByRole('complementary', { name: T('task.filterSidebar') });
    fireEvent.change(within(side).getByLabelText(L('common.name')), { target: { value: name } });
    fireEvent.change(within(side).getByLabelText(L('mod.op3.cardVariables')), { target: { value: vars } });
    fireEvent.click(within(side).getByRole('button', { name: T('common.save') }));
  }

  it('P45 a filter with card variables asks the task API for them and shows them as columns', async () => {
    pages['/tasks'] = tasks;
    show(<TaskInbox />);
    await saveFilter('Big claims', 'amount:Claim amount, district');
    await waitFor(() => expect(lastCall(api.page, '/tasks')?.[1]).toEqual(expect.objectContaining({ variableNames: ['amount', 'district'] })));
    expect(await screen.findByRole('columnheader', { name: 'Claim amount' })).toBeTruthy();
    expect(screen.getByRole('columnheader', { name: 'district' })).toBeTruthy();
    expect(screen.getByText('1200')).toBeTruthy();
    expect(screen.getByText('Jaipur')).toBeTruthy();
  });

  it('P35 saved filters drive the list (section, search, pack) and "All my work" clears them', async () => {
    show(<TaskInbox />);
    fireEvent.click(await screen.findByRole('tab', { name: new RegExp(esc(T('task.section.POOL'))) }));
    fireEvent.change(screen.getByLabelText(L('task.pack')), { target: { value: 'leave' } });
    await saveFilter('Leave pool', '');
    const side = screen.getByRole('complementary', { name: T('task.filterSidebar') });
    fireEvent.click(within(side).getByRole('button', { name: T('task.allWork') }));
    await waitFor(() => expect(lastCall(api.page, '/tasks')?.[1]).toEqual(expect.objectContaining({ section: 'MY_SEAT', mine: true, processDefinitionKey: undefined, variableNames: undefined })));
    fireEvent.click(within(side).getByRole('button', { name: 'Leave pool' }));
    expect(within(side).getByRole('button', { name: 'Leave pool' }).getAttribute('aria-pressed')).toBe('true');
    await waitFor(() => expect(lastCall(api.page, '/tasks')?.[1]).toEqual(expect.objectContaining({ section: 'POOL', unassigned: true, processDefinitionKey: 'leave' })));
  });

  it('P35 filters persist per person and can be deleted', async () => {
    show(<TaskInbox />);
    await saveFilter('Mine', 'amount');
    const stored = JSON.parse(window.localStorage.getItem('sangam.inbox.filters.demo') ?? '[]') as { name: string; variables: unknown[] }[];
    expect(stored.map((f) => [f.name, f.variables])).toEqual([['Mine', [{ name: 'amount', label: 'amount' }]]]);
    cleanup();
    show(<TaskInbox />);
    const side = await screen.findByRole('complementary', { name: T('task.filterSidebar') });
    expect(within(side).getByRole('button', { name: 'Mine' })).toBeTruthy();
    fireEvent.click(within(side).getByRole('button', { name: T('task.deleteFilter', { name: 'Mine' }) }));
    expect(within(side).queryByRole('button', { name: 'Mine' })).toBeNull();
    expect(JSON.parse(window.localStorage.getItem('sangam.inbox.filters.demo') ?? '[]')).toEqual([]);
  });

  it('P45 contract: variableNames only when asked for; card variable parsing bounded to the server maximum', async () => {
    await contract.tasks.list({ variableNames: ['amount'] });
    expect(lastCall(api.page, '/tasks')?.[1]).toEqual(expect.objectContaining({ variableNames: ['amount'] }));
    await contract.tasks.list({});
    expect(lastCall(api.page, '/tasks')?.[1]).toEqual(expect.objectContaining({ variableNames: undefined }));
    expect(parseCardVariables('a:A, b, a:dup, , c:x:y')).toEqual([{ name: 'a', label: 'A' }, { name: 'b', label: 'b' }, { name: 'c', label: 'x:y' }]);
    expect(parseCardVariables(Array.from({ length: 30 }, (_, i) => `v${i}`).join(','))).toHaveLength(20);
  });
});

// ====================================================================== P49 permissions

describe('P49 authorizations: per-permission toggles instead of JSON', () => {
  // the list filter outside the dialog has a resource-type select too, so pick the dialog's own select
  const typeSelect = (d: HTMLElement) => within(d).getAllByRole('combobox')[0];
  it('the toggles follow the resource type and drop permissions the new type does not have', async () => {
    show(<EngineAdminPage />);
    await tab('mod.op.authorizations');
    fireEvent.click(await screen.findByRole('button', { name: T('act.create') }));
    const d = screen.getByRole('dialog');
    expect(within(d).queryByRole('textbox', { name: L('fld.permissions') })).toBeNull();
    fireEvent.change(typeSelect(d), { target: { value: '7' } });
    expect(within(d).getByRole('checkbox', { name: 'ALL' })).toHaveProperty('checked', true);
    expect(within(d).getByRole('checkbox', { name: 'TASK_WORK' })).toBeTruthy();
    expect(within(d).queryByRole('checkbox', { name: 'SUSPEND_INSTANCE' })).toBeNull();
    fireEvent.click(within(d).getByRole('checkbox', { name: 'ALL' }));
    fireEvent.click(within(d).getByRole('checkbox', { name: 'READ' }));
    fireEvent.click(within(d).getByRole('checkbox', { name: 'TASK_WORK' }));
    fireEvent.change(typeSelect(d), { target: { value: '19' } });
    expect(within(d).queryByRole('checkbox', { name: 'TASK_WORK' })).toBeNull();
    expect(within(d).getByRole('checkbox', { name: 'READ' })).toHaveProperty('checked', true);
    fireEvent.change(within(d).getByLabelText(L('fld.resourceId')), { target: { value: '*' } });
    fireEvent.click(within(d).getByRole('button', { name: T('common.confirm') }));
    await waitFor(() => expect(api.request).toHaveBeenCalledWith('POST', '/engine-rest/authorization/create', expect.objectContaining({ body: expect.objectContaining({ type: 1, resourceType: '19', resourceId: '*', permissions: ['READ'] }) })));
  });

  it('no permission ticked is refused before any call', async () => {
    show(<EngineAdminPage />);
    await tab('mod.op.authorizations');
    fireEvent.click(await screen.findByRole('button', { name: T('act.create') }));
    const d = screen.getByRole('dialog');
    fireEvent.change(typeSelect(d), { target: { value: '6' } });
    fireEvent.click(within(d).getByRole('checkbox', { name: 'ALL' }));
    fireEvent.click(within(d).getByRole('button', { name: T('common.confirm') }));
    expect(await within(d).findByText(T('form.required'))).toBeTruthy();
    expect(api.request).not.toHaveBeenCalled();
  });

  it('the table covers every engine resource type, each with ALL and NONE', () => {
    expect(ENGINE_PERMISSIONS).toHaveLength(22);
    for (const p of ENGINE_PERMISSIONS) expect(p.slice(0, 2)).toEqual(['ALL', 'NONE']);
  });
});

// ====================================================================== tab labels

describe('Operations page tab labels', () => {
  it('every tab has its own label (the deployments list and the resource viewer were both "Deployment resources")', async () => {
    show(<OperatonOpsPage />);
    const names = (await screen.findAllByRole('tab')).map((x) => x.textContent?.trim());
    expect(new Set(names).size).toBe(names.length);
    expect(names).toContain(T('mod.op.deployments'));
    expect(names).toContain(T('mod.op2.resources'));
  });
});

// ====================================================================== P02 overlays and thumbnails

describe('P02 diagram overlays and thumbnails', () => {
  it('job-definition, call-activity and plugin overlays are handed to the diagram', async () => {
    routes = {
      '/process-definitions/d1/xml': '<xml/>',
      '/process-definitions/d1': { id: 'd1', key: 'leave', name: 'Leave', version: 1 },
      '/engine-rest/job-definition': [{ activityId: 'svc', jobType: 'async-continuation', suspended: true }],
      '/engine-rest/process-definition/d1/static-called-process-definitions': [{ id: 'sub1', key: 'sub', calledFromActivityIds: ['call'] }],
    };
    plug.overlays = [{ pluginId: 'p1', contribution: { point: 'diagram.overlay', id: 'o', label: { en: 'O', hi: 'O' }, badges: async () => [{ activityId: 'svc', text: 'SLA 2d' }] } }];
    show(<DefinitionDetail />, '/ops/processes/d1', '/ops/processes/:id');
    const badges = await screen.findByRole('list', { name: 'badges' });
    await waitFor(() => expect(within(badges).getAllByRole('listitem').map((x) => x.textContent)).toEqual(['⚙ async-continuation ⏸', '→ sub|/ops/processes/sub1', 'SLA 2d']));
    expect(api.get).toHaveBeenCalledWith('/engine-rest/job-definition', { processDefinitionId: 'd1' });
  });

  it('thumbnails: "show previews" renders a compact diagram per definition from its XML', async () => {
    routes = { '/process-definitions/d1/xml': '<xml/>' };
    pages['/process-definitions'] = [{ id: 'd1', key: 'leave', name: 'Leave', version: 1 }];
    show(<ProcessDefinitions />);
    await screen.findByRole('link', { name: 'Leave' });
    expect(screen.queryByTestId('thumb')).toBeNull();
    fireEvent.click(screen.getByLabelText(new RegExp(esc(T('ops.showPreviews')))));
    expect((await screen.findByTestId('thumb')).getAttribute('title')).toBe('Leave');
    expect(api.get).toHaveBeenCalledWith('/process-definitions/d1/xml');
  });
});

// ====================================================================== P59 welcome

describe('P59 welcome page', () => {
  it('configured links are shown in the user language and open safely; the dashboard plugin slot is placed', async () => {
    cfg.links = [{ url: 'https://gov.example/help', label: { en: 'Help desk', hi: 'सहायता केंद्र' } }];
    show(<Welcome />);
    const link = await screen.findByRole('link', { name: 'Help desk' });
    expect(link.getAttribute('href')).toBe('https://gov.example/help');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    expect(screen.getByTestId('slot-dashboard.widget')).toBeTruthy();
    cleanup();
    await i18n.changeLanguage('hi');
    show(<Welcome />);
    expect(await screen.findByRole('link', { name: 'सहायता केंद्र' })).toBeTruthy();
  });

  it('without configured links there is no links section', async () => {
    show(<Welcome />);
    await screen.findByTestId('slot-dashboard.widget');
    expect(screen.queryByRole('heading', { name: T('mod.op2.links') })).toBeNull();
  });
});
