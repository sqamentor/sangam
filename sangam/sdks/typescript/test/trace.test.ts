// SPDX-License-Identifier: Apache-2.0
// M-062: W3C trace context propagation of the transport (off by default, never fails a call).
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { HttpTransport, validTraceparent, validTracestate, type ApiRequest, type TraceContext } from '../src/http.js';

const TP = '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01';
const req: ApiRequest = { method: 'GET', path: '/me' };

function transport(traceContext?: TraceContext): HttpTransport {
  return new HttpTransport({ baseUrl: 'http://127.0.0.1:1', traceContext });
}

describe('trace context (M-062)', () => {
  it('accepts only well-formed version-00 traceparents', () => {
    assert.equal(validTraceparent(TP), true);
    assert.equal(validTraceparent(undefined), false);
    assert.equal(validTraceparent('00-00000000000000000000000000000000-00f067aa0ba902b7-01'), false);
    assert.equal(validTraceparent('00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01'), false);
    assert.equal(validTraceparent(TP.toUpperCase()), false);
    assert.equal(validTraceparent(`${TP}-extra`), false);
    assert.equal(validTracestate('congo=t61rcWkgMzE'), true);
    assert.equal(validTracestate(`a=${'x'.repeat(511)}`), false);
  });

  it('sends nothing without a trace context', () => {
    assert.deepEqual(transport().traceHeaders(req), {});
  });

  it('sends the provider context', () => {
    const t = transport(() => ({ traceparent: TP, tracestate: 'congo=t61rcWkgMzE' }));
    assert.deepEqual(t.traceHeaders(req), { traceparent: TP, tracestate: 'congo=t61rcWkgMzE' });
  });

  it('drops an invalid or absent context', () => {
    assert.deepEqual(transport(() => undefined).traceHeaders(req), {});
    assert.deepEqual(transport(() => ({ traceparent: 'nope' })).traceHeaders(req), {});
  });

  it('a failing provider never fails the call', () => {
    assert.deepEqual(transport(() => { throw new Error('exporter down'); }).traceHeaders(req), {});
  });

  it('a request-level traceparent wins', () => {
    const own: ApiRequest = { ...req, headers: { TraceParent: '00-11111111111111111111111111111111-2222222222222222-00' } };
    assert.deepEqual(transport(() => ({ traceparent: TP })).traceHeaders(own), {});
  });
});
