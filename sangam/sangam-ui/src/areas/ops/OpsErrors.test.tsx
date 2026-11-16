// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-047 / G21 ops area, error states: every screen of ops/*.tsx with its first read refused by the server. A 403 is
 * named "not available for your seat", a 409 "someone else changed this item first", a 501 "not installed on this
 * server" (components/ui.tsx problemTitleKey), each with the server's detail, inside role=alert, with a retry that
 * reads again. A refused operator action is named on the page and the dialog keeps the reason that was typed.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  patch: vi.fn(),
  del: vi.fn(),
  page: vi.fn(),
  upload: vi.fn(),
  text: vi.fn(),
  engine: vi.fn(),
  getTagged: vi.fn(),
}));

vi.mock('../../api/client', async (orig) => {
  const real = await orig<typeof import('../../api/client')>();
  return { ...real, http: { ...real.http, ...api } };
});
vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ me: { userId: 'demo', tenantId: 't1', roles: ['ops'], seats: [] }, seat: null, mode: 'dev', hasRole: () => true }),
}));
vi.mock('../../components/BpmnDiagram', () => ({ BpmnDiagram: (p: { title: string }) => <div data-testid="bpmn">{p.title}</div> }));
vi.mock('../../components/DmnView', () => ({ DmnView: (p: { title: string }) => <div data-testid="dmn">{p.title}</div> }));
const NO_PLUGINS = vi.hoisted(() => [] as never[]);
vi.mock('../../plugins/host', () => ({ PluginSlot: () => null, usePlugins: () => NO_PLUGINS }));

import apiYaml from '../../../../sangam-rest/api/sangam-api.yaml?raw';
import { initI18n } from '../../i18n';
import { EMPTY_PAGE, KEY, T, contractOperations, outsideContract, pageOf, problem, reasonAndConfirm, recordCalls, rowWith, show } from '../../test/screens';
import { BatchDetail, Batches, Migration } from './BatchMigration';
import { IncidentDetail, Incidents, Reconciliation, ReconciliationDetail } from './IncidentsRecon';
import { InstanceDetail } from './InstanceDetail';
import { OpsDashboard } from './OpsDashboard';
import { Cleanup, Debugger, Decisions, Deployments, EventRegistry, Jobs, NotificationFailures, OperatorAudit, OpsCases, OutboxInbox, Workers } from './OpsMisc';
import { DefinitionDetail, InstanceSearch, ProcessDefinitions } from './Processes';

const READS = ['get', 'page', 'text', 'engine', 'getTagged'] as const;
const seen = new Set<string>();
beforeAll(() => initI18n('en'));
beforeEach(() => {
  for (const f of Object.values(api)) f.mockReset();
  api.get.mockResolvedValue([]);
  api.page.mockResolvedValue(EMPTY_PAGE);
  api.text.mockResolvedValue('');
  api.engine.mockResolvedValue([]);
  api.getTagged.mockResolvedValue({ body: {}, etag: null });
  api.post.mockResolvedValue({});
  api.put.mockResolvedValue({});
  api.patch.mockResolvedValue({});
  api.del.mockResolvedValue(undefined);
});
afterEach(() => {
  recordCalls(seen, api);
  cleanup();
});
afterAll(() => expect(outsideContract(seen, contractOperations(apiYaml))).toEqual([]));

const reads = () => READS.reduce((n, k) => n + api[k].mock.calls.length, 0);

const REFUSALS: [number, string, string][] = [
  [403, 'FORBIDDEN', 'error.notAvailable'],
  [409, 'INSTANCE_STATE_CONFLICT', 'error.conflict'],
  [501, 'NOT_IMPLEMENTED', 'error.notInstalled'],
];

const SCREENS: [string, () => ReactElement, string?, string?][] = [
  ['OpsDashboard', () => <OpsDashboard />],
  ['ProcessDefinitions', () => <ProcessDefinitions />],
  ['DefinitionDetail', () => <DefinitionDetail />, '/ops/processes/grv-4', '/ops/processes/:id'],
  ['InstanceSearch', () => <InstanceSearch />],
  ['InstanceDetail', () => <InstanceDetail />, '/ops/instances/pi1', '/ops/instances/:id'],
  ['Incidents', () => <Incidents />],
  ['IncidentDetail', () => <IncidentDetail />, '/ops/incidents/inc1', '/ops/incidents/:id'],
  ['Reconciliation', () => <Reconciliation />],
  ['ReconciliationDetail', () => <ReconciliationDetail />, '/ops/reconciliation/r1', '/ops/reconciliation/:id'],
  ['Batches', () => <Batches />],
  ['BatchDetail', () => <BatchDetail />, '/ops/batches/b1', '/ops/batches/:id'],
  ['Migration', () => <Migration />, '/ops/migration', '/ops/migration'],
  ['OpsCases', () => <OpsCases />],
  ['Decisions', () => <Decisions />],
  ['Jobs', () => <Jobs />],
  ['OutboxInbox', () => <OutboxInbox />],
  ['Workers', () => <Workers />],
  ['EventRegistry', () => <EventRegistry />],
  ['NotificationFailures', () => <NotificationFailures />],
  ['Deployments', () => <Deployments />],
  ['Cleanup', () => <Cleanup />],
  ['Debugger', () => <Debugger />],
  ['OperatorAudit', () => <OperatorAudit />],
];

const CASES = SCREENS.flatMap(([name, ui, path, route]) => REFUSALS.map(([status, code, key]) => [name, status, code, key, ui, path, route] as const));

describe('ops: a refused first read is named by status, with the detail and a retry', () => {
  it.each(CASES)('%s, %i %s', async (_name, status, code, key, ui, path, route) => {
    const detail = `server says ${status}`;
    for (const k of READS) api[k].mockRejectedValue(problem(status, code, detail));
    show(ui(), path, route);
    const alert = (await screen.findAllByRole('alert')).find((a) => a.textContent?.includes(T(key)));
    expect(alert, `alert titled ${T(key)}`).toBeTruthy();
    expect(within(alert!).getByText(detail)).toBeTruthy();
    const before = reads();
    fireEvent.click(within(alert!).getByRole('button', { name: T('common.retry') }));
    await waitFor(() => expect(reads()).toBeGreaterThan(before));
  });
});

describe('ops: a refused operator action is named on the page', () => {
  it('a skip refused 409 (already delivered meanwhile) shows the conflict and its detail; the list is still there', async () => {
    api.page.mockResolvedValue(pageOf([{ outboxId: 'o1', destination: 'pfms', rootKey: 'BILL-1', state: 'PARKED', attempts: 5, operationId: 'op-1' }]));
    api.post.mockRejectedValue(problem(409, 'OUTBOX_STATE_CONFLICT', 'delivered meanwhile'));
    show(<OutboxInbox />);
    const row = rowWith(await screen.findByText('BILL-1').then((e) => e.textContent!));
    fireEvent.click(within(row).getByRole('button', { name: T('ops.skip') }));
    reasonAndConfirm('Duplicate delivery, already paid');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/ops/outbox/o1/skip', { reason: 'Duplicate delivery, already paid' }, KEY));
    await waitFor(() => expect(screen.getAllByRole('alert').some((a) => a.textContent?.includes(T('error.conflict')))).toBe(true));
    const alert = screen.getAllByRole('alert').find((a) => a.textContent?.includes(T('error.conflict')))!;
    expect(within(alert).getByText('delivered meanwhile')).toBeTruthy();
    expect(screen.getByText('BILL-1')).toBeTruthy();
  });

  it('a debugger status read refused 501 is named "not installed" once, with no second alert for an action that never ran', async () => {
    api.get.mockRejectedValue(problem(501, 'NOT_IMPLEMENTED', 'debugger module absent'));
    show(<Debugger />);
    const alerts = await screen.findAllByRole('alert');
    expect(alerts).toHaveLength(1);
    expect(within(alerts[0]).getByText(T('error.notInstalled'))).toBeTruthy();
  });
});
