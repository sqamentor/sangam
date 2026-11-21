// SPDX-License-Identifier: Apache-2.0
/** Decision 397 (UX-SPEC sections 3-4): the seven job groups, plain labels, one namespace, persona menus. */
import { describe, expect, it } from 'vitest';
import app from './App.tsx?raw';
import type { Role } from '../api/types';
import en from '../i18n/locales/en.json';
import { MODULE_OF, NAV, NAV_ITEMS, navEnd, navLocate, visibleNav } from './nav';

const flat = (o: object, p = '', out: Record<string, string> = {}): Record<string, string> => {
  for (const [k, v] of Object.entries(o)) {
    const key = p ? `${p}.${k}` : k;
    if (v && typeof v === 'object') flat(v as object, key, out);
    else out[key] = String(v);
  }
  return out;
};
const EN = flat(en);
const me = (roles: Role[], permissions: string[] = []) => ({ roles, permissions });
const menuOf = (roles: Role[], perms: string[] = []) => visibleNav(me(roles, perms)).map((a) => a.id);
const routesOf = (roles: Role[]) => visibleNav(me(roles)).flatMap((a) => a.sections.flatMap((s) => s.items.map((i) => i.to)));

describe('menu structure (decision 397)', () => {
  it('has the seven job groups and Help, in order', () => {
    expect(NAV.map((a) => a.id)).toEqual(['work', 'services', 'find', 'design', 'monitor', 'setup', 'reports', 'help']);
  });

  it('every group, section and entry has an English label, and every entry a one-line description', () => {
    for (const a of NAV) {
      expect(EN[a.label], a.label).toBeTruthy();
      for (const s of a.sections) if (s.label) expect(EN[s.label], s.label).toBeTruthy();
    }
    for (const i of NAV_ITEMS) {
      expect(i.label, i.to).toMatch(/^menu\.[a-z]+\.[a-zA-Z]+\.label$/);
      expect(i.description).toBe(i.label.replace(/\.label$/, '.help'));
      expect(EN[i.label], i.label).toBeTruthy();
      expect(EN[i.description], i.description).toBeTruthy();
    }
  });

  it('labels are short, sentence case and free of engine jargon (naming rules 4.2, 4.4, 4.5)', () => {
    const banned = /\b(BPMN|DMN|CMMN|DRD|incidents?|jobs?|external task|topics?|outbox|deployments?|tenants?|seats?|slots?|principals?|IdP|SoD|SLA|idempotency|lease|instances?|definitions?|modules?|ingest|packs?)\b/i;
    for (const i of NAV_ITEMS) {
      const label = EN[i.label];
      expect(label.length, label).toBeLessThanOrEqual(28);
      expect(label.split(/\s+/).length, label).toBeLessThanOrEqual(5);
      expect(label, label).not.toMatch(banned);
      expect(label.split(' ').slice(1).filter((w) => /^[A-Z][a-z]/.test(w)), label).toEqual([]);
      expect(EN[i.description].length, i.description).toBeLessThanOrEqual(90);
    }
    for (const a of NAV) expect(EN[a.label]).not.toMatch(banned);
  });

  it('has no duplicate labels and no route in two places', () => {
    const labels = NAV_ITEMS.map((i) => EN[i.label]);
    expect(labels.filter((l, n) => labels.indexOf(l) !== n)).toEqual([]);
    const routes = NAV_ITEMS.map((i) => i.to);
    expect(routes.filter((r, n) => routes.indexOf(r) !== n)).toEqual([]);
  });

  it('keeps every sub-section to at most 9 lines', () => {
    for (const a of NAV) for (const s of a.sections) expect(s.items.length, `${a.id} ${s.label ?? ''}`).toBeLessThanOrEqual(9);
  });

  it('has no Modules group and no old namespace in the menu', () => {
    expect(NAV.some((a) => a.id === 'modules')).toBe(false);
    expect(NAV_ITEMS.filter((i) => !i.label.startsWith('menu.'))).toEqual([]);
  });
});

describe('persona menus', () => {
  it('a citizen sees only Services and Help', () => {
    expect(menuOf(['CITIZEN'])).toEqual(['services', 'help']);
  });
  it('a task user sees My work, Track and find and Help; not the audit trail (Q6), not platform screens', () => {
    expect(menuOf(['TASK_USER'])).toEqual(['work', 'find', 'help']);
    const r = routesOf(['TASK_USER']);
    expect(r).toEqual(expect.arrayContaining(['/tasks', '/start', '/cases', '/my/delegations', '/documents', '/m/ledger']));
    for (const no of ['/audit', '/audit/exports', '/tasks/filters', '/team']) expect(r).not.toContain(no);
  });
  it('a platform operator sees the engine filters; Team workload and Shared task lists need team.read (decisions 401, 404)', () => {
    expect(routesOf(['PLATFORM_OPERATOR'])).toEqual(expect.arrayContaining(['/ops/engine/filters', '/ops/engine']));
    // U12a: Design is the designers' group; Test run alone no longer brings it into an operator's menu
    expect(routesOf(['PLATFORM_OPERATOR'])).not.toContain('/design/test');
    expect(routesOf(['PLATFORM_OPERATOR'])).not.toContain('/team');
    expect(routesOf(['PLATFORM_OPERATOR'])).not.toContain('/tasks/filters');
  });
  it('a supervisor (team.read) sees Team workload and Shared task lists, and only those from My work', () => {
    const r = visibleNav(me([], ['team.read'])).flatMap((a) => a.sections.flatMap((s) => s.items.map((i) => i.to)));
    expect(r.filter((x) => !x.startsWith('/help') && x !== '/verify')).toEqual(['/team', '/tasks/filters']);
    expect(visibleNav(me([], ['*'])).flatMap((a) => a.sections.flatMap((s) => s.items.map((i) => i.to)))).toContain('/team');
  });
  it('an operator sees Monitor and fix, Reports and audit, but not engine screens and not Design (U12a)', () => {
    expect(menuOf(['OPERATOR'])).toEqual(['work', 'find', 'monitor', 'reports', 'help']);
    expect(routesOf(['OPERATOR'])).not.toContain('/ops/engine');
  });
  it('a designer sees Design with Test run', () => {
    expect(menuOf(['MODELLER'])).toEqual(['design', 'help']);
    expect(routesOf(['MODELLER'])).toContain('/design/test');
  });
  it('a tenant admin sees Set up without the organisations list (Q5); a platform admin sees it', () => {
    expect(routesOf(['ADMIN'])).not.toContain('/admin/tenants');
    expect(routesOf(['ADMIN', 'PLATFORM_ADMIN'])).toContain('/admin/tenants');
  });
  it('an auditor sees the audit trail, exports and data protection', () => {
    expect(routesOf(['AUDITOR'])).toEqual(expect.arrayContaining(['/audit', '/audit/exports', '/m/compliance', '/documents']));
  });
  it('an analyst sees Reports and audit without the audit trail', () => {
    expect(menuOf(['ANALYST'])).toEqual(['reports', 'help']);
    expect(routesOf(['ANALYST'])).not.toContain('/audit');
  });
  it('a holder of a module permission alone still reaches that module (M-028)', () => {
    expect(menuOf([], ['compliance.read'])).toEqual(['reports', 'help']);
  });
  it('a signed-out caller sees nothing', () => {
    expect(visibleNav(null)).toEqual([]);
  });
});

describe('links and breadcrumb', () => {
  it('an entry with another entry below it matches exactly', () => {
    expect(navEnd('/tasks')).toBe(true);
    expect(navEnd('/ops')).toBe(true);
    expect(navEnd('/ops/jobs')).toBe(false);
  });
  it('locates the group and entry of a path, the longest entry winning', () => {
    expect(navLocate('/ops/incidents/42')?.item.to).toBe('/ops/incidents');
    expect(navLocate('/ops/incidents/42')?.area.id).toBe('monitor');
    expect(navLocate('/tasks/filters')?.item.to).toBe('/tasks/filters');
    expect(navLocate('/nowhere')).toBeNull();
  });
});

describe('routes and redirects (decision 397, WP-3)', () => {
  it.each([
    ['/ops/task-dashboard', '/team'],
    ['/ops/debugger', '/design/test'],
    ['/inbox', '/tasks'],
  ])('%s redirects to %s', (from, to) => {
    expect(app).toContain(`<Route path="${from}" element={<Navigate to="${to}" replace />} />`);
  });
  it('the new addresses are real routes', () => {
    for (const p of ['/team', '/design/test', '/help/glossary', '/help/shortcuts']) expect(app).toContain(`<Route path="${p}" element=`);
  });
});

describe('modules that are not installed (decision 399 Q3)', () => {
  const admin = me(['ADMIN', 'PLATFORM_ADMIN', 'TASK_USER', 'AUDITOR', 'OPERATOR']);
  const routes = (installed?: ReadonlySet<string> | null) => visibleNav(admin, installed).flatMap((a) => a.sections.flatMap((s) => s.items.map((i) => i.to)));
  it('every module screen in the menu names its module', () => {
    for (const i of NAV_ITEMS.filter((x) => x.to.startsWith('/m/'))) expect(i.module, i.to).toBe(MODULE_OF[i.to]);
    expect(Object.keys(MODULE_OF).sort()).toEqual(NAV_ITEMS.filter((x) => x.to.startsWith('/m/')).map((x) => x.to).sort());
  });
  it('hides the entries of a module that is off, and only those', () => {
    const all = routes(null);
    const some = routes(new Set(['ledger', 'case']));
    expect(some).toContain('/m/ledger');
    expect(some).toEqual(expect.arrayContaining(['/m/case-files', '/m/committees', '/m/references']));
    for (const off of ['/m/billing', '/m/geo', '/m/compliance', '/m/channels', '/m/queues']) expect(some).not.toContain(off);
    expect(all.filter((r) => !r.startsWith('/m/'))).toEqual(some.filter((r) => !r.startsWith('/m/')));
  });
  it('hides nothing while the list is unknown', () => {
    expect(routes(null)).toEqual(routes(undefined));
    expect(routes(null)).toEqual(expect.arrayContaining(Object.keys(MODULE_OF)));
  });
  it('drops a group left empty', () => {
    expect(visibleNav(me([], ['compliance.read']), new Set()).map((a) => a.id)).toEqual(['help']);
  });
});
