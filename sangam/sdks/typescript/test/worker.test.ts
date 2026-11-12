// SPDX-License-Identifier: Apache-2.0
// Worker runtime: loop, stored-outcome completion (SL-16), outcome mapping, report retries, fencing.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import type { Variables, WorkOperation } from '../src/models.js';
import {
  BpmnError,
  OutcomeUnknownError,
  storedOutcomeCompletion,
  TaskCancelledError,
  TaskFailedError,
  type SubscriptionOptions,
  type TaskContext,
  type TaskHandler,
} from '../src/worker.js';
import { FakeServer, REPORT_ACTIONS, type Recorded, workerFor, workItem } from './fake-server.js';

const SUCCEEDED: WorkOperation = {
  state: 'SUCCEEDED',
  attempts: 2,
  capabilityClass: 'A_IDEMPOTENT_WITH_LOOKUP',
  mayDispatch: false,
  storedOutcome: { output: { paymentRef: 'PAY-77', amount: { type: 'Decimal', value: '12.50' } }, providerRef: 'PAY-77', duplicate: false },
};

/** Runs one leased item through a started worker and returns its report call. */
async function runOne(
  server: FakeServer,
  handler: TaskHandler,
  opts: SubscriptionOptions = {},
  over: Parameters<typeof workItem>[0] = {},
): Promise<Recorded> {
  server.enqueue(workItem(over));
  const worker = workerFor(server).subscribe('pay', handler, { lockDurationMs: 60_000, ...opts });
  worker.start();
  try {
    return await server.waitFor((c) => REPORT_ACTIONS.has(c.action));
  } finally {
    await worker.stop(2_000);
  }
}

describe('storedOutcomeCompletion (SL-16 contract)', () => {
  it('completes from output and providerRef when the operation already SUCCEEDED', () => {
    assert.deepEqual(storedOutcomeCompletion(SUCCEEDED), {
      variables: { paymentRef: 'PAY-77', amount: { type: 'Decimal', value: '12.50' } },
      reference: 'PAY-77',
    });
  });

  it('uses empty variables and no reference when the stored outcome has neither', () => {
    assert.deepEqual(storedOutcomeCompletion({ ...SUCCEEDED, storedOutcome: null }), { variables: {}, reference: undefined });
    assert.deepEqual(storedOutcomeCompletion({ ...SUCCEEDED, storedOutcome: { output: [1, 2], providerRef: null } }), {
      variables: {},
      reference: undefined,
    });
  });

  it('runs the handler for every other operation state', () => {
    assert.equal(storedOutcomeCompletion(undefined), undefined);
    assert.equal(storedOutcomeCompletion({ state: 'INTENT', attempts: 2, mayDispatch: true }), undefined);
    assert.equal(storedOutcomeCompletion({ state: 'UNKNOWN_OUTCOME', attempts: 1, mayDispatch: false }), undefined);
    assert.equal(storedOutcomeCompletion({ state: 'FAILED', attempts: 1, mayDispatch: false }), undefined);
  });
});

describe('SangamWorker stored-outcome completion', () => {
  it('never calls the handler and completes with the stored output and providerRef', async () => {
    const server = new FakeServer();
    let calls = 0;
    const report = await runOne(server, () => {
      calls++;
      return { paymentRef: 'NEW' };
    }, {}, { operation: SUCCEEDED });
    assert.equal(calls, 0);
    assert.equal(report.action, 'complete');
    assert.deepEqual(report.body, {
      workerId: 'worker-1',
      lockVersion: 3,
      attemptId: 'att-1',
      variables: { paymentRef: 'PAY-77', amount: { type: 'Decimal', value: '12.50' } },
      outcome: { reference: 'PAY-77' },
    });
    assert.equal(report.headers['idempotency-key'], 'op-1:complete:3');
    assert.ok(server.logs.some((l) => l.event === 'work.replayed'));
  });

  it('runs the handler while the operation may still be dispatched (repeat attempt)', async () => {
    const server = new FakeServer();
    // assigned in the handler callback: an initializer keeps tsc from narrowing it to `undefined` here
    let seen = undefined as WorkOperation | undefined;
    const report = await runOne(server, (ctx) => {
      seen = ctx.operation;
      return { done: true };
    }, {}, { operation: { state: 'INTENT', attempts: 2, mayDispatch: true } });
    assert.equal(seen?.attempts, 2);
    assert.equal(report.action, 'complete');
    assert.deepEqual(report.body?.variables, { done: true });
    assert.equal(report.body?.outcome, undefined);
  });

  it('sends the handler-set outcome reference as outcome.reference', async () => {
    const server = new FakeServer();
    const report = await runOne(server, (ctx: TaskContext) => {
      ctx.outcomeReference = 'BANK-REF-1';
      return { status: 'PAID' };
    });
    assert.deepEqual(report.body?.outcome, { reference: 'BANK-REF-1' });
  });
});

describe('SangamWorker outcome mapping', () => {
  it('BpmnError -> bpmn-error with code, message and variables', async () => {
    const server = new FakeServer();
    const r = await runOne(server, () => {
      throw new BpmnError('INSUFFICIENT_FUNDS', 'balance too low', { balance: 3 } as Variables);
    });
    assert.equal(r.action, 'bpmn-error');
    assert.equal(r.body?.errorCode, 'INSUFFICIENT_FUNDS');
    assert.equal(r.body?.errorMessage, 'balance too low');
    assert.deepEqual(r.body?.variables, { balance: 3 });
    assert.equal(r.headers['idempotency-key'], 'op-1:bpmn-error:3');
  });

  it('OutcomeUnknownError -> outcome-unknown with detail and evidence', async () => {
    const server = new FakeServer();
    const r = await runOne(server, () => {
      throw new OutcomeUnknownError('bank timed out after send', { details: 'socket read timeout', variables: { sent: true } });
    });
    assert.equal(r.action, 'outcome-unknown');
    assert.equal(r.body?.detail, 'bank timed out after send\nsocket read timeout');
    assert.deepEqual(r.body?.evidence, { sent: true });
  });

  it('TaskFailedError -> fail with its own retries and timeout', async () => {
    const server = new FakeServer();
    const r = await runOne(server, () => {
      throw new TaskFailedError('downstream 503', { retries: 0, retryTimeoutMs: 1234, details: 'd' });
    });
    assert.equal(r.action, 'fail');
    assert.equal(r.body?.errorMessage, 'downstream 503');
    assert.equal(r.body?.errorDetails, 'd');
    assert.equal(r.body?.retries, 0);
    assert.equal(r.body?.retryTimeoutMs, 1234);
  });

  it('an unexpected error -> fail with one retry less than the item carries', async () => {
    const server = new FakeServer();
    const r = await runOne(server, () => {
      throw new Error('boom');
    }, { retryBackoff: { initialMs: 10, maxMs: 10, factor: 1, jitter: 0 } }, { retries: 3 });
    assert.equal(r.action, 'fail');
    assert.equal(r.body?.errorMessage, 'boom');
    assert.equal(r.body?.retries, 2);
    assert.equal(r.body?.retryTimeoutMs, 10);
  });

  it('an unexpected error on an irreversible topic -> outcome-unknown, never a blind retry', async () => {
    const server = new FakeServer();
    const r = await runOne(server, () => {
      throw new Error('connection reset');
    }, { irreversible: true });
    assert.equal(r.action, 'outcome-unknown');
    assert.match(String(r.body?.detail), /^unexpected error on irreversible topic: connection reset/);
  });

  it('TaskCancelledError without a server cancel request -> unlock', async () => {
    const server = new FakeServer();
    const r = await runOne(server, () => {
      throw new TaskCancelledError('stop');
    });
    assert.equal(r.action, 'unlock');
  });
});

describe('SangamWorker report retries and fencing', () => {
  it('retries a retryable answer with the same Idempotency-Key until it is accepted', async () => {
    const server = new FakeServer().reply('complete', { status: 503, body: { title: 'busy' } }, { status: 503 }, { status: 204 });
    await runOne(server, () => ({ ok: true }));
    await waitUntil(() => server.callsOf('complete').length === 3);
    const keys = server.callsOf('complete').map((c) => c.headers['idempotency-key']);
    assert.deepEqual(keys, ['op-1:complete:3', 'op-1:complete:3', 'op-1:complete:3']);
    assert.ok(server.logs.some((l) => l.event === 'work.complete' && l.fields?.attempts === 3));
  });

  it('does not retry a non-retryable answer', async () => {
    const server = new FakeServer().reply('complete', { status: 400, body: { title: 'bad request' } });
    await runOne(server, () => ({ ok: true }));
    await waitUntil(() => server.logs.some((l) => l.event === 'work.report.failed'));
    assert.equal(server.callsOf('complete').length, 1);
  });

  it('abandons the item on 409 stale-lease (the lease is no longer ours)', async () => {
    const server = new FakeServer().reply('complete', { status: 409, body: { code: 'STALE_LEASE', title: 'stale lease' } });
    await runOne(server, () => ({ ok: true }));
    await waitUntil(() => server.logs.some((l) => l.event === 'work.abandoned'));
    assert.equal(server.callsOf('complete').length, 1);
  });

  it('gives up once the lease has expired', async () => {
    const server = new FakeServer().reply('complete', { status: 503 }, { status: 503 });
    await runOne(server, () => ({ ok: true }), {}, { lockExpiresAt: new Date(Date.now() - 1_000).toISOString() });
    await waitUntil(() => server.logs.some((l) => l.event === 'work.report.failed'));
    assert.equal(server.callsOf('complete').length, 1);
  });
});

describe('SangamWorker irreversibility (subscription flag only)', () => {
  it('a stray effect member on the work item does not make it irreversible', async () => {
    // the server sends no effect (WorkPort.ExternalWork); irreversibility is the subscription flag only
    const server = new FakeServer();
    const r = await runOne(server, () => {
      throw new Error('boom');
    }, {}, { effect: 'irreversible' } as unknown as Parameters<typeof workItem>[0]);
    assert.equal(r.action, 'fail');
  });

  it('never starts an item without its lockVersion (A-1)', async () => {
    const server = new FakeServer();
    const item = workItem();
    delete item.lockVersion;
    server.enqueue(item);
    let calls = 0;
    const worker = workerFor(server).subscribe('pay', () => {
      calls++;
      return {};
    });
    worker.start();
    await waitUntil(() => server.logs.some((l) => l.event === 'work.protocol.violation'));
    await worker.stop(2_000);
    assert.equal(calls, 0);
  });
});

describe('SangamWorker loop', () => {
  it('fetches only for free slots and sends the subscription filters', async () => {
    const server = new FakeServer();
    await runOne(server, () => ({}), { variables: ['amount'], businessKey: 'BILL-1' });
    const fetch = server.callsOf('fetch-and-lock')[0]!;
    assert.equal(fetch.body?.workerId, 'worker-1');
    assert.equal(fetch.body?.maxTasks, 1);
    assert.deepEqual(fetch.body?.topics, [{ name: 'pay', lockDurationMs: 60_000, variables: ['amount'], businessKey: 'BILL-1' }]);
  });

  it('never starts an item without the LOCKED confirmation (attemptId, A-1)', async () => {
    const server = new FakeServer();
    server.enqueue(workItem({ attemptId: '' }));
    let calls = 0;
    const worker = workerFor(server).subscribe('pay', () => {
      calls++;
      return {};
    });
    worker.start();
    await waitUntil(() => server.logs.some((l) => l.event === 'work.protocol.violation'));
    await worker.stop(2_000);
    assert.equal(calls, 0);
    assert.equal(server.calls.filter((c) => REPORT_ACTIONS.has(c.action)).length, 0);
  });

  it('unlocks an item of a topic it is not subscribed to', async () => {
    const server = new FakeServer();
    server.enqueue(workItem({ topic: 'other' }));
    const worker = workerFor(server).subscribe('pay', () => ({}));
    worker.start();
    const r = await server.waitFor((c) => c.action === 'unlock');
    await worker.stop(2_000);
    assert.equal(r.headers['idempotency-key'], 'op-1:unlock:3');
  });

  it('rejects invalid subscriptions', () => {
    const server = new FakeServer();
    const w = workerFor(server);
    assert.throws(() => w.subscribe('pay', () => ({}), { lockDurationMs: 1_000 }), RangeError);
    assert.throws(() => w.subscribe('pay', () => ({}), { lockDurationMs: 25 * 3600 * 1000 }), RangeError);
    assert.throws(() => w.subscribe('pay', () => ({}), { retries: -1 }), RangeError);
  });
});

async function waitUntil(cond: () => boolean, timeoutMs = 5_000): Promise<void> {
  const end = Date.now() + timeoutMs;
  while (!cond()) {
    if (Date.now() > end) throw new Error('condition not met in time');
    await new Promise((r) => setTimeout(r, 5));
  }
}
