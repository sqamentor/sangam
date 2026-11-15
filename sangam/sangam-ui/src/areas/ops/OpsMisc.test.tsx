// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-047 ops area, OpsMisc.tsx: case instances, decisions, jobs, outbox and inbox, workers and topics, the event
 * registry, notification failures, deployments, history cleanup, the sandbox debugger and the operator audit log. The
 * API client (src/api/client.ts http.*) is mocked; each test asserts what the screen renders from API data, the exact
 * request it sends and how a refusal is shown. Every call is checked against the REST contract at the end.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), page: vi.fn(), upload: vi.fn() }));

vi.mock('../../api/client', async (orig) => {
  const real = await orig<typeof import('../../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post, put: api.put, del: api.del, page: api.page, upload: api.upload } };
});
vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ me: { userId: 'demo', tenantId: 't1', roles: [], seats: [] }, seat: null, mode: 'dev', hasRole: () => false }) }));
vi.mock('../../components/BpmnDiagram', () => ({
  BpmnDiagram: (p: { title: string; selected?: string[]; onSelect?: (a: string) => void }) => (
    <div data-testid="bpmn" data-selected={(p.selected ?? []).join(',')}>
      {p.title}
      <button type="button" onClick={() => p.onSelect?.('Task_Review')}>
        pick Task_Review
      </button>
    </div>
  ),
}));
vi.mock('../../components/DmnView', () => ({ DmnView: (p: { title: string; highlightRules?: string[] }) => <div data-testid="dmn" data-rules={(p.highlightRules ?? []).join(',')}>{p.title}</div> }));

import apiYaml from '../../../../sangam-rest/api/sangam-api.yaml?raw';
import { fmtNumber, initI18n } from '../../i18n';
import { wholeNumberProblem } from '../../lib/wholeNumber';
import { EMPTY_PAGE, KEY, L, T, buttonIn, confirmIn, contractOperations, dialog, noDialog, outsideContract, pageOf, problem, reasonAndConfirm, recordCalls, rowWith, show, type } from '../../test/screens';
import { Cleanup, Debugger, Decisions, Deployments, EventRegistry, Jobs, NotificationFailures, OperatorAudit, OpsCases, OutboxInbox, Workers } from './OpsMisc';

let routes: Record<string, unknown> = {};
let pages: Record<string, unknown> = {};
const seen = new Set<string>();

beforeAll(() => initI18n('en'));
beforeEach(() => {
  routes = {};
  pages = {};
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

// ====================================================================== cases and instance search

/** Evidence: OpsMisc.tsx:44-81, endpoints.ts:351-353 (/case-instances), Processes.tsx:293-311 (InstanceSearch). */
describe('operator case view', () => {
  it('lists case instances by state and links to the case; the second tab searches process instances', async () => {
    pages['/case-instances'] = pageOf([{ id: 'c1', businessKey: 'GRV-7', definitionKey: 'grievance', state: 'ACTIVE', created: '2026-11-01T10:00:00Z' }]);
    show(<OpsCases />);
    expect((await screen.findByRole('link', { name: 'GRV-7' })).getAttribute('href')).toBe('/cases/c1');
    fireEvent.change(screen.getByLabelText(L('admin.status')), { target: { value: 'SUSPENDED' } });
    await waitFor(() => expect(api.page).toHaveBeenLastCalledWith('/case-instances', { page: 0, size: 25, businessKey: '', active: false, state: 'SUSPENDED' }));
    fireEvent.click(screen.getByRole('tab', { name: T('ops.instanceSearch') }));
    await waitFor(() => expect(api.page).toHaveBeenCalledWith('/process-instances', expect.objectContaining({ page: 0, size: 25, withIncidents: undefined })));
  });
});

// ====================================================================== decisions

/** Evidence: OpsMisc.tsx:83-173, endpoints.ts:366-372 (definitions, xml, history/decisions, evaluate). */
describe('decisions', () => {
  const def = { id: 'dd1', key: 'fee', name: 'Fee table', version: 3, decisionRequirementsKey: 'drd-fees' };

  it('picking a definition shows its table and instances; an instance highlights the rules that fired', async () => {
    pages['/decision-definitions'] = pageOf([def]);
    routes['/decision-definitions/dd1/xml'] = { dmnXml: '<dmn/>' };
    pages['/history/decisions'] = pageOf([
      { id: 'di1', decisionKey: 'fee', decisionVersion: 3, evaluationTime: '2026-11-10T08:00:00Z', processInstanceId: 'pi1', businessKey: 'APP-1', inputs: [{ name: 'amount', value: 500 }], outputs: [{ rule: 'r2', name: 'fee', value: 20 }] },
    ]);
    show(<Decisions />);
    fireEvent.click(await screen.findByRole('button', { name: 'Fee table' }));
    expect((await screen.findByTestId('dmn')).textContent).toBe('Fee table');
    await waitFor(() => expect(api.page).toHaveBeenLastCalledWith('/history/decisions', { page: 0, size: 25, decisionKey: 'fee' }));
    expect(await screen.findByText('amount=500')).toBeTruthy();
    expect(screen.getByText('[r2] fee=20')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'APP-1' }).getAttribute('href')).toBe('/ops/instances/pi1');
    fireEvent.click(within(rowWith('amount=500')).getAllByRole('button')[0]);
    expect(screen.getByTestId('dmn').getAttribute('data-rules')).toBe('r2');
  });

  it('evaluate sends the variables as typed values and shows the outputs; a refusal is shown', async () => {
    pages['/decision-definitions'] = pageOf([def]);
    api.post.mockResolvedValueOnce({ outputs: [{ fee: 25 }] });
    show(<Decisions />);
    fireEvent.click(await screen.findByRole('button', { name: 'Fee table' }));
    fireEvent.change(screen.getByLabelText(L('ops.variablesJson')), { target: { value: '{"amount": 1200, "urgent": true}' } });
    fireEvent.click(screen.getByRole('button', { name: T('ops.evaluate') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/decisions/fee/evaluate', { variables: { amount: { type: 'Long', value: 1200 }, urgent: { type: 'Boolean', value: true } } }));
    expect(await screen.findByText(/"fee": 25/)).toBeTruthy();
    api.post.mockRejectedValueOnce(problem(422, 'DECISION_INPUT_MISSING', 'Input amount is missing'));
    fireEvent.click(screen.getByRole('button', { name: T('ops.evaluate') }));
    expect(await screen.findByText(T('error.validation'))).toBeTruthy();
    expect(screen.getByText('Input amount is missing')).toBeTruthy();
  });

  it('variables that are not a JSON object are refused by name, never evaluated as {}', async () => {
    pages['/decision-definitions'] = pageOf([def]);
    show(<Decisions />);
    fireEvent.click(await screen.findByRole('button', { name: 'Fee table' }));
    fireEvent.change(screen.getByLabelText(L('ops.variablesJson')), { target: { value: '{"amount": 1200' } });
    fireEvent.click(screen.getByRole('button', { name: T('ops.evaluate') }));
    expect(await screen.findByText(T('mod.common.invalidJson'))).toBeTruthy();
    fireEvent.change(screen.getByLabelText(L('ops.variablesJson')), { target: { value: '[1200]' } });
    fireEvent.click(screen.getByRole('button', { name: T('ops.evaluate') }));
    expect(await screen.findByText(T('form.jsonObject'))).toBeTruthy();
    expect(api.post).not.toHaveBeenCalled();
  });});

// ====================================================================== jobs

/** Evidence: OpsMisc.tsx:175-253; endpoints.ts:475-479 (jobs filter, JobRetriesRequest {retries:int, reason}). */
describe('jobs', () => {
  const job = { id: 'j1', type: 'async-continuation', processInstanceId: 'pi1', activityId: 'Task_Pay', retries: 0, exceptionMessage: 'Connection refused' };

  it('shows the summary and failed jobs; the type filter reaches the contract', async () => {
    routes['/ops/jobs/summary'] = { executable: 12, failed: 3, timersDue: 4, locked: 1 };
    pages['/ops/jobs'] = pageOf([job]);
    show(<Jobs />);
    expect(await screen.findByText('Connection refused')).toBeTruthy();
    expect(api.page).toHaveBeenCalledWith('/ops/jobs', { page: 0, size: 25, withException: true, timers: undefined, type: undefined });
    expect(screen.getByText(fmtNumber(12))).toBeTruthy();
    fireEvent.change(screen.getByLabelText(L('ops.jobType')), { target: { value: 'timer' } });
    await waitFor(() => expect(api.page).toHaveBeenLastCalledWith('/ops/jobs', { page: 0, size: 25, withException: true, timers: true, type: 'timer' }));
  });

  it('set retries sends the count as a whole number with the due date as an instant', async () => {
    pages['/ops/jobs'] = pageOf([job]);
    show(<Jobs />);
    fireEvent.click(within(rowWith((await screen.findByText('Connection refused')).textContent!)).getByRole('button', { name: T('ops.setRetries') }));
    const d = dialog();
    type(d, 'ops.retries', '3');
    type(d, 'ops.newDueDate', '2026-11-20T10:00');
    reasonAndConfirm('Bank gateway is back');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/ops/jobs/j1/retries', { retries: 3, reason: 'Bank gateway is back', dueDate: new Date('2026-11-20T10:00').toISOString() }));
    await waitFor(noDialog);
  });

  it('an empty or fractional retry count is refused by name, never sent as 0 or a fraction', async () => {
    pages['/ops/jobs'] = pageOf([job]);
    show(<Jobs />);
    fireEvent.click(within(rowWith((await screen.findByText('Connection refused')).textContent!)).getByRole('button', { name: T('ops.setRetries') }));
    const d = dialog();
    type(d, 'ops.retries', '');
    reasonAndConfirm('Bank gateway is back');
    expect(await within(d).findByText(T('form.required'))).toBeTruthy();
    type(d, 'ops.retries', '1.5');
    fireEvent.click(confirmIn(d));
    expect(await within(d).findByText(T('form.wholeNumber'))).toBeTruthy();
    expect(api.post).not.toHaveBeenCalled();
  });
});

// ====================================================================== outbox and inbox

/** Evidence: OpsMisc.tsx:255-343; endpoints.ts:482-485 (outbox state filter, requeue / skip {reason}, inbox). */
describe('outbox and inbox', () => {
  it('parked deliveries can be requeued or skipped with a reason; others cannot', async () => {
    pages['/ops/outbox'] = pageOf([
      { outboxId: 'o1', destination: 'pfms', rootKey: 'BILL-1', state: 'PARKED', attempts: 5, operationId: 'op-123456789012345', lastError: '502' },
      { outboxId: 'o2', destination: 'sms', rootKey: 'BILL-2', state: 'DELIVERED', attempts: 1, operationId: 'op-2' },
    ]);
    show(<OutboxInbox />);
    const parked = rowWith(await screen.findByText('BILL-1').then((e) => e.textContent!));
    expect(api.page).toHaveBeenCalledWith('/ops/outbox', { page: 0, size: 25, state: 'PARKED', destination: undefined });
    expect(within(parked).getByText('op-123456789')).toBeTruthy();
    expect(within(rowWith('BILL-2')).queryByRole('button', { name: T('ops.requeue') })).toBeNull();
    fireEvent.click(within(parked).getByRole('button', { name: T('ops.skip') }));
    expect(within(dialog()).getByText(T('ops.skipHint'))).toBeTruthy();
    reasonAndConfirm('Duplicate delivery, already paid');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/ops/outbox/o1/skip', { reason: 'Duplicate delivery, already paid' }, KEY));
  });

  it('the inbox tab lists received messages', async () => {
    pages['/ops/inbox'] = pageOf([{ id: 'in1', source: 'pfms', messageId: 'm-77', status: 'PROCESSED', receivedAt: '2026-11-10T08:00:00Z' }]);
    show(<OutboxInbox />);
    fireEvent.click(await screen.findByRole('tab', { name: T('ops.inbox') }));
    expect(await screen.findByText('m-77')).toBeTruthy();
  });
});

// ====================================================================== workers and topics

/** Evidence: OpsMisc.tsx:345-419; endpoints.ts:505-507. */
describe('workers and topics', () => {
  it('lists topics and workers; pausing a topic asks for a reason', async () => {
    routes['/ops/topics'] = [{ name: 'payments', capabilityClass: 'b', paused: false, backlog: 42, lockDurationMs: 30000 }];
    routes['/ops/workers'] = [{ workerId: 'w-1', sdk: 'java', sdkVersion: '0.1.0', topics: ['payments', 'sms'], activeLeases: 2, heartbeatAgeSec: 5 }];
    show(<Workers />);
    const topic = rowWith(await screen.findByText('payments').then((e) => e.textContent!));
    expect(within(topic).getByText(T('recon.classLabel.b'))).toBeTruthy();
    expect(screen.getByText('payments, sms')).toBeTruthy();
    fireEvent.click(within(topic).getByRole('button', { name: T('ops.pauseTopic') }));
    reasonAndConfirm('Bank maintenance window');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/ops/topics/payments/pause', { reason: 'Bank maintenance window' }));
  });

  it('no workers says so', async () => {
    show(<Workers />);
    expect(await screen.findByText(T('ops.noWorkers'))).toBeTruthy();
  });
});

// ====================================================================== event registry

/** Evidence: OpsMisc.tsx:421-610; endpoints.ts:732-744 (channels, definitions re-created on edit, CloudEvents test send). */
describe('event registry', () => {
  it('a new channel is posted without the screen-only marker; an inbound channel offers a test send as a CloudEvent', async () => {
    pages['/events/channels'] = pageOf([{ key: 'grievance-in', kind: 'WEBHOOK', direction: 'INBOUND', destination: '/hooks/g', active: true }]);
    show(<EventRegistry />);
    fireEvent.click((await screen.findAllByRole('button', { name: T('common.new') }))[0]);
    const d = dialog();
    type(d, 'ops.key', 'payments-out');
    fireEvent.change(within(d).getByLabelText(L('events.direction')), { target: { value: 'OUTBOUND' } });
    type(d, 'recon.destination', 'payments.topic');
    type(d, 'events.dedupWindow', '300');
    fireEvent.click(buttonIn(d, 'common.save'));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/events/channels', { key: 'payments-out', kind: 'KAFKA', direction: 'OUTBOUND', destination: 'payments.topic', active: true, dedupWindowSec: 300 }),
    );
    fireEvent.click(within(rowWith('grievance-in')).getByRole('button', { name: T('events.testSend') }));
    const s = dialog();
    fireEvent.change(within(s).getByLabelText(L('events.payload')), { target: { value: '{"ref":"G-1"}' } });
    fireEvent.click(buttonIn(s, 'common.send'));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith(
        '/events/grievance-in',
        { specversion: '1.0', id: expect.any(String), source: 'sangam-ui', type: 'sangam.test', datacontenttype: 'application/json', data: { ref: 'G-1' } },
        KEY,
      ),
    );
  });

  it('an edited event definition is re-created under its key with the parsed correlation and payload', async () => {
    pages['/events/definitions'] = pageOf([{ key: 'paid', name: 'Payment made', correlationParameters: ['billId'], payload: [{ name: 'amount', type: 'decimal' }] }]);
    show(<EventRegistry />);
    fireEvent.click(await screen.findByRole('tab', { name: T('events.definitions') }));
    fireEvent.click(within(rowWith(await screen.findByText('Payment made').then((e) => e.textContent!))).getByRole('button', { name: T('common.edit') }));
    const d = dialog();
    type(d, 'events.correlation', 'billId, office');
    type(d, 'events.payload', 'amount:decimal, utr');
    fireEvent.click(buttonIn(d, 'common.save'));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/events/definitions', {
        id: undefined,
        key: 'paid',
        name: 'Payment made',
        uniqueStart: undefined,
        correlationParameters: ['billId', 'office'],
        payload: [
          { name: 'amount', type: 'decimal' },
          { name: 'utr', type: 'string' },
        ],
      }),
    );
    expect(api.del).toHaveBeenCalledWith('/events/definitions/paid', { reason: 'definition updated' });
  });
});

// ====================================================================== notification failures

/** Evidence: OpsMisc.tsx:612-670; endpoints.ts:513-515. */
describe('notification failures', () => {
  it('shows the masked recipient and the reason by name; resend asks for a reason', async () => {
    pages['/ops/notification-failures'] = pageOf([{ id: 'n1', at: '2026-11-10T08:00:00Z', channel: 'SMS', reason: 'TEMPLATE_MISMATCH', templateId: 'otp', recipientMasked: '98******10' }]);
    show(<NotificationFailures />);
    const r = rowWith(await screen.findByText('98******10').then((e) => e.textContent!));
    expect(within(r).getByText(T('ops.notifReason.TEMPLATE_MISMATCH'))).toBeTruthy();
    expect(within(r).getByRole('link', { name: T('ops.fixTemplate') }).getAttribute('href')).toBe('/admin/templates');
    fireEvent.click(within(r).getByRole('button', { name: T('ops.resend') }));
    reasonAndConfirm('Template corrected');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/ops/notification-failures/n1/resend', { reason: 'Template corrected' }));
  });

  it('none says so', async () => {
    show(<NotificationFailures />);
    expect(await screen.findByText(T('ops.notifEmpty'))).toBeTruthy();
  });
});

// ====================================================================== deployments

/** Evidence: OpsMisc.tsx:672-706. */
describe('deployments', () => {
  it('lists deployments with their package; a row expands to its resources', async () => {
    pages['/deployments'] = pageOf([{ id: 'dep1', name: 'grievance-v4', deploymentTime: '2026-11-01T10:00:00Z', packageId: 'pkg1', resources: [{ id: 'r1', name: 'grievance.bpmn' }, { id: 'r2', name: 'fee.dmn' }] }]);
    show(<Deployments />);
    const name = await screen.findByRole('button', { name: 'grievance-v4' });
    expect(screen.getByRole('link', { name: 'pkg1' }).getAttribute('href')).toBe('/modeller/pkg1');
    expect(within(rowWith('grievance-v4')).getByText('2')).toBeTruthy();
    fireEvent.click(name);
    expect(screen.getByText('grievance.bpmn, fee.dmn')).toBeTruthy();
  });
});

// ====================================================================== history cleanup

/** Evidence: OpsMisc.tsx:708-790; endpoints.ts:508-510 (cleanup, run ?reason, TtlRequest {ttlDays:int, reason}). */
describe('history cleanup', () => {
  const status = { enabled: true, window: '22:00-06:00', batchSize: 500, legalHolds: 2, definitions: [{ key: 'grievance', version: 4, ttlDays: 365, finished: 1000, cleanable: 120 }, { key: 'fee', version: 1, finished: 10, cleanable: 0 }] };

  it('shows the window, legal holds and per-definition TTL; a run asks for a reason', async () => {
    routes['/ops/cleanup'] = status;
    show(<Cleanup />);
    expect(await screen.findByText('22:00-06:00')).toBeTruthy();
    expect(within(rowWith('fee')).getByText(T('ops.ttlNone'))).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: T('ops.runCleanup') }));
    reasonAndConfirm('Quarterly clean-up');
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(`/ops/cleanup?reason=${encodeURIComponent('Quarterly clean-up')}`));
  });

  it('a TTL change sends whole days', async () => {
    routes['/ops/cleanup'] = status;
    show(<Cleanup />);
    fireEvent.click(within(rowWith(await screen.findByText('grievance').then((e) => e.textContent!))).getByRole('button', { name: T('ops.changeTtl') }));
    const d = dialog();
    expect((within(d).getByLabelText(L('ops.ttlDays')) as HTMLInputElement).value).toBe('365');
    type(d, 'ops.ttlDays', '730');
    reasonAndConfirm('Records rule 2026');
    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/ops/cleanup/ttl/grievance', { ttlDays: 730, reason: 'Records rule 2026' }));
  });

  it('a definition without a TTL starts with an empty box, and confirming it is refused, never sent as 0 days', async () => {
    routes['/ops/cleanup'] = status;
    show(<Cleanup />);
    fireEvent.click(within(rowWith(await screen.findByText('fee').then((e) => e.textContent!))).getByRole('button', { name: T('ops.changeTtl') }));
    const d = dialog();
    expect((within(d).getByLabelText(L('ops.ttlDays')) as HTMLInputElement).value).toBe('');
    reasonAndConfirm('Set the records rule');
    expect(await within(d).findByText(T('form.required'))).toBeTruthy();
    type(d, 'ops.ttlDays', '30.5');
    fireEvent.click(confirmIn(d));
    expect(await within(d).findByText(T('form.wholeNumber'))).toBeTruthy();
    expect(api.put).not.toHaveBeenCalled();
  });

  it('whole-number check: digits only, empty is required', () => {
    expect(wholeNumberProblem('')).toBe('form.required');
    expect(wholeNumberProblem('  ')).toBe('form.required');
    expect(wholeNumberProblem('0')).toBeUndefined();
    expect(wholeNumberProblem(' 12 ')).toBeUndefined();
    expect(wholeNumberProblem('1.5')).toBe('form.wholeNumber');
    expect(wholeNumberProblem('-1')).toBe('form.wholeNumber');
    expect(wholeNumberProblem('1e3')).toBe('form.wholeNumber');
    expect(wholeNumberProblem('99999999999999999999')).toBe('form.wholeNumber');
  });
});

// ====================================================================== sandbox debugger

/** Evidence: OpsMisc.tsx:792-879; endpoints.ts:556-564 (status, breakpoints, step, fire timer through /ops/jobs/{id}/execute). */
describe('sandbox debugger', () => {
  it('outside the sandbox profile the debugger is refused by name', async () => {
    routes['/debug/status'] = { enabled: false, profile: 'production' };
    show(<Debugger />);
    expect(await screen.findByText(T('debug.disabled', { profile: 'production' }))).toBeTruthy();
    expect(screen.queryByRole('button', { name: T('debug.attach') })).toBeNull();
  });

  it('attach, toggle a breakpoint, step and fire a timer', async () => {
    routes['/debug/status'] = { enabled: true, profile: 'sandbox', sandboxTenant: 'sbx' };
    routes['/process-instances/pi1'] = { id: 'pi1', processDefinitionId: 'def1', state: 'ACTIVE' };
    routes['/process-definitions/def1/xml'] = { bpmn20Xml: '<bpmn/>' };
    routes['/process-instances/pi1/activity-tree'] = { id: 'pi1', activityId: 'proc', activityType: 'processDefinition', childActivityInstances: [] };
    routes['/debug/instances/pi1/breakpoints'] = ['Task_Start'];
    pages['/ops/jobs'] = pageOf([{ id: 'job-t', activityId: 'Timer_1', dueDate: '2026-11-20T10:00:00Z' }]);
    api.post.mockImplementation(async (path: string) => (path.endsWith('/step') ? { executed: 'Task_Start' } : {}));
    show(<Debugger />);
    expect(await screen.findByText(T('debug.sandboxOnly', { tenant: 'sbx' }))).toBeTruthy();
    type(document.body, 'ops.instance', ' pi1 ');
    fireEvent.click(screen.getByRole('button', { name: T('debug.attach') }));
    expect((await screen.findByTestId('bpmn')).getAttribute('data-selected')).toBe('Task_Start');
    expect(api.page).toHaveBeenCalledWith('/ops/jobs', { processInstanceId: 'pi1', timers: true, size: 100 });
    fireEvent.click(screen.getByRole('button', { name: 'pick Task_Review' }));
    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/debug/instances/pi1/breakpoints', { activityIds: ['Task_Start', 'Task_Review'] }));
    fireEvent.click(screen.getByRole('button', { name: T('debug.step') }));
    expect(await screen.findByText(T('debug.executed', { what: 'Task_Start' }))).toBeTruthy();
    expect(api.post).toHaveBeenCalledWith('/debug/instances/pi1/step');
    fireEvent.click(screen.getByRole('button', { name: T('debug.fire') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(`/ops/jobs/job-t/execute?reason=${encodeURIComponent('debugger: fire timer')}`));
  });

  it('a refused step (production guard) is shown', async () => {
    routes['/debug/status'] = { enabled: true, profile: 'sandbox' };
    routes['/process-instances/pi1'] = { id: 'pi1', processDefinitionId: 'def1', state: 'ACTIVE' };
    routes['/process-definitions/def1/xml'] = { bpmn20Xml: '<bpmn/>' };
    api.post.mockRejectedValueOnce(problem(403, 'DEBUG_NOT_ALLOWED'));
    show(<Debugger />);
    await screen.findByRole('button', { name: T('debug.attach') });
    type(document.body, 'ops.instance', 'pi1');
    fireEvent.click(screen.getByRole('button', { name: T('debug.attach') }));
    fireEvent.click(await screen.findByRole('button', { name: T('debug.step') }));
    expect(await screen.findByText(T('error.notAvailable'))).toBeTruthy();
  });
});

// ====================================================================== operator audit

/** Evidence: OpsMisc.tsx:881-912; endpoints.ts:511-512 (/history/operation-log, actor as assignee). */
describe('operator audit', () => {
  it('lists operator actions and filters by actor and operation', async () => {
    pages['/history/operation-log'] = pageOf([{ id: 'op1', timestamp: '2026-11-10T08:00:00Z', userId: 'ravi', operationType: 'Suspend', processInstanceId: 'pi1', linkId: 'pi1' }]);
    show(<OperatorAudit />);
    expect(await screen.findByText('ravi')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'pi1' }).getAttribute('href')).toBe('/ops/instances/pi1');
    const actor = screen.getAllByRole('search')[0];
    fireEvent.change(within(actor).getByRole('searchbox'), { target: { value: 'meena' } });
    fireEvent.submit(actor);
    await waitFor(() => expect(api.page).toHaveBeenLastCalledWith('/history/operation-log', { page: 0, size: 25, assignee: 'meena', operation: undefined }));
  });
});
