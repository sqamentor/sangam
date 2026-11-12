// SPDX-License-Identifier: Apache-2.0
// M-025 durable steps (R37): ctx.step on the R18 test engine's step journal, through the real client.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import type { SangamClient } from '../src/client.js';
import type { WorkStep } from '../src/generated/models.js';
import { noopLogger } from '../src/logger.js';
import { type Step, StepNoEffectError } from '../src/steps.js';
import { SangamTestEngine } from '../src/testing.js';
import { TypedValues } from '../src/typed.js';
import { OutcomeUnknownError, TaskCancelledError, TaskContext } from '../src/worker.js';

const NO_RETRY = { maxAttempts: 1, initialDelayMs: 0, maxDelayMs: 0, multiplier: 1 };
const LEASE_MS = 60_000;

interface Rig { engine: SangamTestEngine; client: SangamClient; workId: string }

function rig(): Rig {
  const engine = new SangamTestEngine();
  const workId = engine.addWork('pay', { amount: TypedValues.decimal('12.50') });
  return { engine, client: engine.client({ retryPolicy: NO_RETRY }), workId };
}

/** Leases the work (a new attempt) and returns its context. */
async function lease(r: Rig, workerId = 'w1'): Promise<TaskContext> {
  const [w] = await r.client.work.fetchAndLock({ workerId, topics: [{ name: 'pay', lockDurationMs: LEASE_MS }], maxTasks: 1 });
  assert.ok(w, 'work was leased');
  return new TaskContext(w, workerId, r.client, noopLogger);
}

/** Lets the current lease expire and leases the same work again (same operationId, new attemptId). */
async function nextAttempt(r: Rig, workerId = 'w1'): Promise<TaskContext> {
  r.engine.advance(LEASE_MS);
  return lease(r, workerId);
}

async function journal(r: Rig): Promise<WorkStep[]> {
  return r.client.api.work.listWorkSteps(r.workId);
}

/** A step body that counts its calls. */
function counted<T>(fn: (s: Step) => T | Promise<T>): { calls: Step[]; fn: (s: Step) => Promise<T> } {
  const calls: Step[] = [];
  return { calls, fn: async (s) => { calls.push(s); return fn(s); } };
}

describe('ctx.step (M-025, R37)', () => {
  it('records INTENT then SUCCEEDED and replays the recorded result without calling again', async () => {
    const r = rig();
    const ctx = await lease(r);
    const body = counted((s: Step) => {
      s.providerRef = 'PAY-1';
      return { paymentRef: 'PAY-1', key: s.idempotencyKey };
    });
    const first = await ctx.step('charge', body.fn);
    assert.deepEqual(first, { paymentRef: 'PAY-1', key: `${ctx.operationId}:charge` });
    assert.equal(body.calls.length, 1);
    assert.equal(body.calls[0]!.previousState, undefined);
    assert.equal(body.calls[0]!.operationId, ctx.operationId);
    const steps = await journal(r);
    assert.equal(steps.length, 1);
    assert.equal(steps[0]!.state, 'SUCCEEDED');
    assert.equal(steps[0]!.providerRef, 'PAY-1');
    assert.equal(steps[0]!.attemptId, ctx.attemptId);

    const again = await nextAttempt(r);
    assert.equal(again.operationId, ctx.operationId);
    assert.notEqual(again.attemptId, ctx.attemptId);
    assert.deepEqual(await again.step('charge', body.fn), first);
    assert.equal(body.calls.length, 1, 'a SUCCEEDED step is never called again');
  });

  it('replays an R33 typed result unwrapped, like ctx.variable', async () => {
    const r = rig();
    const ctx = await lease(r);
    await ctx.step('fee', () => TypedValues.decimal('12.50'));
    const again = await nextAttempt(r);
    assert.equal(await again.step('fee', () => assert.fail('must not run')), '12.50');
  });

  it('a step returning nothing is recorded without a result and replays the journal null', async () => {
    const r = rig();
    const ctx = await lease(r);
    assert.equal(await ctx.step('notify', () => undefined), undefined);
    assert.equal((await journal(r))[0]!.result, null);
    const again = await nextAttempt(r);
    assert.equal(await again.step('notify', () => assert.fail('must not run')), null);
  });

  it('any other error leaves INTENT (uncertain) and propagates unchanged', async () => {
    const r = rig();
    const ctx = await lease(r);
    const boom = new Error('read timeout after send');
    await assert.rejects(ctx.step('charge', () => { throw boom; }), (e: unknown) => e === boom);
    const steps = await journal(r);
    assert.equal(steps[0]!.state, 'INTENT');
    assert.equal(steps[0]!.error, null);
  });

  it('INTENT with the default options (class c) throws OutcomeUnknownError and does not call again', async () => {
    const r = rig();
    const ctx = await lease(r);
    await assert.rejects(ctx.step('charge', () => { throw new Error('timeout'); }));
    const again = await nextAttempt(r);
    const body = counted(() => 'never');
    await assert.rejects(again.step('charge', body.fn), (e: unknown) => {
      if (!(e instanceof OutcomeUnknownError)) return false;
      assert.equal(e.message,
        `step charge of operation ${again.operationId} was started by an earlier attempt and its outcome is unknown (R37)`);
      assert.equal(e.details, 'step=charge');
      assert.deepEqual(e.variables, { step: 'charge', idempotencyKey: `${again.operationId}:charge` });
      return true;
    });
    assert.equal(body.calls.length, 0);
    assert.equal((await journal(r))[0]!.state, 'INTENT');
  });

  it('INTENT with a lookup that finds the effect records SUCCEEDED from the lookup', async () => {
    const r = rig();
    const ctx = await lease(r);
    await assert.rejects(ctx.step('charge', () => { throw new Error('timeout'); }));
    const again = await nextAttempt(r);
    const body = counted(() => 'never');
    const seen: Step[] = [];
    const v = await again.step('charge', body.fn, {
      lookup: (s) => {
        seen.push(s);
        s.providerRef = 'PAY-9';
        return 'found';
      },
    });
    assert.equal(v, 'found');
    assert.equal(body.calls.length, 0);
    assert.equal(seen[0]!.previousState, 'INTENT');
    assert.equal(seen[0]!.idempotencyKey, `${again.operationId}:charge`);
    const [row] = await journal(r);
    assert.equal(row!.state, 'SUCCEEDED');
    assert.equal(row!.result, 'found');
    assert.equal(row!.providerRef, 'PAY-9');
  });

  it('INTENT with a lookup that finds nothing runs the call again', async () => {
    const r = rig();
    const ctx = await lease(r);
    await assert.rejects(ctx.step('charge', () => { throw new Error('timeout'); }));
    const again = await nextAttempt(r);
    const body = counted(() => 'paid');
    for (const empty of [undefined, null]) {
      // the second iteration replays: the first one recorded SUCCEEDED
      assert.equal(await again.step('charge', body.fn, { lookup: async () => empty }), 'paid');
    }
    assert.equal(body.calls.length, 1);
    assert.equal(body.calls[0]!.previousState, 'INTENT');
  });

  it('a lookup error propagates and the step stays INTENT', async () => {
    const r = rig();
    const ctx = await lease(r);
    await assert.rejects(ctx.step('charge', () => { throw new Error('timeout'); }));
    const again = await nextAttempt(r);
    const down = new Error('provider status API down');
    await assert.rejects(again.step('charge', () => 'never', { lookup: () => { throw down; } }), (e: unknown) => e === down);
    assert.equal((await journal(r))[0]!.state, 'INTENT');
  });

  it('INTENT with idempotent (class a) runs again with the same idempotency key', async () => {
    const r = rig();
    const ctx = await lease(r);
    const keys: string[] = [];
    await assert.rejects(ctx.step('charge', (s) => { keys.push(s.idempotencyKey); throw new Error('timeout'); }));
    const again = await nextAttempt(r);
    const v = await again.step('charge', (s) => {
      keys.push(s.idempotencyKey);
      assert.equal(s.previousState, 'INTENT');
      return 7;
    }, { idempotent: true });
    assert.equal(v, 7);
    assert.deepEqual(keys, [`${ctx.operationId}:charge`, `${ctx.operationId}:charge`]);
    assert.equal((await journal(r))[0]!.state, 'SUCCEEDED');
  });

  it('StepNoEffectError records FAILED, propagates, and a later attempt runs the step again', async () => {
    const r = rig();
    const ctx = await lease(r);
    await assert.rejects(ctx.step('charge', (s) => {
      s.providerRef = 'REJ-1';
      throw new StepNoEffectError('insufficient funds');
    }), (e: unknown) => e instanceof StepNoEffectError && e.message === 'insufficient funds');
    const [row] = await journal(r);
    assert.equal(row!.state, 'FAILED');
    assert.equal(row!.error, 'insufficient funds');
    assert.equal(row!.providerRef, 'REJ-1');

    const again = await nextAttempt(r);
    const body = counted((s: Step) => s.previousState);
    assert.equal(await again.step('charge', body.fn), 'FAILED');
    assert.equal(body.calls.length, 1);
    assert.equal((await journal(r))[0]!.state, 'SUCCEEDED');
  });

  it('a stale lease marks the lease lost and throws TaskCancelledError without calling', async () => {
    const r = rig();
    const old = await lease(r, 'w1');
    await nextAttempt(r, 'w2');
    const body = counted(() => 'never');
    await assert.rejects(old.step('charge', body.fn), (e: unknown) => {
      if (!(e instanceof TaskCancelledError)) return false;
      assert.equal(e.message, 'lease lost while recording step charge');
      return true;
    });
    assert.equal(old.leaseLost, true);
    assert.equal(old.signal.aborted, true);
    assert.equal(body.calls.length, 0);
  });

  it('a lease lost while recording the outcome (410, deleted instance) throws TaskCancelledError', async () => {
    const r = rig();
    const ctx = await lease(r);
    await assert.rejects(ctx.step('charge', () => {
      r.engine.deleteInstance(r.workId);
      return 'paid';
    }), TaskCancelledError);
    assert.equal(ctx.leaseLost, true);
  });

  it('rejects a step name that is empty, longer than 128 or not printable ASCII', async () => {
    const r = rig();
    const ctx = await lease(r);
    for (const bad of ['', 'x'.repeat(129), 'tab\there', 'näme']) {
      await assert.rejects(ctx.step(bad, () => 1), TypeError);
    }
    assert.equal(await ctx.step('x'.repeat(128), () => 1), 1);
    assert.equal(await ctx.step('with space/and ~', () => 2), 2);
  });

  it('steps of one operation are independent and listed in recording order', async () => {
    const r = rig();
    const ctx = await lease(r);
    await ctx.step('reserve', () => 'r');
    await ctx.step('charge', () => 'c');
    assert.deepEqual((await journal(r)).map((s) => [s.name, s.state, s.result]), [
      ['reserve', 'SUCCEEDED', 'r'],
      ['charge', 'SUCCEEDED', 'c'],
    ]);
  });
});
