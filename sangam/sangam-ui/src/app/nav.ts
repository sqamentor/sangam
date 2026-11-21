// SPDX-License-Identifier: Apache-2.0
/**
 * The menu (owner decision 397, UX-SPEC section 3): seven groups named by the job the user wants to do, each split into
 * short sub-sections, plus Help for everyone. Every entry has a plain label and a one-line description (shown as the
 * entry's tooltip and as the page description), both under the one namespace `menu.*` in en.json.
 *
 * A convenience only: every screen's data is authorised by the server (X.4.7). The visibility rule mirrors each route's
 * guard in App.tsx, so the menu never offers a screen that the guard would refuse.
 */
import type { Me, Role } from '../api/types';
import { EVENT_ADMIN, ROUTE_ACCESS, TEAM_READ, canAccess, type Access } from './access';

export interface NavItem {
  to: string;
  /** i18n key of the label (`menu.<group>.<entry>.label`). */
  label: string;
  /** i18n key of the one-line description (`menu.<group>.<entry>.help`). */
  description: string;
  /** Further restriction: within the group's roles, or (with `access`) to the callers whose job the group is. */
  only?: Role[];
  /** Replaces the group's roles for this entry: role or endpoint permission (M-028, access.ts). */
  access?: Access;
  /** Module id (GET /me/capabilities): the entry is hidden when the installation has not wired that module (Q3). */
  module?: string;
}
export interface NavSection {
  /** i18n key of the small heading; absent for the group's first, unnamed section. */
  label?: string;
  items: NavItem[];
}
export interface NavArea {
  id: string;
  label: string; // i18n key
  /** Roles that see the group's entries without their own `access`; empty = every signed-in caller. */
  roles: Role[];
  sections: NavSection[];
}

const OPS: Role[] = ['OPERATOR', 'PLATFORM_OPERATOR'];
const MODEL: Role[] = ['MODELLER', 'REVIEWER'];
const ANALYST: Role[] = ['ANALYST', 'OPERATOR'];

/** Entry with the conventional keys `menu.<group>.<entry>.label` and `.help`. */
const e = (group: string, entry: string, to: string, extra: Partial<NavItem> = {}): NavItem => ({
  to,
  label: `menu.${group}.${entry}.label`,
  description: `menu.${group}.${entry}.help`,
  ...extra,
});
const sec = (group: string, id: string) => `menu.${group}.section.${id}`;
/**
 * Decision 399 Q3: the module behind each module screen, as GET /me/capabilities names it (InstalledModulesController).
 * When a module is off its routes are not mapped at all, so its screen could only fail.
 */
export const MODULE_OF: Readonly<Record<string, string>> = {
  '/m/ai': 'ai',
  '/m/channels': 'channels',
  '/m/case-files': 'case',
  '/m/committees': 'case',
  '/m/references': 'case',
  '/m/registry': 'registry',
  '/m/ledger': 'ledger',
  '/m/billing': 'billing',
  '/m/resources': 'resources',
  '/m/queues': 'resources',
  '/m/rosters': 'resources',
  '/m/programmes': 'programme',
  '/m/field': 'field',
  '/m/geo': 'geo',
  '/m/compliance': 'compliance',
};
const mod = (group: string, entry: string, to: string, extra: Partial<NavItem> = {}) => e(group, entry, to, { access: ROUTE_ACCESS[to], module: MODULE_OF[to], ...extra });
/**
 * U12a (owner, 2026-11-21): a group shows only for the callers it is meant for. An entry with its own `access` is
 * admitted by the server permission of its screen, which more callers hold than the group is meant for (every staff
 * role holds the module reads), so such an entry also names whose job it is with `only`; its route guard is unchanged.
 */
/**
 * Department tools: not an auditor's work (an auditor holds the module reads, Permissions.java GROUP_AUDITOR; the
 * section has 9 entries, of which he saw 7). Deviation from the U12a wording, as ruled on 2026-11-21: an organisation
 * admin keeps them. His My work is his anyway through Team workload and Shared task lists (team.read, decision 404), and
 * he alone holds the admin permissions of these tools.
 */
const DEPARTMENT: Role[] = ['TASK_USER', 'OPERATOR', 'ADMIN', 'PLATFORM_ADMIN'];
/** Track and find: the group's own roles; an organisation admin holds the module reads too and is not one of them. */
const FIND: Role[] = ['TASK_USER', 'OPERATOR', 'AUDITOR'];
const dept = (entry: string, to: string) => mod('work', entry, to, { only: DEPARTMENT });
/** The platform administrator ("*") keeps a menu link to every page he can open (ruling of 2026-11-21). */
const PLATFORM: Role[] = ['PLATFORM_ADMIN'];
const find = (entry: string, to: string) => mod('find', entry, to, { only: [...FIND, ...PLATFORM] });

export const NAV: NavArea[] = [
  {
    id: 'work',
    label: 'menu.work.title',
    roles: ['TASK_USER'],
    sections: [
      {
        items: [
          e('work', 'tasks', '/tasks'),
          e('work', 'start', '/start'),
          e('work', 'requests', '/cases'),
          // Decision 401: a Sangam read limited to the caller's organisation, for holders of team.read.
          e('work', 'team', '/team', { access: TEAM_READ }),
          // Decision 404: Sangam-kept saved lists of the caller's organisation (team.read).
          e('work', 'taskLists', '/tasks/filters', { access: TEAM_READ }),
          e('work', 'field', '/field'),
          e('work', 'away', '/my/delegations'),
          mod('work', 'signature', '/my/dsc-certificates'),
        ],
      },
      {
        label: sec('work', 'department'),
        items: [
          dept('ledger', '/m/ledger'),
          dept('billing', '/m/billing'),
          dept('programmes', '/m/programmes'),
          dept('resources', '/m/resources'),
          dept('queues', '/m/queues'),
          dept('rosters', '/m/rosters'),
          dept('geo', '/m/geo'),
          dept('fieldSetup', '/m/field'),
          dept('ai', '/m/ai'),
        ],
      },
    ],
  },
  {
    id: 'services',
    label: 'menu.services.title',
    roles: ['CITIZEN'],
    sections: [
      {
        items: [
          e('services', 'find', '/portal'),
          e('services', 'applications', '/portal/applications'),
          e('services', 'complaints', '/portal/grievances'),
          e('services', 'notices', '/portal/notices'),
          e('services', 'consent', '/portal/consents'),
        ],
      },
    ],
  },
  {
    id: 'find',
    label: 'menu.find.title',
    // The guard of /documents (App.tsx AUDIT).
    roles: FIND,
    sections: [
      {
        items: [
          find('caseFiles', '/m/case-files'),
          find('committees', '/m/committees'),
          find('references', '/m/references'),
          find('registers', '/m/registry'),
          e('find', 'documents', '/documents'),
        ],
      },
    ],
  },
  {
    id: 'design',
    label: 'menu.design.title',
    roles: MODEL,
    sections: [
      {
        items: [
          e('design', 'projects', '/modeller'),
          e('design', 'review', '/modeller/review'),
          e('design', 'recordTypes', '/modeller/data-objects'),
          e('design', 'screens', '/modeller/pages'),
          // U12a: the route admits operators too (App.tsx), but Design is the designers' group (and the platform administrator's link).
          e('design', 'testRun', '/design/test', { access: { roles: [...MODEL, ...OPS], perms: [] }, only: [...MODEL, ...PLATFORM] }),
        ],
      },
      {
        label: sec('design', 'more'),
        items: [e('design', 'assess', '/modeller/assess'), e('design', 'compatibility', '/modeller/compatibility'), e('design', 'tools', '/modeller/tools')],
      },
    ],
  },
  {
    id: 'monitor',
    label: 'menu.monitor.title',
    roles: OPS,
    sections: [
      { label: sec('monitor', 'glance'), items: [e('monitor', 'health', '/ops')] },
      {
        label: sec('monitor', 'requests'),
        items: [
          e('monitor', 'stuck', '/ops/incidents'),
          e('monitor', 'running', '/ops/processes'),
          e('monitor', 'cases', '/ops/cases'),
          e('monitor', 'decisions', '/ops/decisions'),
          e('monitor', 'batches', '/ops/batches'),
          e('monitor', 'migration', '/ops/migration'),
        ],
      },
      {
        label: sec('monitor', 'behind'),
        items: [
          e('monitor', 'jobs', '/ops/jobs'),
          e('monitor', 'outbox', '/ops/outbox'),
          e('monitor', 'workers', '/ops/workers'),
          e('monitor', 'notifications', '/ops/notifications'),
          // U12h: the page loads with event.admin, which an operator does not hold; shown within the group's roles only.
          e('monitor', 'events', '/ops/events', { access: EVENT_ADMIN, only: OPS }),
          e('monitor', 'money', '/ops/reconciliation'),
        ],
      },
      {
        label: sec('monitor', 'maintenance'),
        items: [
          // U12c: the route admits designers too (deployment.read); they see this one entry of the group.
          e('monitor', 'deployments', '/ops/deployments', { access: { roles: [...OPS, ...MODEL], perms: [] } }),
          e('monitor', 'cleanup', '/ops/cleanup'),
          e('monitor', 'tools', '/ops/tools'),
          e('monitor', 'engine', '/ops/engine', { only: ['PLATFORM_OPERATOR'] }),
          // The engine's own saved filters (no organisation of their own): platform identities only, as before.
          e('monitor', 'engineFilters', '/ops/engine/filters', { access: { roles: ['PLATFORM_OPERATOR', 'PLATFORM_ADMIN'], perms: [] } }),
          // U12d: the route admits an auditor too (history.read); he sees this one entry of the group.
          e('monitor', 'operatorLog', '/ops/audit-log', { access: { roles: [...OPS, 'AUDITOR'], perms: [] } }),
        ],
      },
    ],
  },
  {
    id: 'setup',
    label: 'menu.setup.title',
    roles: ['ADMIN', 'PLATFORM_ADMIN'],
    sections: [
      { label: sec('setup', 'start'), items: [mod('setup', 'checklist', '/admin/onboarding'), mod('setup', 'packs', '/admin/packs')] },
      {
        label: sec('setup', 'org'),
        items: [
          e('setup', 'organisations', '/admin/tenants', { only: ['PLATFORM_ADMIN'] }),
          e('setup', 'offices', '/admin/org'),
          e('setup', 'posts', '/admin/seats'),
          e('setup', 'postings', '/admin/assignments'),
          e('setup', 'people', '/admin/users'),
          e('setup', 'groups', '/admin/groups'),
          e('setup', 'powers', '/admin/delegations'),
        ],
      },
      {
        label: sec('setup', 'access'),
        items: [
          e('setup', 'policies', '/admin/policies'),
          e('setup', 'sod', '/admin/sod'),
          e('setup', 'idp', '/admin/idp'),
          e('setup', 'principals', '/admin/principals'),
          mod('setup', 'securityDefaults', '/admin/security-defaults'),
          mod('setup', 'signatures', '/admin/dsc-certificates'),
        ],
      },
      {
        label: sec('setup', 'work'),
        items: [
          e('setup', 'sla', '/admin/sla'),
          e('setup', 'routing', '/admin/routing'),
          e('setup', 'services', '/admin/services'),
          e('setup', 'templates', '/admin/templates'),
          e('setup', 'channels', '/admin/channels'),
          // channels.read is a module read held by every staff role; the status page is an administrator's job.
          e('setup', 'channelStatus', '/m/channels', { access: ROUTE_ACCESS['/m/channels'], module: MODULE_OF['/m/channels'], only: ['ADMIN', 'PLATFORM_ADMIN'] }),
          e('setup', 'agents', '/admin/agents'),
          // U12g: form.read (the list call) is held by every task user, operator and designer; the page is an administrator's job.
          mod('setup', 'customFields', '/admin/custom-fields', { only: ['ADMIN', 'PLATFORM_ADMIN'] }),
        ],
      },
      {
        label: sec('setup', 'data'),
        items: [
          mod('setup', 'bulk', '/admin/bulk'),
          e('setup', 'retention', '/admin/retention'),
          e('setup', 'config', '/admin/config'),
          e('setup', 'plugins', '/admin/plugins', { only: ['PLATFORM_ADMIN'] }),
          e('setup', 'engine', '/admin/engine', { only: ['PLATFORM_ADMIN'] }),
        ],
      },
    ],
  },
  {
    id: 'reports',
    label: 'menu.reports.title',
    roles: ['ANALYST', 'OPERATOR', 'AUDITOR'],
    sections: [
      {
        items: [
          e('reports', 'overview', '/analytics', { only: ANALYST }),
          e('reports', 'reports', '/analytics/reports', { only: ANALYST }),
          e('reports', 'alerts', '/analytics/alerts', { only: ANALYST }),
          e('reports', 'mining', '/analytics/mining', { only: ANALYST }),
          e('reports', 'aiUsage', '/analytics/agents', { only: ANALYST }),
          // Decision 397 Q6: task users read a request's history inside the request; the route guard is unchanged.
          e('reports', 'audit', '/audit', { only: ['AUDITOR', 'OPERATOR'] }),
          e('reports', 'exports', '/audit/exports', { only: ['AUDITOR', 'OPERATOR'] }),
          mod('reports', 'dataProtection', '/m/compliance'),
        ],
      },
    ],
  },
  {
    id: 'help',
    label: 'menu.help.title',
    roles: [],
    sections: [
      {
        items: [
          e('help', 'glossary', '/help/glossary'),
          e('help', 'shortcuts', '/help/shortcuts'),
          e('help', 'verify', '/verify'),
          e('help', 'accessibility', '/help/accessibility'),
          e('help', 'about', '/help/about'),
        ],
      },
    ],
  },
];

/** Every entry of a group, in menu order. */
export const areaItems = (a: NavArea): NavItem[] => a.sections.flatMap((s) => s.items);
/** Every entry of the menu. */
export const NAV_ITEMS: NavItem[] = NAV.flatMap(areaItems);

const hasAny = (me: Pick<Me, 'roles'>, roles: Role[]) => roles.length === 0 || roles.some((r) => me.roles.includes(r));

/**
 * M-028: an entry with its own access (role or endpoint permission) is shown on that alone; otherwise on the group's
 * roles and its `only`. A group is shown when one of its entries is.
 */
export function navVisible(me: Pick<Me, 'roles' | 'permissions'> | null | undefined, a: NavArea, i: NavItem, installed?: ReadonlySet<string> | null): boolean {
  if (!me) return false;
  // Q3: unknown (null/undefined: the read failed or has not answered) shows the entry; the server stays the judge.
  if (i.module && installed && !installed.has(i.module)) return false;
  // `only` with `access`: the entry belongs to the group's job, though the screen itself opens to more callers.
  if (i.access) return canAccess(me, i.access) && (!i.only || hasAny(me, i.only));
  return hasAny(me, a.roles) && (!i.only || hasAny(me, i.only));
}

/** The menu as one caller sees it: groups and sections with no visible entry are left out. */
export function visibleNav(me: Pick<Me, 'roles' | 'permissions'> | null | undefined, installed?: ReadonlySet<string> | null): NavArea[] {
  return NAV.map((a) => ({ ...a, sections: a.sections.map((s) => ({ ...s, items: s.items.filter((i) => navVisible(me, a, i, installed)) })).filter((s) => s.items.length > 0) })).filter(
    (a) => a.sections.length > 0,
  );
}

/** A link must match exactly when another entry lies below it (/tasks vs /tasks/filters, /ops vs /ops/jobs). */
export const navEnd = (to: string): boolean => NAV_ITEMS.some((i) => i.to !== to && i.to.startsWith(to + '/'));

/** The group and entry a path belongs to (longest matching entry), for the breadcrumb and page description. */
export function navLocate(pathname: string): { area: NavArea; item: NavItem } | null {
  let best: { area: NavArea; item: NavItem } | null = null;
  for (const area of NAV)
    for (const item of areaItems(area))
      if ((pathname === item.to || pathname.startsWith(item.to + '/')) && (!best || item.to.length > best.item.to.length)) best = { area, item };
  return best;
}
