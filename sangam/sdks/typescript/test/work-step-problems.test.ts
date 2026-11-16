// SPDX-License-Identifier: Apache-2.0
// SL-174: step routes and lease calls on work that is not open answer exactly what the server answers. The expected
// problems come from sdks/testing/work-step-problems.json, which the server's WorkStepJournalH2Test and
// WorkCallProblemsVectorH2Test check against the server work adapters too, so the engine and the server are compared
// through one table.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import { SangamTestEngine } from '../src/testing.js';

type Json = Record<string, unknown>;
interface Vectors { stepName: string; unknownWorkId: string; cases: Json[]; workCalls: Json[] }

const VECTORS = JSON.parse(readFileSync(new URL('../../../testing/work-step-problems.json', import.meta.url), 'utf8')) as Vectors;
const FIXED = new Set(['id', 'scenario', 'call', 'status', 'code']);
const CALL_BODY: Record<string, Json> = {
  complete: { variables: {} },
  fail: { errorMessage: 'again', retries: 1 },
  'bpmn-error': { errorCode: 'E1' },
  unlock: {},
  'extend-lock': { newDurationMs: 60_000 },
  'outcome-unknown': { detail: 'unsure' },
};

function expectProblem(c: Json, route: string, a: { status: number; body?: unknown }): void {
  const what = `${String(c.id)} ${route}`;
  const p = (a.body ?? {}) as Json;
  assert.equal(a.status, c.status, what);
  assert.equal(p.code, c.code, what);
  for (const [k, v] of Object.entries(c)) if (!FIXED.has(k)) assert.equal(p[k], v, `${what} ${k}`);
}

/** The UNKNOWN_OUTCOME scenario: the worker reports an unknown outcome; the work awaits reconciliation. */
function awaitReconciliation(e: SangamTestEngine, wid: string, lease: Json): void {
  assert.equal(e.handle('POST', `/work/${wid}/outcome-unknown`, undefined, { ...lease, detail: 'unsure' }).status, 202);
  assert.equal(e.state(wid), 'UNKNOWN_OUTCOME');
}

describe('step routes on work that is not open (SL-174)', () => {
  for (const c of VECTORS.cases) {
    it(`${String(c.id)}: ${String(c.status)} ${String(c.code)} as on the server`, () => {
      const e = new SangamTestEngine();
      const wid = e.addWork('pay');
      const w = (e.handle('POST', '/work/fetch-and-lock', undefined,
        { workerId: 'w1', topics: [{ name: 'pay', lockDurationMs: 60_000 }], maxTasks: 1 }).body as Json[])[0]!;
      const lease = { workerId: 'w1', lockVersion: w.lockVersion, attemptId: w.attemptId };
      let target = wid;
      switch (c.scenario) {
        case 'UNKNOWN': target = VECTORS.unknownWorkId; break;
        case 'COMPLETED': assert.equal(e.handle('POST', `/work/${wid}/complete`, undefined, { ...lease, variables: {} }).status, 204); break;
        case 'DELETED': e.deleteInstance(wid); break;
        case 'RELEASED': assert.equal(e.handle('POST', `/work/${wid}/unlock`, undefined, lease).status, 204); break;
        case 'UNKNOWN_OUTCOME': awaitReconciliation(e, wid, lease); break;
        default: assert.fail(`unknown scenario ${String(c.scenario)}`);
      }
      const path = `/work/${target}/step`;
      const query = new URLSearchParams({ workerId: 'w1', lockVersion: String(w.lockVersion), attemptId: String(w.attemptId), stepName: VECTORS.stepName });
      expectProblem(c, 'GET', e.handle('GET', path, undefined, undefined, query));
      expectProblem(c, 'PUT', e.handle('PUT', path, undefined, { ...lease, state: 'INTENT' }, new URLSearchParams({ stepName: VECTORS.stepName })));
    });
  }

  for (const c of VECTORS.workCalls) {
    it(`${String(c.id)}: ${String(c.status)} ${String(c.code)} as on the server`, () => {
      const e = new SangamTestEngine();
      const wid = e.addWork('pay');
      const w = (e.handle('POST', '/work/fetch-and-lock', undefined,
        { workerId: 'w1', topics: [{ name: 'pay', lockDurationMs: 60_000 }], maxTasks: 1 }).body as Json[])[0]!;
      const lease = { workerId: 'w1', lockVersion: w.lockVersion, attemptId: w.attemptId };
      switch (c.scenario) {
        case 'INCIDENT':
          assert.equal(e.handle('POST', `/work/${wid}/fail`, undefined, { ...lease, errorMessage: 'no retries left', retries: 0 }).status, 204);
          assert.equal(e.state(wid), 'INCIDENT');
          break;
        case 'UNKNOWN_OUTCOME': awaitReconciliation(e, wid, lease); break;
        default: assert.fail(`unknown scenario ${String(c.scenario)}`);
      }
      const call = String(c.call);
      const body = CALL_BODY[call];
      assert.ok(body, `known call ${call}`);
      expectProblem(c, call, e.handle('POST', `/work/${wid}/${call}`, undefined, { ...lease, ...body }));
    });
  }

  it('reads every vector', () => {
    assert.ok(VECTORS.cases.length >= 5);
    assert.ok(VECTORS.workCalls.length >= 12);
  });
});
