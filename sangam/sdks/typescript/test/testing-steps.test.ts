// SPDX-License-Identifier: Apache-2.0
// M-025: the R18 test engine's step journal (GET/PUT /work/{id}/steps/{name}, GET /work/{id}/steps), same transitions
// and fencing as the server, through `SangamTestEngine.handle`.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { SangamTestEngine } from '../src/testing.js';

type Json = Record<string, unknown>;

function leased(e: SangamTestEngine, worker = 'w1'): { wid: string; w: Json } {
  const wid = e.addWork('pay');
  const a = e.handle('POST', '/work/fetch-and-lock', undefined, { workerId: worker, topics: [{ name: 'pay', lockDurationMs: 60_000 }], maxTasks: 1 });
  const w = (a.body as Json[])[0]!;
  assert.equal(w.id, wid);
  return { wid, w };
}

const fence = (w: Json, extra: Json = {}): Json => ({ workerId: 'w1', lockVersion: w.lockVersion, attemptId: w.attemptId, ...extra });

function put(e: SangamTestEngine, wid: string, name: string, body: Json): [number, Json] {
  const a = e.handle('PUT', `/work/${wid}/steps/${encodeURIComponent(name)}`, undefined, body);
  return [a.status, (a.body ?? {}) as Json];
}

function get(e: SangamTestEngine, wid: string, name: string, q: Json): [number, Json] {
  const query = new URLSearchParams();
  for (const [k, v] of Object.entries(q)) if (v !== undefined) query.set(k, String(v));
  const a = e.handle('GET', `/work/${wid}/steps/${encodeURIComponent(name)}`, undefined, undefined, query);
  return [a.status, (a.body ?? {}) as Json];
}

describe('SangamTestEngine step journal (M-025)', () => {
  it('INTENT first, then SUCCEEDED; GET returns the row fenced by the query lease', async () => {
    const e = new SangamTestEngine();
    const { wid, w } = leased(e);
    let [s, b] = get(e, wid, 'charge', fence(w));
    assert.equal(s, 404);
    assert.equal(b.code, 'NOT_FOUND');
    [s, b] = put(e, wid, 'charge', fence(w, { state: 'INTENT' }));
    assert.equal(s, 200);
    assert.equal(b.state, 'INTENT');
    assert.equal(b.operationId, w.operationId);
    assert.equal(b.attemptId, w.attemptId);
    [s, b] = put(e, wid, 'charge', fence(w, { state: 'SUCCEEDED', result: { ref: 'P1' }, providerRef: 'P1' }));
    assert.equal(s, 200);
    [s, b] = get(e, wid, 'charge', fence(w));
    assert.equal(s, 200);
    assert.deepEqual(
      { state: b.state, result: b.result, providerRef: b.providerRef, error: b.error, name: b.name },
      { state: 'SUCCEEDED', result: { ref: 'P1' }, providerRef: 'P1', error: null, name: 'charge' },
    );
    assert.equal(typeof b.recordedAt, 'string');
  });

  it('SUCCEEDED or FAILED without an INTENT is 409 STEP_INTENT_REQUIRED', () => {
    const e = new SangamTestEngine();
    const { wid, w } = leased(e);
    for (const state of ['SUCCEEDED', 'FAILED']) {
      const [s, b] = put(e, wid, 'charge', fence(w, { state }));
      assert.equal(s, 409);
      assert.equal(b.code, 'STEP_INTENT_REQUIRED');
    }
  });

  it('SUCCEEDED is final; an identical repeat is a no-op', () => {
    const e = new SangamTestEngine();
    const { wid, w } = leased(e);
    put(e, wid, 'charge', fence(w, { state: 'INTENT' }));
    const done = fence(w, { state: 'SUCCEEDED', result: { b: 2, a: 1 }, providerRef: 'P1' });
    assert.equal(put(e, wid, 'charge', done)[0], 200);
    assert.equal(put(e, wid, 'charge', { ...done, result: { a: 1, b: 2 } })[0], 200, 'same result in another key order');
    for (const body of [
      { ...done, result: { a: 1 } },
      { ...done, providerRef: 'P2' },
      fence(w, { state: 'INTENT' }),
      fence(w, { state: 'FAILED', error: 'x' }),
    ]) {
      const [s, b] = put(e, wid, 'charge', body);
      assert.equal(s, 409);
      assert.equal(b.code, 'STEP_ALREADY_RECORDED');
    }
  });

  it('FAILED needs an INTENT; INTENT is allowed again on a FAILED step; identical repeats are no-ops', () => {
    const e = new SangamTestEngine();
    const { wid, w } = leased(e);
    assert.equal(put(e, wid, 'charge', fence(w, { state: 'INTENT' }))[0], 200);
    assert.equal(put(e, wid, 'charge', fence(w, { state: 'INTENT' }))[0], 200, 'identical INTENT repeat');
    assert.equal(put(e, wid, 'charge', fence(w, { state: 'FAILED', error: 'rejected' }))[0], 200);
    assert.equal(put(e, wid, 'charge', fence(w, { state: 'FAILED', error: 'rejected' }))[0], 200, 'identical FAILED repeat');
    const [s, b] = put(e, wid, 'charge', fence(w, { state: 'FAILED', error: 'other' }));
    assert.equal(s, 409);
    assert.equal(b.code, 'STEP_INTENT_REQUIRED');
    assert.equal(put(e, wid, 'charge', fence(w, { state: 'INTENT' }))[0], 200);
    assert.equal(put(e, wid, 'charge', fence(w, { state: 'SUCCEEDED', result: 1 }))[0], 200);
  });

  it('keys steps by operationId: a later attempt of the same work sees them', () => {
    const e = new SangamTestEngine();
    const { wid, w } = leased(e);
    put(e, wid, 'charge', fence(w, { state: 'INTENT' }));
    e.advance(60_000);
    const a = e.handle('POST', '/work/fetch-and-lock', undefined, { workerId: 'w1', topics: [{ name: 'pay' }], maxTasks: 1 });
    const w2 = (a.body as Json[])[0]!;
    assert.equal(w2.operationId, w.operationId);
    assert.notEqual(w2.attemptId, w.attemptId);
    const [s, b] = get(e, wid, 'charge', fence(w2));
    assert.equal(s, 200);
    assert.equal(b.state, 'INTENT');
    assert.equal(b.attemptId, w.attemptId);
  });

  it('fences in the server order and changes nothing on a stale lease', () => {
    const e = new SangamTestEngine();
    const { wid, w } = leased(e);
    let [s, b] = put(e, wid, 'charge', fence(w, { state: 'INTENT', lockVersion: (w.lockVersion as number) + 1 }));
    assert.equal(s, 409);
    assert.equal(b.code, 'STALE_LEASE');
    assert.equal(b.reason, 'STALE_VERSION');
    [, b] = put(e, wid, 'charge', fence(w, { state: 'INTENT', workerId: 'intruder' }));
    assert.equal(b.reason, 'NOT_OWNER');
    [, b] = put(e, wid, 'charge', fence(w, { state: 'INTENT', attemptId: 'old' }));
    assert.equal(b.reason, 'ATTEMPT_MISMATCH');
    [, b] = get(e, wid, 'charge', fence(w, { attemptId: 'old' }));
    assert.equal(b.reason, 'ATTEMPT_MISMATCH');
    e.advance(60_000);
    [s, b] = put(e, wid, 'charge', fence(w, { state: 'INTENT' }));
    assert.equal(s, 409);
    assert.equal(b.code, 'STALE_LEASE');
    assert.deepEqual(e.handle('GET', `/work/${wid}/steps`, undefined, undefined).body, []);
  });

  it('validates attemptId, the step name, the state and the GET lockVersion (400 VALIDATION_FAILED)', () => {
    const e = new SangamTestEngine();
    const { wid, w } = leased(e);
    const cases: Array<[number, Json]> = [
      put(e, wid, 'charge', fence(w, { state: 'INTENT', attemptId: undefined })),
      put(e, wid, 'charge', fence(w, { state: 'INTENT', attemptId: ' ' })),
      put(e, wid, 'a\u0001b', fence(w, { state: 'INTENT' })),
      put(e, wid, 'x'.repeat(129), fence(w, { state: 'INTENT' })),
      put(e, wid, 'charge', fence(w, { state: 'DONE' })),
      get(e, wid, 'charge', fence(w, { lockVersion: undefined })),
      get(e, wid, 'charge', fence(w, { attemptId: undefined })),
    ];
    for (const [s, b] of cases) {
      assert.equal(s, 400);
      assert.equal(b.code, 'VALIDATION_FAILED');
    }
    assert.equal(put(e, wid, 'x'.repeat(128), fence(w, { state: 'INTENT' }))[0], 200);
  });

  it('unknown work is 404; work whose instance was deleted is 410 GONE_INSTANCE_DELETED', () => {
    const e = new SangamTestEngine();
    const { wid, w } = leased(e);
    let [s, b] = put(e, 'nope', 'charge', fence(w, { state: 'INTENT' }));
    assert.equal(s, 404);
    assert.equal(b.code, 'NOT_FOUND');
    assert.equal(e.handle('GET', '/work/nope/steps', undefined, undefined).status, 404);
    e.deleteInstance(wid);
    [s, b] = put(e, wid, 'charge', fence(w, { state: 'INTENT' }));
    assert.equal(s, 410);
    assert.equal(b.code, 'GONE_INSTANCE_DELETED');
    [s] = get(e, wid, 'charge', fence(w));
    assert.equal(s, 410);
  });

  it('lists the steps of the operation in recording order', () => {
    const e = new SangamTestEngine();
    const { wid, w } = leased(e);
    for (const n of ['reserve', 'charge', 'notify']) put(e, wid, n, fence(w, { state: 'INTENT' }));
    put(e, wid, 'reserve', fence(w, { state: 'SUCCEEDED', result: 1 }));
    const a = e.handle('GET', `/work/${wid}/steps`, undefined, undefined);
    assert.equal(a.status, 200);
    assert.deepEqual((a.body as Json[]).map((r) => [r.name, r.state]), [
      ['reserve', 'SUCCEEDED'],
      ['charge', 'INTENT'],
      ['notify', 'INTENT'],
    ]);
  });
});
