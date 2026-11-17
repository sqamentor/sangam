// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * UI-DEMO 2026-11-16 regressions at the request level: the real client (client.ts) and endpoints (endpoints.ts) run
 * against a mocked fetch, so each test sees the exact URL and headers the browser would send, checked against the
 * REST contract (sangam-api.yaml).
 * - UI-DEMO-1: a value that can carry '/' (form key, business key, profile id, custom-field object type) is a query
 *   parameter; Tomcat refuses an encoded slash (%2F) in a path with an HTML 400 before the application.
 * - UI-DEMO-2: an operation that produces only XML or plain text is asked for with that media type; the default JSON
 *   Accept got a 406.
 * - Sweep S1: analytics from/to are instants (the contract's date-time); a bare date was a 400 on every screen.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import apiYaml from '../../../sangam-rest/api/sangam-api.yaml?raw';
import { api, dayRange } from './endpoints';

type Call = { url: string; method: string; accept: string | null };
let calls: Call[] = [];

beforeEach(() => {
  calls = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      const h = new Headers(init.headers);
      calls.push({ url, method: init.method ?? 'GET', accept: h.get('Accept') });
      const xml = (h.get('Accept') ?? '').startsWith('application/xml');
      const text = (h.get('Accept') ?? '').startsWith('text/plain');
      if (xml) return new Response('<definitions/>', { status: 200, headers: { 'Content-Type': 'application/xml' } });
      if (text) return new Response('java.lang.IllegalStateException', { status: 200, headers: { 'Content-Type': 'text/plain' } });
      return new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

/** The block of one operation in the contract, by method and path. */
function operation(method: string, path: string): string {
  const lines = apiYaml.split(/\r?\n/);
  const start = lines.findIndex((l) => l === `  ${path}:`);
  expect(start, `contract path ${path}`).toBeGreaterThan(-1);
  const end = lines.findIndex((l, i) => i > start && /^ {2}\//.test(l));
  const block = lines.slice(start, end < 0 ? undefined : end);
  const m = block.findIndex((l) => l === `    ${method}:`);
  expect(m, `${method} ${path}`).toBeGreaterThan(-1);
  const next = block.findIndex((l, i) => i > m && /^ {4}\w+:\s*$/.test(l));
  return block.slice(m, next < 0 ? undefined : next).join('\n');
}
const requiredQuery = (op: string, name: string) => new RegExp(`- name: ${name}\\n\\s+in: query[\\s\\S]*?required: true`).test(op);
const pathOf = (u: string) => decodeURIComponent(new URL(u, 'http://x').pathname);
const queryOf = (u: string) => Object.fromEntries(new URL(u, 'http://x').searchParams);

describe('UI-DEMO-1: values that can carry "/" travel in the query', () => {
  const KEY = 'sangam:grievance/lodge';

  it('a form is read and validated by key with the key in the query', async () => {
    await api.forms.get(KEY);
    await api.forms.validate(KEY, { a: 1 });
    // an encoded slash in the query is fine for Tomcat; in the path it is refused
    for (const c of calls) expect(c.url.split('?')[0]).not.toMatch(/%2F/i);
    expect(pathOf(calls[0].url)).toBe('/api/v1/forms/by-key');
    expect(queryOf(calls[0].url)).toEqual({ key: KEY });
    expect([calls[1].method, pathOf(calls[1].url)]).toEqual(['POST', '/api/v1/forms/by-key/validate']);
    expect(queryOf(calls[1].url)).toEqual({ key: KEY });
    expect(requiredQuery(operation('get', '/forms/by-key'), 'key')).toBe(true);
    expect(requiredQuery(operation('post', '/forms/by-key/validate'), 'key')).toBe(true);
  });

  it('the audit timeline and evidence pack of a business key with "/" use the query', async () => {
    await api.audit.timeline('BK/2026/0001');
    expect(calls[0].url.split('?')[0]).not.toMatch(/%2F/i);
    expect(pathOf(calls[0].url)).toBe('/api/v1/audit/timeline');
    expect(queryOf(calls[0].url)).toMatchObject({ businessKey: 'BK/2026/0001', includeHistory: 'true' });
    expect(requiredQuery(operation('get', '/audit/timeline'), 'businessKey')).toBe(true);
    expect(requiredQuery(operation('get', '/audit/evidence-pack'), 'businessKey')).toBe(true);
  });

  it('no contract operation keeps a form key, business key or human-readable number in its path', () => {
    const paths = [...apiYaml.matchAll(/^ {2}(\/[^:\s]*):\s*$/gm)].map((m) => m[1]);
    const offenders = paths.filter((p) => /\{(formKey|businessKey|number|resourceName|objectType|stepName|period)\}/.test(p) || /^\/forms\/\{key\}/.test(p) || /^\/config\/profiles\/\{id\}/.test(p));
    expect(offenders).toEqual([]);
  });
});

describe('UI-DEMO-2: an operation that produces XML or text is asked for in that media type', () => {
  it('process and decision XML send Accept application/xml', async () => {
    const p = await api.processes.xml('leave:1:abc');
    const d = await api.decisions.xml('dmn:1:abc');
    expect(p.bpmn20Xml).toBe('<definitions/>');
    expect(d.dmnXml).toBe('<definitions/>');
    expect(calls.map((c) => c.accept)).toEqual(['application/xml, application/problem+json', 'application/xml, application/problem+json']);
    for (const [path] of [['/process-definitions/by-id/xml'], ['/decision-definitions/by-id/xml']]) {
      const op = operation('get', path);
      expect(op).toMatch(/application\/xml:/);
      expect(op.split("'200'")[1].split(/'\d{3}'/)[0]).not.toMatch(/application\/json:/);
    }
  });

  it('a job stack trace sends Accept text/plain', async () => {
    expect(await api.ops.jobStacktrace('j1')).toBe('java.lang.IllegalStateException');
    expect(calls[0].accept).toBe('text/plain, application/problem+json');
    expect(operation('get', '/ops/jobs/{id}/stacktrace')).toMatch(/text\/plain:/);
  });
});

describe('sweep S1: analytics ranges are instants', () => {
  it('a day range becomes local midnight to the start of the day after, as instants', () => {
    const r = dayRange('2026-11-01', '2026-11-16');
    expect(r.from).toBe(new Date(2026, 10, 1).toISOString());
    expect(r.to).toBe(new Date(2026, 10, 17).toISOString());
    expect(dayRange()).toEqual({ from: undefined, to: undefined });
    expect(dayRange('2026-11-01T05:00:00Z').from).toBe('2026-11-01T05:00:00Z');
  });

  it('the dashboard queries send instants and the contract parameter names', async () => {
    await api.analytics.throughput({ definitionKey: 'leave', from: '2026-11-01', to: '2026-11-02', bucket: 'week' });
    const q = queryOf(calls[0].url);
    expect(q.processDefinitionKey).toBe('leave');
    expect(q.interval).toBe('week');
    expect(q.from).toMatch(/T.*Z$/);
    expect(q.to).toMatch(/T.*Z$/);
    expect(q).not.toHaveProperty('bucket');
    expect(q).not.toHaveProperty('definitionKey');
  });
});
