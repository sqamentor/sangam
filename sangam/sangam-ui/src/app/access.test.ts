// SPDX-License-Identifier: Apache-2.0
// M-028: every module screen is guarded by the server's own endpoint permission for its first call (access.ts), the
// menu uses the same requirement, and the only unguarded routes are the ones the server opens to every signed-in
// caller.
import { describe, expect, it } from 'vitest';
import appSource from './App.tsx?raw';
import { MODULE_READERS, ROUTE_ACCESS, SIGNED_IN_ROUTES, canAccess } from './access';
import { NAV_ITEMS } from './nav';
import type { Role } from '../api/types';

const me = (roles: Role[], permissions?: string[]) => ({ roles, permissions });

/** Routes in App.tsx's authenticated table, and whether each is wrapped in a guard (g(...) or m(...)). */
function routes(): { path: string; guarded: boolean; byAccess?: boolean }[] {
  const out: { path: string; guarded: boolean; byAccess?: boolean }[] = [];
  const table = appSource.slice(appSource.indexOf('function AuthedRoutes'), appSource.indexOf('function NotFound'));
  expect(table.length).toBeGreaterThan(0);
  for (const line of table.split(/\r?\n/)) {
    const route = /<Route path="([^"]+)" element=\{(.*)\} \/>/.exec(line);
    // guarded: the element is a g(...) call, or it contains a <Guard ...> element
    if (route) out.push({ path: route[1], guarded: route[2].startsWith('g(') || route[2].includes('<Guard ') });
    const mod = /\{m\('([^']+)', /.exec(line);
    if (mod) out.push({ path: mod[1], guarded: true, byAccess: true });
  }
  return out;
}

describe('M-028 route guards', () => {
  it('guards every /m/* route with its access entry, and has an entry only for real routes', () => {
    const all = routes();
    const modules = all.filter((r) => r.path.startsWith('/m/'));
    expect(modules.length).toBe(15);
    for (const r of modules) {
      expect(r.guarded, r.path).toBe(true);
      expect(ROUTE_ACCESS[r.path], r.path).toBeDefined();
    }
    // every m(...) route (the 15 modules plus the W2-C admin screens) has its entry, and every entry is a real m(...) route
    expect(Object.keys(ROUTE_ACCESS).sort()).toEqual(all.filter((r) => r.byAccess).map((r) => r.path).sort());
  });

  it('leaves unguarded only the routes the server opens to every signed-in caller', () => {
    // '/', '*': redirect and not-found; /welcome, /account, /help/*: no module call (Account's engine calls are made
    // for platform users only, inside the screen). /pages/:key (lowcode.read, LowCodeController.java:53) is outside
    // M-028 and unchanged.
    // /privacy/:tenant? (M-033): the privacy policy is public on the server (security: [], SecurityConfig.java:80-81).
    // /inbox (UI-DEMO-6), /ops/task-dashboard and /ops/debugger (decision 397): redirects to guarded routes; they render
    // nothing themselves. /help/glossary and /help/shortcuts: static help, no server call.
    const open = new Set(['/', '*', '/welcome', '/account', '/pages/:key', '/help/accessibility', '/help/about', '/help/glossary', '/help/shortcuts', '/privacy/:tenant?', '/inbox', '/ops/task-dashboard', '/ops/debugger', ...SIGNED_IN_ROUTES]);
    const unguarded = routes().filter((r) => !r.guarded).map((r) => r.path);
    expect(unguarded.filter((p) => !open.has(p))).toEqual([]);
    for (const p of SIGNED_IN_ROUTES) expect(unguarded).toContain(p);
  });

  it('admits by role, by endpoint permission or by "*", and refuses otherwise', () => {
    const ledger = ROUTE_ACCESS['/m/ledger'];
    expect(canAccess(me(['TASK_USER']), ledger)).toBe(true);
    expect(canAccess(me([], ['ledger.read']), ledger)).toBe(true);
    expect(canAccess(me([], ['*']), ledger)).toBe(true);
    expect(canAccess(me(['CITIZEN'], ['portal.use']), ledger)).toBe(false);
    expect(canAccess(me([]), ledger)).toBe(false);
    expect(canAccess(null, ledger)).toBe(false);
  });

  it('follows the server persona map: who may open which module', () => {
    // tenant administrator (sangam-tenant-admin → ADMIN): module read/write/admin, but not field.capture or ai.use
    const tenantAdmin = me(['ADMIN']);
    expect(canAccess(tenantAdmin, ROUTE_ACCESS['/m/ledger'])).toBe(true);
    expect(canAccess(tenantAdmin, ROUTE_ACCESS['/m/compliance'])).toBe(true);
    expect(canAccess(tenantAdmin, ROUTE_ACCESS['/m/ai'])).toBe(false);
    expect(canAccess(tenantAdmin, ROUTE_ACCESS['/m/field'])).toBe(false);
    // platform administrator (sangam-admin → ADMIN, PLATFORM_ADMIN): everything
    for (const a of Object.values(ROUTE_ACCESS)) expect(canAccess(me(['ADMIN', 'PLATFORM_ADMIN']), a)).toBe(true);
    // auditor: module read and compliance.read, not ai.use / field.capture
    expect(canAccess(me(['AUDITOR']), ROUTE_ACCESS['/m/registry'])).toBe(true);
    expect(canAccess(me(['AUDITOR']), ROUTE_ACCESS['/m/compliance'])).toBe(true);
    expect(canAccess(me(['AUDITOR']), ROUTE_ACCESS['/m/ai'])).toBe(false);
    // plain user: module read, ai.use and field.capture, not compliance.read
    expect(canAccess(me(['TASK_USER']), ROUTE_ACCESS['/m/ai'])).toBe(true);
    expect(canAccess(me(['TASK_USER']), ROUTE_ACCESS['/m/field'])).toBe(true);
    expect(canAccess(me(['TASK_USER']), ROUTE_ACCESS['/m/compliance'])).toBe(false);
    // ANALYST alone (analytics.read) holds no module permission
    for (const a of Object.values(ROUTE_ACCESS)) expect(canAccess(me(['ANALYST']), a)).toBe(false);
    // data protection officer (sangam-dpo has no UI role): admitted to compliance by the permission /me reports
    expect(canAccess(me([], ['compliance.read', 'audit.read']), ROUTE_ACCESS['/m/compliance'])).toBe(true);
    expect(canAccess(me([], ['compliance.read', 'audit.read']), ROUTE_ACCESS['/m/ledger'])).toBe(false);
  });

  it('gives the menu the same requirement as the route', () => {
    const modules = NAV_ITEMS.filter((i) => i.to.startsWith('/m/'));
    expect(modules).toHaveLength(15);
    for (const i of modules) expect(i.access, i.to).toBe(ROUTE_ACCESS[i.to]);
    for (const a of Object.values(ROUTE_ACCESS)) for (const r of a.roles) expect(MODULE_READERS).toContain(r);
  });
});

describe('W2-C admin screens follow the server gates', () => {
  it('packs and onboarding: platform administrators only (group sangam-admin), never a permission alone', () => {
    for (const r of ['/admin/packs', '/admin/onboarding']) {
      expect(canAccess(me(['ADMIN', 'PLATFORM_ADMIN']), ROUTE_ACCESS[r]), r).toBe(true);
      expect(canAccess(me(['ADMIN']), ROUTE_ACCESS[r]), r).toBe(false);
      expect(canAccess(me([], ['tenant.admin']), ROUTE_ACCESS[r]), r).toBe(false);
    }
  });
  it('bulk ingest: the ops.variable sink permission (ops.tenant.admin) or a tenant administrator', () => {
    expect(canAccess(me(['ADMIN']), ROUTE_ACCESS['/admin/bulk'])).toBe(true);
    expect(canAccess(me([], ['ops.tenant.admin']), ROUTE_ACCESS['/admin/bulk'])).toBe(true);
    expect(canAccess(me(['OPERATOR']), ROUTE_ACCESS['/admin/bulk'])).toBe(false);
  });
  it('custom fields: administrators (tenant.admin), not every holder of form.read (U12g); module defaults: tenant.admin', () => {
    expect(canAccess(me([], ['form.read']), ROUTE_ACCESS['/admin/custom-fields'])).toBe(false);
    expect(canAccess(me(['TASK_USER'], ['form.read']), ROUTE_ACCESS['/admin/custom-fields'])).toBe(false);
    expect(canAccess(me(['ADMIN']), ROUTE_ACCESS['/admin/custom-fields'])).toBe(true);
    expect(canAccess(me([], ['tenant.admin']), ROUTE_ACCESS['/admin/custom-fields'])).toBe(true);
    expect(canAccess(me(['AUDITOR']), ROUTE_ACCESS['/admin/custom-fields'])).toBe(false);
    expect(canAccess(me(['ADMIN']), ROUTE_ACCESS['/admin/security-defaults'])).toBe(true);
    expect(canAccess(me(['TASK_USER']), ROUTE_ACCESS['/admin/security-defaults'])).toBe(false);
  });
  it('the menu uses the same requirement as the route', () => {
    for (const r of ['/admin/onboarding', '/admin/packs', '/admin/custom-fields', '/admin/bulk', '/admin/security-defaults']) {
      expect(NAV_ITEMS.find((i) => i.to === r)?.access, r).toBe(ROUTE_ACCESS[r]);
    }
  });
});

describe('UI-DEMO-6: menu entries and addresses', () => {
  /** A route path of App.tsx as a matcher: :param matches one segment, a trailing * the rest, an optional :param? may be absent. */
  const matcher = (route: string) =>
    new RegExp(`^${route.replace(/\/:\w+\?/g, '(?:/[^/]+)?').replace(/:\w+/g, '[^/]+').replace(/\/\*$/, '(?:/.*)?')}$`);
  // /verify/:tenant? is public and served by Gate outside the authenticated table (App.tsx).
  const paths = () => [...routes().map((r) => r.path).filter((p) => p !== '*'), '/verify/:tenant?'];
  const resolves = (to: string) => paths().some((r) => matcher(r).test(to.split('?')[0]));

  it('every menu entry opens a real route, not the not-found page', () => {
    const missing = NAV_ITEMS.map((i) => i.to).filter((to) => !resolves(to));
    expect(missing).toEqual([]);
  });

  it('/inbox, the menu name of the task list, is an address that leads to /tasks', () => {
    expect(appSource).toMatch(/<Route path="\/inbox" element=\{<Navigate to="\/tasks" replace \/>\} \/>/);
    expect(NAV_ITEMS.find((i) => i.label === 'menu.work.tasks.label')?.to).toBe('/tasks');
  });

  it('every redirect in the route table leads to a real route', () => {
    const table = appSource.slice(appSource.indexOf('function AuthedRoutes'), appSource.indexOf('function NotFound'));
    const targets = [...table.matchAll(/<Route path="[^"]+" element=\{<Navigate to="([^"]+)"/g)].map((m) => m[1]);
    expect(targets.length).toBeGreaterThan(0);
    expect(targets.filter((t) => !resolves(t))).toEqual([]);
  });
});