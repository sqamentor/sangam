import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from ed import sub
os.chdir('D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui/src/modules')
p = 'compliance.tsx'
R = [
("""    { id: 'define', label: 'act.definePurpose', method: 'PUT', path: '/compliance/purposes/{code}', fields: [f.text('code', undefined, true), f.text('name', undefined, true), f.select('lawfulBasis', ['CONSENT', 'LEGITIMATE_USE', 'LEGAL_OBLIGATION', 'STATE_FUNCTION'], undefined, true), f.text('retention'), f.json('nameI18n', 'fld.hindiLabels')] },""",
"""    // M-051: PurposeDefinition(code, names {lang: text}, descriptions, dataItems, basis, ...) (PurposeDefinition.java:27); basis is a legal
    // basis code of the tenant's jurisdiction pack (PurposeBindingGuard.java:64-65)
    { id: 'define', label: 'act.definePurpose', method: 'PUT', path: '/compliance/purposes/{code}', fields: [f.text('code', undefined, true), f.json('names', 'fld.names', true), f.select('basis', ['CONSENT', 'LEGITIMATE_USE', 'LEGAL_OBLIGATION', 'STATE_FUNCTION'], 'fld.lawfulBasis', true), f.text('retention')] },"""),
("""  actions: [{ id: 'withdraw', label: 'act.withdrawConsent', path: '/compliance/consents/{id}/withdraw', fields: [f.json('purposeCodes')], reason: 'body' }],""",
"""  // M-051: WithdrawConsentRequest(purposes) (ComplianceRequests.java:27)
  actions: [{ id: 'withdraw', label: 'act.withdrawConsent', path: '/compliance/consents/{id}/withdraw', fields: [f.json('purposes', 'fld.purposeCodes')], reason: 'body' }],"""),
("""    { id: 'incident', label: 'act.registerIncident', path: '/compliance/incidents', fields: [f.dt('noticedAt', undefined, true), f.text('type', 'fld.incidentType', true), f.json('affectedSystems'), f.area('summary', undefined, true)], showResult: true, hint: 'mod.compliance.incidentHint' },
    { id: 'submitIncident', label: 'act.submitIncidentReport', path: '/compliance/incidents/{incidentId}/submit', fields: [f.text('incidentId', undefined, true), f.text('submissionRef'), f.dt('submittedAt')], showResult: true },""",
"""    // M-051: IncidentRequest(authority, type, occurredAt, noticedAt, systems, description) (ComplianceRequests.java:34); required
    // authority, type, occurredAt (sangam-api.yaml ComplianceIncidentRequest)
    { id: 'incident', label: 'act.registerIncident', path: '/compliance/incidents', fields: [f.text('authority', undefined, true), f.text('type', 'fld.incidentType', true), f.dt('occurredAt', 'fld.occurredAt', true), f.dt('noticedAt', undefined, true), f.json('systems', 'fld.affectedSystems'), f.area('description', 'fld.summary', true)], showResult: true, hint: 'mod.compliance.incidentHint' },
    // M-051: SubmitIncidentRequest(actionsTaken) (ComplianceRequests.java:38); the incident id is the path only
    { id: 'submitIncident', label: 'act.submitIncidentReport', path: '/compliance/incidents/{incidentId}/submit', fields: [f.path(f.text('incidentId', undefined, true)), f.area('actionsTaken', 'fld.actionsTaken')], showResult: true },"""),
("""    { id: 'receive', label: 'act.receiveLawful', path: '/compliance/lawful-requests', fields: [f.text('authority', undefined, true), f.text('legalProvision', undefined, true), f.text('orderRef', undefined, true), f.json('scope', undefined, true), f.dt('dueAt')], showResult: true },
    { id: 'verify', label: 'act.verifyLawful', path: '/compliance/lawful-requests/{id}/verify', fields: [f.text('verificationRef', undefined, true), f.area('notes')] },""",
"""    // M-051: LawfulRequestBody(authority, officerName, officerDesignation, basis, reference, scope: text, dueAt) (ComplianceRequests.java:41);
    // authority and reference required (LawfulRequestService.java:51-52), basis a code of the jurisdiction pack (:53)
    { id: 'receive', label: 'act.receiveLawful', path: '/compliance/lawful-requests', fields: [f.text('authority', undefined, true), f.text('officerName', 'fld.officerName'), f.text('officerDesignation', 'fld.officerDesignation'), f.text('basis', 'fld.legalProvision', true), f.text('reference', 'fld.orderRef', true), f.area('scope', undefined, true), f.dt('dueAt')], showResult: true },
    // M-051: VerifyLawfulRequest(rankAdequate) (ComplianceRequests.java:45)
    { id: 'verify', label: 'act.verifyLawful', path: '/compliance/lawful-requests/{id}/verify', fields: [f.bool('rankAdequate', 'fld.rankAdequate')] },"""),
("""    { id: 'respond', label: 'act.respond', path: '/compliance/lawful-requests/{id}/respond', fields: [f.json('disclosedRefs', undefined, true), f.text('channel')] },""",
"""    // M-051: RespondLawfulRequest(responseRef, principalRefs, dataCategories) (ComplianceRequests.java:48); responseRef required
    { id: 'respond', label: 'act.respond', path: '/compliance/lawful-requests/{id}/respond', fields: [f.text('responseRef', 'fld.responseRef', true), f.json('principalRefs', 'fld.disclosedRefs'), f.json('dataCategories', 'fld.categories')] },"""),
("""    { id: 'dpia', label: 'act.recordDpia', path: '/compliance/evidence/impact-assessments', fields: [f.text('system', undefined, true), f.text('assessor', undefined, true), f.date('assessedOn', undefined, true), f.json('risks', undefined, true), f.text('documentRef')], showResult: true },
    { id: 'audit', label: 'act.recordAudit', path: '/compliance/evidence/audits', fields: [f.text('auditor', undefined, true), f.date('period', 'fld.auditDate', true), f.text('scope', undefined, true), f.text('reportRef', undefined, true), f.json('findings')], showResult: true },
    { id: 'transfer', label: 'act.evaluateTransfer', path: '/compliance/transfers/evaluate', fields: [f.text('destinationCountry', undefined, true), f.json('categories', undefined, true), f.text('purposeCode', undefined, true)], showResult: true },
    { id: 'seal', label: 'act.sealLogs', path: '/compliance/logs/segments/seal', fields: [f.dt('upTo')], showResult: true },""",
"""    // M-051: ImpactAssessmentRequest(activityId, scope, risks, measures, residualRisk, assessor, independent, assessedAt, authorityReportRef)
    // (ComplianceRequests.java:59); scope and assessor required (AccountabilityService.java:85-86)
    { id: 'dpia', label: 'act.recordDpia', path: '/compliance/evidence/impact-assessments', fields: [f.text('scope', 'fld.system', true), f.text('assessor', undefined, true), f.dt('assessedAt', 'fld.assessedOn'), f.json('risks', undefined, true), f.json('measures'), f.text('authorityReportRef', 'fld.documentRef')], showResult: true },
    // M-051: AuditEvidenceRequest(kind, periodFrom, periodTo, completedAt, auditor, accreditationRef, independent, findings, reportHash,
    // authorityReportRef) (ComplianceRequests.java:54); kind is an evidence kind of the jurisdiction pack, auditor and reportHash are
    // required, and a kind may require independence or an accreditation reference (AccountabilityService.java:106-118)
    { id: 'audit', label: 'act.recordAudit', path: '/compliance/evidence/audits', fields: [f.text('kind', 'fld.evidenceKind', true), f.text('auditor', undefined, true), f.dt('periodFrom', 'fld.from'), f.dt('periodTo', 'fld.to'), f.text('reportHash', 'fld.reportHash', true), f.bool('independent', 'fld.independent'), f.text('accreditationRef', 'fld.accreditationRef'), f.json('findings')], showResult: true },
    // M-051: TransferRequest(destinationCountry, recipient, purposeCode, dataCategories, ...) (CrossBorderTransferGuard.java:37)
    { id: 'transfer', label: 'act.evaluateTransfer', path: '/compliance/transfers/evaluate', fields: [f.text('destinationCountry', undefined, true), f.json('dataCategories', 'fld.categories', true), f.text('purposeCode', undefined, true)], showResult: true },
    // M-051: SealLogsRequest(from, to) (ComplianceRequests.java:64); from required (ComplianceController.java:348-352), to defaults to now
    { id: 'seal', label: 'act.sealLogs', path: '/compliance/logs/segments/seal', fields: [f.dt('from', undefined, true), f.dt('to', 'fld.upTo')], showResult: true },"""),
]
for old, new in R:
    sub(p, old, new)
