// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-047 ops area: the dashboard, process definitions and definition detail, instance search, incidents and incident
 * detail, the batch list and the instance detail actions. (OpsMisc.tsx has its own file; Reconciliation, BatchDetail
 * and Migration are covered by modules/m047-ops-audit-portal.test.tsx.) The API client (src/api/client.ts http.*) is
 * mocked; each test asserts what the screen renders from API data, the exact request it sends, role-gated controls
 * and how a refusal is shown. Every call is checked against the REST contract at the end.
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
vi.mock('../../components/BpmnDiagram', () => ({
  BpmnDiagram: (p: { title: string; onSelect?: (a: string) => void }) => (
    <div data-testid="bpmn">
      {p.title}
      <button type="button" onClick={() => p.onSelect?.('Task_Check')}>
        pick Task_Check
      </button>
    </div>
  ),
}));
// One stable empty list: a fresh [] per render would change the plugin-badge read's dependencies on every render.
const NO_PLUGINS = vi.hoisted(() => [] as never[]);
vi.mock('../../plugins/host', () => ({ PluginSlot: () => null, usePlugins: () => NO_PLUGINS }));

import apiYaml from '../../../../sangam-rest/api/sangam-api.yaml?raw';
import { fmtNumber, initI18n } from '../../i18n';
import { EMPTY_PAGE, KEY, L, T, buttonIn, confirmIn, contractOperations, dialog, noDialog, outsideContract, pageOf, problem, reasonAndConfirm, recordCalls, rowWith, show, type } from '../../test/screens';
import { OpsDashboard } from './OpsDashboard';
import { DefinitionDetail, InstanceSearch, ProcessDefinitions } from './Processes';
import { IncidentDetail, Incidents } from './IncidentsRecon';
import { Batches } from './BatchMigration';
import { InstanceDetail } from './InstanceDetail';

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
  api.del.mockResolvedValue(undefined);
});
afterEach(() => {
  recordCalls(seen, api);
  cleanup();
});
afterAll(() => expect(outsideContract(seen, contractOperations(apiYaml))).toEqual([]));

// ====================================================================== dashboard

/** Evidence: OpsDashboard.tsx:15-128; endpoints.ts:449,477 (/ops/dashboard, /ops/jobs/summary). */
describe('operations dashboard', () => {
  const dash = {
    openIncidents: 3,
    slaBreaches: 0,
    outbox: { used: 85, budget: 100, drainTimeSec: 120 },
    nodes: [{ id: 'node-a', status: 'UP', jobExecutorActive: true, lastHeartbeat: '2026-11-10T08:00:00Z' }],
    backlogByTopic: [{ topic: 'payments', count: 7, oldestAgeSec: 60 }],
    openReconciliationByAge: [{ bucket: '<1h', count: 2 }],
    securityIncidentClocks: [{ id: 's1', title: 'Breach at office 4', deadline: '2026-11-11T08:00:00Z' }],
  };

  it('shows the figures, the outbox use as a share of the budget, the nodes and the security clocks', async () => {
    routes['/ops/dashboard'] = dash;
    routes['/ops/jobs/summary'] = { executable: 12, failed: 1, timersDue: 4, locked: 0, byPartition: [{ partition: 0, owner: 'node-b', count: 9 }] };
    show(<OpsDashboard />);
    expect(await screen.findByText('85%')).toBeTruthy();
    expect(screen.getByText(fmtNumber(3))).toBeTruthy();
    expect(screen.getByText(fmtNumber(12))).toBeTruthy();
    expect(within(rowWith('node-a')).getByText(T('common.yes'))).toBeTruthy();
    expect(screen.getByText(/^Breach at office 4:/)).toBeTruthy();
    expect(screen.getByRole('link', { name: T('common.open') }).getAttribute('href')).toBe('/ops/reconciliation');
    expect(screen.getByText(T('ops.partitions'), { selector: 'h2,h3' })).toBeTruthy();
  });

  it('a refused dashboard read is shown by name, with a retry that reads again', async () => {
    routes['/ops/dashboard'] = problem(503, 'BACKPRESSURE');
    show(<OpsDashboard />);
    expect(await screen.findByText(T('error.backpressure'))).toBeTruthy();
    routes['/ops/dashboard'] = dash;
    fireEvent.click(screen.getByRole('button', { name: T('common.retry') }));
    expect(await screen.findByText('85%')).toBeTruthy();
  });
});

// ====================================================================== process definitions

/** Evidence: Processes.tsx:20-69; endpoints.ts:227 (definitions: the search box is the key filter). */
describe('process definitions', () => {
  it('lists definitions with running instances and incidents; the search is the key filter, latest only by default', async () => {
    pages['/process-definitions'] = pageOf([{ id: 'grv-4', key: 'grievance', name: 'Grievance', version: 4, versionTag: 'r2', runningInstances: 17, incidents: 2, suspended: false }]);
    show(<ProcessDefinitions />);
    expect((await screen.findByRole('link', { name: 'Grievance' })).getAttribute('href')).toBe('/ops/processes/grv-4');
    expect(screen.getByText('4 (r2)')).toBeTruthy();
    expect(api.page).toHaveBeenCalledWith('/process-definitions', expect.objectContaining({ page: 0, size: 25, latest: true, key: '' }));
    fireEvent.click(screen.getByLabelText(L('ops.latestOnly')));
    await waitFor(() => expect(api.page).toHaveBeenLastCalledWith('/process-definitions', expect.objectContaining({ latest: false })));
  });
});

/** Evidence: Processes.tsx:79-265; endpoints.ts:234 (suspension {suspended, reason, includeInstances}), 460-470 (batches). */
describe('definition detail', () => {
  const def = { id: 'grv-4', key: 'grievance', name: 'Grievance', version: 4, suspended: false };
  const setup = () => {
    routes['/process-definitions/grv-4'] = def;
    routes['/process-definitions/grv-4/xml'] = { bpmn20Xml: '<bpmn/>' };
    pages['/process-definitions'] = pageOf([def]);
    pages['/process-instances'] = pageOf([{ id: 'pi1', businessKey: 'GRV-1', state: 'ACTIVE', processDefinitionVersion: 4 }]);
  };

  it('suspending the definition sends the include-instances box as an explicit boolean', async () => {
    setup();
    show(<DefinitionDetail />, '/ops/processes/grv-4', '/ops/processes/:id');
    fireEvent.click(await screen.findByRole('button', { name: T('ops.suspendDefinition') }));
    const d = dialog();
    fireEvent.click(within(d).getByLabelText(L('ops.includeInstances')));
    reasonAndConfirm('Fee rule under review');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/process-definitions/grv-4/suspension', { suspended: true, reason: 'Fee rule under review', includeInstances: false }));
  });

  it('picking an activity narrows the instances; a batch on the selection previews its count and opens the created batch', async () => {
    setup();
    api.post.mockImplementation(async (path: string) => (path === '/ops/batches/preview' ? { count: 1, summary: ['1 instance'] } : path === '/ops/batches' ? { id: 'b9', type: 'SUSPEND', state: 'QUEUED' } : {}));
    show(<DefinitionDetail />, '/ops/processes/grv-4', '/ops/processes/:id');
    fireEvent.click(await screen.findByRole('button', { name: 'pick Task_Check' }));
    await waitFor(() => expect(api.page).toHaveBeenLastCalledWith('/process-instances', expect.objectContaining({ processDefinitionId: 'grv-4', activityId: 'Task_Check', state: 'ACTIVE' })));
    fireEvent.click(within(rowWith(await screen.findByRole('link', { name: 'pi1' }).then((e) => e.textContent!))).getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: T('batch.type.SUSPEND') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/ops/batches/preview', expect.objectContaining({ type: 'SUSPEND', filter: expect.objectContaining({ processInstanceIds: ['pi1'] }) })));
    reasonAndConfirm('Office closed for audit');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/ops/batches', expect.objectContaining({ type: 'SUSPEND', filter: expect.objectContaining({ processInstanceIds: ['pi1'] }), reason: 'Office closed for audit' }), KEY));
    expect(await screen.findByTestId('elsewhere')).toBeTruthy();
  });

  it('a batch payload that is not a JSON object is refused by name, never sent as no payload', async () => {
    setup();
    api.post.mockImplementation(async (path: string) => (path === '/ops/batches/preview' ? { count: 1, summary: [] } : {}));
    show(<DefinitionDetail />, '/ops/processes/grv-4', '/ops/processes/:id');
    fireEvent.click(await screen.findByRole('button', { name: T('batch.type.SET_VARIABLES') }));
    const d = dialog();
    fireEvent.change(within(d).getByLabelText(L('batch.payload')), { target: { value: '{"office": "Ajmer"' } });
    reasonAndConfirm('Office renamed by order');
    expect(await within(d).findByText(T('mod.common.invalidJson'))).toBeTruthy();
    expect(api.post.mock.calls.some((c) => c[0] === '/ops/batches')).toBe(false);
  });});

/** Evidence: Processes.tsx:293-311; endpoints.ts:238-257 (INCIDENT is withIncidents, history reads /history/process-instances). */
describe('instance search', () => {
  it('the incident state is sent as withIncidents; including history reads the history route', async () => {
    show(<InstanceSearch />);
    fireEvent.change(screen.getByLabelText(L('admin.status')), { target: { value: 'INCIDENT' } });
    await waitFor(() => expect(api.page).toHaveBeenLastCalledWith('/process-instances', expect.objectContaining({ withIncidents: true, state: undefined })));
    fireEvent.click(screen.getByLabelText(L('ops.includeHistory')));
    await waitFor(() => expect(api.page).toHaveBeenLastCalledWith('/history/process-instances', expect.objectContaining({ withIncidents: true })));
  });
});

// ====================================================================== incidents

/** Evidence: IncidentsRecon.tsx:35-115; endpoints.ts:451. */
describe('incidents', () => {
  it('lists incidents by type with a link to each; the type filter reaches the contract; selecting shows the bulk retry', async () => {
    pages['/ops/incidents'] = pageOf([{ id: 'inc1', type: 'failedJob', message: 'Gateway timeout', businessKey: 'APP-9', createdAt: '2026-11-10T08:00:00Z' }]);
    show(<Incidents />);
    expect((await screen.findByRole('link', { name: T('ops.incType.failedJob') })).getAttribute('href')).toBe('/ops/incidents/inc1');
    expect(within(rowWith('Gateway timeout')).getByText('APP-9')).toBeTruthy();
    fireEvent.change(screen.getByLabelText(L('ops.incidentType')), { target: { value: 'poisonEvent' } });
    await waitFor(() => expect(api.page).toHaveBeenLastCalledWith('/ops/incidents', expect.objectContaining({ page: 0, size: 25, type: 'poisonEvent' })));
    fireEvent.click(within(rowWith('Gateway timeout')).getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: T('ops.bulkRetry') }));
    expect(within(dialog()).getByText(T('ops.bulkRetryHint', { n: 1 }))).toBeTruthy();
  });

  it('none says so', async () => {
    show(<Incidents />);
    expect(await screen.findByText(T('ops.noIncidents'))).toBeTruthy();
  });

  const two = [
    { id: 'inc1', type: 'failedJob', message: 'Gateway timeout', businessKey: 'APP-9', processInstanceId: 'pi1', createdAt: '2026-11-10T08:00:00Z' },
    { id: 'inc2', type: 'failedJob', message: 'Bank refused', businessKey: 'APP-10', processInstanceId: 'pi2', createdAt: '2026-11-10T08:05:00Z' },
  ];
  const pickBoth = async () => {
    fireEvent.click(within(rowWith(await screen.findByText('Gateway timeout').then((e) => e.textContent!))).getByRole('checkbox'));
    fireEvent.click(within(rowWith('Bank refused')).getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: T('ops.bulkRetry') }));
  };

  it('bulk retry retries each selected incident on its own route, never a batch over their ids', async () => {
    pages['/ops/incidents'] = pageOf(two);
    show(<Incidents />);
    await pickBoth();
    expect(within(dialog()).getByText(T('ops.bulkRetryHint', { n: 2 }))).toBeTruthy();
    reasonAndConfirm('Gateway is back up');
    const body = { reason: 'Gateway is back up', retries: undefined, annotation: undefined };
    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(2));
    expect(api.post).toHaveBeenCalledWith('/ops/incidents/inc1/retry', body, KEY);
    expect(api.post).toHaveBeenCalledWith('/ops/incidents/inc2/retry', body, KEY);
    const [k1, k2] = api.post.mock.calls.map((c) => c[2] as string);
    expect(k1).not.toBe(k2);
    expect(api.post.mock.calls.some((c) => c[0] === '/ops/batches')).toBe(false);
    await waitFor(noDialog);
  });

  it('a partial failure names each refused incident; a second confirm retries only those, under the same key', async () => {
    pages['/ops/incidents'] = pageOf(two);
    api.post.mockImplementation(async (path: string) => {
      if (path === '/ops/incidents/inc2/retry') throw problem(409, 'INCIDENT_ALREADY_RESOLVED');
      return {};
    });
    show(<Incidents />);
    await pickBoth();
    reasonAndConfirm('Gateway is back up');
    const d = dialog();
    expect(await within(d).findByText(T('ops.bulkRetryFailed', { n: 1 }))).toBeTruthy();
    expect(within(d).getByText(`${T('ops.incType.failedJob')} · APP-10: ${T('error.conflict')} (INCIDENT_ALREADY_RESOLVED)`)).toBeTruthy();
    expect(within(d).getByText(T('ops.bulkRetryHint', { n: 1 }))).toBeTruthy();
    const firstKey = api.post.mock.calls.find((c) => c[0] === '/ops/incidents/inc2/retry')![2];
    api.post.mockClear();
    api.post.mockResolvedValue({});
    fireEvent.click(confirmIn(d));
    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    expect(api.post).toHaveBeenCalledWith('/ops/incidents/inc2/retry', { reason: 'Gateway is back up', retries: undefined, annotation: undefined }, firstKey);
    await waitFor(noDialog);
  });});

/** Evidence: IncidentsRecon.tsx:122-223; endpoints.ts:452-458, 481 (stack trace). */
describe('incident detail', () => {
  const inc = { id: 'inc1', type: 'failedJob', message: 'Gateway timeout', jobId: 'j7', processInstanceId: 'pi1', activityId: 'Task_Pay', createdAt: '2026-11-10T08:00:00Z', annotation: 'Looked at by Ravi' };
  const open = () => show(<IncidentDetail />, '/ops/incidents/inc1', '/ops/incidents/:id');

  it('shows the incident, its note and the link to the instance; claim and note reach their routes', async () => {
    routes['/ops/incidents/inc1'] = inc;
    open();
    expect(await screen.findByText('Looked at by Ravi')).toBeTruthy();
    expect(screen.getByRole('link', { name: T('ops.modifyInInstance') }).getAttribute('href')).toBe('/ops/instances/pi1');
    fireEvent.click(screen.getByRole('button', { name: T('ops.claimIncident') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/ops/incidents/inc1/claim', {}, undefined));
    type(document.body, 'ops.addNote', 'Bank says retry after 6 pm');
    fireEvent.click(screen.getByRole('button', { name: T('common.save') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/ops/incidents/inc1/note', { note: 'Bank says retry after 6 pm' }, undefined));
  });

  it('retry shows the consequence preview and sends the reason under an idempotency key', async () => {
    routes['/ops/incidents/inc1'] = inc;
    routes['/ops/incidents/inc1/preview'] = { summary: ['The payment call runs again'], affectedCount: 1 };
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('ops.retry') }));
    expect(await within(dialog()).findByText('The payment call runs again')).toBeTruthy();
    expect(api.get).toHaveBeenCalledWith('/ops/incidents/inc1/preview', { action: 'retry' });
    reasonAndConfirm('Gateway is back up');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/ops/incidents/inc1/retry', { reason: 'Gateway is back up', retries: undefined, annotation: undefined }, KEY));
  });

  it('a refused retry is shown by its problem title and the dialog stays open', async () => {
    routes['/ops/incidents/inc1'] = inc;
    routes['/ops/incidents/inc1/preview'] = { summary: ['The payment call runs again'] };
    api.post.mockRejectedValueOnce(problem(409, 'INCIDENT_ALREADY_RESOLVED'));
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('ops.retry') }));
    reasonAndConfirm('Gateway is back up');
    expect(await within(dialog()).findByText(T('error.conflict'))).toBeTruthy();
  });

  it('the stack trace is offered to a platform operator only (R28)', async () => {
    routes['/ops/incidents/inc1'] = inc;
    open();
    await screen.findByText('Looked at by Ravi');
    expect(screen.queryByRole('button', { name: T('ops.showStacktrace') })).toBeNull();
    cleanup();
    auth.roles = ['PLATFORM_OPERATOR'];
    routes['/ops/jobs/j7/stacktrace'] = 'java.net.SocketTimeoutException: Read timed out';
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('ops.showStacktrace') }));
    expect(await screen.findByText('java.net.SocketTimeoutException: Read timed out')).toBeTruthy();
  });

  it('a refused stack-trace read is shown, not swallowed', async () => {
    auth.roles = ['PLATFORM_OPERATOR'];
    routes['/ops/incidents/inc1'] = inc;
    routes['/ops/jobs/j7/stacktrace'] = problem(404, 'JOB_NOT_FOUND');
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('ops.showStacktrace') }));
    expect(await screen.findByText(T('error.notAvailable'))).toBeTruthy();
  });
});

// ====================================================================== batches

/** Evidence: BatchMigration.tsx:18-70; endpoints.ts:461-462 (state filter, CANCELED sent as CANCELLED). */
describe('batches', () => {
  it('lists batches with their progress; the cancelled filter is sent in the contract spelling', async () => {
    pages['/ops/batches'] = pageOf([{ id: 'b1', type: 'RETRY', state: 'RUNNING', total: 10, succeeded: 6, failed: 1, createdBy: 'ravi', createdAt: '2026-11-10T08:00:00Z' }]);
    show(<Batches />);
    expect((await screen.findByRole('link', { name: 'b1' })).getAttribute('href')).toBe('/ops/batches/b1');
    expect(within(rowWith('b1')).getByText(T('batch.type.RETRY'))).toBeTruthy();
    expect(within(rowWith('b1')).getByText(fmtNumber(10))).toBeTruthy();
    fireEvent.change(screen.getByLabelText(L('admin.status')), { target: { value: 'CANCELED' } });
    await waitFor(() => expect(api.page).toHaveBeenLastCalledWith('/ops/batches', { page: 0, size: 25, state: 'CANCELLED' }));
  });

  it('none says so', async () => {
    show(<Batches />);
    expect(await screen.findByText(T('batch.empty'))).toBeTruthy();
  });
});

// ====================================================================== instance detail

/** Evidence: InstanceDetail.tsx:48-230, 334-409, 457-525, 740-768; endpoints.ts:262-300, 326-334. */
describe('instance detail', () => {
  const running = { id: 'pi1', businessKey: 'GRV-1', processDefinitionId: 'grv-4', processDefinitionKey: 'grievance', state: 'ACTIVE' };
  const open = (i: Record<string, unknown> = running) => {
    routes['/process-instances/pi1'] = i;
    routes['/process-definitions/grv-4/xml'] = { bpmn20Xml: '<bpmn/>' };
    routes['/process-instances/pi1/activity-tree'] = { id: 'pi1', activityId: 'grievance', activityType: 'processDefinition', children: [] };
    routes['/process-instances/pi1/variables'] = { amount: { type: 'Long', value: 500 }, aadhaar: { type: 'String', value: null, masked: true } };
    return show(<InstanceDetail />, '/ops/instances/pi1', '/ops/instances/:id');
  };

  it('a running instance offers suspend, modify and cancel; a finished one offers restart only', async () => {
    open();
    expect(await screen.findByRole('button', { name: T('ops.cancelInstance') })).toBeTruthy();
    expect(screen.queryByRole('button', { name: T('ops.restart') })).toBeNull();
    cleanup();
    open({ ...running, state: 'COMPLETED' });
    expect(await screen.findByRole('button', { name: T('ops.restart') })).toBeTruthy();
    expect(screen.queryByRole('button', { name: T('ops.cancelInstance') })).toBeNull();
  });

  it('business cancel posts the reason; technical cancel deletes; the skip options are for a platform operator only', async () => {
    routes['/process-instances/pi1/cancel-preview'] = { summary: ['2 open tasks are withdrawn'] };
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('ops.cancelInstance') }));
    expect(await within(dialog()).findByText('2 open tasks are withdrawn')).toBeTruthy();
    expect(api.get).toHaveBeenCalledWith('/process-instances/pi1/cancel-preview', { mode: 'BUSINESS' });
    reasonAndConfirm('Applicant withdrew');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/process-instances/pi1/cancel', { reason: 'Applicant withdrew' }, KEY));
    fireEvent.click(await screen.findByRole('button', { name: T('ops.cancelInstance') }));
    fireEvent.click(within(dialog()).getByLabelText(T('ops.cancelModeTECHNICAL')));
    expect(within(dialog()).queryByLabelText(L('fld.skipCustomListeners'))).toBeNull();
    reasonAndConfirm('Duplicate test record');
    await waitFor(() => expect(api.del).toHaveBeenCalledWith('/process-instances/pi1', { reason: 'Duplicate test record' }, KEY));
  });

  it('a platform operator sees the technical skip options', async () => {
    auth.roles = ['PLATFORM_OPERATOR'];
    routes['/process-instances/pi1/cancel-preview'] = { summary: [] };
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('ops.cancelInstance') }));
    fireEvent.click(within(dialog()).getByLabelText(T('ops.cancelModeTECHNICAL')));
    expect(within(dialog()).getByLabelText(L('fld.skipCustomListeners'))).toBeTruthy();
    expect(within(dialog()).getByLabelText(L('fld.skipIoMappings'))).toBeTruthy();
  });

  it('suspend sends no re-date flag; resume sends the re-date box as a boolean', async () => {
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('ops.suspend') }));
    reasonAndConfirm('Waiting for court order');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/process-instances/pi1/suspension', { suspended: true, reason: 'Waiting for court order', redateDeadlines: undefined }));
    cleanup();
    open({ ...running, state: 'SUSPENDED' });
    fireEvent.click(await screen.findByRole('button', { name: T('ops.resume') }));
    fireEvent.click(within(dialog()).getByLabelText(L('ops.redateDeadlines')));
    reasonAndConfirm('Court order received');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/process-instances/pi1/suspension', { suspended: false, reason: 'Court order received', redateDeadlines: false }));
  });

  it('variables show masked values masked and not editable; an edit sends the typed value with the reason', async () => {
    open();
    fireEvent.click(await screen.findByRole('tab', { name: T('ops.variables') }));
    const masked = rowWith(await screen.findByText('aadhaar').then((e) => e.textContent!));
    expect(within(masked).getByText(T('ops.masked'))).toBeTruthy();
    expect(within(masked).queryByRole('button', { name: T('common.edit') })).toBeNull();
    fireEvent.click(within(rowWith('amount')).getByRole('button', { name: T('common.edit') }));
    const d = dialog();
    expect((within(d).getByLabelText(L('ops.varValue')) as HTMLTextAreaElement).value).toBe('500');
    type(d, 'ops.varValue', '750');
    reasonAndConfirm('Corrected fee amount');
    await waitFor(() => expect(api.put).toHaveBeenCalledWith(`/process-instances/pi1/variables?reason=${encodeURIComponent('Corrected fee amount')}`, { amount: { type: 'Long', value: 750 } }, KEY));
  });

  it('restart sends the start activity and the initial-variables box as explicit primitives and links the batch', async () => {
    api.post.mockResolvedValueOnce({ id: 'b5', type: 'RESTART', state: 'QUEUED' });
    open({ ...running, state: 'COMPLETED' });
    fireEvent.click(await screen.findByRole('button', { name: T('ops.restart') }));
    const d = dialog();
    type(d, 'ops.restartFrom', 'Task_Check');
    fireEvent.click(within(d).getByLabelText(L('ops.initialVariables')));
    reasonAndConfirm('Re-run after data fix');
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith(
        '/ops/restarts',
        { processDefinitionId: 'grv-4', processInstanceIds: ['pi1'], startBeforeActivityIds: ['Task_Check'], initialVariables: false, skipCustomListeners: false, reason: 'Re-run after data fix' },
        KEY,
      ),
    );
    expect((await within(d).findByRole('link', { name: T('batch.created', { id: 'b5' }) })).getAttribute('href')).toBe('/ops/batches/b5');
  });

  it('message variables that are not a JSON object are refused by name, never sent as {}', async () => {
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('ops.sendMessage') }));
    const d = dialog();
    type(d, 'common.name', 'PaymentReceived');
    fireEvent.change(within(d).getByLabelText(L('ops.variablesJson')), { target: { value: '{"utr": ' } });
    fireEvent.click(buttonIn(d, 'common.send'));
    expect(await within(d).findByText(T('mod.common.invalidJson'))).toBeTruthy();
    fireEvent.change(within(d).getByLabelText(L('ops.variablesJson')), { target: { value: '{"utr": "N123"}' } });
    fireEvent.click(buttonIn(d, 'common.send'));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/messages', expect.objectContaining({ variables: { utr: { type: 'String', value: 'N123' } } }), KEY));
    expect(api.post).toHaveBeenCalledTimes(1);
  });
  it('move across a boundary refuses each blank required field by name and sends nothing', async () => {
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('ops.moveAcross') }));
    const d = dialog();
    type(d, 'ops.targetInstance', '   ');
    reasonAndConfirm('Move to the parent case');
    expect(await within(d).findAllByText(T('form.required'))).toHaveLength(3);
    expect(api.post.mock.calls.some((c) => String(c[0]).endsWith('/cross-boundary-move'))).toBe(false);
  });

  it('inject refuses a blank name or after-activity by name and sends nothing', async () => {
    open();
    fireEvent.click(await screen.findByRole('button', { name: T('ops.inject') }));
    const d = dialog();
    reasonAndConfirm('Add a legal opinion step');
    expect(await within(d).findAllByText(T('form.required'))).toHaveLength(2);
    type(d, 'common.name', 'Legal opinion');
    type(d, 'ops.afterActivity', 'Task_Check');
    fireEvent.click(confirmIn(d));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/process-instances/pi1/inject', expect.objectContaining({ kind: 'USER_TASK', name: 'Legal opinion', afterActivityId: 'Task_Check', reason: 'Add a legal opinion step' }), KEY));
    expect(api.post).toHaveBeenCalledTimes(1);
  });

  it('restart refuses a blank start activity by name, never sending startBeforeActivityIds [""]', async () => {
    open({ ...running, state: 'COMPLETED' });
    fireEvent.click(await screen.findByRole('button', { name: T('ops.restart') }));
    const d = dialog();
    reasonAndConfirm('Re-run after data fix');
    expect(await within(d).findByText(T('form.required'))).toBeTruthy();
    expect(api.post).not.toHaveBeenCalled();
  });
  it('a missing instance is shown by name', async () => {
    routes['/process-instances/pi1'] = problem(404, 'INSTANCE_NOT_FOUND');
    show(<InstanceDetail />, '/ops/instances/pi1', '/ops/instances/:id');
    expect(await screen.findByText(T('error.notAvailable'))).toBeTruthy();
  });
});
