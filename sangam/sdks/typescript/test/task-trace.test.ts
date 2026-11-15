// SPDX-License-Identifier: Apache-2.0
// M-062: ExternalWork.traceContext (sangam-api.yaml TraceContext) reaches the handler as ctx.traceparent.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { SangamClient } from '../src/client.js';
import { noopLogger } from '../src/logger.js';
import { TaskContext } from '../src/worker.js';

const TP = '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01';
const client = new SangamClient({ baseUrl: 'http://127.0.0.1:1' });

describe('task trace context (M-062)', () => {
  it('exposes the engine trace context of the work item', () => {
    const ctx = new TaskContext({ id: 'wk-1', lockVersion: 1, attemptId: 'at-1', traceContext: { traceparent: TP, tracestate: 'congo=t61rcWkgMzE' } },
      'w-1', client, noopLogger);
    assert.equal(ctx.traceparent, TP);
    assert.equal(ctx.tracestate, 'congo=t61rcWkgMzE');
  });

  it('is undefined when the server stored none', () => {
    const ctx = new TaskContext({ id: 'wk-1', lockVersion: 1, attemptId: 'at-1' }, 'w-1', client, noopLogger);
    assert.equal(ctx.traceparent, undefined);
    assert.equal(ctx.tracestate, undefined);
  });
});
