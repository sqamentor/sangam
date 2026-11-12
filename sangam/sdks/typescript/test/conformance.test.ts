// SPDX-License-Identifier: Apache-2.0
// M-023 / R18 / AC-08: contract tests T01-T04 and T18 (SANGAM.md II §16) for the TypeScript SDK, run against the R18
// test engine (src/testing.ts) through the real SangamClient and SangamWorker.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import { SangamApiError, StaleLeaseError, TransportError } from '../src/errors.js';
import type { Logger, LogFields } from '../src/logger.js';
import type { Variables } from '../src/models.js';
import { SangamTestEngine } from '../src/testing.js';
import { fromTypedValue, TypedValues, type TypedValue } from '../src/typed.js';
import { OutcomeUnknownError, SangamWorker, type TaskHandler } from '../src/worker.js';

type Json = Record<string, unknown>;
const NO_RETRY = { maxAttempts: 1, initialDelayMs: 0, maxDelayMs: 0, multiplier: 1 };
const FAST = { initialMs: 1, maxMs: 2, factor: 1, jitter: 0 };

class Run {
  readonly logs: Array<{ event: string; fields?: LogFields }> = [];
  readonly logger: Logger = { log: (_l, event, _m, fields) => { this.logs.push({ event, fields }); } };
  readonly worker: SangamWorker;

  constructor(readonly engine: SangamTestEngine, workerId = 'worker-1') {
    this.worker = new SangamWorker(engine.client({ retryPolicy: NO_RETRY, logger: this.logger }), {
      workerId, maxConcurrency: 1, maxTasksPerFetch: 1, asyncResponseTimeoutMs: 10, fetchBackoff: FAST,
      reportBackoff: FAST, shutdownTimeoutMs: 2_000, logger: this.logger,
    });
  }

  events(name: string): Array<LogFields | undefined> {
    return this.logs.filter((l) => l.event === name).map((l) => l.fields);
  }

  async with(topic: string, handler: TaskHandler, body: () => Promise<void>, lockDurationMs = 60_000, irreversible = false): Promise<void> {
    this.worker.subscribe(topic, handler, { lockDurationMs, irreversible }).start();
    try {
      await body();
    } finally {
      await this.worker.stop(2_000);
    }
  }
}

async function until(cond: () => boolean, timeoutMs = 10_000): Promise<void> {
  const end = Date.now() + timeoutMs;
  while (!cond()) {
    if (Date.now() > end) throw new Error('condition not met in time');
    await new Promise((r) => setTimeout(r, 5));
  }
}

// ------------------------------------------------------------------ T01

describe('T01 duplicate process start', () => {
  it('T01: the same key and payload give one instance; a conflicting payload is rejected', async () => {
    const engine = new SangamTestEngine();
    const client = engine.client({ retryPolicy: NO_RETRY });
    const req = { key: 'p1_bill', businessKey: 'BILL-1', variables: { amount: TypedValues.decimal('125000.00') } };
    const first = await client.processes.start(req, { idempotencyKey: 'start-1' });
    const again = await client.processes.start(req, { idempotencyKey: 'start-1' });
    assert.equal(again.id, first.id);
    assert.equal(again.idempotentReplayed, true);
    assert.equal(engine.instances.length, 1);
    await assert.rejects(
      client.processes.start({ ...req, variables: { amount: TypedValues.decimal('1.00') } }, { idempotencyKey: 'start-1' }),
      (e: unknown) => e instanceof SangamApiError && e.status === 422 && e.problem.code === 'IDEMPOTENCY_KEY_REUSED',
    );
    assert.equal(engine.instances.length, 1);
  });

  it('T01: simultaneous duplicate starts give one instance', async () => {
    const engine = new SangamTestEngine();
    const client = engine.client({ retryPolicy: NO_RETRY });
    const all = await Promise.all([1, 2, 3, 4, 5].map(() => client.processes.start({ key: 'p1', businessKey: 'BK' }, { idempotencyKey: 'same' })));
    assert.equal(new Set(all.map((p) => p.id)).size, 1);
    assert.equal(engine.instances.length, 1);
  });
});

// ------------------------------------------------------------------ T02

describe('T02 effect happened, answer lost', () => {
  it('T02: a lost complete answer is retried with the same Idempotency-Key and completes once', async () => {
    const engine = new SangamTestEngine();
    const wid = engine.addWork('pay', { amount: { type: 'Decimal', value: '12.50' } });
    engine.loseNextResponse('complete');
    const run = new Run(engine);
    await run.with('pay', () => ({ paid: true }), async () => {
      await until(() => engine.state(wid) === 'COMPLETED' && run.events('work.complete').length === 1);
    });
    const calls = engine.callsOf('complete');
    assert.ok(calls.length >= 2, 'the lost answer must be retried');
    assert.equal(new Set(calls.map((c) => c.idempotencyKey)).size, 1, 'every retry carries the same Idempotency-Key');
    assert.equal(engine.completions(wid), 1);
  });

  it('T02: OutcomeUnknown opens a reconciliation item and the work is never re-offered', async () => {
    const engine = new SangamTestEngine();
    const wid = engine.addWork('pay');
    const run = new Run(engine);
    await run.with('pay', () => { throw new OutcomeUnknownError('bank timeout after debit request'); }, async () => {
      await until(() => engine.state(wid) === 'UNKNOWN_OUTCOME');
      engine.advance(10 * 60_000);
      await new Promise((r) => setTimeout(r, 100));
    });
    assert.ok(engine.reconciliationItems.some((i) => i.workId === wid));
    assert.equal(engine.snapshot(wid).attempts, 1, 'UNKNOWN_OUTCOME work is never handed out again');
  });

  it('T02: an unexpected error on an irreversible topic is outcome-unknown, not fail', async () => {
    const engine = new SangamTestEngine({ irreversibleTopics: ['pay-irreversible'] });
    const wid = engine.addWork('pay-irreversible');
    const run = new Run(engine);
    await run.with('pay-irreversible', () => { throw new Error('connection reset after send'); }, async () => {
      await until(() => engine.state(wid) === 'UNKNOWN_OUTCOME');
    }, 60_000, true);
    assert.equal(engine.callsOf('fail').length, 0);
  });
});

// ------------------------------------------------------------------ T03

describe('T03 stale worker after lease expiry', () => {
  it('T03: the old worker gets STALE_LEASE, nothing changes, the new owner completes', async () => {
    const engine = new SangamTestEngine();
    const wid = engine.addWork('pay');
    const a = engine.client({ retryPolicy: NO_RETRY });
    const b = engine.client({ retryPolicy: NO_RETRY });
    const [wa] = await a.work.fetchAndLock({ workerId: 'worker-a', topics: [{ name: 'pay', lockDurationMs: 10_000 }] });
    engine.advance(10_001);
    const [wb] = await b.work.fetchAndLock({ workerId: 'worker-b', topics: [{ name: 'pay', lockDurationMs: 10_000 }] });
    assert.equal(wb!.id, wid);
    assert.ok(wb!.lockVersion! > wa!.lockVersion!);
    assert.equal(wb!.operationId, wa!.operationId);
    await assert.rejects(
      a.work.complete(wid, { workerId: 'worker-a', lockVersion: wa!.lockVersion!, attemptId: wa!.attemptId, variables: { by: 'a' } }),
      (e: unknown) => e instanceof StaleLeaseError && !e.retryable && e.problem.code === 'STALE_LEASE',
    );
    assert.equal(engine.state(wid), 'LOCKED');
    assert.equal(engine.snapshot(wid).workerId, 'worker-b');
    await b.work.complete(wid, { workerId: 'worker-b', lockVersion: wb!.lockVersion!, attemptId: wb!.attemptId, variables: { by: 'b' } });
    assert.equal(engine.completions(wid), 1);
    assert.deepEqual(engine.snapshot(wid).output, { by: 'b' });
  });

  it('T03: the worker drops a stale result without retrying', async () => {
    const engine = new SangamTestEngine();
    const wid = engine.addWork('pay');
    let release!: () => void;
    const released = new Promise<void>((r) => { release = r; });
    const other = engine.client({ retryPolicy: NO_RETRY });
    const run = new Run(engine, 'worker-a');
    let wb: Json | undefined;
    await run.with('pay', async () => { await released; return { by: 'a' }; }, async () => {
      await until(() => engine.state(wid) === 'LOCKED');
      engine.advance(30_001);
      [wb] = (await other.work.fetchAndLock({ workerId: 'worker-b', topics: [{ name: 'pay' }] })) as unknown as Json[];
      release();
      await until(() => run.events('work.abandoned').length > 0 || run.events('work.lease.lost').length > 0);
      await new Promise((r) => setTimeout(r, 50));
    }, 30_000);
    const aCompletes = engine.callsOf('complete').filter((c) => (c.body as Json | undefined)?.workerId === 'worker-a');
    assert.ok(aCompletes.length <= 1, 'a 409 STALE_LEASE is never retried');
    assert.equal(engine.snapshot(wid).workerId, 'worker-b');
    await other.work.complete(wid, { workerId: 'worker-b', lockVersion: wb!.lockVersion as number, attemptId: wb!.attemptId as string });
    assert.equal(engine.completions(wid), 1);
  });
});

// ------------------------------------------------------------------ T04

describe('T04 transport loss does not re-dispatch acknowledged work', () => {
  it('T04: a lost fetch answer keeps the work LOCKED until the lease expires', async () => {
    const engine = new SangamTestEngine();
    const wid = engine.addWork('pay');
    const client = engine.client({ retryPolicy: NO_RETRY });
    engine.loseNextResponse('fetch-and-lock');
    await assert.rejects(client.work.fetchAndLock({ workerId: 'worker-a', topics: [{ name: 'pay', lockDurationMs: 20_000 }] }), TransportError);
    assert.equal(engine.state(wid), 'LOCKED');
    assert.deepEqual(await client.work.fetchAndLock({ workerId: 'worker-b', topics: [{ name: 'pay' }] }), []);
    engine.advance(19_999);
    assert.deepEqual(await client.work.fetchAndLock({ workerId: 'worker-b', topics: [{ name: 'pay' }] }), []);
    engine.advance(1);
    const [w] = await client.work.fetchAndLock({ workerId: 'worker-b', topics: [{ name: 'pay' }] });
    assert.equal(w!.id, wid);
    assert.ok(w!.lockVersion! > 1);
  });

  it('T04: a heart-beating lease holder is never re-offered', async () => {
    const engine = new SangamTestEngine();
    const wid = engine.addWork('pay');
    const client = engine.client({ retryPolicy: NO_RETRY });
    const [w] = await client.work.fetchAndLock({ workerId: 'worker-a', topics: [{ name: 'pay', lockDurationMs: 9_000 }] });
    for (let i = 0; i < 5; i++) {
      engine.advance(6_000);
      const ls = await client.work.extendLock(wid, { workerId: 'worker-a', lockVersion: w!.lockVersion!, attemptId: w!.attemptId, newDurationMs: 9_000 });
      assert.equal(ls?.lockVersion, w!.lockVersion);
      assert.deepEqual(await client.work.fetchAndLock({ workerId: 'worker-b', topics: [{ name: 'pay' }] }), []);
    }
    await client.work.complete(wid, { workerId: 'worker-a', lockVersion: w!.lockVersion!, attemptId: w!.attemptId });
    assert.equal(engine.completions(wid), 1);
  });
});

// ------------------------------------------------------------------ T18

interface Vector { name: string; wire: TypedValue | null; value: string | null; kind: string }
const VECTORS = (JSON.parse(readFileSync(new URL('../../../testing/t18-vectors.json', import.meta.url), 'utf8')) as { vectors: Vector[] }).vectors;

/** The SDK's native value for a vector (fromTypedValue), re-encoded with the TypedValues builders. */
function reEncode(kind: string, v: unknown): unknown {
  switch (kind) {
    case 'null': return null;
    case 'decimal': return TypedValues.decimal(v as string);
    case 'biginteger': return TypedValues.bigInteger(v as bigint);
    case 'long': return TypedValues.long(v as bigint);
    case 'integer': return TypedValues.integer(v as number);
    case 'boolean': return TypedValues.boolean(v as boolean);
    case 'string': return TypedValues.string(v as string);
    case 'date': return TypedValues.date(v as Date);
    case 'localdate': return TypedValues.localDate(v as string);
    default: throw new Error(`unknown kind ${kind}`);
  }
}

function same(kind: string, expected: string | null, actual: unknown): void {
  switch (kind) {
    case 'null': assert.equal(actual, null); break;
    case 'decimal': assert.equal(actual, expected); break; // exact string: same number and scale
    case 'biginteger':
    case 'long': assert.equal(typeof actual, 'bigint'); assert.equal(actual, BigInt(expected!)); break; // no digit lost beyond 2^53
    case 'integer': assert.equal(actual, Number(expected)); break;
    case 'boolean': assert.equal(actual, expected === 'true'); break;
    case 'string': assert.equal(actual, expected); break;
    case 'date': assert.ok(actual instanceof Date); assert.equal(actual.getTime(), Date.parse(expected!)); break; // same instant
    case 'localdate': assert.equal(actual, expected); break;
    default: throw new Error(`unknown kind ${kind}`);
  }
}

describe('T18 decimal, large integer, date and null', () => {
  for (const v of VECTORS) {
    it(`T18: ${v.name} survives decode and re-encode`, () => {
      const decoded = fromTypedValue(v.wire);
      same(v.kind, v.value, decoded);
      const wire = JSON.parse(JSON.stringify(reEncode(v.kind, decoded))) as unknown;
      same(v.kind, v.value, fromTypedValue(wire));
    });
  }

  it('T18: every vector survives a round trip through a worker', async () => {
    const engine = new SangamTestEngine();
    const wid = engine.addWork('t18', Object.fromEntries(VECTORS.map((v) => [v.name, v.wire])));
    const run = new Run(engine);
    await run.with('t18', (ctx) => ({ ...ctx.variables }) as Variables, async () => {
      await until(() => engine.state(wid) === 'COMPLETED');
    });
    const out = engine.snapshot(wid).output as Json;
    for (const v of VECTORS) {
      assert.ok(v.name in out, v.name);
      same(v.kind, v.value, fromTypedValue(out[v.name]));
    }
  });
});
