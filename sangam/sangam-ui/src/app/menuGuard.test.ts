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

describe('U12c: Deployments is in the menu of the designers who may open it', () => {
  // GET /deployments needs deployment.read (DeploymentController); the route guard admits MODELLER and REVIEWER
  it.each(['sangam-model-author', 'sangam-model-reviewer', 'sangam-model-approver'])('%s sees Deployments in the menu', (group) => {
    const designer = callerOf(group);
    expect(designer.permissions).toContain('deployment.read');
    expect(opens(designer, '/ops/deployments')).toBe(true);
    expect(menuRoutes(designer)).toContain('/ops/deployments');
    // and nothing else of Monitor and fix
    expect(menuRoutes(designer).filter((r) => r.startsWith('/ops'))).toEqual(['/ops/deployments']);
  });
  it('operators keep it; a caller the route refuses does not get it', () => {
    for (const g of ['sangam-operator', 'sangam-engine-admin', 'sangam-admin']) expect(menuRoutes(callerOf(g)), g).toContain('/ops/deployments');
    for (const g of ['sangam-user', 'sangam-auditor', 'sangam-tenant-admin', 'sangam-model-deployer', 'sangam-integration']) {
      expect(opens(callerOf(g), '/ops/deployments'), g).toBe(false);
      expect(menuRoutes(callerOf(g)), g).not.toContain('/ops/deployments');
    }
  });
});

describe('U12d: the operator audit log is in an auditor\'s menu', () => {
  // GET /history/operation-log needs history.read (HistoryAuditController), which sangam-auditor holds
  it('sangam-auditor may open it and finds it in the menu, alone from Monitor and fix', () => {
    const auditor = callerOf('sangam-auditor');
    expect(auditor.permissions).toContain('history.read');
    expect(opens(auditor, '/ops/audit-log')).toBe(true);
    expect(menuRoutes(auditor)).toContain('/ops/audit-log');
    expect(menuRoutes(auditor).filter((r) => r.startsWith('/ops'))).toEqual(['/ops/audit-log']);
  });
  it('operators keep it; a caller the route refuses does not get it', () => {
    for (const g of ['sangam-operator', 'sangam-engine-admin', 'sangam-admin']) expect(menuRoutes(callerOf(g)), g).toContain('/ops/audit-log');
    for (const g of ['sangam-user', 'sangam-tenant-admin', 'sangam-dpo', 'sangam-model-author']) {
      expect(opens(callerOf(g), '/ops/audit-log'), g).toBe(false);
      expect(menuRoutes(callerOf(g)), g).not.toContain('/ops/audit-log');
    }
  });
});

describe('U12e: Retention is not open to an auditor', () => {
  // GET /admin/retention-classes needs admin.read (RegistryController), which sangam-auditor does not hold
  it('the route refuses sangam-auditor, and the menu never offered it', () => {
    const auditor = callerOf('sangam-auditor');
    expect(auditor.permissions).not.toContain('admin.read');
    expect(opens(auditor, '/admin/retention')).toBe(false);
    expect(menuRoutes(auditor)).not.toContain('/admin/retention');
  });
  it('administrators keep it, in the route and in the menu', () => {
    for (const g of ['sangam-tenant-admin', 'sangam-admin']) {
      expect(opens(callerOf(g), '/admin/retention'), g).toBe(true);
      expect(menuRoutes(callerOf(g)), g).toContain('/admin/retention');
    }
  });
});

describe('U12f: the list of organisations is for platform administrators only (decision 397 Q5)', () => {
  it('the route refuses an organisation admin, as the menu already did', () => {
    const tenantAdmin = callerOf('sangam-tenant-admin');
    expect(tenantAdmin.roles).toContain('ADMIN');
    expect(tenantAdmin.roles).not.toContain('PLATFORM_ADMIN');
    expect(menuRoutes(tenantAdmin)).not.toContain('/admin/tenants');
    expect(opens(tenantAdmin, '/admin/tenants')).toBe(false);
  });
  it('a platform administrator keeps it, in the route and in the menu; nobody else opens it', () => {
    expect(opens(callerOf('sangam-admin'), '/admin/tenants')).toBe(true);
    expect(menuRoutes(callerOf('sangam-admin'))).toContain('/admin/tenants');
    expect(SERVER_GROUPS.filter((g) => opens(callerOf(g), '/admin/tenants'))).toEqual(['sangam-admin']);
  });
});

describe('U12g: Extra fields is an administrator page; holding form.read does not bring Set up into the menu', () => {
  // GET /custom-fields needs form.read, which every user, operator and designer holds for filling forms; defining and
  // retiring a field need tenant.admin (TenantCustomFieldController), the page's job
  it.each(['sangam-user', 'sangam-operator', 'sangam-model-author'])('%s holds form.read and gets neither Set up nor the page', (group) => {
    const caller = callerOf(group);
    expect(caller.permissions).toContain('form.read');
    expect(caller.permissions).not.toContain('tenant.admin');
    expect(menuGroups(caller)).not.toContain('setup');
    expect(menuRoutes(caller)).not.toContain('/admin/custom-fields');
    expect(opens(caller, '/admin/custom-fields')).toBe(false);
  });
  it('an organisation admin and a platform admin keep it, in the route and in the menu; nobody else has Set up', () => {
    for (const g of ['sangam-tenant-admin', 'sangam-admin']) {
      expect(opens(callerOf(g), '/admin/custom-fields'), g).toBe(true);
      expect(menuRoutes(callerOf(g)), g).toContain('/admin/custom-fields');
    }
    expect(SERVER_GROUPS.filter((g) => menuGroups(callerOf(g)).includes('setup'))).toEqual(['sangam-tenant-admin', 'sangam-admin']);
  });
});

describe('U12h: Event types is not offered to a caller without event.admin', () => {
  // both first calls, GET /events/channels and GET /events/definitions, need event.admin (EventFormController)
  it('sangam-operator lacks event.admin and does not get the entry; the rest of Monitor and fix stays', () => {
    const operator = callerOf('sangam-operator');
    expect(operator.permissions).not.toContain('event.admin');
    expect(menuRoutes(operator)).not.toContain('/ops/events');
    expect(menuRoutes(operator)).toEqual(expect.arrayContaining(['/ops', '/ops/incidents', '/ops/jobs', '/ops/outbox', '/ops/workers', '/ops/notifications', '/ops/reconciliation']));
  });
  it('an engine administrator (no permission at all) does not get it either; a platform administrator ("*") does', () => {
    expect(menuRoutes(callerOf('sangam-engine-admin'))).not.toContain('/ops/events');
    expect(menuRoutes(callerOf('sangam-admin'))).toContain('/ops/events');
  });
  it('holding event.admin without an operator role does not bring Monitor and fix: the route refuses an organisation admin', () => {
    const tenantAdmin = callerOf('sangam-tenant-admin');
    expect(tenantAdmin.permissions).toContain('event.admin');
    expect(opens(tenantAdmin, '/ops/events')).toBe(false);
    expect(menuRoutes(tenantAdmin)).not.toContain('/ops/events');
  });
});
