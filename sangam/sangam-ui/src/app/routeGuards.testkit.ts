// SPDX-License-Identifier: Apache-2.0
/**
 * Test support (U12): the route guards of App.tsx as data, read from its source, so a test can ask "does the guard of
 * this route admit this caller" for the callers of serverGroups.testkit.ts without rendering every screen.
 */
import appSource from './App.tsx?raw';
import type { Me, Role } from '../api/types';
import * as access from './access';
import { ROUTE_ACCESS, TEAM_READ, canAccess, type Access } from './access';
import { NAV_ITEMS, visibleNav } from './nav';

export type Caller = Pick<Me, 'roles' | 'permissions'>;

export interface GuardedRoute {
  path: string;
  /** roles: g(...); access: m(...) or <Guard access>; open: every signed-in caller; redirect: <Navigate>. */
  kind: 'roles' | 'access' | 'open' | 'redirect';
  roles?: Role[];
  access?: Access;
  redirectTo?: string;
}

/** The role lists App.tsx names (WORK, OPS, ADMIN, MODEL, ANALYST, AUDIT, CITIZEN). */
const ROLE_LISTS: Record<string, Role[]> = Object.fromEntries(
  [...appSource.matchAll(/^const (\w+): Role\[\] = \[([^\]]*)\];/gm)].map((m) => [m[1], [...m[2].matchAll(/'(\w+)'/g)].map((r) => r[1] as Role)]),
);
const NAMED_ACCESS: Record<string, Access> = { TEAM_READ, ...(access as unknown as Record<string, Access>) };

/** WORK, ['PLATFORM_ADMIN'] or [...OPS, 'AUDITOR'] → roles. */
function rolesOf(expr: string): Role[] {
  if (expr in ROLE_LISTS) return ROLE_LISTS[expr];
  if (!expr.startsWith('[')) throw new Error(`App.tsx: unknown role list ${expr}`);
  return expr
    .slice(1, -1)
    .split(',')
    .map((x) => x.trim())
    .filter(Boolean)
    .flatMap((x) => {
      if (x.startsWith('...')) {
        if (!(x.slice(3) in ROLE_LISTS)) throw new Error(`App.tsx: unknown role list ${x}`);
        return ROLE_LISTS[x.slice(3)];
      }
      return [x.replace(/'/g, '') as Role];
    });
}

/** Every route of the signed-in table (AuthedRoutes), in source order, without the not-found route. */
export const ROUTES: GuardedRoute[] = (() => {
  const table = appSource.slice(appSource.indexOf('function AuthedRoutes'), appSource.indexOf('function NotFound'));
  const out: GuardedRoute[] = [];
  for (const line of table.split(/\r?\n/)) {
    const mod = /\{m\('([^']+)', /.exec(line);
    if (mod) {
      out.push({ path: mod[1], kind: 'access', access: ROUTE_ACCESS[mod[1]] });
      continue;
    }
    const route = /<Route path="([^"]+)" element=\{(.*)\} \/>/.exec(line);
    if (!route || route[1] === '*') continue;
    const [, path, element] = route;
    const byRoles = /^g\((\[[^\]]*\]|\w+), </.exec(element);
    const byAccess = /^<Guard access=\{(\w+)\}>/.exec(element);
    const redirect = /^<Navigate to="([^"]+)"/.exec(element);
    if (byRoles) out.push({ path, kind: 'roles', roles: rolesOf(byRoles[1]) });
    else if (byAccess) {
      if (!(byAccess[1] in NAMED_ACCESS)) throw new Error(`App.tsx: unknown access ${byAccess[1]}`);
      out.push({ path, kind: 'access', access: NAMED_ACCESS[byAccess[1]] });
    } else if (redirect) out.push({ path, kind: 'redirect', redirectTo: redirect[1] });
    else if (element.includes('Guard') || element.startsWith('g(')) throw new Error(`App.tsx: guard of ${path} not understood`);
    else out.push({ path, kind: 'open' });
  }
  return out;
})();

export const routeOf = (path: string): GuardedRoute => {
  const r = ROUTES.find((x) => x.path === path);
  if (!r) throw new Error(`App.tsx has no route ${path}`);
  return r;
};

/** Whether the guard of a route lets the caller in (Guard in App.tsx: hasRole(...roles), or canAccess for an access). */
export function admits(route: GuardedRoute, me: Caller): boolean {
  if (route.kind === 'roles') return (route.roles ?? []).some((r) => me.roles.includes(r));
  if (route.kind === 'access') return canAccess(me, route.access!);
  if (route.kind === 'redirect') return admits(routeOf(route.redirectTo!), me);
  return true;
}
export const opens = (me: Caller, path: string): boolean => admits(routeOf(path), me);

/** The addresses in the caller's menu, in menu order. */
export const menuRoutes = (me: Caller): string[] => visibleNav(me).flatMap((a) => a.sections.flatMap((s) => s.items.map((i) => i.to)));
/** The groups of the caller's menu. */
export const menuGroups = (me: Caller): string[] => visibleNav(me).map((a) => a.id);

/** The menu entry a route belongs to: itself, or the longest entry above it (/ops/incidents/:id → /ops/incidents). */
export function menuEntryOf(path: string): string | null {
  let best: string | null = null;
  for (const i of NAV_ITEMS) if ((path === i.to || path.startsWith(i.to + '/')) && (!best || i.to.length > best.length)) best = i.to;
  return best;
}
