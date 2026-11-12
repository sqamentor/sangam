// SPDX-License-Identifier: Apache-2.0
// M-026 saga helper: the forward record variable, the compensation context and the two subscriptions.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import type { ExternalWork, Variables } from '../src/models.js';
import { noopLogger } from '../src/logger.js';
import { type CompensationContext, Saga } from '../src/saga.js';
import { SangamWorker, TaskContext } from '../src/worker.js';
import { clientFor, FakeServer, workItem } from './fake-server.js';

const server = new FakeServer();
const client = clientFor(server);

function ctxOf(over: Partial<ExternalWork> = {}): TaskContext {
  return new TaskContext(workItem(over), 'w1', client, noopLogger);
}

function chargeSaga(seen: CompensationContext[] = []): Saga {
  return Saga.step('charge')
    .action('payment.charge', async (ctx) => {
      ctx.outcomeReference = 'PAY-1';
      return { chargeId: 'C-1' };
    })
    .compensation('payment.refund', (c) => {
      seen.push(c);
      return { refunded: true };
    })
    .build();
}

describe('Saga (M-026)', () => {
  it('names the record variable sangamSaga_<name> with . and - as _', () => {
    assert.equal(Saga.recordVariable('charge'), 'sangamSaga_charge');
    assert.equal(Saga.recordVariable('pay.charge-1'), 'sangamSaga_pay_charge_1');
  });

  it('the forward action returns its variables plus the R33 Json record of the forward operation', async () => {
    const saga = chargeSaga();
    assert.equal(saga.name, 'charge');
    assert.equal(saga.action.topic, 'payment.charge');
    assert.equal(saga.compensation.topic, 'payment.refund');
    const out = await saga.action.handler(ctxOf({ id: 'wk-7', operationId: 'op-7' }));
    assert.deepEqual(out, {
      chargeId: 'C-1',
      sangamSaga_charge: { type: 'Json', value: { step: 'charge', operationId: 'op-7', providerRef: 'PAY-1', workId: 'wk-7' } },
    });
  });

  it('records providerRef null when the action sets no outcome reference, and works with no variables', async () => {
    const saga = Saga.step('reserve').action('stock.reserve', () => undefined).compensation('stock.release', () => undefined).build();
    const out = (await saga.action.handler(ctxOf({ id: 'wk-2', operationId: undefined }))) as Variables;
    assert.deepEqual(out, {
      sangamSaga_reserve: { type: 'Json', value: { step: 'reserve', operationId: 'wk-2', providerRef: null, workId: 'wk-2' } },
    });
  });

  it('the compensation gets the forward record and the derived idempotency key', async () => {
    const seen: CompensationContext[] = [];
    const saga = chargeSaga(seen);
    const fwd = (await saga.action.handler(ctxOf({ id: 'wk-7', operationId: 'op-7' }))) as Variables;
    const undoCtx = ctxOf({ id: 'wk-8', operationId: 'op-8', variables: { sangamSaga_charge: fwd.sangamSaga_charge } });
    assert.deepEqual(await saga.compensation.handler(undoCtx), { refunded: true });
    const c = seen[0]!;
    assert.equal(c.task, undoCtx);
    assert.deepEqual(c.forward, { step: 'charge', operationId: 'op-7', providerRef: 'PAY-1', workId: 'wk-7' });
    assert.equal(c.forwardOperationId, 'op-7');
    assert.equal(c.forwardProviderRef, 'PAY-1');
    assert.equal(c.idempotencyKey, 'op-7:compensate:charge');
  });

  it('accepts the record as a plain JSON object too', async () => {
    const seen: CompensationContext[] = [];
    await chargeSaga(seen).compensation.handler(ctxOf({ variables: { sangamSaga_charge: { operationId: 'op-3', step: 'charge' } } }));
    assert.equal(seen[0]!.forwardOperationId, 'op-3');
    assert.equal(seen[0]!.forwardProviderRef, null);
  });

  it('without a record (or one without operationId) the compensation uses its own operationId', async () => {
    const seen: CompensationContext[] = [];
    const saga = chargeSaga(seen);
    await saga.compensation.handler(ctxOf({ operationId: 'op-9' }));
    await saga.compensation.handler(ctxOf({ operationId: 'op-9', variables: { sangamSaga_charge: { type: 'Json', value: { step: 'charge' } } } }));
    for (const c of seen) {
      assert.equal(c.forward, null);
      assert.equal(c.forwardOperationId, null);
      assert.equal(c.forwardProviderRef, null);
      assert.equal(c.idempotencyKey, 'op-9');
    }
  });

  it('applies the options; irreversible only to the forward action; restricted variables add the record to the compensation', () => {
    const saga = Saga.step('pay.charge')
      .action('a', () => undefined)
      .compensation('b', () => undefined)
      .lockDurationMs(120_000)
      .retries(5)
      .irreversible(true)
      .variables(['amount'])
      .build();
    assert.equal(saga.action.lockDurationMs, 120_000);
    assert.equal(saga.compensation.lockDurationMs, 120_000);
    assert.equal(saga.action.retries, 5);
    assert.equal(saga.compensation.retries, 5);
    assert.equal(saga.action.irreversible, true);
    assert.equal(saga.compensation.irreversible, false);
    assert.deepEqual(saga.action.variables, ['amount']);
    assert.deepEqual(saga.compensation.variables, ['amount', 'sangamSaga_pay_charge']);
    assert.deepEqual(saga.subscriptions().map((s) => s.topic), ['a', 'b']);
    const all = chargeSaga();
    assert.equal(all.action.variables, undefined);
    assert.equal(all.compensation.variables, undefined);
  });

  it('rejects a bad name, a missing handler and a shared topic', () => {
    for (const bad of ['', 'x'.repeat(65), 'has space', 'a/b']) assert.throws(() => Saga.step(bad), TypeError);
    assert.throws(() => Saga.step('s').action('a', () => undefined).build(), /needs an action and a compensation/);
    assert.throws(
      () => Saga.step('s').action('t', () => undefined).compensation('t', () => undefined).build(),
      /action and compensation need their own topics/,
    );
  });

  it('registerOn subscribes both topics on the worker', () => {
    const s = new FakeServer();
    const worker = new SangamWorker(clientFor(s), { workerId: 'w1' });
    assert.equal(chargeSaga().registerOn(worker), worker);
    assert.deepEqual(s.logs.filter((l) => l.event === 'worker.subscribed').map((l) => l.fields?.topic), ['payment.charge', 'payment.refund']);
  });
});
