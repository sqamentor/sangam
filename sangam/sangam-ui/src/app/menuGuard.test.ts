// SPDX-License-Identifier: Apache-2.0
/**
 * U12 (owner answers of 2026-11-21 to the menu and route-guard questions): what each server group sees in the menu and
 * what its route guards admit. Every caller here is the one GET /me describes for a server group
 * (serverGroups.testkit.ts: permissions from Permissions.java, roles as OperatonWorkspaceAdapter derives them), never a
 * bare role with no permissions.
 */
import { describe, expect, it } from 'vitest';
import { ROUTES, menuGroups, menuRoutes, opens } from './routeGuards.testkit';
import { SERVER_GROUPS, callerOf } from './serverGroups.testkit';

const DEPARTMENT_TOOLS = ['/m/ledger', '/m/billing', '/m/programmes', '/m/resources', '/m/queues', '/m/rosters', '/m/geo', '/m/field', '/m/ai'];
const FIND_MODULES = ['/m/case-files', '/m/committees', '/m/references', '/m/registry'];

describe('test support reads the route table', () => {
  it('finds every route of the signed-in table and understands its guard', () => {
    expect(ROUTES.length).toBeGreaterThan(110);
    expect(ROUTES.filter((r) => r.kind === 'redirect').map((r) => r.path)).toEqual(['/inbox', '/ops/task-dashboard', '/ops/debugger']);
    expect(ROUTES.find((r) => r.path === '/tasks')).toEqual({ path: '/tasks', kind: 'roles', roles: ['TASK_USER'] });
    expect(ROUTES.find((r) => r.path === '/m/ledger')?.kind).toBe('access');
    expect(ROUTES.find((r) => r.path === '/team')?.access).toEqual({ roles: [], perms: ['team.read'] });
    expect(ROUTES.find((r) => r.path === '/welcome')?.kind).toBe('open');
  });
});

describe('U12a: a menu group shows only for the callers it is meant for', () => {
  it('an auditor does not get My work (it held only Department tools)', () => {
    const auditor = callerOf('sangam-auditor');
    expect(menuGroups(auditor)).not.toContain('work');
    for (const r of DEPARTMENT_TOOLS) expect(menuRoutes(auditor), r).not.toContain(r);
    // what an auditor is meant to see stays: Track and find, Reports and audit
    expect(menuGroups(auditor)).toEqual(expect.arrayContaining(['find', 'reports', 'help']));
    expect(menuRoutes(auditor)).toEqual(expect.arrayContaining([...FIND_MODULES, '/documents', '/audit', '/audit/exports', '/m/compliance']));
  });

  it('an operator and an engine administrator do not get Design (it held only Test run)', () => {
    for (const g of ['sangam-operator', 'sangam-engine-admin', 'sangam-admin']) {
      expect(menuGroups(callerOf(g)), g).not.toContain('design');
      expect(menuRoutes(callerOf(g)), g).not.toContain('/design/test');
    }
    // designers keep Test run (decision 397 Q10)
    for (const g of ['sangam-model-author', 'sangam-model-reviewer', 'sangam-model-approver']) expect(menuRoutes(callerOf(g)), g).toContain('/design/test');
  });

  it('an organisation admin and a platform admin do not get Track and find (it held four module entries)', () => {
    for (const g of ['sangam-tenant-admin', 'sangam-admin']) {
      expect(menuGroups(callerOf(g)), g).not.toContain('find');
      for (const r of FIND_MODULES) expect(menuRoutes(callerOf(g)), `${g} ${r}`).not.toContain(r);
    }
    // the callers the group is meant for keep all of it
    for (const g of ['sangam-user', 'sangam-operator', 'sangam-auditor']) expect(menuRoutes(callerOf(g)), g).toEqual(expect.arrayContaining([...FIND_MODULES, '/documents']));
  });

  it('task users and operators keep Department tools; an organisation admin keeps My work for the team entries (decision 404)', () => {
    expect(menuRoutes(callerOf('sangam-user'))).toEqual(expect.arrayContaining(DEPARTMENT_TOOLS));
    expect(menuRoutes(callerOf('sangam-operator'))).toEqual(expect.arrayContaining(DEPARTMENT_TOOLS));
    expect(menuRoutes(callerOf('sangam-tenant-admin'))).toEqual(expect.arrayContaining(['/team', '/tasks/filters']));
    expect(menuRoutes(callerOf('sangam-supervisor'))).toEqual(['/team', '/tasks/filters', '/help/glossary', '/help/shortcuts', '/verify', '/help/accessibility', '/help/about']);
  });

  it('Reports and audit is meant for an organisation admin: GET /me gives every holder of analytics.read the role ANALYST', () => {
    const admin = callerOf('sangam-tenant-admin');
    expect(admin.roles).toContain('ANALYST');
    expect(menuRoutes(admin)).toEqual(expect.arrayContaining(['/analytics', '/analytics/reports', '/analytics/alerts', '/analytics/mining', '/analytics/agents', '/m/compliance']));
    // a data protection officer (no role) still reaches Data protection by permission (M-028)
    expect(menuGroups(callerOf('sangam-dpo'))).toEqual(['reports', 'help']);
  });
});

describe('U12b: the INTEGRATION role is not let into pages its permissions cannot load', () => {
  // first calls: GET /ops/workers ops.read (OpsConsoleController), GET /events/channels event.admin
  // (EventFormController), GET /admin/service-principals admin.read (IdentityAdminController), GET /admin/services
  // admin.read (RegistryController); sangam-integration holds none of them
  const PAGES: [string, string][] = [
    ['/ops/workers', 'ops.read'],
    ['/ops/events', 'event.admin'],
    ['/admin/principals', 'admin.read'],
    ['/admin/services', 'admin.read'],
  ];
  it.each(PAGES)('%s (first call needs %s) refuses sangam-integration', (path, permission) => {
    const integration = callerOf('sangam-integration');
    expect(integration.roles).toEqual(['INTEGRATION']);
    expect(integration.permissions).not.toContain(permission);
    expect(opens(integration, path)).toBe(false);
  });
  it('no route names the INTEGRATION role any more, and the callers who had these pages keep them', () => {
    expect(ROUTES.filter((r) => r.roles?.includes('INTEGRATION')).map((r) => r.path)).toEqual([]);
    for (const p of ['/ops/workers', '/ops/events']) for (const g of ['sangam-operator', 'sangam-engine-admin', 'sangam-admin']) expect(opens(callerOf(g), p), `${g} ${p}`).toBe(true);
    for (const p of ['/admin/principals', '/admin/services']) for (const g of ['sangam-tenant-admin', 'sangam-admin']) expect(opens(callerOf(g), p), `${g} ${p}`).toBe(true);
    expect(SERVER_GROUPS).toContain('sangam-integration');
  });
});
