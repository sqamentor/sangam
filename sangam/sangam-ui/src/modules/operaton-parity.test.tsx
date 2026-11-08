// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * OPERATON-UI-PARITY action rows (the non-"Built" rows of the action-level audit): each test renders the Sangam
 * screen against a mocked API, drives the control the row names and asserts the exact REST call (method, path, body,
 * query). Row ids (P01…) match the per-row table in TESTING-GAP-REGISTER.md (SL-37, UI parity). The matching engine
 * calls are exercised on a real engine by sangam-server EngineRestUiParityTest.
 */
import type { ReactElement } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18n from 'i18next';

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
  upload: vi.fn(),
  page: vi.fn(),
  request: vi.fn(),
  download: vi.fn(),
}));

vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post, put: api.put, del: api.del, upload: api.upload, page: api.page }, request: api.request, download: api.download };
});
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ me: { userId: 'demo', displayName: 'Demo', tenantId: 't1', roles: ['PLATFORM_ADMIN', 'PLATFORM_OPERATOR'], seats: [] }, mode: 'dev', hasRole: () => true }),
}));
vi.mock('../components/BpmnDiagram', () => ({ BpmnDiagram: (p: { title: string }) => <div data-testid="bpmn">{p.title}</div> }));
vi.mock('../components/DmnView', () => ({ DmnView: (p: { title: string; xml: string }) => <div data-testid="dmn">{p.title}:{p.xml}</div> }));
vi.mock('../plugins/host', () => ({ PluginSlot: () => null, usePlugins: () => [] }));

import { initI18n } from '../i18n';
import { EngineAdminPage, OperatonOpsPage, TaskDashboard, TaskFiltersPage, Account } from './operaton';
import { DrdPage } from './operaton2';
import { AddVariables, TaskDates } from './operaton3';
import { buildRequest, engineDate, f, filterQuery } from './engine';
import { InstanceDetail } from '../areas/ops/InstanceDetail';
import { Incidents } from '../areas/ops/IncidentsRecon';
import { StartCatalog } from '../areas/tasklist/StartAndCases';
import { api as contract } from '../api/endpoints';

const T = (k: string, o?: Record<string, unknown>) => i18n.t(k, o) as string;
const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
/** A field label: the translated text, optionally followed by the required marker. */
const L = (k: string) => new RegExp(`^${esc(T(k))}(\\s*\\*.*)?$`);
const ENGINE_DATE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+0000$/;

type Routes = Record<string, unknown>;
let routes: Routes = {};

function answer(path: string): unknown {
  const keys = Object.keys(routes).sort((a, b) => b.length - a.length);
  const k = keys.find((x) => path === x || path.startsWith(x));
  return k === undefined ? [] : routes[k];
}

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
  for (const m of Object.values(api)) m.mockReset();
  api.get.mockImplementation(async (path: string) => answer(path));
  api.page.mockImplementation(async () => ({ items: [], page: 0, size: 25, total: 0, capped: false }));
  api.request.mockResolvedValue(undefined);
  api.post.mockResolvedValue({ id: 'new-1' });
  api.put.mockResolvedValue(undefined);
  api.del.mockResolvedValue(undefined);
  api.upload.mockResolvedValue(undefined);
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

const panel = () => screen.getAllByRole('tabpanel')[0];
const dialog = () => screen.getByRole('dialog');

function set(scope: HTMLElement, key: string, value: string | boolean) {
  const el = within(scope).getByLabelText(L(key)) as HTMLInputElement;
  if (el.type === 'checkbox') {
    if (el.checked !== value) fireEvent.click(el);
  } else fireEvent.change(el, { target: { value } });
}

async function tab(label: string, index = 0) {
  fireEvent.click((await screen.findAllByRole('tab', { name: T(label) }))[index]);
}

async function searchWith(filters: [string, string][]) {
  const p = panel();
  for (const [k, v] of filters) set(p, k, v);
  fireEvent.click(within(p).getByRole('button', { name: T('common.search') }));
}

async function pickRow(text: string) {
  fireEvent.click(await screen.findByRole('button', { name: text }));
}

function rowAction(label: string) {
  const buttons = screen.getAllByRole('button', { name: T(label) });
  fireEvent.click(buttons[buttons.length - 1]);
  return dialog();
}

function confirm(scope: HTMLElement = dialog()) {
  fireEvent.click(within(scope.closest('dialog') ?? scope).getByRole('button', { name: T('common.confirm') }));
}

async function sent(method: string, path: string) {
  await waitFor(() => expect(api.request.mock.calls.some((c) => c[0] === method && c[1] === path)).toBe(true));
  return api.request.mock.calls.find((c) => c[0] === method && c[1] === path)![2] as { body?: Record<string, unknown>; query?: Record<string, unknown> };
}

// ====================================================================== generic engine-REST plumbing (root fix)

describe('engine REST dates (root fix: every date-time field and filter on /engine-rest)', () => {
  it('formats dates the way Operaton documents them (RFC 822 offset) instead of the bare datetime-local input value', () => {
    expect(engineDate('2026-11-08T10:30:00.000Z')).toBe('2026-11-08T10:30:00.000+0000');
    const fields = [f.dt('executionDate')];
    expect(buildRequest(fields, { executionDate: '2026-11-08T10:30' }, '', engineDate).body.executionDate).toMatch(ENGINE_DATE);
    expect(buildRequest(fields, { executionDate: '2026-11-08T10:30' }, '').body.executionDate).toMatch(/Z$/);
    expect(filterQuery([f.dt('dueBefore')], { dueBefore: '2026-11-08T10:30', name: 'x' }, true)).toEqual({ dueBefore: expect.stringMatching(ENGINE_DATE), name: 'x' });
    expect(filterQuery([f.dt('dueBefore')], { dueBefore: '2026-11-08T10:30' }, false)).toEqual({ dueBefore: '2026-11-08T10:30' });
  });

  it('P00 definition suspension at a date sends executionDate in the engine format', async () => {
    routes = { '/engine-rest/process-definition': [{ id: 'd1', name: 'Leave', key: 'leave', version: 1 }] };
    show(<OperatonOpsPage />);
    await pickRow('Leave');
    const d = rowAction('act.setSuspension');
    set(d, 'fld.suspended', true);
    set(d, 'fld.executionDate', '2026-12-01T09:00');
    confirm(d);
    const r = await sent('PUT', '/engine-rest/process-definition/d1/suspended');
    expect(r.body).toEqual({ suspended: true, executionDate: expect.stringMatching(ENGINE_DATE) });
  });

  it('P00 date-time list filters reach the engine in its format', async () => {
    show(<OperatonOpsPage />);
    await tab('mod.op.userTasks');
    await searchWith([['fld.dueBefore', '2026-12-01T09:00']]);
    await waitFor(() => expect(api.get.mock.calls.some((c) => c[0] === '/engine-rest/task' && ENGINE_DATE.test(String((c[1] as Record<string, unknown>)?.dueBefore)))).toBe(true));
  });
});

// ====================================================================== Cockpit: process definition

describe('Cockpit process definition', () => {
  it('P01 TTL can be cleared (null) and the removal time of historic instances re-dated as a batch', async () => {
    routes = { '/engine-rest/process-definition': [{ id: 'd1', name: 'Leave', key: 'leave', version: 1, historyTimeToLive: 30 }] };
    show(<OperatonOpsPage />);
    await pickRow('Leave');
    confirm(rowAction('act.setTtl'));
    expect((await sent('PUT', '/engine-rest/process-definition/d1/history-time-to-live')).body).toEqual({ historyTimeToLive: null });
    fireEvent.click(await screen.findByRole('button', { name: T('act.setRemovalTime') }));
    confirm(dialog());
    expect((await sent('POST', '/engine-rest/history/process-instance/set-removal-time')).body).toEqual({
      historicProcessInstanceQuery: { processDefinitionId: 'd1' },
      calculatedRemovalTime: true,
      hierarchical: true,
    });
  });

  it('P03 job definition retries for all jobs of a definition', async () => {
    routes = { '/engine-rest/job-definition': [{ id: 'jd1', activityId: 'svc', jobType: 'async-continuation' }] };
    show(<OperatonOpsPage />);
    await tab('mod.op.jobDefinitions');
    await pickRow('jd1');
    const d = rowAction('ops.setRetries');
    set(d, 'fld.retries', '3');
    confirm(d);
    expect((await sent('PUT', '/engine-rest/job-definition/jd1/retries')).body).toEqual({ retries: 3 });
  });

  it('P02 bulk job priority applies per job definition and reports failures', async () => {
    routes = { '/engine-rest/job-definition': [{ id: 'a' }, { id: 'b' }] };
    api.put.mockImplementation(async (p: string) => {
      if (p.includes('/b/')) throw new Error('nope');
    });
    show(<OperatonOpsPage />);
    await tab('mod.op2.bulkPriority');
    set(panel(), 'fld.processDefinitionId', 'd1');
    set(panel(), 'fld.priority', '7');
    fireEvent.click(within(panel()).getByRole('button', { name: T('common.confirm') }));
    await screen.findByText(T('mod.op2.bulkResult', { ok: 1, failed: 'b' }));
    expect(api.put).toHaveBeenCalledWith('/engine-rest/job-definition/a/jobPriority', { priority: 7, includeJobs: false });
  });
});

// ====================================================================== Cockpit: process instance

describe('Cockpit process instance', () => {
  function instance() {
    routes = {
      '/process-instances/pi1/activity-tree': { id: 'pi1', activityId: 'leave', activityName: 'Leave', children: [{ id: 'a1', activityId: 'review', activityName: 'Review', children: [] }, { id: 'a2', activityId: 'pay', activityName: 'Pay', children: [] }] },
      '/process-instances/pi1/links': [],
      '/process-instances/pi1/cancel-preview': { summary: ['1 open task closes'] },
      '/process-instances/pi1/variables': {},
      '/process-instances/pi1': { id: 'pi1', state: 'ACTIVE', definitionId: 'd1', definitionKey: 'leave' },
      '/process-definitions/d1/xml': '<xml/>',
    };
    show(<InstanceDetail />, '/ops/instances/pi1', '/ops/instances/:id');
  }

  it('P04 cancel offers skip custom listeners and skip IO mappings (technical delete on the engine)', async () => {
    instance();
    fireEvent.click(await screen.findByRole('button', { name: T('ops.cancelInstance') }));
    const d = dialog();
    fireEvent.click(within(d).getByLabelText(new RegExp(esc(T('ops.cancelModeTECHNICAL')))));
    set(d, 'fld.skipCustomListeners', true);
    set(d, 'fld.skipIoMappings', true);
    fireEvent.change(within(d).getByLabelText(L('common.reason')), { target: { value: 'duplicate case' } });
    confirm(d);
    await waitFor(() => expect(api.del).toHaveBeenCalledWith('/engine-rest/process-instance/pi1', { skipCustomListeners: true, skipIoMappings: true, deleteReason: 'duplicate case' }, expect.any(String)));
  });

  it('P14 tree quick filter by name keeps matching nodes only', async () => {
    instance();
    expect(await screen.findByText('Review')).toBeTruthy();
    fireEvent.change(within(panel()).getByLabelText(L('common.search')), { target: { value: 'pay' } });
    expect(screen.queryByText('Review')).toBeNull();
    expect(screen.getByText('Pay')).toBeTruthy();
  });

  it('P15 instance incidents: the instance page links to incidents filtered by this instance and the list sends the filter', async () => {
    instance();
    expect((await screen.findByRole('link', { name: T('nav.incidents') })).getAttribute('href')).toBe('/ops/incidents?processInstanceId=pi1');
    cleanup();
    show(<Incidents />, '/ops/incidents?processInstanceId=pi1');
    await waitFor(() => expect(api.page).toHaveBeenCalledWith('/ops/incidents', expect.objectContaining({ processInstanceId: 'pi1' })));
  });

  it('P05 modification as a batch (async) across instances', async () => {
    show(<OperatonOpsPage />);
    await tab('mod.op.userTasks');
    fireEvent.click(screen.getByRole('button', { name: T('act.batchModification') }));
    const d = dialog();
    set(d, 'fld.processDefinitionId', 'd1');
    set(d, 'fld.processInstanceIds', '["pi1","pi2"]');
    set(d, 'fld.instructions', '[{"type":"startBeforeActivity","activityId":"review"}]');
    confirm(d);
    expect((await sent('POST', '/engine-rest/modification/executeAsync')).body).toEqual({
      processDefinitionId: 'd1',
      processInstanceIds: ['pi1', 'pi2'],
      instructions: [{ type: 'startBeforeActivity', activityId: 'review' }],
    });
  });

  describe('variables (engine views)', () => {
    const vars = [{ id: 'v1', name: 'amount', type: 'Long', value: 5, valueInfo: {}, processInstanceId: 'pi1', executionId: 'ex1', activityInstanceId: 'pi1' },
      { id: 'v2', name: 'doc', type: 'Object', value: '{"a":1}', valueInfo: { objectTypeName: 'com.x.Doc', serializationDataFormat: 'application/json' }, processInstanceId: 'pi1', executionId: 'ex1' }];
    async function open() {
      routes = { '/engine-rest/variable-instance': vars };
      show(<OperatonOpsPage />);
      await tab('mod.op2.variables');
      await searchWith([['fld.processInstanceId', 'pi1'], ['fld.variableName', 'am%']]);
    }

    it('P12 variable search sends the instance and name filters', async () => {
      await open();
      await waitFor(() => expect(api.get).toHaveBeenCalledWith('/engine-rest/variable-instance?deserializeValues=false', expect.objectContaining({ processInstanceIdIn: 'pi1', variableNameLike: 'am%' })));
    });

    it('P06 add a variable of any engine type (name only fills the path)', async () => {
      await open();
      fireEvent.click(screen.getByRole('button', { name: T('ops.addVariable') }));
      const d = dialog();
      expect(within(d).getByLabelText(L('fld.type')).querySelectorAll('option').length).toBe(12);
      set(d, 'fld.name', 'note');
      set(d, 'fld.type', 'Short');
      set(d, 'fld.value', '4');
      confirm(d);
      expect((await sent('PUT', '/engine-rest/process-instance/pi1/variables/note')).body).toEqual({ type: 'Short', value: 4 });
    });

    it('P07/P09 edit is prefilled (serialized value and type name) and local scope writes on the execution', async () => {
      await open();
      await pickRow('doc');
      let d = rowAction('act.update');
      expect((within(d).getByLabelText(L('fld.valueInfo')) as HTMLTextAreaElement).value).toContain('com.x.Doc');
      confirm(d);
      expect((await sent('PUT', '/engine-rest/process-instance/pi1/variables/doc')).body).toEqual({ type: 'Object', value: '{"a":1}', valueInfo: { objectTypeName: 'com.x.Doc', serializationDataFormat: 'application/json' } });
      d = rowAction('act.setLocal');
      set(d, 'fld.modifications', '{"x":{"value":1,"type":"Integer"}}');
      confirm(d);
      expect((await sent('POST', '/engine-rest/execution/ex1/localVariables')).body).toEqual({ modifications: { x: { value: 1, type: 'Integer' } } });
    });

    it('P08 delete a variable', async () => {
      await open();
      await pickRow('amount');
      confirm(rowAction('common.delete'));
      await sent('DELETE', '/engine-rest/process-instance/pi1/variables/amount');
    });
  });

  it('P10/P11 binary variable upload and download', async () => {
    show(<OperatonOpsPage />);
    await tab('mod.op2.binary');
    const p = panel();
    set(p, 'fld.variableInstanceId', 'v9');
    fireEvent.click(within(p).getByRole('button', { name: T('common.download') }));
    await waitFor(() => expect(api.download).toHaveBeenCalledWith('/engine-rest/variable-instance/v9/data', undefined, 'variable.bin'));
    set(p, 'fld.processInstanceId', 'pi1');
    set(p, 'fld.name', 'scan');
    fireEvent.change(within(p).getByLabelText(L('mod.op2.upload')), { target: { files: [new File(['x'], 'a.pdf')] } });
    await waitFor(() => expect(api.upload).toHaveBeenCalled());
    const [path, fd] = api.upload.mock.calls[0] as [string, FormData];
    expect(path).toBe('/engine-rest/process-instance/pi1/variables/scan/data');
    expect(fd.get('valueType')).toBe('Bytes');
    expect((fd.get('data') as File).name).toBe('a.pdf');
  });

  it('P16 bulk job retry: pick failed jobs, set a due date, per-job result', async () => {
    routes = { '/engine-rest/job': [{ id: 'j1', exceptionMessage: 'boom' }, { id: 'j2', exceptionMessage: 'bang' }] };
    api.put.mockImplementation(async (p: string) => {
      if (p === '/engine-rest/job/j2/retries') throw new Error('locked');
    });
    show(<OperatonOpsPage />);
    await tab('mod.op3.bulkRetry');
    set(panel(), 'fld.processInstanceId', 'pi1');
    fireEvent.click(within(panel()).getByRole('button', { name: T('common.open') }));
    await screen.findByText('boom');
    for (const cb of within(panel()).getAllByRole('checkbox').slice(1)) fireEvent.click(cb);
    fireEvent.click(within(panel()).getByLabelText(new RegExp(esc(T('mod.op3.due_now')))));
    fireEvent.click(within(panel()).getByRole('button', { name: T('common.confirm') }));
    await screen.findByText(T('mod.op2.bulkResult', { ok: 1, failed: 'j2' }));
    expect(api.put).toHaveBeenCalledWith('/engine-rest/job/j1/duedate', { duedate: expect.stringMatching(ENGINE_DATE) });
    expect(api.put).toHaveBeenCalledWith('/engine-rest/job/j1/retries', { retries: 1 });
  });

  describe('jobs (engine views)', () => {
    async function open() {
      routes = { '/engine-rest/job': [{ id: 'j1', retries: 0, exceptionMessage: 'boom' }], '/engine-rest/job/j1/stacktrace': 'java.lang.IllegalStateException: boom' };
      show(<OperatonOpsPage />);
      await tab('mod.op2.jobs');
      await searchWith([['fld.processInstanceId', 'pi1']]);
      await pickRow('j1');
    }

    it('P17 runtime jobs of one instance', async () => {
      await open();
      expect(api.get).toHaveBeenCalledWith('/engine-rest/job', expect.objectContaining({ processInstanceId: 'pi1' }));
    });

    it('P18 suspend / activate one job', async () => {
      await open();
      const d = rowAction('act.setSuspension');
      set(d, 'fld.suspended', true);
      confirm(d);
      expect((await sent('PUT', '/engine-rest/job/j1/suspended')).body).toEqual({ suspended: true });
    });

    it('P19 reschedule: set a date with cascade, or recalculate from creation', async () => {
      await open();
      let d = rowAction('act.reschedule');
      set(d, 'fld.dueDate', '2026-12-01T09:00:00.000+0000');
      set(d, 'fld.cascade', true);
      confirm(d);
      expect((await sent('PUT', '/engine-rest/job/j1/duedate')).body).toEqual({ duedate: '2026-12-01T09:00:00.000+0000', cascade: true });
      d = rowAction('act.recalculateDue');
      set(d, 'fld.creationDateBased', true);
      confirm(d);
      expect((await sent('POST', '/engine-rest/job/j1/duedate/recalculate')).query).toEqual({ creationDateBased: 'true' });
    });

    it('P20 job stack trace on the jobs screen', async () => {
      await open();
      fireEvent.click(screen.getByRole('tab', { name: T('ops.stacktrace') }));
      expect(await screen.findByText(/IllegalStateException/)).toBeTruthy();
    });
  });

  it('P21 user tasks of one instance', async () => {
    show(<OperatonOpsPage />);
    await tab('mod.op.userTasks');
    await searchWith([['fld.processInstanceId', 'pi1']]);
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/engine-rest/task', expect.objectContaining({ processInstanceId: 'pi1' })));
  });

  describe('external tasks', () => {
    async function open() {
      routes = { '/engine-rest/external-task': [{ id: 'x1', topicName: 'pay', errorMessage: 'short' }] };
      api.request.mockImplementation(async (m: string, p: string) => (m === 'GET' && p.endsWith('/errorDetails') ? 'full stack of the worker' : undefined));
      show(<OperatonOpsPage />);
      await tab('mod.op.externalTasks');
      await pickRow('x1');
    }
    it('P22 unlock', async () => {
      await open();
      confirm(rowAction('act.unlock'));
      await sent('POST', '/engine-rest/external-task/x1/unlock');
    });
    it('P23 full error details', async () => {
      await open();
      confirm(rowAction('act.errorDetails'));
      await sent('GET', '/engine-rest/external-task/x1/errorDetails');
      expect(await screen.findByText(/full stack of the worker/)).toBeTruthy();
    });
  });

  it('P24 operation log annotation: set and clear', async () => {
    routes = { '/engine-rest/history/user-operation': [{ id: 'e1', operationId: 'op1', timestamp: '2026-11-01T10:00:00.000+0000', userId: 'demo', operationType: 'Suspend' }] };
    show(<OperatonOpsPage />);
    await tab('mod.op2.opLog');
    fireEvent.click(await waitFor(() => { const b = screen.getAllByRole('button').find((x) => x.hasAttribute('aria-pressed')); expect(b).toBeTruthy(); return b!; }));
    const d = rowAction('act.annotate');
    set(d, 'fld.annotation', 'approved by board');
    confirm(d);
    expect((await sent('PUT', '/engine-rest/history/user-operation/op1/set-annotation')).body).toEqual({ annotation: 'approved by board' });
    confirm(rowAction('common.clear'));
    await sent('PUT', '/engine-rest/history/user-operation/op1/clear-annotation');
  });
});

// ====================================================================== Cockpit: decisions, batches, deployments, dashboards

describe('Cockpit decisions, batches, deployments, dashboards', () => {
  it('P25 decision TTL set and clear', async () => {
    routes = { '/engine-rest/decision-definition': [{ id: 'dd1', name: 'Eligibility', key: 'elig', version: 2 }] };
    show(<OperatonOpsPage />);
    await tab('mod.op2.decisionDefs');
    await pickRow('Eligibility');
    const d = rowAction('act.setTtl');
    set(d, 'fld.historyTimeToLive', '90');
    confirm(d);
    expect((await sent('PUT', '/engine-rest/decision-definition/dd1/history-time-to-live')).body).toEqual({ historyTimeToLive: 90 });
  });

  it('P26 DRD page renders the requirements XML', async () => {
    routes = { '/engine-rest/decision-requirements-definition/drd1/xml': { dmnXml: '<definitions/>' } };
    show(<DrdPage />, '/ops/drd/drd1', '/ops/drd/:id');
    expect((await screen.findByTestId('dmn')).textContent).toBe('drd1:<definitions/>');
  });

  describe('batches', () => {
    async function open() {
      routes = { '/engine-rest/batch/statistics': [{ id: 'b1', type: 'instance-migration', batchJobDefinitionId: 'bjd1', failedJobs: 2 }], '/engine-rest/job?jobDefinitionId=bjd1&noRetriesLeft=true': [{ id: 'fj1', exceptionMessage: 'x' }] };
      show(<OperatonOpsPage />);
      await tab('mod.op2.batches');
    }
    it('P31 batch search by id, type and suspension', async () => {
      await open();
      await searchWith([['fld.batchId', 'b1'], ['fld.type', 'instance-migration']]);
      await waitFor(() => expect(api.get).toHaveBeenCalledWith('/engine-rest/batch/statistics', expect.objectContaining({ batchId: 'b1', type: 'instance-migration' })));
    });
    it('P27 delete with or without history (cascade)', async () => {
      await open();
      await pickRow('b1');
      const d = rowAction('common.delete');
      set(d, 'fld.cascade', true);
      confirm(d);
      expect((await sent('DELETE', '/engine-rest/batch/b1')).query).toEqual({ cascade: 'true' });
    });
    it('P28 retry all failed batch jobs', async () => {
      await open();
      await pickRow('b1');
      confirm(rowAction('act.retryAll'));
      expect((await sent('PUT', '/engine-rest/job-definition/bjd1/retries')).body).toEqual({ retries: 1 });
    });
    it('P29/P30 failed jobs of the batch are listed; one job is retried, deleted or traced on the jobs screen', async () => {
      await open();
      await pickRow('b1');
      fireEvent.click(screen.getByRole('tab', { name: T('mod.op2.failedJobs') }));
      expect(await screen.findByText('fj1')).toBeTruthy();
      cleanup();
      routes = { '/engine-rest/job': [{ id: 'fj1', retries: 0 }], '/engine-rest/history/job-log?jobId=fj1': [{ id: 'l1', jobExceptionMessage: 'logged failure' }] };
      show(<OperatonOpsPage />);
      await tab('mod.op2.jobs');
      await searchWith([['fld.jobDefinitionId', 'bjd1']]);
      await pickRow('fj1');
      fireEvent.click(screen.getByRole('tab', { name: T('ops.log.jobs') }));
      expect(await screen.findByText('logged failure')).toBeTruthy();
      const d = rowAction('ops.setRetries');
      set(d, 'fld.retries', '1');
      confirm(d);
      await sent('PUT', '/engine-rest/job/fj1/retries');
      confirm(rowAction('common.delete'));
      await sent('DELETE', '/engine-rest/job/fj1');
    });
  });

  it('P32 deployment delete shows the instance count first and sends cascade / skip flags', async () => {
    routes = { '/engine-rest/deployment': [{ id: 'dep1', name: 'leave.bpmn' }], '/engine-rest/process-instance/count?deploymentId=dep1': { count: 4 } };
    show(<OperatonOpsPage />);
    await tab('mod.op.deploymentResources');
    await pickRow('leave.bpmn');
    fireEvent.click(screen.getByRole('tab', { name: T('mod.op3.instanceCount') }));
    expect(await screen.findByText('4')).toBeTruthy();
    const d = rowAction('common.delete');
    set(d, 'fld.cascade', true);
    set(d, 'fld.skipIoMappings', true);
    confirm(d);
    expect((await sent('DELETE', '/engine-rest/deployment/dep1')).query).toEqual({ cascade: 'true', skipIoMappings: 'true' });
  });

  it('P33 deployment resource preview and download', async () => {
    routes = { '/engine-rest/deployment/dep1/resources/r1/data': 'plain text body', '/engine-rest/deployment/dep1/resources': [{ id: 'r1', name: 'notes.txt' }] };
    show(<OperatonOpsPage />);
    await tab('mod.op2.resources', 1);
    set(panel(), 'fld.deploymentId', 'dep1');
    fireEvent.click(within(panel()).getByRole('button', { name: T('common.open') }));
    fireEvent.click(await screen.findByRole('button', { name: T('mod.op2.preview') }));
    expect(await screen.findByText('plain text body')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: T('common.download') }));
    await waitFor(() => expect(api.download).toHaveBeenCalledWith('/engine-rest/deployment/dep1/resources/r1/data', undefined, 'notes.txt'));
  });

  it('P34 task dashboard counts open the matching tasks', async () => {
    api.get.mockImplementation(async (p: string, q?: Record<string, unknown>) => {
      if (p === '/engine-rest/task/count') return { count: q?.unassigned ? 2 : 5 };
      if (p === '/engine-rest/task/report/candidate-group-count') return [{ groupName: 'clerks', taskCount: 2 }];
      if (p === '/engine-rest/task') return [{ id: 't1', name: q?.candidateGroup === 'clerks' ? 'Clerk task' : 'Other' }];
      return [];
    });
    show(<TaskDashboard />);
    fireEvent.click(await screen.findByRole('button', { name: 'clerks' }));
    expect((await screen.findByRole('link', { name: 'Clerk task' })).getAttribute('href')).toBe('/tasks/t1');
    fireEvent.click(screen.getByRole('button', { name: `${T('mod.op.unassigned')}: 2` }));
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/engine-rest/task', { unassigned: true, maxResults: 200 }));
  });
});

// ====================================================================== Tasklist

describe('Tasklist', () => {
  const filter = { id: 'f1', name: 'Mine', owner: 'demo', query: { assignee: 'demo', unassigned: false }, properties: { priority: 1, refresh: true, description: 'd', variables: [{ name: 'amount', label: 'Amount' }] } };

  it('P35/P38 filter sidebar drives the list and shows card variables', async () => {
    routes = { '/engine-rest/filter': [filter] };
    api.request.mockImplementation(async (_m: string, p: string) => (p === '/engine-rest/filter/f1/list' ? { _embedded: { task: [{ id: 't1', name: 'Pay claim', _embedded: { variable: [{ name: 'amount', value: 1200 }] } }] } } : undefined));
    show(<TaskFiltersPage />);
    await tab('mod.op3.board');
    fireEvent.click(await screen.findByRole('button', { name: 'Mine' }));
    expect(await screen.findByRole('columnheader', { name: 'Amount' })).toBeTruthy();
    expect(screen.getByText('1200')).toBeTruthy();
    expect(api.request).toHaveBeenCalledWith('GET', '/engine-rest/filter/f1/list', { query: { maxResults: 100 }, headers: { Accept: 'application/hal+json' } });
  });

  it('P36/P37 guided filter editor: general fields and criteria rows build the engine query', async () => {
    routes = { '/engine-rest/filter': [] };
    show(<TaskFiltersPage />);
    await tab('mod.op3.board');
    fireEvent.click(await screen.findByRole('button', { name: T('common.new') }));
    const d = dialog();
    fireEvent.change(within(d).getByLabelText(L('common.name')), { target: { value: 'Clerks' } });
    fireEvent.change(within(d).getByLabelText(L('fld.priority')), { target: { value: '3' } });
    fireEvent.click(within(d).getByRole('button', { name: T('mod.op3.addCriterion') }));
    fireEvent.change(within(d).getByLabelText(L('mod.op3.criterion')), { target: { value: 'candidateGroup' } });
    fireEvent.change(within(d).getByLabelText(L('fld.value')), { target: { value: 'clerks' } });
    fireEvent.change(within(d).getByLabelText(L('mod.op3.cardVariables')), { target: { value: 'amount:Amount' } });
    fireEvent.click(within(d).getByRole('button', { name: T('common.save') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/engine-rest/filter/create', { resourceType: 'Task', name: 'Clerks', owner: undefined, query: { candidateGroup: 'clerks' }, properties: { priority: 3, description: '', refresh: true, variables: [{ name: 'amount', label: 'Amount' }] } }, expect.any(String)));
  });

  describe('P39 filter permission "visible to everyone" (root fix: reflects, creates once, revokes)', () => {
    async function edit(global: unknown[]) {
      api.get.mockImplementation(async (p: string, q?: Record<string, unknown>) => {
        if (p === '/engine-rest/filter') return [filter];
        if (p === '/engine-rest/authorization' && q?.type === 0 && q?.resourceType === 5 && q?.resourceId === 'f1') return global;
        return [];
      });
      show(<TaskFiltersPage />);
      await tab('mod.op3.board');
      fireEvent.click(await screen.findByRole('button', { name: 'Mine' }));
      fireEvent.click(screen.getByRole('button', { name: T('common.edit') }));
      const d = dialog();
      const box = within(d).getByLabelText(L('mod.op3.everyone')) as HTMLInputElement;
      return { d, box };
    }
    it('an existing global read shows as ticked and saving does not create a duplicate', async () => {
      const { d, box } = await edit([{ id: 'g1', type: 0, resourceId: 'f1', userId: '*', permissions: ['READ'] }]);
      await waitFor(() => expect(box.checked).toBe(true));
      fireEvent.click(within(d).getByRole('button', { name: T('common.save') }));
      await waitFor(() => expect(api.put).toHaveBeenCalledWith('/engine-rest/filter/f1', expect.anything(), expect.any(String)));
      expect(api.post).not.toHaveBeenCalledWith('/engine-rest/authorization/create', expect.anything());
      expect(api.del).not.toHaveBeenCalled();
    });
    it('unticking revokes the global read', async () => {
      const { d, box } = await edit([{ id: 'g1' }]);
      await waitFor(() => expect(box.checked).toBe(true));
      fireEvent.click(box);
      fireEvent.click(within(d).getByRole('button', { name: T('common.save') }));
      await waitFor(() => expect(api.del).toHaveBeenCalledWith('/engine-rest/authorization/g1'));
    });
    it('ticking creates one GLOBAL READ authorization', async () => {
      const { d, box } = await edit([]);
      fireEvent.click(box);
      fireEvent.click(within(d).getByRole('button', { name: T('common.save') }));
      await waitFor(() => expect(api.post).toHaveBeenCalledWith('/engine-rest/authorization/create', { type: 0, resourceType: 5, resourceId: 'f1', userId: '*', permissions: ['READ'] }));
    });
  });

  it('P40/P42 criteria search with sort by a process variable', async () => {
    api.request.mockResolvedValue([{ id: 't1', name: 'Big claim' }]);
    show(<TaskFiltersPage />);
    await tab('mod.op3.search');
    const p = panel();
    fireEvent.change(within(p).getByLabelText(L('fld.value')), { target: { value: '%claim%' } });
    fireEvent.change(within(p).getByLabelText(L('mod.op3.sortVariable')), { target: { value: 'amount' } });
    fireEvent.change(within(p).getByLabelText(L('fld.type')), { target: { value: 'Long' } });
    fireEvent.click(within(p).getByRole('button', { name: T('common.search') }));
    expect(await screen.findByRole('link', { name: 'Big claim' })).toBeTruthy();
    expect(api.request).toHaveBeenCalledWith('POST', '/engine-rest/task', { body: { nameLike: '%claim%', sorting: [{ sortBy: 'processVariable', sortOrder: 'desc', parameters: { variable: 'amount', type: 'Long' } }] }, query: { maxResults: 200 } });
  });

  it('P41 multi-key sort reaches the contract as ordered sortBy / sortOrder lists', async () => {
    await contract.tasks.list({ sort: 'slaDue,-priority' });
    expect(api.page).toHaveBeenCalledWith('/tasks', expect.objectContaining({ sortBy: 'due,priority', sortOrder: 'asc,desc' }));
  });

  it('P43 due and follow-up dates: whole task re-sent with engine dates', async () => {
    routes = { '/engine-rest/task/t1': { id: 't1', name: 'Review', assignee: 'demo', due: null, followUp: null, priority: 50 } };
    show(<TaskDates taskId="t1" />);
        const p = document.body;
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/engine-rest/task/t1'));
    fireEvent.change(within(p).getByLabelText(L('fld.due')), { target: { value: '2026-12-01T09:00' } });
    fireEvent.click(within(p).getByRole('button', { name: T('common.save') }));
    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/engine-rest/task/t1', { id: 't1', name: 'Review', assignee: 'demo', due: expect.stringMatching(ENGINE_DATE), followUp: null, priority: 50 }, expect.any(String)));
  });

  it('P44 generic form: add typed variables', () => {
    const onChange = vi.fn();
    show(<AddVariables values={{}} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText(L('fld.name')), { target: { value: 'count' } });
    fireEvent.change(screen.getByLabelText(L('fld.type')), { target: { value: 'number' } });
    fireEvent.change(screen.getByLabelText(L('fld.value')), { target: { value: '12' } });
    fireEvent.click(screen.getByRole('button', { name: T('common.new') }));
    expect(onChange).toHaveBeenCalledWith({ count: 12 });
  });

  it('P46 start process: search the catalogue, business key and ad hoc variables', async () => {
    routes = { '/start-catalogue': [{ key: 'leave', name: 'Leave request', kind: 'PROCESS' }, { key: 'pension', name: 'Pension', kind: 'PROCESS' }] };
    api.post.mockResolvedValue({ id: 'pi1' });
    show(<StartCatalog />);
    fireEvent.change(await screen.findByLabelText(L('common.search')), { target: { value: 'leave' } });
    expect(screen.queryByText('Pension')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: T('start.begin') }));
    fireEvent.change(screen.getByLabelText(L('start.businessKey')), { target: { value: 'LV-1' } });
    fireEvent.click(screen.getByText(T('mod.op3.addVariable')));
    fireEvent.change(screen.getByLabelText(L('fld.name')), { target: { value: 'days' } });
    fireEvent.change(screen.getByLabelText(L('fld.type')), { target: { value: 'number' } });
    fireEvent.change(screen.getByLabelText(L('fld.value')), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: T('common.new') }));
    expect(screen.getByText('days=3')).toBeTruthy();
  });

  it('P47 standalone task with tenant', async () => {
    show(<OperatonOpsPage />);
    await tab('mod.op.userTasks');
    fireEvent.click(screen.getByRole('button', { name: T('act.createStandaloneTask') }));
    const d = dialog();
    set(d, 'fld.taskId', 'st1');
    set(d, 'fld.name', 'Call citizen');
    set(d, 'fld.tenantId', 't1');
    confirm(d);
    expect((await sent('POST', '/engine-rest/task/create')).body).toEqual({ id: 'st1', name: 'Call citizen', tenantId: 't1' });
  });

  it('P48 attachments: add by URL, delete', async () => {
    show(<TaskFiltersPage />);
    await tab('mod.op3.attachUrl');
    const p = panel();
    set(p, 'fld.taskId', 't1');
    set(p, 'common.name', 'Order');
    set(p, 'fld.url', 'https://gov.example/order.pdf');
    fireEvent.click(within(p).getByRole('button', { name: T('common.save') }));
    await waitFor(() => expect(api.upload).toHaveBeenCalled());
    const [path, fd] = api.upload.mock.calls[0] as [string, FormData];
    expect(path).toBe('/engine-rest/task/t1/attachment/create');
    expect([fd.get('attachment-name'), fd.get('url')]).toEqual(['Order', 'https://gov.example/order.pdf']);
    cleanup();
    routes = { '/engine-rest/task/t1/attachment': [{ id: 'at1', name: 'Order' }] };
    show(<OperatonOpsPage />);
    await tab('mod.op2.attachments');
    await searchWith([['fld.taskId', 't1']]);
    await pickRow('Order');
    confirm(rowAction('common.delete'));
    await sent('DELETE', '/engine-rest/task/t1/attachment/at1');
  });
});

// ====================================================================== Admin, account, welcome

describe('Admin and account', () => {
  it('P49 authorization update sends the resource type (root fix: the engine needs it to convert permission names)', async () => {
    routes = { '/engine-rest/authorization': [{ id: 'a1', type: 1, resourceType: 6, resourceId: 'leave', userId: 'demo', permissions: ['READ'] }] };
    show(<EngineAdminPage />);
    await tab('mod.op.authorizations');
    fireEvent.click(await waitFor(() => { const b = screen.getAllByRole('button').find((x) => x.hasAttribute('aria-pressed')); expect(b).toBeTruthy(); return b!; }));
    const d = rowAction('act.update');
    set(d, 'fld.permissions', '["READ","UPDATE"]');
    confirm(d);
    expect((await sent('PUT', '/engine-rest/authorization/a1')).body).toEqual({ resourceType: '6', resourceId: 'leave', userId: 'demo', permissions: ['READ', 'UPDATE'] });
  });

  describe('users, groups, tenants', () => {
    async function user() {
      routes = { '/engine-rest/user': [{ id: 'asha', firstName: 'Asha' }] };
      show(<EngineAdminPage />);
      await pickRow('asha');
    }
    it('P50 admin password reset', async () => {
      await user();
      const d = rowAction('act.resetPassword');
      set(d, 'mod.op.newPassword', 'N3w-secret');
      set(d, 'mod.op.currentPassword', 'admin-pw');
      confirm(d);
      expect((await sent('PUT', '/engine-rest/user/asha/credentials')).body).toEqual({ password: 'N3w-secret', authenticatedUserPassword: 'admin-pw' });
    });
    it('P51 group membership with a group picker', async () => {
      await user();
      const d = rowAction('act.addToGroup');
      expect(within(d).getByLabelText(L('fld.groupId')).getAttribute('list')).toBeTruthy();
      await waitFor(() => expect(api.get).toHaveBeenCalledWith('/engine-rest/group', expect.anything()));
      set(d, 'fld.groupId', 'clerks');
      confirm(d);
      await sent('PUT', '/engine-rest/group/clerks/members/asha');
    });
    it('P52 tenant membership for a user, add and remove, with a tenant picker', async () => {
      await user();
      let d = rowAction('act.addToTenant');
      expect(within(d).getByLabelText(L('fld.tenantId')).getAttribute('list')).toBeTruthy();
      set(d, 'fld.tenantId', 't1');
      confirm(d);
      await sent('PUT', '/engine-rest/tenant/t1/user-members/asha');
      d = rowAction('act.removeFromTenant');
      set(d, 'fld.tenantId', 't1');
      confirm(d);
      await sent('DELETE', '/engine-rest/tenant/t1/user-members/asha');
    });
    it('P53 tenant membership for a group: remove', async () => {
      routes = { '/engine-rest/group': [{ id: 'clerks', name: 'Clerks' }] };
      show(<EngineAdminPage />);
      await tab('mod.op.groups');
      await pickRow('clerks');
      const d = rowAction('act.removeFromTenant');
      set(d, 'fld.tenantId', 't1');
      confirm(d);
      await sent('DELETE', '/engine-rest/tenant/t1/group-members/clerks');
    });
    it('P54 engine tenant rename', async () => {
      routes = { '/engine-rest/tenant': [{ id: 't1', name: 'Old' }] };
      show(<EngineAdminPage />);
      await tab('mod.op.tenants');
      await pickRow('t1');
      const d = rowAction('act.update');
      set(d, 'common.name', 'New name');
      confirm(d);
      expect((await sent('PUT', '/engine-rest/tenant/t1')).body).toEqual({ id: 't1', name: 'New name' });
    });
  });

  it('P56 metrics chart reads hourly engine metrics', async () => {
    routes = { '/engine-rest/metrics': [{ timestamp: '2026-11-08T10:00:00.000+0000', value: 3 }] };
    show(<EngineAdminPage />);
    await tab('mod.op2.metricsChart');
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/engine-rest/metrics', { name: 'activity-instance-start', interval: 3600, aggregateByReporter: true, maxResults: 168 }));
  });

  it('P57/P58 account: own profile update and own tenant membership', async () => {
    routes = { '/engine-rest/user/demo/profile': { id: 'demo', firstName: 'De', lastName: 'Mo', email: 'd@x.in' } };
    show(<Account />);
    const first = await screen.findByLabelText(L('fld.firstName'));
    await waitFor(() => expect((first as HTMLInputElement).value).toBe('De'));
    fireEvent.change(first, { target: { value: 'Dee' } });
    const save = screen.getAllByRole('button', { name: T('common.save') })[0];
    fireEvent.click(save);
    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/engine-rest/user/demo/profile', { id: 'demo', firstName: 'Dee', lastName: 'Mo', email: 'd@x.in' }, expect.any(String)));
    fireEvent.change(screen.getByLabelText(L('fld.tenantId')), { target: { value: 't2' } });
    fireEvent.click(screen.getByRole('button', { name: T('act.addToTenant') }));
    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/engine-rest/tenant/t2/user-members/demo', undefined, expect.any(String)));
    fireEvent.click(screen.getByRole('button', { name: T('act.removeFromTenant') }));
    await waitFor(() => expect(api.del).toHaveBeenCalledWith('/engine-rest/tenant/t2/user-members/demo', undefined, expect.any(String)));
  });
});
