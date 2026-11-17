// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 393 batch 1 (engine and admin routes) at the request level: the real client and endpoints run against a
 * mocked fetch, so each test sees the exact URL the browser would send, checked against the REST contract. A process
 * or decision definition id (key:version:uuid), a user id (IdP principal), a group id (IdP group path such as
 * /staff/clerks) and a role id (pack:clerk) travel as query parameters on literal by-<name> routes, never in a path
 * segment (Tomcat refuses an encoded '/' with an HTML 400 before the application).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import apiYaml from '../../../sangam-rest/api/sangam-api.yaml?raw';
import { api } from './endpoints';

type Call = { url: string; method: string };
let calls: Call[] = [];

beforeEach(() => {
  calls = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, method: init.method ?? 'GET' });
      const xml = (new Headers(init.headers).get('Accept') ?? '').startsWith('application/xml');
      if (xml) return new Response('<definitions/>', { status: 200, headers: { 'Content-Type': 'application/xml' } });
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

const pathOf = (u: string) => new URL(u, 'http://x').pathname;
const queryOf = (u: string) => Object.fromEntries(new URL(u, 'http://x').searchParams);
const contractPaths = [...apiYaml.matchAll(/^ {2}(\/[^:\s]*):\s*$/gm)].map((m) => m[1]);

const DEF = 'sangam-leave:3:9f1c2d3e';
const GROUP = '/staff/clerks';
const USER = 'idp:ravi/kumar';

describe('decision 393 batch 1: engine and admin ids travel in the query', () => {
  it('process and decision definitions are read and suspended by id in the query', async () => {
    await api.processes.definition(DEF);
    await api.processes.xml(DEF);
    await api.decisions.xml(DEF);
    expect(calls.map((c) => [c.method, pathOf(c.url), queryOf(c.url)])).toEqual([
      ['GET', '/api/v1/process-definitions/by-id', { id: DEF }],
      ['GET', '/api/v1/process-definitions/by-id/xml', { id: DEF }],
      ['GET', '/api/v1/decision-definitions/by-id/xml', { id: DEF }],
    ]);
  });

  it('group members, users and groups are addressed by id in the query', async () => {
    await api.admin.groupMembers(GROUP);
    await api.admin.setMembership(GROUP, USER, true);
    await api.admin.setMembership(GROUP, USER, false);
    await api.resource.save('/admin/users', { id: USER, displayName: 'Ravi' }, false);
    await api.resource.action('/admin/users', USER, 'disable', { reason: 'left' });
    await api.resource.save('/admin/groups', { id: GROUP, name: 'Clerks' }, false);
    expect(calls.map((c) => [c.method, pathOf(c.url), queryOf(c.url)])).toEqual([
      ['GET', '/api/v1/admin/groups/by-id/members', { id: GROUP }],
      ['POST', '/api/v1/admin/groups/by-id/members/by-user-id', { id: GROUP, userId: USER }],
      ['DELETE', '/api/v1/admin/groups/by-id/members/by-user-id', { id: GROUP, userId: USER }],
      ['PUT', '/api/v1/admin/users/by-id', { id: USER }],
      ['POST', '/api/v1/admin/users/by-id/disable', { id: USER }],
      ['PUT', '/api/v1/admin/groups/by-id', { id: GROUP }],
    ]);
    for (const c of calls) expect(new URL(c.url, 'http://x').pathname).not.toMatch(/%2F|%3A/i);
  });

  it('a collection that keeps safe ids in the path still does', async () => {
    await api.resource.save('/admin/seats', { id: 's1', name: 'Clerk' }, false);
    expect(pathOf(calls[0].url)).toBe('/api/v1/admin/seats/s1');
  });

  it('the contract has the by-id routes and none of the old path routes', () => {
    for (const p of [
      '/process-definitions/by-id',
      '/process-definitions/by-id/xml',
      '/process-definitions/by-id/suspension',
      '/process-definitions/by-id/handlers',
      '/decision-definitions/by-id/xml',
      '/admin/users/by-id',
      '/admin/users/by-id/disable',
      '/admin/groups/by-id',
      '/admin/groups/by-id/members',
      '/admin/groups/by-id/members/by-user-id',
      '/admin/roles/by-role-id',
      '/ops-admin/secrets/by-ref',
    ])
      expect(contractPaths, p).toContain(p);
    const old = contractPaths.filter((p) =>
      /^\/(process-definitions|decision-definitions)\/\{id\}|^\/admin\/(users|groups)\/\{id\}|^\/admin\/roles\/\{|^\/ops-admin\/secrets\/\{/.test(p),
    );
    expect(old).toEqual([]);
  });
});
