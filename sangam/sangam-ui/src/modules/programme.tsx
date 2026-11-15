// SPDX-License-Identifier: Apache-2.0
/**
 * sangam-programme screens (contract /programmes, /programme-*): programmes and components, targets (set → propose →
 * approve / reject → revise → allocate to child offices), milestones, achievements and roll-up, physical and financial
 * progress (ledger import), variance alerts, dashboards; reporting periods; returns (campaigns, values, validation
 * findings, submit / accept / send back / freeze / reopen, defaulters).
 */
import { ModulePage, c, f, type ScreenDef } from './engine';
import { programmeConfigFields } from './programmeConfig';

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
  // M-051: ProgrammeInput(parentId, kind, code, name, ownerOrg, startsOn, endsOn, budget, currency, indicators, attributes)
  // (ProgrammeService.java:42-44)
  create: { id: 'create', label: 'act.create', path: '/programmes', fields: [f.text('code', undefined, true), f.text('name', undefined, true), f.text('kind', undefined, true), f.text('parentId'), f.text('ownerOrg', 'fld.ownerOrgUnit'), f.date('startsOn', 'fld.startsOn'), f.date('endsOn', 'fld.endsOn'), f.money('budget', 'fld.budget'), f.text('currency'), f.json('indicators')] },
  actions: [
    { id: 'status', label: 'act.setStatus', path: '/programmes/{programmeId}/status', fields: [f.select('status', ['PLANNED', 'ACTIVE', 'SUSPENDED', 'CLOSED'], undefined, true)], reason: 'body' },
    { id: 'target', label: 'act.setTarget', method: 'PUT', path: '/programmes/{programmeId}/targets', fields: [f.text('indicatorKey', undefined, true), f.text('periodId', undefined, true), f.text('orgUnit', undefined, true), f.money('value', 'fld.targetValue', true)] },
    // M-039/M-051: MilestoneRequest(orgUnit, name, seq, plannedStart, plannedEnd, weight, dependsOn) (ProgrammeController.java:91-92,
    // :254-259); the earlier plannedDate field is not read by the server
    { id: 'milestone', label: 'act.planMilestone', path: '/programmes/{programmeId}/milestones', noJson: true, fields: [f.text('name', undefined, true), f.text('orgUnit'), f.num('seq', undefined, true), f.date('plannedStart', 'fld.plannedStart', true), f.date('plannedEnd', 'fld.plannedEnd', true), f.money('weight'), f.json('dependsOn', 'fld.dependsOn')] },
    // M-051: AchievementRequest(indicatorKey, periodId, orgUnit, value) (ProgrammeController.java:104)
    { id: 'achievement', label: 'act.recordAchievement', path: '/programmes/{programmeId}/achievements', fields: [f.text('indicatorKey', undefined, true), f.text('periodId', undefined, true), f.text('orgUnit', undefined, true), f.money('value', 'fld.achievedValue', true)] },
    // M-051: FinancialRequest(programmeId, periodId, orgUnit, head, allocated, released, committed, expended, currency) (ProgrammeRestPort.java:51)
    { id: 'financial', label: 'act.recordFinancial', method: 'PUT', path: '/programmes/{programmeId}/financial', fields: [f.text('head', undefined, true), f.text('periodId', undefined, true), f.text('orgUnit'), f.money('allocated', 'fld.allocated'), f.money('released'), f.money('committed'), f.money('expended', 'fld.spent'), f.text('currency')] },
    // M-051: ImportRequest(periodId, orgUnit) (ProgrammeController.java:110); the ledger is not chosen by the caller
    { id: 'import', label: 'act.importFinancial', path: '/programmes/{programmeId}/financial/import', fields: [f.text('periodId', undefined, true), f.text('orgUnit')], showResult: true },
    { id: 'rollup', label: 'act.rollup', path: '/programmes/{programmeId}/rollup', fields: [f.text('periodId', undefined, true)], showResult: true },
    // M-051: VarianceRequest(periodId, asOf) (ProgrammeController.java:116); thresholds come from the programme config (variance)
    { id: 'variance', label: 'act.varianceCheck', path: '/programmes/{programmeId}/variance-check', fields: [f.text('periodId', undefined, true), f.date('asOf')], showResult: true },
    { id: 'refresh', label: 'act.refreshDashboard', path: '/programmes/{programmeId}/dashboard/refresh', fields: [f.text('periodId', undefined, true)], showResult: true },
  ],
};

// M-039: milestone progress (PATCH /programme-milestones/{id}, programme.write, ProgrammeController.java:262-269). Body
// MilestoneService.Progress(milestoneId, expectedVersion, percentComplete, actualStart, actualEnd, addEvidence, drop, reason)
// (MilestoneService.java:38-39); milestoneId must equal the path id, so it is sent from the row.
const milestones: ScreenDef = {
  id: 'milestones',
  title: 'mod.programmes.milestoneProgress',
  list: '/programmes/{programmeId}/milestones',
  unpaged: true,
  filters: [f.text('programmeId', undefined, true), f.text('orgUnit')],
  requireFilters: ['programmeId'],
  columns: [c('name'), c('orgUnit'), c('seq'), c('plannedStart', 'fld.plannedStart'), c('plannedEnd', 'fld.plannedEnd'), c('percentComplete', 'fld.percentComplete'), c('status'), c('delayDays', 'fld.delayDays'), c('rowVersion', 'fld.rowVersion')],
  rowKey: ['id'],
  actions: [
    {
      id: 'progress',
      label: 'act.recordProgress',
      method: 'PATCH',
      path: '/programme-milestones/{id}',
      noJson: true,
      bodyFromRow: (r) => ({ milestoneId: r.id }),
      defaults: (r) => ({ expectedVersion: r?.rowVersion as number }),
      fields: [f.num('expectedVersion', 'fld.expectedVersion', true), f.money('percentComplete', 'fld.percentComplete'), f.date('actualStart', 'fld.actualStart'), f.date('actualEnd', 'fld.actualEnd'), f.json('addEvidence', 'fld.addEvidence'), f.bool('drop', 'fld.dropMilestone'), f.text('reason')],
    },
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
    // M-051: AllocationRequest(explicit {orgUnit: value}, weights {orgUnit: weight}, allowOverAllocation) (ProgrammeRestPort.java:48)
    { id: 'allocate', label: 'act.allocate', path: '/programme-targets/{targetId}/allocate', fields: [f.json('explicit', 'fld.allocations'), f.json('weights', 'fld.weights'), f.bool('allowOverAllocation', 'fld.allowOverAllocation')], hint: 'mod.programmes.allocateHint' },
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
  // M-051: GeneratePeriodsRequest(frequency, fiscalStartYear, dueOffsetDays) (ProgrammeController.java:70); frequency is
  // IndicatorDefinition.Frequency (IndicatorDefinition.java:33)
  toolbar: [{ id: 'generate', label: 'act.generatePeriods', path: '/programme-periods/generate', fields: [f.num('fiscalStartYear', 'fld.fiscalYear', true), f.select('frequency', ['WEEKLY', 'FORTNIGHTLY', 'MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'ANNUAL'], undefined, true), f.num('dueOffsetDays', 'fld.dueOffsetDays', true)], showResult: true }],
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
  // M-051: CampaignRequest(returnKey, periodId, rootNode, programmeId) (ProgrammeController.java:95)
  toolbar: [{ id: 'campaign', label: 'act.startCampaign', path: '/programme-returns/campaigns', fields: [f.text('returnKey', undefined, true), f.text('periodId', undefined, true), f.text('rootNode', 'fld.rootOrgUnit', true), f.text('programmeId')], showResult: true, hint: 'mod.programmes.campaignHint' }],
  actions: [
    // M-051: ReturnValuesRequest(expectedVersion, values, remarks) and VersionBody(expectedVersion) (ProgrammeController.java:98, :101); the
    // expected version is the return's rowVersion (ReturnService.java:54-58, :190-191, :216-218), prefilled from the selected row
    { id: 'values', label: 'act.saveValues', method: 'PUT', path: '/programme-returns/{returnId}/values', fields: [f.num('expectedVersion', 'fld.version', true), f.json('values', undefined, true), f.json('remarks', 'fld.remarks')], defaults: (r) => ({ expectedVersion: r?.rowVersion as number }) },
    { id: 'validate', label: 'act.validate', path: '/programme-returns/{returnId}/validate', showResult: true, noJson: true },
    { id: 'submit', label: 'act.submit', path: '/programme-returns/{returnId}/submit', fields: [f.num('expectedVersion', 'fld.version', true)], defaults: (r) => ({ expectedVersion: r?.rowVersion as number }), showResult: true },
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
  // history rows: version, contentHash, current, definedBy, definedAt (IndicatorRegistry.java:167-171)
  columns: [c('version'), c('current'), c('definedAt', 'fld.definedAt'), c('definedBy', 'fld.definedBy'), c('contentHash', 'fld.contentHash')],
  rowKey: ['version'],
  // X1-01 / M-051: fiscalYearStartMonth is required (ProgrammeConfig.java:221-235); programmeKinds (:58); see programmeConfig.ts
  toolbar: [{ id: 'publish', label: 'act.publish', method: 'PUT', path: '/programme-config', fields: programmeConfigFields(), hint: 'mod.programmes.configHint' }],
};

export function ProgrammesPage() {
  return <ModulePage title="mod.programmes.title" description="mod.programmes.desc" screens={[programmes, targets, milestones, periods, returns, defaulters, alerts, config]} />;
}

/** The screen definitions, for the tests of the request bodies their actions send. */
export const programmeScreens = { programmes, milestones, targets, periods, returns };
