import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from ed import sub
os.chdir('D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui/src/modules')
p = 'programme.tsx'
R = [
("""  create: { id: 'create', label: 'act.create', path: '/programmes', fields: [f.text('code', undefined, true), f.text('name', undefined, true), f.text('kind', undefined, true), f.text('parentId'), f.text('ownerOrgUnit'), f.text('fiscalYear'), f.json('indicators')] },""",
"""  // M-051: ProgrammeInput(parentId, kind, code, name, ownerOrg, startsOn, endsOn, budget, currency, indicators, attributes)
  // (ProgrammeService.java:42-44)
  create: { id: 'create', label: 'act.create', path: '/programmes', fields: [f.text('code', undefined, true), f.text('name', undefined, true), f.text('kind', undefined, true), f.text('parentId'), f.text('ownerOrg', 'fld.ownerOrgUnit'), f.date('startsOn', 'fld.startsOn'), f.date('endsOn', 'fld.endsOn'), f.money('budget', 'fld.budget'), f.text('currency'), f.json('indicators')] },"""),
("""    { id: 'achievement', label: 'act.recordAchievement', path: '/programmes/{programmeId}/achievements', fields: [f.text('indicatorKey', undefined, true), f.text('periodId', undefined, true), f.text('orgUnit', undefined, true), f.money('value', 'fld.achievedValue', true), f.text('evidenceRef')] },
    { id: 'financial', label: 'act.recordFinancial', method: 'PUT', path: '/programmes/{programmeId}/financial', fields: [f.text('head', undefined, true), f.text('periodId', undefined, true), f.text('orgUnit'), f.money('released'), f.money('spent')] },
    { id: 'import', label: 'act.importFinancial', path: '/programmes/{programmeId}/financial/import', fields: [f.text('ledger', 'fld.ledgerId', true), f.text('periodId', undefined, true)], showResult: true },""",
"""    // M-051: AchievementRequest(indicatorKey, periodId, orgUnit, value) (ProgrammeController.java:104)
    { id: 'achievement', label: 'act.recordAchievement', path: '/programmes/{programmeId}/achievements', fields: [f.text('indicatorKey', undefined, true), f.text('periodId', undefined, true), f.text('orgUnit', undefined, true), f.money('value', 'fld.achievedValue', true)] },
    // M-051: FinancialRequest(programmeId, periodId, orgUnit, head, allocated, released, committed, expended, currency) (ProgrammeRestPort.java:51)
    { id: 'financial', label: 'act.recordFinancial', method: 'PUT', path: '/programmes/{programmeId}/financial', fields: [f.text('head', undefined, true), f.text('periodId', undefined, true), f.text('orgUnit'), f.money('allocated', 'fld.allocated'), f.money('released'), f.money('committed'), f.money('expended', 'fld.spent'), f.text('currency')] },
    // M-051: ImportRequest(periodId, orgUnit) (ProgrammeController.java:110); the ledger is not chosen by the caller
    { id: 'import', label: 'act.importFinancial', path: '/programmes/{programmeId}/financial/import', fields: [f.text('periodId', undefined, true), f.text('orgUnit')], showResult: true },"""),
("""    { id: 'variance', label: 'act.varianceCheck', path: '/programmes/{programmeId}/variance-check', fields: [f.text('periodId', undefined, true), f.num('thresholdPct')], showResult: true },""",
"""    // M-051: VarianceRequest(periodId, asOf) (ProgrammeController.java:116); thresholds come from the programme config (variance)
    { id: 'variance', label: 'act.varianceCheck', path: '/programmes/{programmeId}/variance-check', fields: [f.text('periodId', undefined, true), f.date('asOf')], showResult: true },"""),
("""    { id: 'allocate', label: 'act.allocate', path: '/programme-targets/{targetId}/allocate', fields: [f.json('allocations', 'fld.allocations', true)], hint: 'mod.programmes.allocateHint' },""",
"""    // M-051: AllocationRequest(explicit {orgUnit: value}, weights {orgUnit: weight}, allowOverAllocation) (ProgrammeRestPort.java:48)
    { id: 'allocate', label: 'act.allocate', path: '/programme-targets/{targetId}/allocate', fields: [f.json('explicit', 'fld.allocations'), f.json('weights', 'fld.weights'), f.bool('allowOverAllocation', 'fld.allowOverAllocation')], hint: 'mod.programmes.allocateHint' },"""),
("""  toolbar: [{ id: 'generate', label: 'act.generatePeriods', path: '/programme-periods/generate', fields: [f.text('fiscalYear', undefined, true), f.select('frequency', ['MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'ANNUAL'], undefined, true)], showResult: true }],""",
"""  // M-051: GeneratePeriodsRequest(frequency, fiscalStartYear, dueOffsetDays) (ProgrammeController.java:70); frequency is
  // IndicatorDefinition.Frequency (IndicatorDefinition.java:33)
  toolbar: [{ id: 'generate', label: 'act.generatePeriods', path: '/programme-periods/generate', fields: [f.num('fiscalStartYear', 'fld.fiscalYear', true), f.select('frequency', ['WEEKLY', 'FORTNIGHTLY', 'MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'ANNUAL'], undefined, true), f.num('dueOffsetDays', 'fld.dueOffsetDays')], showResult: true }],"""),
("""  toolbar: [{ id: 'campaign', label: 'act.startCampaign', path: '/programme-returns/campaigns', fields: [f.text('returnKey', undefined, true), f.text('periodId', undefined, true), f.text('rootOrgUnit', undefined, true), f.dt('dueAt')], showResult: true, hint: 'mod.programmes.campaignHint' }],""",
"""  // M-051: CampaignRequest(returnKey, periodId, rootNode, programmeId) (ProgrammeController.java:95)
  toolbar: [{ id: 'campaign', label: 'act.startCampaign', path: '/programme-returns/campaigns', fields: [f.text('returnKey', undefined, true), f.text('periodId', undefined, true), f.text('rootNode', 'fld.rootOrgUnit', true), f.text('programmeId')], showResult: true, hint: 'mod.programmes.campaignHint' }],"""),
("""    { id: 'values', label: 'act.saveValues', method: 'PUT', path: '/programme-returns/{returnId}/values', fields: [f.num('version', undefined, true), f.json('values', undefined, true)] },""",
"""    // M-051: ReturnValuesRequest(expectedVersion, values, remarks) and VersionBody(expectedVersion) (ProgrammeController.java:98, :101)
    { id: 'values', label: 'act.saveValues', method: 'PUT', path: '/programme-returns/{returnId}/values', fields: [f.num('expectedVersion', 'fld.version', true), f.json('values', undefined, true), f.json('remarks', 'fld.remarks')] },"""),
("""    { id: 'submit', label: 'act.submit', path: '/programme-returns/{returnId}/submit', fields: [f.num('version', undefined, true)], showResult: true },""",
"""    { id: 'submit', label: 'act.submit', path: '/programme-returns/{returnId}/submit', fields: [f.num('expectedVersion', 'fld.version', true)], showResult: true },"""),
("""  toolbar: [{ id: 'publish', label: 'act.publish', method: 'PUT', path: '/programme-config', fields: [f.json('indicators', undefined, true), f.json('returns'), f.json('kinds')], hint: 'mod.programmes.configHint' }],""",
"""  // M-051: the config keys the server reads include programmeKinds (ProgrammeConfig.java:25, :58)
  toolbar: [{ id: 'publish', label: 'act.publish', method: 'PUT', path: '/programme-config', fields: [f.json('indicators', undefined, true), f.json('returns'), f.json('programmeKinds', 'fld.kinds')], hint: 'mod.programmes.configHint' }],"""),
]
for old, new in R:
    sub(p, old, new)
