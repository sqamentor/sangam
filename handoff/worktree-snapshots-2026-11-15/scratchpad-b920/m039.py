import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from ed import sub
os.chdir('D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui/src')

# ---------------- registry
p = 'modules/registry.tsx'
sub(p, """    { id: 'update', label: 'act.update', method: 'PATCH', path: '/registry/objects/{id}', fields: [f.json('attributes', undefined, true)] },
  ],
};""", """    { id: 'update', label: 'act.update', method: 'PATCH', path: '/registry/objects/{id}', fields: [f.json('attributes', undefined, true)] },
    // M-039: the version in force at an instant (GET /registry/objects/{id}/as-of ?at, Instant.parse, RegistryController.java:151-154)
    { id: 'asOf', label: 'act.asOf', method: 'GET', path: '/registry/objects/{id}/as-of', fields: [f.dt('at', 'fld.asOfInstant', true)], noJson: true, showResult: true },
  ],
};""")
sub(p, """    { id: 'transfer', label: 'act.transferHolder', path: '/registry/instruments/{id}/transfer-holder', fields: [f.text('newHolderPartyId', undefined, true)], reason: 'body' },""", """    { id: 'transfer', label: 'act.transferHolder', path: '/registry/instruments/{id}/transfer-holder', fields: [f.text('newHolderPartyId', undefined, true)], reason: 'body' },
    // M-039: transfer by reissue {newHolderPartyId, reason} (InstrumentController.java:115-120); expire now (:92-95)
    { id: 'reissue', label: 'act.transferReissue', path: '/registry/instruments/{id}/transfer-reissue', fields: [f.text('newHolderPartyId', undefined, true)], reason: 'body', noJson: true, showResult: true, hint: 'mod.registry.reissueHint' },
    { id: 'expire', label: 'act.expire', path: '/registry/instruments/{id}/expire', noJson: true, danger: true, showResult: true, hint: 'mod.registry.expireHint' },
    // the expiry sweep of instruments past validity (?typeKey &limit, InstrumentController.java:157-161)
    { id: 'expireDue', label: 'act.expireDue', path: '/registry/instruments/jobs/expire', fields: [f.q(f.text('typeKey')), f.q(f.num('limit', 'fld.limit'))], noJson: true, showResult: true },""")
sub(p, """    { id: 'deactivate', label: 'act.deactivate', path: '/registry/parties/{id}/deactivate', reason: 'body', danger: true },""", """    { id: 'deactivate', label: 'act.deactivate', path: '/registry/parties/{id}/deactivate', reason: 'body', danger: true },
    // M-039: reveal an identifier; the declared purpose is the X-Purpose header (PartyController.java:29, :119-124), checked,
    // consent-gated and audited as PARTY_IDENTIFIER_REVEALED by the server (PartyService.java:432-443)
    { id: 'reveal', label: 'act.reveal', path: '/registry/parties/identifiers/{identifierId}/reveal', fields: [f.path(f.text('identifierId', 'fld.identifierId', true))], reason: 'body', reasonHeader: 'X-Purpose', noJson: true, showResult: true, hint: 'mod.registry.revealHint' },""")

# ---------------- field
p = 'modules/fieldgeo.tsx'
sub(p, """  actions: [{ id: 'resolve', label: 'act.resolve', path: '/field/conflicts/{id}/resolve', fields: [f.select('choice', ['KEEP_SERVER', 'KEEP_DEVICE', 'MERGE'], undefined, true), f.json('merged')], reason: 'body' }],
};""", """  actions: [
    { id: 'resolve', label: 'act.resolve', path: '/field/conflicts/{id}/resolve', fields: [f.select('choice', ['KEEP_SERVER', 'KEEP_DEVICE', 'MERGE'], undefined, true), f.json('merged')], reason: 'body' },
    // M-039: re-check an evidence item's hash chain (field.review, FieldWorkController.java:281-284) → {evidenceId, intact}
    { id: 'reverify', label: 'act.reverify', path: '/field/evidence/{evidenceId}/reverify', fields: [f.path(f.text('evidenceId', 'fld.evidenceId', true))], noJson: true, showResult: true },
  ],
};""")
sub(p, """  actions: [{ id: 'status', label: 'act.setStatus', path: '/field/units/{id}/status', fields: [f.select('status', ['AVAILABLE', 'BUSY', 'OFF_DUTY', 'OUT_OF_SERVICE'], undefined, true)] }],
};""", """  actions: [
    { id: 'status', label: 'act.setStatus', path: '/field/units/{id}/status', fields: [f.select('status', ['AVAILABLE', 'BUSY', 'OFF_DUTY', 'OUT_OF_SERVICE'], undefined, true)] },
    // M-039 (dispatch.operate, FieldWorkController.java:40-43): ETA to a job (GET ?jobId, :325-328) and a position ping
    // {lat, lon, at?, accuracyM?, speedMps?, headingDeg?, source?} (:313-316; keys read at FieldRestService.java:404-409)
    { id: 'eta', label: 'act.eta', method: 'GET', path: '/field/units/{id}/eta', fields: [f.text('jobId', 'fld.jobId', true)], noJson: true, showResult: true },
    { id: 'ping', label: 'act.ping', path: '/field/units/{id}/pings', fields: [f.num('lat', undefined, true), f.num('lon', undefined, true), f.dt('at'), f.num('accuracyM', 'fld.accuracyM'), f.num('speedMps', 'fld.speedMps'), f.num('headingDeg', 'fld.headingDeg'), f.text('source')], noJson: true },
  ],
};""")

# ---------------- queues and rosters
p = 'modules/resources.tsx'
sub(p, """    { id: 'sla', label: 'mod.queues.slaBreaches', path: '/queues/service-points/{servicePointId}/sla-breaches' },
  ],""", """    { id: 'sla', label: 'mod.queues.slaBreaches', path: '/queues/service-points/{servicePointId}/sla-breaches' },
    // M-039: the call log (GET .../call-events, resources.read, QueueController.java:215-220; after / limit default on the server)
    { id: 'calls', label: 'mod.queues.callEvents', path: '/queues/service-points/{servicePointId}/call-events', columns: [c('eventId'), c('kind'), c('tokenId'), c('counterId'), c('actorId'), c('at')] },
  ],""")
sub(p, """    { id: 'close', label: 'act.closePeriod', path: '/rosters/periods/{periodId}/close', fields: [f.q(f.num('version', undefined, true))], reason: 'body' },
  ],
};""", """    { id: 'close', label: 'act.closePeriod', path: '/rosters/periods/{periodId}/close', fields: [f.q(f.num('version', undefined, true))], reason: 'body' },
    // M-039: mark on call {personId, shiftId, day} (OnCallRequest, RosterController.java:50, :238-243; resources.admin)
    { id: 'onCall', label: 'act.markOnCall', path: '/rosters/periods/{periodId}/on-call', fields: [f.text('personId', undefined, true), f.text('shiftId', undefined, true), f.date('day', undefined, true)], noJson: true },
  ],
  toolbar: [
    // M-039: one person's duties in a window (GET ?from &to, ISO instants, RosterController.java:257-265)
    { id: 'duties', label: 'act.personDuties', method: 'GET', path: '/rosters/people/{personId}/duties', fields: [f.path(f.text('personId', undefined, true)), f.dt('from', undefined, true), f.dt('to', undefined, true)], noJson: true, showResult: true },
  ],
};""")

# ---------------- committees, links, case definitions
p = 'modules/casework.tsx'
sub(p, """    { id: 'cancel', label: 'act.cancel', path: '/meetings/{meetingId}/cancel', reason: 'body', danger: true },""", """    { id: 'cancel', label: 'act.cancel', path: '/meetings/{meetingId}/cancel', reason: 'body', danger: true },
    // M-039: acknowledge a sent notice by its id (from the send-notices answer; MeetingController.java:212-216, 204)
    { id: 'ackNotice', label: 'act.ackNotice', path: '/meeting-notices/{noticeId}/ack', fields: [f.path(f.text('noticeId', 'fld.noticeId', true))], noJson: true, hint: 'mod.committees.ackHint' },""")
sub(p, """  return <ModulePage title="mod.cases.title" description="mod.cases.desc" screens={[caseFiles, caseParties, hearings, deadlines, caseConfig]} />;""", """  return <ModulePage title="mod.cases.title" description="mod.cases.desc" screens={[caseFiles, caseParties, hearings, deadlines, caseConfig, links, caseDefinitions]} />;""")
sub(p, """export function CaseFilesPage() {""", """// M-039: links between process, case and task scopes (GET/POST /links, DELETE /links/{id}; process.read / process.write,
// DocumentController.java:97-121). Body CreateLinkRequest(linkType, sourceScopeType, sourceId, targetScopeType, targetId,
// referenceType) (LinkPort.java:20-21); scope types bpmn | cmmn | task (sangam-api.yaml listLinks).
const SCOPES = ['bpmn', 'cmmn', 'task'];
const links: ScreenDef = {
  id: 'links',
  title: 'mod.links.title',
  description: 'mod.links.desc',
  list: '/links',
  unpaged: true,
  filters: [f.select('scopeType', SCOPES, 'fld.scopeType', true, 'mod.links.scope.'), f.text('scopeId', undefined, true), f.select('linkType', ['REFERENCE', 'SIBLING', 'PARENT', 'CHILD', 'ROOT', 'REACTIVATED_FROM'])],
  requireFilters: ['scopeType', 'scopeId'],
  columns: [c('id'), c('linkType'), c('sourceScopeType', 'fld.sourceScopeType'), c('sourceId', 'fld.sourceId'), c('targetScopeType', 'fld.targetScopeType'), c('targetId'), c('referenceType', 'fld.referenceType'), c('createdAt')],
  rowKey: ['id'],
  create: {
    id: 'link',
    label: 'act.createLink',
    path: '/links',
    noJson: true,
    fields: [
      f.select('linkType', ['REFERENCE', 'SIBLING', 'PARENT', 'CHILD'], undefined, true),
      f.select('sourceScopeType', SCOPES, 'fld.sourceScopeType', true, 'mod.links.scope.'),
      f.text('sourceId', 'fld.sourceId', true),
      f.select('targetScopeType', SCOPES, 'fld.targetScopeType', true, 'mod.links.scope.'),
      f.text('targetId', undefined, true),
      f.text('referenceType', 'fld.referenceType'),
    ],
  },
  actions: [{ id: 'delete', label: 'common.delete', method: 'DELETE', path: '/links/{id}', reason: 'query', danger: true, noJson: true }],
};

// M-039: deployed case definitions (GET /case-definitions ?key &latest; case.read, CaseDecisionController.java:90-96)
const caseDefinitions: ScreenDef = {
  id: 'caseDefinitions',
  title: 'mod.caseDefs.title',
  list: '/case-definitions',
  filters: [f.text('key'), f.bool('latest', 'fld.latestOnly')],
  columns: [c('key'), c('name'), c('version'), c('deploymentId'), c('tenantId'), c('id')],
  rowKey: ['id'],
};

export function CaseFilesPage() {""")

# ---------------- programme milestones
p = 'modules/programme.tsx'
sub(p, """    { id: 'milestone', label: 'act.planMilestone', path: '/programmes/{programmeId}/milestones', fields: [f.text('name', undefined, true), f.text('orgUnit'), f.date('plannedDate', undefined, true), f.num('weight')] },""", """    // M-039/M-051: MilestoneRequest(orgUnit, name, seq, plannedStart, plannedEnd, weight, dependsOn) (ProgrammeController.java:91-92,
    // :254-259); the earlier plannedDate field is not read by the server
    { id: 'milestone', label: 'act.planMilestone', path: '/programmes/{programmeId}/milestones', noJson: true, fields: [f.text('name', undefined, true), f.text('orgUnit'), f.num('seq'), f.date('plannedStart', 'fld.plannedStart', true), f.date('plannedEnd', 'fld.plannedEnd', true), f.money('weight'), f.json('dependsOn', 'fld.dependsOn')] },""")
sub(p, """  return <ModulePage title="mod.programmes.title" description="mod.programmes.desc" screens={[programmes, targets, periods, returns, defaulters, alerts, config]} />;""", """  return <ModulePage title="mod.programmes.title" description="mod.programmes.desc" screens={[programmes, targets, milestones, periods, returns, defaulters, alerts, config]} />;""")
sub(p, """const targets: ScreenDef = {""", """// M-039: milestone progress (PATCH /programme-milestones/{id}, programme.write, ProgrammeController.java:262-269). Body
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

const targets: ScreenDef = {""")
print('M-039 done')
