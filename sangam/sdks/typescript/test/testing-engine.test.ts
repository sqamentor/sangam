// SPDX-License-Identifier: Apache-2.0
// M-021: the R18 test engine's state machine (VI.3), through `SangamTestEngine.handle` and once through the client.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { StaleLeaseError } from '../src/errors.js';
import { DEFAULT_LOCK_MS, MAX_LOCK_MS, SangamTestEngine } from '../src/testing.js';

type Json = Record<string, unknown>;
const NO_RETRY = { maxAttempts: 1, initialDelayMs: 0, maxDelayMs: 0, multiplier: 1 };

function fetchWork(e: SangamTestEngine, worker = 'w1', topic = 'pay', extra: Json = {}): Json[] {
  const a = e.handle('POST', '/work/fetch-and-lock', undefined, { workerId: worker, topics: [{ name: topic, ...extra }], maxTasks: 10 });
  assert.equal(a.status, 200);
  return a.body as Json[];
}

function post(e: SangamTestEngine, wid: string, action: string, body: Json): [number, Json] {
  const a = e.handle('POST', `/work/${wid}/${action}`, undefined, body);
  return [a.status, (a.body ?? {}) as Json];
}

const fence = (w: Json, extra: Json = {}): Json => ({ workerId: 'w1', lockVersion: w.lockVersion, attemptId: w.attemptId, ...extra });

describe('SangamTestEngine (M-021)', () => {
  it('grants lockVersion, attemptId and an INTENT operation; LOCKED work is not handed out again', () => {
    const e = new SangamTestEngine();
    const wid = e.addWork('pay', { a: 1 });
    const [w] = fetchWork(e);
    assert.equal(w!.id, wid);
    assert.equal(w!.lockVersion, 1);
    assert.deepEqual(w!.operation, { state: 'INTENT', attempts: 1, mayDispatch: true });
    assert.equal(e.state(wid), 'LOCKED');
    assert.deepEqual(fetchWork(e, 'w2'), []);
  });

  it('validates fetch-and-lock', () => {
    const e = new SangamTestEngine();
    const a = e.handle('POST', '/work/fetch-and-lock', undefined, { topics: [{ name: 'pay' }] });
    assert.equal(a.status, 400);
    assert.equal((a.body as Json).code, 'VALIDATION_FAILED');
    assert.equal(e.handle('POST', '/work/fetch-and-lock', undefined, { workerId: 'w', topics: [] }).status, 400);
  });

  it('hands out one item by default and caps the lease at 24 h', () => {
    const e = new SangamTestEngine();
    e.addWork('pay');
    e.addWork('pay');
    const a = e.handle('POST', '/work/fetch-and-lock', undefined, { workerId: 'w', topics: [{ name: 'pay', lockDurationMs: MAX_LOCK_MS * 2 }] });
    const items = a.body as Json[];
    assert.equal(items.length, 1);
    e.advance(MAX_LOCK_MS);
    assert.ok(fetchWork(e, 'w2').some((x) => x.id === items[0]!.id));
  });

  it('fences in the server order with the ApiProblem shape and changes nothing', () => {
    const e = new SangamTestEngine();
    const wid = e.addWork('pay');
    const [w] = fetchWork(e);
    let [s, p] = post(e, wid, 'complete', fence(w!, { lockVersion: (w!.lockVersion as number) + 1 }));
    assert.equal(s, 409);
    assert.equal(p.code, 'STALE_LEASE');
    assert.equal(p.reason, 'STALE_VERSION');
    assert.equal(p.type, 'https://sangam.dev/problems/stale-lease');
    assert.equal(p.title, 'Stale lease');
    assert.equal(p.workId, wid);
    [, p] = post(e, wid, 'complete', fence(w!, { workerId: 'intruder' }));
    assert.equal(p.reason, 'NOT_OWNER');
    [, p] = post(e, wid, 'complete', fence(w!, { attemptId: 'old' }));
    assert.equal(p.reason, 'ATTEMPT_MISMATCH');
    assert.equal(e.state(wid), 'LOCKED');
    [s, p] = post(e, 'nope', 'complete', fence(w!));
    assert.equal(s, 404);
    assert.equal(p.code, 'NOT_FOUND');
  });

  it('expiry returns work to AVAILABLE with a new version and the same operationId', () => {
    const e = new SangamTestEngine();
    const wid = e.addWork('pay');
    const [w] = fetchWork(e, 'w1', 'pay', { lockDurationMs: 10_000 });
    e.advance(10_000);
    const [w2] = fetchWork(e, 'w2');
    assert.equal(w2!.id, wid);
    assert.ok((w2!.lockVersion as number) > (w!.lockVersion as number));
    assert.notEqual(w2!.attemptId, w!.attemptId);
    assert.equal(w2!.operationId, w!.operationId);
    assert.equal(post(e, wid, 'complete', fence(w!))[0], 409);
  });

  it('irreversible expiry and an unacknowledged cancel end in UNKNOWN_OUTCOME', () => {
    const e = new SangamTestEngine({ irreversibleTopics: ['pay'] });
    const a = e.addWork('pay');
    fetchWork(e, 'w1', 'pay', { lockDurationMs: 5_000 });
    e.advance(5_000);
    assert.deepEqual(fetchWork(e, 'w2'), []);
    assert.equal(e.state(a), 'UNKNOWN_OUTCOME');
    assert.equal(e.reconciliationItems.at(-1)!.workId, a);
    const b = e.addWork('ship');
    fetchWork(e, 'w1', 'ship', { lockDurationMs: 5_000 });
    e.requestCancel(b);
    assert.equal(e.state(b), 'CANCEL_REQUESTED');
    e.advance(5_000);
    fetchWork(e, 'w1', 'ship');
    assert.equal(e.state(b), 'UNKNOWN_OUTCOME');
  });

  it('extend-lock keeps the version and reports cancelRequested; cancel-ack cancels', () => {
    const e = new SangamTestEngine();
    const wid = e.addWork('pay');
    const [w] = fetchWork(e, 'w1', 'pay', { lockDurationMs: 9_000 });
    e.advance(6_000);
    let [s, ls] = post(e, wid, 'extend-lock', fence(w!, { newDurationMs: 9_000 }));
    assert.equal(s, 200);
    assert.equal(ls.lockVersion, w!.lockVersion);
    assert.equal(ls.cancelRequested, false);
    e.advance(6_000);
    assert.deepEqual(fetchWork(e, 'w2'), []);
    e.requestCancel(wid);
    [, ls] = post(e, wid, 'extend-lock', fence(w!, { newDurationMs: 9_000 }));
    assert.equal(ls.cancelRequested, true);
    assert.equal(post(e, wid, 'cancel-ack', fence(w!))[0], 204);
    assert.equal(e.state(wid), 'CANCELLED');
  });

  it('completes once; an identical repeat answers 204 again', () => {
    const e = new SangamTestEngine();
    const wid = e.addWork('pay');
    const [w] = fetchWork(e);
    const body = fence(w!, { variables: { ok: true } });
    assert.equal(post(e, wid, 'complete', body)[0], 204);
    assert.equal(post(e, wid, 'complete', body)[0], 204);
    assert.equal(e.completions(wid), 1);
    const [s, p] = post(e, wid, 'complete', fence(w!, { variables: { ok: false } }));
    assert.equal(s, 404);
    assert.equal(p.code, 'NOT_FOUND');
  });

  it('fail with retries goes back after the back-off, retries = 0 raises an incident', () => {
    const e = new SangamTestEngine();
    const wid = e.addWork('pay', {}, { retries: 2 });
    const [w] = fetchWork(e);
    assert.equal(post(e, wid, 'fail', fence(w!, { errorMessage: 'x', retries: 1, retryTimeoutMs: 1_000 }))[0], 204);
    assert.equal(e.state(wid), 'AVAILABLE');
    assert.deepEqual(fetchWork(e), []);
    e.advance(1_000);
    const [w2] = fetchWork(e);
    assert.equal(w2!.retries, 1);
    assert.equal(w2!.errorMessage, 'x');
    assert.equal(post(e, wid, 'fail', fence(w2!, { retries: 0 }))[0], 204);
    assert.equal(e.state(wid), 'INCIDENT');
  });

  it('a failure on an irreversible topic becomes outcome-unknown', () => {
    const e = new SangamTestEngine({ irreversibleTopics: ['pay'] });
    const wid = e.addWork('pay');
    const [w] = fetchWork(e);
    assert.equal(post(e, wid, 'fail', fence(w!, { errorMessage: 'timeout' }))[0], 204);
    assert.equal(e.state(wid), 'UNKNOWN_OUTCOME');
  });

  it('bpmn-error, unlock and outcome-unknown; RETRY re-offers the same operationId', () => {
    const e = new SangamTestEngine();
    const a = e.addWork('pay');
    const b = e.addWork('pay');
    const c = e.addWork('pay');
    const [wa, wb, wc] = fetchWork(e);
    assert.equal(post(e, a, 'bpmn-error', fence(wa!))[0], 400);
    assert.equal(post(e, a, 'bpmn-error', fence(wa!, { errorCode: 'E1' }))[0], 204);
    assert.equal(e.state(a), 'COMPLETED_WITH_ERROR');
    assert.equal(post(e, b, 'unlock', fence(wb!))[0], 204);
    assert.equal(e.lockVersion(b), (wb!.lockVersion as number) + 1);
    const [s, r] = post(e, c, 'outcome-unknown', fence(wc!, { detail: 'timeout' }));
    assert.equal(s, 202);
    assert.equal(r.state, 'UNKNOWN_OUTCOME');
    assert.equal(r.operationId, wc!.operationId);
    e.advance(DEFAULT_LOCK_MS * 3);
    assert.ok(!fetchWork(e, 'w9').some((x) => x.id === c));
    e.resolveReconciliation(r.reconciliationItemId as string, 'RETRY');
    const again = fetchWork(e, 'w9').find((x) => x.id === c);
    assert.equal(again?.operationId, wc!.operationId);
  });

  it('a result for a deleted instance answers 410', () => {
    const e = new SangamTestEngine();
    const wid = e.addWork('pay');
    const [w] = fetchWork(e);
    e.deleteInstance(wid);
    const [s, p] = post(e, wid, 'complete', fence(w!));
    assert.equal(s, 410);
    assert.equal(p.code, 'GONE_INSTANCE_DELETED');
    assert.ok(p.reconciliationItemId);
  });

  it('process start is idempotent per key (R41)', () => {
    const e = new SangamTestEngine();
    const a1 = e.handle('POST', '/process-instances', 'k1', { key: 'p1', businessKey: 'BK' });
    const a2 = e.handle('POST', '/process-instances', 'k1', { key: 'p1', businessKey: 'BK' });
    assert.equal(a1.status, 201);
    assert.deepEqual(a2.body, a1.body);
    assert.equal(a2.headers['Idempotent-Replayed'], 'true');
    assert.equal(e.instances.length, 1);
    const a3 = e.handle('POST', '/process-instances', 'k1', { key: 'p1', businessKey: 'OTHER' });
    assert.equal(a3.status, 422);
    assert.equal((a3.body as Json).code, 'IDEMPOTENCY_KEY_REUSED');
  });

  it('serves the real client through its fetch seam', async () => {
    const e = new SangamTestEngine();
    const client = e.client({ retryPolicy: NO_RETRY });
    const wid = e.addWork('pay');
    const [w] = await client.work.fetchAndLock({ workerId: 'w1', topics: [{ name: 'pay' }] });
    assert.equal(w!.id, wid);
    await assert.rejects(client.work.complete(wid, { workerId: 'w1', lockVersion: 99, attemptId: w!.attemptId }), StaleLeaseError);
    await client.work.complete(wid, { workerId: 'w1', lockVersion: w!.lockVersion!, attemptId: w!.attemptId, variables: { x: 1 } });
    assert.equal(e.state(wid), 'COMPLETED');
  });
});
