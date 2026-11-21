// SPDX-License-Identifier: Apache-2.0
/**
 * U12 (owner answers of 2026-11-21 to the menu and route-guard questions): what each server group sees in the menu and
 * what its route guards admit. Every caller here is the one GET /me describes for a server group
 * (serverGroups.testkit.ts: permissions from Permissions.java, roles as OperatonWorkspaceAdapter derives them), never a
 * bare role with no permissions.
 */
import { describe, expect, it } from 'vitest';
import { ROUTES, admits, menuEntryOf, menuGroups, menuRoutes, opens } from './routeGuards.testkit';
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
  it('an auditor does not get My work (it held only Department tools: 7 of the 9 entries)', () => {
    const auditor = callerOf('sangam-auditor');
    // the section has 9 entries; an auditor may open 7: /m/field and /m/ai need field.capture and ai.use (USER_EXTRA_HOLDERS)
    expect(DEPARTMENT_TOOLS).toHaveLength(9);
    expect(DEPARTMENT_TOOLS.filter((r) => opens(auditor, r))).toEqual(['/m/ledger', '/m/billing', '/m/programmes', '/m/resources', '/m/queues', '/m/rosters', '/m/geo']);
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

describe('U12g: Extra fields is an administrator entry; holding form.read does not bring Set up into the menu', () => {
  // Integrator ruling 2026-11-21 (joint ledger [AGREE] 03:57 UTC): the menu entry only; the route guard stays as built.
  // GET /custom-fields needs form.read, which every user, operator and designer holds for filling forms; defining and
  // retiring a field need tenant.admin (TenantCustomFieldController), the page's job
  it.each(['sangam-user', 'sangam-operator', 'sangam-model-author'])('%s holds form.read and does not get Set up; the route guard is unchanged', (group) => {
    const caller = callerOf(group);
    expect(caller.permissions).toContain('form.read');
    expect(caller.permissions).not.toContain('tenant.admin');
    expect(menuGroups(caller)).not.toContain('setup');
    expect(menuRoutes(caller)).not.toContain('/admin/custom-fields');
    expect(opens(caller, '/admin/custom-fields')).toBe(true);
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

/**
 * Every route of the signed-in table against its menu entry, for the caller GET /me describes per server group.
 * A cell: M = the guard admits the caller and the entry (for a sub-page: the entry above it) is in his menu;
 * o = the guard admits him and the page has no menu entry for anyone (opened from a list, the banner or a link);
 * . = the guard refuses him and his menu does not offer it; R = the guard admits him but his menu has no entry (each
 * one is listed in INTENDED with its reason); X = his menu offers a page the guard refuses (never).
 */
const INTENDED: Record<string, string[]> = {
  'the status page of the channels is an administrator\'s job (nav.ts channelStatus; kept as built, U12 "no change")': [
    '/m/channels | sangam-user',
    '/m/channels | sangam-operator',
    '/m/channels | sangam-auditor',
  ],
  'decision 397 Q6: a task user reads a request\'s history from inside the request; no audit entries in his menu': [
    '/audit | sangam-user',
    '/audit/timeline/:businessKey | sangam-user',
    '/audit/exports | sangam-user',
  ],
  'a request page opened from the operator\'s lists (Requests in Monitor and fix); My requests is a task user\'s entry': [
    '/cases/:id | sangam-operator',
    '/cases/:id | sangam-admin',
    '/cases/:id | sangam-engine-admin',
  ],
  'U12a: Department tools are not in an auditor\'s menu (he holds the module reads; route guards unchanged)': [
    '/m/resources | sangam-auditor',
    '/m/queues | sangam-auditor',
    '/m/rosters | sangam-auditor',
    '/m/ledger | sangam-auditor',
    '/m/billing | sangam-auditor',
    '/m/programmes | sangam-auditor',
    '/m/geo | sangam-auditor',
  ],
  'U12a: Track and find is not in an administrator\'s menu (he holds the module reads; route guards unchanged)': [
    '/m/registry | sangam-tenant-admin',
    '/m/registry | sangam-admin',
    '/m/case-files | sangam-tenant-admin',
    '/m/case-files | sangam-admin',
    '/m/committees | sangam-tenant-admin',
    '/m/committees | sangam-admin',
    '/m/references | sangam-tenant-admin',
    '/m/references | sangam-admin',
  ],
  'U12a: Design is the designers\' group; Test run alone does not bring it to operators (route guard unchanged)': [
    '/design/test | sangam-operator',
    '/design/test | sangam-admin',
    '/design/test | sangam-engine-admin',
  ],
  'U12g: Extra fields is an administrator entry; the ruling changes the menu only, the guard (form.read) stays as built': [
    '/admin/custom-fields | sangam-user',
    '/admin/custom-fields | sangam-operator',
    '/admin/custom-fields | sangam-model-author',
  ],
  'U12h: Event types is hidden from a caller without event.admin; the record changes the menu only, not the guard': [
    '/ops/events | sangam-operator',
    '/ops/events | sangam-engine-admin',
  ],
};

describe('every route against its menu entry, per server group', () => {
  const rows = ROUTES.filter((r) => r.kind !== 'redirect');
  const cell = (path: string, group: string): string => {
    const r = rows.find((x) => x.path === path)!;
    const me = callerOf(group);
    const entry = menuEntryOf(path);
    const open = admits(r, me);
    const inMenu = entry !== null && menuRoutes(me).includes(entry);
    if (open) return entry === null ? 'o' : inMenu ? 'M' : 'R';
    return entry === path && inMenu ? 'X' : '.';
  };
  const cells = (symbol: string) => rows.flatMap((r) => SERVER_GROUPS.filter((g) => cell(r.path, g) === symbol).map((g) => `${r.path} | ${g}`));

  it('no menu offers a page its route guard refuses', () => {
    expect(cells('X')).toEqual([]);
  });

  it('a page open to a caller without a menu entry is one of the listed, intended cases, and every listed case exists', () => {
    const intended = Object.values(INTENDED).flat();
    expect(new Set(intended).size).toBe(intended.length);
    expect(cells('R').sort()).toEqual([...intended].sort());
    expect(intended).toHaveLength(32);
  });

  it('every menu entry is a route of the table, and every caller\'s menu is covered by the table', () => {
    for (const g of SERVER_GROUPS) for (const to of menuRoutes(callerOf(g))) expect(rows.some((r) => r.path === to || r.path === `${to}/:tenant?`), `${g} ${to}`).toBe(true);
  });

  it('writes the table (src/app/__snapshots__/route-menu-table.md)', async () => {
    const short = (g: string) => g.replace(/^sangam-/, '');
    const reasonOf = (key: string) => Object.entries(INTENDED).find(([, v]) => v.includes(key))?.[0] ?? '';
    const lines = [
      '# Routes against the menu, per server group',
      '',
      'Generated by `src/app/menuGuard.test.ts` from `App.tsx` (route guards), `nav.ts` (menu) and the server\'s default',
      'group permissions (`Permissions.java`, roles as `OperatonWorkspaceAdapter` derives them). Do not edit by hand:',
      'run `npx vitest --run src/app/menuGuard.test.ts -u`.',
      '',
      'M = open and in the menu (for a sub-page: the entry above it); o = open, the page has no menu entry for anyone;',
      '. = refused and not offered; R = open but not in this caller\'s menu (listed below); X = offered but refused.',
      '',
      `| Route | Guard | Menu entry | ${SERVER_GROUPS.map(short).join(' | ')} |`,
      `|---|---|---|${SERVER_GROUPS.map(() => '---').join('|')}|`,
      ...rows.map((r) => {
        const guard = r.kind === 'roles' ? r.roles!.join(', ') : r.kind === 'access' ? [...r.access!.roles, ...r.access!.perms.map((p) => `perm ${p}`)].join(', ') : 'signed in';
        return `| ${r.path} | ${guard} | ${menuEntryOf(r.path) ?? '-'} | ${SERVER_GROUPS.map((g) => cell(r.path, g)).join(' | ')} |`;
      }),
      '',
      `Redirects: ${ROUTES.filter((r) => r.kind === 'redirect').map((r) => `${r.path} to ${r.redirectTo}`).join('; ')}.`,
      '',
      `Totals: ${rows.length} routes x ${SERVER_GROUPS.length} groups = ${rows.length * SERVER_GROUPS.length} cells; M ${cells('M').length}, o ${cells('o').length}, . ${cells('.').length}, R ${cells('R').length}, X ${cells('X').length}.`,
      '',
      '## Open without a menu entry (R), each intended',
      '',
      '| Route | Group | Reason |',
      '|---|---|---|',
      ...cells('R').map((k) => `| ${k.split(' | ')[0]} | ${k.split(' | ')[1]} | ${reasonOf(k)} |`),
      '',
      '## Menu per server group',
      '',
      '| Group | Roles (GET /me) | Menu groups | Entries |',
      '|---|---|---|---|',
      ...SERVER_GROUPS.map((g) => `| ${g} | ${callerOf(g).roles.join(', ') || '-'} | ${menuGroups(callerOf(g)).join(', ')} | ${menuRoutes(callerOf(g)).length} |`),
      '',
    ];
    await expect(lines.join('\n')).toMatchFileSnapshot('./__snapshots__/route-menu-table.md');
  });
});