// SPDX-License-Identifier: Apache-2.0
/**
 * sangam-programme screens (contract /programmes, /programme-*): programmes and components, targets (set → propose →
 * approve / reject → revise → allocate to child offices), milestones, achievements and roll-up, physical and financial
 * progress (ledger import), variance alerts, dashboards; reporting periods; returns (campaigns, values, validation
 * findings, submit / accept / send back / freeze / reopen, defaulters).
 */
import { ModulePage, c, f, type ScreenDef } from './engine';

const programmes: ScreenDef = {
  id: 'programmes',
  title: 'mod.programmes.list',
  list: '/programmes',
  unpaged: true,
  filters: [f.text('kind'), f.text('parentId'), f.text('status')],
  columns: [c('name'), c('code'), c('kind'), c('status'), c('ownerOrgUnit'), c('fiscalYear')],
  rowKey: ['programmeId', 'id'],
  detail: '/programmes/{programmeId}',
  sections: [
    { id: 'tree', label: 'mod.programmes.tree', path: '/programmes/{programmeId}/tree' },
    { id: 'targets', label: 'mod.programmes.targets', path: '/programmes/{programmeId}/targets' },
    { id: 'milestones', label: 'mod.programmes.milestones', path: '/programmes/{programmeId}/milestones' },
    { id: 'achievements', label: 'mod.programmes.achievements', path: '/programmes/{programmeId}/achievements' },
    { id: 'progress', label: 'mod.programmes.progress', path: '/programmes/{programmeId}/progress' },
    { id: 'progressChildren', label: 'mod.programmes.progressChildren', path: '/programmes/{programmeId}/progress/children' },
    { id: 'dashboard', label: 'mod.programmes.dashboard', path: '/programmes/{programmeId}/dashboard' },
  ],
  create: { id: 'create', label: 'act.create', path: '/programmes', fields: [f.text('code', undefined, true), f.text('name', undefined, true), f.text('kind', undefined, true), f.text('parentId'), f.text('ownerOrgUnit'), f.text('fiscalYear'), f.json('indicators')] },
  actions: [
    { id: 'status', label: 'act.setStatus', path: '/programmes/{programmeId}/status', fields: [f.select('status', ['PLANNED', 'ACTIVE', 'SUSPENDED', 'CLOSED'], undefined, true)], reason: 'body' },
    { id: 'target', label: 'act.setTarget', method: 'PUT', path: '/programmes/{programmeId}/targets', fields: [f.text('indicatorKey', undefined, true), f.text('periodId', undefined, true), f.text('orgUnit', undefined, true), f.money('value', 'fld.targetValue', true)] },
    { id: 'milestone', label: 'act.planMilestone', path: '/programmes/{programmeId}/milestones', fields: [f.text('name', undefined, true), f.text('orgUnit'), f.date('plannedDate', undefined, true), f.num('weight')] },
    { id: 'achievement', label: 'act.recordAchievement', path: '/programmes/{programmeId}/achievements', fields: [f.text('indicatorKey', undefined, true), f.text('periodId', undefined, true), f.text('orgUnit', undefined, true), f.money('value', 'fld.achievedValue', true), f.text('evidenceRef')] },
    { id: 'financial', label: 'act.recordFinancial', method: 'PUT', path: '/programmes/{programmeId}/financial', fields: [f.text('head', undefined, true), f.text('periodId', undefined, true), f.text('orgUnit'), f.money('released'), f.money('spent')] },
    { id: 'import', label: 'act.importFinancial', path: '/programmes/{programmeId}/financial/import', fields: [f.text('ledger', 'fld.ledgerId', true), f.text('periodId', undefined, true)], showResult: true },
    { id: 'rollup', label: 'act.rollup', path: '/programmes/{programmeId}/rollup', fields: [f.text('periodId', undefined, true)], showResult: true },
    { id: 'variance', label: 'act.varianceCheck', path: '/programmes/{programmeId}/variance-check', fields: [f.text('periodId', undefined, true), f.num('thresholdPct')], showResult: true },
    { id: 'refresh', label: 'act.refreshDashboard', path: '/programmes/{programmeId}/dashboard/refresh', fields: [f.text('periodId', undefined, true)], showResult: true },
  ],
};

const targets: ScreenDef = {
  id: 'targets',
  title: 'mod.programmes.targetApproval',
  description: 'mod.programmes.targetApprovalDesc',
  list: '/programmes/{programmeId}/targets',
  unpaged: true,
  filters: [f.text('programmeId', undefined, true), f.text('periodId'), f.text('orgUnit'), f.bool('approvedOnly')],
  requireFilters: ['programmeId'],
  columns: [c('indicatorKey'), c('periodId'), c('orgUnit'), c('value', 'fld.targetValue'), c('status'), c('targetId')],
  rowKey: ['targetId', 'id'],
  actions: [
    { id: 'propose', label: 'act.propose', path: '/programme-targets/{targetId}/propose', noJson: true },
    { id: 'approve', label: 'act.approve', path: '/programme-targets/{targetId}/approve', noJson: true },
    { id: 'reject', label: 'act.reject', path: '/programme-targets/{targetId}/reject', reason: 'body', danger: true },
    { id: 'revise', label: 'act.revise', path: '/programme-targets/{targetId}/revise', fields: [f.money('value', 'fld.targetValue', true)], reason: 'body' },
    { id: 'allocate', label: 'act.allocate', path: '/programme-targets/{targetId}/allocate', fields: [f.json('allocations', 'fld.allocations', true)], hint: 'mod.programmes.allocateHint' },
  ],
};

const periods: ScreenDef = {
  id: 'periods',
  title: 'mod.programmes.periods',
  list: '/programme-periods',
  unpaged: true,
  filters: [f.select('frequency', ['MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'ANNUAL']), f.text('fiscalYear')],
  columns: [c('periodId'), c('frequency'), c('fiscalYear'), c('from'), c('to'), c('state')],
  rowKey: ['periodId', 'id'],
  toolbar: [{ id: 'generate', label: 'act.generatePeriods', path: '/programme-periods/generate', fields: [f.text('fiscalYear', undefined, true), f.select('frequency', ['MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'ANNUAL'], undefined, true)], showResult: true }],
  actions: [{ id: 'state', label: 'act.setState', path: '/programme-periods/{periodId}/state', fields: [f.select('state', ['OPEN', 'CLOSED', 'LOCKED'], undefined, true)], reason: 'body' }],
};

const alerts: ScreenDef = {
  id: 'alerts',
  title: 'mod.programmes.alerts',
  list: '/programme-alerts',
  unpaged: true,
  filters: [f.text('programmeId'), f.select('kind', ['BEHIND_TARGET', 'DELAY', 'NO_REPORT'])],
  columns: [c('kind'), c('programmeId'), c('orgUnit'), c('message'), c('raisedAt')],
  rowKey: ['alertId', 'id'],
  actions: [{ id: 'ack', label: 'act.acknowledge', path: '/programme-alerts/{alertId}/ack', noJson: true }],
};

const returns: ScreenDef = {
  id: 'returns',
  title: 'mod.programmes.returns',
  description: 'mod.programmes.returnsDesc',
  list: '/programme-returns',
  unpaged: true,
  filters: [f.text('returnKey'), f.text('periodId'), f.text('orgUnit'), f.text('state')],
  columns: [c('returnKey'), c('periodId'), c('orgUnit'), c('state'), c('version'), c('submittedAt')],
  rowKey: ['returnId', 'id'],
  detail: '/programme-returns/{returnId}',
  toolbar: [{ id: 'campaign', label: 'act.startCampaign', path: '/programme-returns/campaigns', fields: [f.text('returnKey', undefined, true), f.text('periodId', undefined, true), f.text('rootOrgUnit', undefined, true), f.dt('dueAt')], showResult: true, hint: 'mod.programmes.campaignHint' }],
  actions: [
    { id: 'values', label: 'act.saveValues', method: 'PUT', path: '/programme-returns/{returnId}/values', fields: [f.num('version', undefined, true), f.json('values', undefined, true)] },
    { id: 'validate', label: 'act.validate', path: '/programme-returns/{returnId}/validate', showResult: true, noJson: true },
    { id: 'submit', label: 'act.submit', path: '/programme-returns/{returnId}/submit', fields: [f.num('version', undefined, true)], showResult: true },
    { id: 'ack', label: 'act.ackFinding', path: '/programme-returns/{returnId}/findings/{seq}/ack', fields: [f.num('seq', undefined, true)], reason: 'body' },
    { id: 'accept', label: 'act.accept', path: '/programme-returns/{returnId}/accept', noJson: true },
    { id: 'sendBack', label: 'act.sendBack', path: '/programme-returns/{returnId}/send-back', reason: 'body' },
    { id: 'freeze', label: 'act.freeze', path: '/programme-returns/{returnId}/freeze', noJson: true },
    { id: 'reopen', label: 'act.reopen', path: '/programme-returns/{returnId}/reopen', reason: 'body' },
  ],
};

const defaulters: ScreenDef = {
  id: 'defaulters',
  title: 'mod.programmes.defaulters',
  list: '/programme-returns/defaulters',
  unpaged: true,
  filters: [f.text('returnKey', undefined, true), f.text('periodId', undefined, true), f.dt('asOf')],
  requireFilters: ['returnKey', 'periodId'],
  columns: [c('orgUnit'), c('state'), c('dueAt'), c('returnId')],
  rowKey: ['returnId', 'orgUnit'],
};

const config: ScreenDef = {
  id: 'config',
  title: 'mod.programmes.config',
  list: '/programme-config/history',
  unpaged: true,
  columns: [c('version'), c('publishedAt'), c('publishedBy'), c('hash')],
  rowKey: ['version'],
  toolbar: [{ id: 'publish', label: 'act.publish', method: 'PUT', path: '/programme-config', fields: [f.json('indicators', undefined, true), f.json('returns'), f.json('kinds')], hint: 'mod.programmes.configHint' }],
};

export function ProgrammesPage() {
  return <ModulePage title="mod.programmes.title" description="mod.programmes.desc" screens={[programmes, targets, periods, returns, defaulters, alerts, config]} />;
}
