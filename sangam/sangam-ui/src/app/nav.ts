// SPDX-License-Identifier: Apache-2.0
import type { Role } from '../api/types';

export interface NavItem {
  to: string;
  label: string; // i18n key
  /** Further restriction within the area. */
  only?: Role[];
}
export interface NavArea {
  id: string;
  label: string; // i18n key
  roles: Role[];
  items: NavItem[];
}

/** Role-aware menu. A convenience only: every screen's data is authorised by the server (X.4.7). */
export const NAV: NavArea[] = [
  {
    id: 'work',
    label: 'nav.work',
    roles: ['TASK_USER'],
    items: [
      { to: '/tasks', label: 'nav.inbox' },
      { to: '/tasks/filters', label: 'nav.taskFilters', only: ['PLATFORM_OPERATOR', 'PLATFORM_ADMIN'] },
      { to: '/start', label: 'nav.start' },
      { to: '/cases', label: 'nav.caseWorkspace' },
      { to: '/my/delegations', label: 'nav.myDelegations' },
      { to: '/field', label: 'nav.field' },
      { to: '/notifications', label: 'nav.notifications' },
    ],
  },
  {
    id: 'ops',
    label: 'nav.ops',
    roles: ['OPERATOR', 'PLATFORM_OPERATOR'],
    items: [
      { to: '/ops', label: 'nav.opsDashboard' },
      { to: '/ops/processes', label: 'nav.processes' },
      { to: '/ops/cases', label: 'nav.cases' },
      { to: '/ops/decisions', label: 'nav.decisions' },
      { to: '/ops/incidents', label: 'nav.incidents' },
      { to: '/ops/reconciliation', label: 'nav.reconciliation' },
      { to: '/ops/batches', label: 'nav.batches' },
      { to: '/ops/migration', label: 'nav.migration' },
      { to: '/ops/jobs', label: 'nav.jobs' },
      { to: '/ops/outbox', label: 'nav.outbox' },
      { to: '/ops/workers', label: 'nav.workers' },
      { to: '/ops/events', label: 'nav.events' },
      { to: '/ops/notifications', label: 'nav.notificationFailures' },
      { to: '/ops/deployments', label: 'nav.deployments' },
      { to: '/ops/cleanup', label: 'nav.cleanup' },
      { to: '/ops/debugger', label: 'nav.debugger' },
      { to: '/ops/audit-log', label: 'nav.operatorAudit' },
      { to: '/ops/tools', label: 'mod.ops2.title' },
      { to: '/ops/engine', label: 'nav.engineViews', only: ['PLATFORM_OPERATOR'] },
      { to: '/ops/task-dashboard', label: 'nav.taskDashboard', only: ['PLATFORM_OPERATOR'] },
    ],
  },
  {
    id: 'admin',
    label: 'nav.admin',
    roles: ['ADMIN', 'PLATFORM_ADMIN'],
    items: [
      { to: '/admin/onboarding', label: 'nav.onboarding' },
      { to: '/admin/tenants', label: 'nav.tenants' },
      { to: '/admin/org', label: 'nav.org' },
      { to: '/admin/seats', label: 'nav.seats' },
      { to: '/admin/assignments', label: 'nav.assignments' },
      { to: '/admin/delegations', label: 'nav.delegationTables' },
      { to: '/admin/policies', label: 'nav.policies' },
      { to: '/admin/sod', label: 'nav.sod' },
      { to: '/admin/users', label: 'nav.users' },
      { to: '/admin/groups', label: 'nav.groups' },
      { to: '/admin/idp', label: 'nav.idp' },
      { to: '/admin/principals', label: 'nav.principals' },
      { to: '/admin/services', label: 'nav.serviceRegistry' },
      { to: '/admin/sla', label: 'nav.sla' },
      { to: '/admin/routing', label: 'nav.routing' },
      { to: '/admin/templates', label: 'nav.templates' },
      { to: '/admin/channels', label: 'nav.channels' },
      { to: '/admin/agents', label: 'nav.agents' },
      { to: '/admin/retention', label: 'nav.retention' },
      { to: '/admin/config', label: 'nav.config' },
      { to: '/admin/engine', label: 'nav.engineAdmin', only: ['PLATFORM_ADMIN'] },
      { to: '/admin/plugins', label: 'mod.plugins.title', only: ['PLATFORM_ADMIN'] },
    ],
  },
  {
    id: 'model',
    label: 'nav.model',
    roles: ['MODELLER', 'REVIEWER'],
    items: [
      { to: '/modeller', label: 'nav.packages' },
      { to: '/modeller/review', label: 'nav.review' },
      { to: '/modeller/data-objects', label: 'nav.dataObjects' },
      { to: '/modeller/pages', label: 'nav.pageBuilder' },
      { to: '/modeller/assess', label: 'nav.assessment' },
      { to: '/modeller/compatibility', label: 'nav.compatibility' },
      { to: '/modeller/tools', label: 'mod.modeller2.title' },
    ],
  },
  {
    id: 'analytics',
    label: 'nav.analytics',
    roles: ['ANALYST', 'OPERATOR'],
    items: [
      { to: '/analytics', label: 'nav.analyticsDashboard' },
      { to: '/analytics/mining', label: 'nav.mining' },
      { to: '/analytics/reports', label: 'nav.reports' },
      { to: '/analytics/alerts', label: 'nav.alerts' },
      { to: '/analytics/agents', label: 'nav.agentUsage' },
    ],
  },
  {
    id: 'audit',
    label: 'nav.audit',
    roles: ['AUDITOR', 'OPERATOR', 'TASK_USER'],
    items: [
      { to: '/audit', label: 'nav.auditSearch' },
      { to: '/audit/exports', label: 'nav.auditExports' },
      { to: '/documents', label: 'nav.documents' },
    ],
  },
  {
    id: 'modules',
    label: 'nav.modules',
    roles: ['TASK_USER', 'OPERATOR', 'ADMIN', 'PLATFORM_ADMIN', 'AUDITOR', 'ANALYST'],
    items: [
      { to: '/m/ai', label: 'mod.ai.title' },
      { to: '/m/channels', label: 'mod.channels.title' },
      { to: '/m/case-files', label: 'mod.cases.title' },
      { to: '/m/committees', label: 'mod.committees.title' },
      { to: '/m/references', label: 'mod.references.title' },
      { to: '/m/registry', label: 'mod.registry.title' },
      { to: '/m/ledger', label: 'mod.ledger.title' },
      { to: '/m/billing', label: 'mod.billing.title' },
      { to: '/m/resources', label: 'mod.resources.title' },
      { to: '/m/queues', label: 'mod.queues.title' },
      { to: '/m/rosters', label: 'mod.rosters.title' },
      { to: '/m/programmes', label: 'mod.programmes.title' },
      { to: '/m/field', label: 'mod.field.title' },
      { to: '/m/geo', label: 'mod.geo.title' },
      { to: '/m/compliance', label: 'mod.compliance.title' },
    ],
  },
  {
    id: 'portal',
    label: 'nav.portal',
    roles: ['CITIZEN'],
    items: [
      { to: '/portal', label: 'nav.services' },
      { to: '/portal/applications', label: 'nav.myApplications' },
      { to: '/portal/grievances', label: 'nav.grievances' },
    ],
  },
];
