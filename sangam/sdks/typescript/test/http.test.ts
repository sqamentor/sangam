// SPDX-License-Identifier: Apache-2.0
// Transport: problem+json error mapping, retryability, base-URL normalisation (linear, CodeQL js/polynomial-redos).
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { SangamClient } from '../src/client.js';
import { GoneError, problemIs, SangamApiError, StaleLeaseError } from '../src/errors.js';
import { trimTrailingSlashes } from '../src/http.js';
import { clientFor, FakeServer } from './fake-server.js';

async function errorFor(status: number, body?: unknown): Promise<unknown> {
  const server = new FakeServer().reply('unlock', { status, body });
  const client = clientFor(server);
  try {
    await client.work.unlock('wk-1', { workerId: 'w', lockVersion: 1, attemptId: 'a' }, { idempotencyKey: 'k' });
  } catch (e) {
    return e;
  }
  return undefined;
}

/** `e` as an instance of exactly `cls` (fails the test otherwise). */
function expectExactly<T>(e: unknown, cls: new (...args: never[]) => T): T {
  assert.ok(e instanceof cls, `expected ${cls.name}, got ${String(e)}`);
  assert.equal((e as object).constructor, cls);
  return e as T;
}

describe('error mapping', () => {
  it('409 stale-lease -> StaleLeaseError (not retryable)', async () => {
    const e = expectExactly(await errorFor(409, { type: 'https://sangam.dev/problems/stale-lease', title: 'Stale lease' }), StaleLeaseError);
    assert.equal(e.status, 409);
    assert.equal(e.retryable, false);
  });

  it('409 with another problem stays a plain SangamApiError', async () => {
    const e = expectExactly(await errorFor(409, { code: 'CONFLICT', title: 'Conflict' }), SangamApiError);
    assert.equal(e.status, 409);
  });

  it('410 -> GoneError (not retryable)', async () => {
    const e = expectExactly(await errorFor(410, { code: 'GONE_INSTANCE_DELETED' }), GoneError);
    assert.equal(e.retryable, false);
  });

  it('a non-JSON error body becomes a synthetic problem with the status', async () => {
    const e = expectExactly(await errorFor(502), SangamApiError);
    assert.equal(e.problem.status, 502);
    assert.equal(e.retryable, true);
  });

  it('retryable statuses are exactly 408, 425, 429, 500, 502, 503, 504', () => {
    const retryable = [400, 401, 403, 404, 408, 409, 422, 425, 429, 500, 501, 502, 503, 504]
      .filter((s) => new SangamApiError(s, {}, 'x').retryable);
    assert.deepEqual(retryable, [408, 425, 429, 500, 502, 503, 504]);
  });

  it('problemIs matches code or the last type segment, case and -/_ insensitive', () => {
    assert.equal(problemIs({ code: 'STALE_LEASE' }, 'stale-lease'), true);
    assert.equal(problemIs({ type: 'urn:x#Stale_Lease' }, 'stale-lease'), true);
    assert.equal(problemIs({ type: 'https://h/p/stale-lease' }, 'STALE_LEASE'), true);
    assert.equal(problemIs({ code: 'GONE' }, 'stale-lease'), false);
    assert.equal(problemIs(undefined, 'stale-lease'), false);
  });
});

describe('base URL normalisation', () => {
  it('trimTrailingSlashes removes only trailing slashes', () => {
    assert.equal(trimTrailingSlashes('https://h:8080///'), 'https://h:8080');
    assert.equal(trimTrailingSlashes('https://h:8080'), 'https://h:8080');
    assert.equal(trimTrailingSlashes('a//b/'), 'a//b');
    assert.equal(trimTrailingSlashes('////'), '');
    assert.equal(trimTrailingSlashes(''), '');
  });

  it('is linear on adversarial input (many slashes not at the end)', () => {
    const evil = '/'.repeat(200_000) + 'x';
    const t0 = process.hrtime.bigint();
    assert.equal(trimTrailingSlashes(evil), evil);
    assert.equal(trimTrailingSlashes(evil + '/'.repeat(200_000)), evil);
    const ms = Number(process.hrtime.bigint() - t0) / 1e6;
    // the replaced /\/+$/ needs ~n^2/2 = 2e10 steps here; a linear scan takes well under a second
    assert.ok(ms < 1_000, `took ${ms} ms`);
  });

  it('accepts a base URL with trailing slashes and /api/v1', async () => {
    for (const base of ['http://sangam.test', 'http://sangam.test///', 'http://sangam.test/api/v1', 'http://sangam.test/api/v1/']) {
      const server = new FakeServer();
      const urls: string[] = [];
      const fetchSpy: typeof fetch = (input, init) => {
        urls.push(String(input));
        return server.fetch(input, init);
      };
      const client = new SangamClient({ baseUrl: base, fetch: fetchSpy, logger: server.logger });
      await client.work.unlock('wk-1', { workerId: 'w', lockVersion: 1 }, { idempotencyKey: 'k' });
      assert.deepEqual(urls, ['http://sangam.test/api/v1/work/wk-1/unlock'], base);
    }
  });
});
