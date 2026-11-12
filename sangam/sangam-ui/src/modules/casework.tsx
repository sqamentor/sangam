// SPDX-License-Identifier: Apache-2.0
/**
 * sangam-case screens (contract /case-files, /case-parties, /hearings, /case-deadlines, /committees, /meetings,
 * /action-items, /references, /case-config): case files (parties with conflict-of-interest check, documents, notes,
 * timeline, links, merge / split, hearings and cause list, deadlines), committees and meetings (agenda, notices,
 * attendance and quorum, decisions with dissent, minutes circulate → approve → issue bound to the content hash,
 * action-taken reports), and references (receipt, assignment, interim / final reply with an approved draft, pendency).
 */
import { ModulePage, c, f, type ScreenDef } from './engine';

const caseFiles: ScreenDef = {
  id: 'caseFiles',
  title: 'mod.cases.files',
  description: 'mod.cases.filesDesc',
  list: '/case-files',
  filters: [f.text('caseType'), f.text('status'), f.text('stage'), f.text('orgUnit'), f.text('ownerSeat'), f.text('partyId'), f.text('text', 'fld.search'), f.date('openedFrom'), f.date('openedTo')],
  columns: [c('caseNumber'), c('title'), c('caseType'), c('stage'), c('status'), c('ownerSeat'), c('openedAt')],
  rowKey: ['caseId', 'caseNumber'],
  detail: '/case-files/{caseId}',
  sections: [
    { id: 'timeline', label: 'mod.cases.timeline', path: '/case-files/{caseId}/timeline?engine=true' },
    { id: 'notes', label: 'mod.cases.notes', path: '/case-files/{caseId}/notes' },
    { id: 'duplicates', label: 'mod.cases.duplicates', path: '/case-files/{caseId}/duplicates' },
  ],
  create: {
    id: 'open',
    label: 'act.openCase',
    path: '/case-files',
    fields: [f.text('caseType', undefined, true), f.text('title', undefined, true), f.text('orgUnit'), f.text('ownerSeat'), f.select('priority', ['LOW', 'NORMAL', 'HIGH', 'URGENT']), f.select('confidentiality', ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET']), f.text('businessKey'), f.json('attributes')],
  },
  actions: [
    // M-051: StageRequest(stage, reason, rowVersion), AssignRequest(ownerSeat, orgUnit, rowVersion) (CaseFileRestPort.java:34, :37); a
    // long rowVersion of 0 would be checked as version 0 (CaseFileService.bump, expected >= 0), so the row's version is sent
    { id: 'stage', label: 'act.moveStage', path: '/case-files/{caseId}/stage', fields: [f.text('stage', undefined, true), f.num('rowVersion', 'fld.rowVersion', true)], defaults: (r) => ({ rowVersion: r?.rowVersion as number }), reason: 'body' },
    { id: 'assign', label: 'act.assign', path: '/case-files/{caseId}/assign', fields: [f.text('ownerSeat', undefined, true), f.text('orgUnit'), f.num('rowVersion', 'fld.rowVersion', true)], defaults: (r) => ({ rowVersion: r?.rowVersion as number }), reason: 'body' },
    { id: 'party', label: 'act.addParty', path: '/case-files/{caseId}/parties', fields: [f.text('partyId', undefined, true), f.text('role', undefined, true)], hint: 'mod.cases.conflictHint' },
    // M-051: DocumentRequest(folderId, contentItemId, title, ...) (CaseFileRestPort.java:53)
    { id: 'document', label: 'act.attachDocument', path: '/case-files/{caseId}/documents', fields: [f.text('contentItemId', 'fld.documentId', true), f.text('folderId'), f.text('title')] },
    // M-051: FolderRequest(parentFolderId, name) (CaseFileController.java:63)
    { id: 'folder', label: 'act.addFolder', path: '/case-files/{caseId}/folders', fields: [f.text('name', undefined, true), f.text('parentFolderId', 'fld.parentId')] },
    // M-051: NoteRequest(body, visibility ALL | INTERNAL | PRIVATE) (CaseFileController.java:66; CaseContentsService.java:48)
    { id: 'note', label: 'act.addNote', path: '/case-files/{caseId}/notes', fields: [f.area('body', undefined, true), f.select('visibility', ['ALL', 'INTERNAL', 'PRIVATE'], 'fld.visibility')] },
    // M-051: RecordRequest(recordType, recordRef, relation, title) (CaseFileRestPort.java:50); recordType is data (bpmn, cmmn, task, ...,
    // CaseContentsService.java:29); bpmn / cmmn / task also link the engine scope (:84-85)
    { id: 'record', label: 'act.linkRecord', path: '/case-files/{caseId}/records', fields: [f.text('recordType', undefined, true, { description: 'mod.cases.recordTypeHint' }), f.text('recordRef', 'fld.recordId', true), f.text('relation', 'fld.relation'), f.text('title')] },
    // M-051: LinkRequest(toCaseId, linkType, reason) (CaseFileRestPort.java:57)
    { id: 'link', label: 'act.linkCase', path: '/case-files/{caseId}/links', fields: [f.text('toCaseId', 'fld.targetCaseId', true), f.text('linkType', undefined, true)] },
    // M-051: MilestoneRequest(milestone, occurredAt) (CaseFileController.java:60)
    { id: 'milestone', label: 'act.recordMilestone', path: '/case-files/{caseId}/milestones', fields: [f.text('milestone', undefined, true), f.dt('occurredAt', 'fld.at')] },
    // M-051: HearingRequest(kind, forum, scheduledAt, externalRef) (CaseFileRestPort.java:63); kind is required (HearingService.java:47)
    { id: 'hearing', label: 'act.scheduleHearing', path: '/case-files/{caseId}/hearings', fields: [f.text('kind', undefined, true), f.text('forum', undefined, true), f.dt('scheduledAt', undefined, true), f.text('externalRef', 'fld.externalRef')] },
    // M-051: MergeRequest(mergedCaseId, reason) (CaseFileRestPort.java:60)
    { id: 'merge', label: 'act.merge', path: '/case-files/{caseId}/merge', fields: [f.text('mergedCaseId', 'fld.sourceCaseId', true)], reason: 'body', danger: true },
    // M-051: SplitRequest(newCaseType, title, attributes, movePartyIds, copyRecordIds, moveDocumentIds, reason) (CaseLinkService.java:35-36)
    { id: 'split', label: 'act.split', path: '/case-files/{caseId}/split', fields: [f.text('title', undefined, true), f.text('newCaseType', 'fld.caseType'), f.json('movePartyIds', 'fld.partyIds'), f.json('copyRecordIds', 'fld.recordIds'), f.json('moveDocumentIds', 'fld.documentIds')], reason: 'body' },
    // M-051: ReasonRequest(reason, stage, rowVersion) (CaseFileRestPort.java:40)
    { id: 'close', label: 'act.close', path: '/case-files/{caseId}/close', fields: [f.num('rowVersion', 'fld.rowVersion', true)], defaults: (r) => ({ rowVersion: r?.rowVersion as number }), noJson: true, reason: 'body', visible: (r) => r.status !== 'CLOSED' },
    { id: 'reopen', label: 'act.reopen', path: '/case-files/{caseId}/reopen', fields: [f.text('stage'), f.num('rowVersion', 'fld.rowVersion', true)], defaults: (r) => ({ rowVersion: r?.rowVersion as number }), noJson: true, reason: 'body', visible: (r) => r.status === 'CLOSED' },
  ],
};

const caseParties: ScreenDef = {
  id: 'parties',
  title: 'mod.cases.partyBook',
  description: 'mod.cases.partyBookDesc',
  list: '/case-parties/candidates',
  unpaged: true,
  filters: [f.text('name', undefined, true)],
  requireFilters: ['name'],
  emptyHint: 'mod.cases.searchFirst',
  columns: [c('displayName'), c('kind'), c('identifiers'), c('partyId')],
  rowKey: ['partyId', 'id'],
  sections: [{ id: 'cases', label: 'mod.cases.files', path: '/case-parties/{partyId}/cases' }],
  // M-051: PartyInput(kind, displayName, identifiers, contact, externalRef) (PartyService.java:40); the address goes in contact
  create: { id: 'register', label: 'act.register', path: '/case-parties', fields: [f.select('kind', ['PERSON', 'ORGANISATION'], undefined, true), f.text('displayName', undefined, true), f.json('identifiers'), f.json('contact', 'fld.contact')], hint: 'mod.cases.duplicateCheckHint' },
};

const hearings: ScreenDef = {
  id: 'hearings',
  title: 'mod.cases.causeList',
  list: '/hearings/cause-list',
  unpaged: true,
  filters: [f.text('forum', undefined, true), f.date('from', undefined, true), f.date('to', undefined, true)],
  requireFilters: ['forum', 'from', 'to'],
  columns: [c('scheduledAt'), c('caseNumber'), c('purpose'), c('status'), c('hearingId')],
  rowKey: ['hearingId', 'id'],
  // M-051: OutcomeRequest(outcome, outcomeText, adjourned, nextDate) (CaseFileRestPort.java:66)
  actions: [{ id: 'outcome', label: 'act.recordOutcome', path: '/hearings/{hearingId}/outcome', fields: [f.select('outcome', ['HEARD', 'ADJOURNED', 'DECIDED', 'NOT_TAKEN_UP'], undefined, true), f.bool('adjourned', 'fld.adjourned'), f.dt('nextDate'), f.area('outcomeText', 'fld.remarks')] }],
};

const deadlines: ScreenDef = {
  id: 'deadlines',
  title: 'mod.cases.deadlines',
  list: '/case-deadlines/due',
  unpaged: true,
  filters: [f.select('scopeType', ['CASE', 'REFERENCE']), f.dt('before', undefined, true)],
  requireFilters: ['before'],
  columns: [c('dueAt'), c('scopeType'), c('scopeId'), c('kind'), c('status')],
  rowKey: ['deadlineId', 'id'],
  actions: [{ id: 'extend', label: 'act.extend', path: '/case-deadlines/{deadlineId}/extend', fields: [f.dt('newDueAt', undefined, true)], reason: 'body' }],
};

const caseConfig: ScreenDef = {
  id: 'config',
  title: 'mod.cases.config',
  description: 'mod.cases.configDesc',
  list: '/case-config/{kind}',
  unpaged: true,
  filters: [f.select('kind', ['case-type', 'reference-kind', 'committee-kind'], undefined, true)],
  requireFilters: ['kind'],
  columns: [c('key'), c('version'), c('name'), c('state')],
  rowKey: ['key', 'id'],
  // M-051: the body is the definition itself (@RequestBody Map, CaseFileController.java:70-71); kind is the path only
  toolbar: [{ id: 'publish', label: 'act.publish', method: 'PUT', path: '/case-config/{kind}', fields: [f.path(f.select('kind', ['case-type', 'reference-kind', 'committee-kind'], undefined, true))], hint: 'mod.cases.configBodyHint' }],
};

// M-039: links between process, case and task scopes (GET/POST /links, DELETE /links/{id}; process.read / process.write,
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

export function CaseFilesPage() {
  return <ModulePage title="mod.cases.title" description="mod.cases.desc" screens={[caseFiles, caseParties, hearings, deadlines, caseConfig, links, caseDefinitions]} />;
}

const committees: ScreenDef = {
  id: 'committees',
  title: 'mod.committees.committees',
  list: '/committees',
  unpaged: true,
  filters: [f.text('kind'), f.bool('activeOnly')],
  columns: [c('name'), c('kind'), c('orgUnit'), c('quorumRule'), c('status')],
  rowKey: ['committeeId', 'id'],
  detail: '/committees/{committeeId}',
  sections: [{ id: 'pendency', label: 'mod.committees.pendency', path: '/committees/{committeeId}/pendency' }],
  create: {
    id: 'constitute',
    label: 'act.constitute',
    path: '/committees',
    fields: [f.text('kind', undefined, true), f.text('name', undefined, true), f.text('orgUnit'), f.text('constitutionRef', undefined, true), f.dt('validFrom'), f.dt('validTo'), f.text('quorumRule'), f.bool('chairRequired'), f.num('minNoticeHours'), f.json('members')],
  },
  actions: [
    // M-051: MemberInput(personRef, seatRef, displayName, role, voting) (CommitteeService.java:44); default roles CHAIR, MEMBER,
    // MEMBER_SECRETARY, INVITEE (CaseConfig.java:318-320), checked at CommitteeService.java:102
    { id: 'member', label: 'act.addMember', path: '/committees/{committeeId}/members', fields: [f.text('personRef', 'fld.personId'), f.text('seatRef', 'fld.seatId'), f.text('displayName'), f.select('role', ['CHAIR', 'MEMBER', 'MEMBER_SECRETARY', 'INVITEE'], undefined, true), f.bool('voting')] },
    { id: 'removeMember', label: 'act.removeMember', method: 'DELETE', path: '/committees/{committeeId}/members/{memberId}', fields: [f.text('memberId', undefined, true)], reason: 'query', danger: true },
    { id: 'reconstitute', label: 'act.reconstitute', path: '/committees/{committeeId}/reconstitute', fields: [f.text('constitutionRef', undefined, true), f.json('members')], reason: 'body' },
    { id: 'dissolve', label: 'act.dissolve', path: '/committees/{committeeId}/dissolve', reason: 'body', danger: true },
  ],
};

const meetings: ScreenDef = {
  id: 'meetings',
  title: 'mod.committees.meetings',
  list: '/meetings',
  unpaged: true,
  filters: [f.text('committeeId', undefined, true), f.date('from'), f.date('to')],
  requireFilters: ['committeeId'],
  columns: [c('title'), c('scheduledAt'), c('venue'), c('mode'), c('status')],
  rowKey: ['meetingId', 'id'],
  detail: '/meetings/{meetingId}',
  sections: [
    { id: 'quorum', label: 'mod.committees.quorum', path: '/meetings/{meetingId}/quorum' },
    { id: 'atr', label: 'mod.committees.atr', path: '/meetings/{meetingId}/atr' },
  ],
  create: { id: 'schedule', label: 'act.scheduleMeeting', path: '/meetings', fields: [f.text('committeeId', undefined, true), f.text('title', undefined, true), f.dt('scheduledAt', undefined, true), f.dt('endsAt'), f.text('venue'), f.select('mode', ['IN_PERSON', 'VIRTUAL', 'HYBRID']), f.text('urgentReason', undefined, false, { description: 'mod.committees.urgentHint' }), f.text('caseId')] },
  actions: [
    // M-051: AgendaRequest(title, description, linkedType, linkedRef, presenter) (MeetingRestPort.java:29)
    { id: 'agenda', label: 'act.addAgenda', path: '/meetings/{meetingId}/agenda', fields: [f.text('title', undefined, true), f.text('linkedType', 'fld.linkedType'), f.text('linkedRef', 'fld.linkedRef'), f.text('presenter'), f.area('description')] },
    { id: 'notices', label: 'act.sendNotices', path: '/meetings/{meetingId}/notices', fields: [f.q(f.select('kind', ['NOTICE', 'AGENDA', 'REMINDER', 'CANCELLATION'], undefined, true))], noJson: true, hint: 'mod.committees.noticeHint' },
    // M-051: AttendanceRequest(memberId, attendeeRef, attendance, proxyFor) (MeetingRestPort.java:32); attendance PRESENT | ABSENT | LEAVE |
    // PROXY (MeetingService.java:40, :341)
    { id: 'attendance', label: 'act.markAttendance', method: 'PUT', path: '/meetings/{meetingId}/attendance', fields: [f.text('memberId', undefined, true), f.select('attendance', ['PRESENT', 'ABSENT', 'LEAVE', 'PROXY'], 'fld.presence', true), f.text('proxyFor')] },
    { id: 'start', label: 'act.startMeeting', path: '/meetings/{meetingId}/start', noJson: true, hint: 'mod.committees.quorumChecked' },
    // M-051: DecisionInput(itemId, text, outcome, votesFor, votesAgainst, abstentions, dissent: list of objects) (MeetingService.java:76-77);
    // outcome required, one of APPROVED, REJECTED, DEFERRED, NOTED, MODIFIED, WITHDRAWN (:41, :445-447)
    { id: 'decision', label: 'act.recordDecision', path: '/meetings/{meetingId}/decisions', fields: [f.text('itemId', 'fld.agendaItemId', true), f.area('text', 'fld.decisionText', true), f.select('outcome', ['APPROVED', 'REJECTED', 'DEFERRED', 'NOTED', 'MODIFIED', 'WITHDRAWN'], undefined, true), f.num('votesFor', 'fld.votesFor'), f.num('votesAgainst', 'fld.votesAgainst'), f.num('abstentions', 'fld.abstentions'), f.json('dissent')] },
    // M-051: AdjournRequest(reason, newAt) (MeetingRestPort.java:35)
    { id: 'adjourn', label: 'act.adjourn', path: '/meetings/{meetingId}/adjourn', fields: [f.dt('newAt', 'fld.newDate', true)], reason: 'body' },
    { id: 'end', label: 'act.endMeeting', path: '/meetings/{meetingId}/end', noJson: true },
    { id: 'minutes', label: 'act.draftMinutes', path: '/meetings/{meetingId}/minutes', fields: [f.area('text', 'fld.minutesText', true)], noJson: true },
    { id: 'circulate', label: 'act.circulateMinutes', path: '/meetings/{meetingId}/minutes/circulate', noJson: true },
    { id: 'comment', label: 'act.commentMinutes', path: '/meetings/{meetingId}/minutes/comments', fields: [f.area('text', undefined, true)], noJson: true },
    // M-051: ApproveMinutesRequest(version, contentHash, esignRef) (MeetingRestPort.java:38; MeetingService.java:543-551)
    { id: 'approve', label: 'act.approveMinutes', path: '/meetings/{meetingId}/minutes/approve', fields: [f.num('version', undefined, true), f.text('contentHash', 'fld.presentedHash', true), f.text('esignRef')], hint: 'mod.committees.minutesHashHint' },
    { id: 'issue', label: 'act.issueMinutes', path: '/meetings/{meetingId}/minutes/issue', noJson: true },
    { id: 'carry', label: 'act.carryForward', path: '/meetings/{meetingId}/carry-forward', fields: [f.q(f.text('to', 'fld.targetMeeting', true))], noJson: true },
    { id: 'placeAtr', label: 'act.placeAtr', path: '/meetings/{meetingId}/atr/place', fields: [f.q(f.text('next', 'fld.targetMeeting', true))], noJson: true },
    { id: 'reschedule', label: 'act.reschedule', path: '/meetings/{meetingId}/reschedule', fields: [f.dt('scheduledAt', undefined, true)], reason: 'body' },
    { id: 'cancel', label: 'act.cancel', path: '/meetings/{meetingId}/cancel', reason: 'body', danger: true },
    // M-039: acknowledge a sent notice by its id (from the send-notices answer; MeetingController.java:212-216, 204)
    { id: 'ackNotice', label: 'act.ackNotice', path: '/meeting-notices/{noticeId}/ack', fields: [f.path(f.text('noticeId', 'fld.noticeId', true))], noJson: true, hint: 'mod.committees.ackHint' },
  ],
};

const actionItems: ScreenDef = {
  id: 'actions',
  title: 'mod.committees.actionItems',
  list: '/action-items',
  unpaged: true,
  filters: [f.text('meetingId'), f.text('committeeId'), f.text('ownerSeat'), f.bool('open')],
  columns: [c('title'), c('ownerSeat'), c('dueAt'), c('status'), c('meetingId')],
  rowKey: ['actionItemId', 'id'],
  create: { id: 'create', label: 'act.create', path: '/action-items', fields: [f.text('decisionId', undefined, true), f.text('title', undefined, true), f.text('ownerSeat', undefined, true), f.dt('dueAt')] },
  actions: [
    { id: 'start', label: 'act.start', path: '/action-items/{actionItemId}/start', noJson: true },
    // M-051: AtrBody(atrText) (MeetingController.java:57); VerifyRequest(accept, reason) (MeetingRestPort.java:41);
    // ReassignBody(ownerSeat, ownerPerson) (MeetingController.java:54)
    { id: 'atr', label: 'act.submitAtr', path: '/action-items/{actionItemId}/atr', fields: [f.area('atrText', 'fld.atrText', true)] },
    { id: 'verify', label: 'act.verifyAtr', path: '/action-items/{actionItemId}/verify', fields: [f.bool('accept', 'fld.acceptAtr'), f.area('reason')] },
    { id: 'reassign', label: 'act.reassign', path: '/action-items/{actionItemId}/reassign', fields: [f.text('ownerSeat', undefined, true), f.text('ownerPerson', 'fld.person')], reason: 'body' },
    { id: 'drop', label: 'act.drop', path: '/action-items/{actionItemId}/drop', reason: 'body', danger: true },
  ],
};

export function CommitteesPage() {
  return <ModulePage title="mod.committees.title" description="mod.committees.desc" screens={[committees, meetings, actionItems]} />;
}

const references: ScreenDef = {
  id: 'references',
  title: 'mod.references.list',
  list: '/references',
  filters: [f.text('kind'), f.text('status'), f.text('seat'), f.text('orgUnit'), f.text('sourceCategory'), f.bool('overdue'), f.text('q', 'fld.search')],
  columns: [c('number'), c('subject'), c('kind'), c('sourceName'), c('status'), c('dueAt'), c('assignedSeat')],
  rowKey: ['referenceId', 'number'],
  detail: '/references/{referenceId}',
  create: {
    id: 'register',
    label: 'act.registerReference',
    path: '/references',
    fields: [f.text('kind', undefined, true), f.text('sourceName', undefined, true), f.select('sourceCategory', ['VIP', 'PUBLIC', 'COURT', 'LEGISLATURE', 'DEPARTMENT', 'RTI', 'OTHER']), f.text('sourceRef'), f.text('subject', undefined, true), f.area('body'), f.dt('receivedAt'), f.select('receivedVia', ['POST', 'EMAIL', 'PORTAL', 'HAND', 'DAK']), f.select('priority', ['LOW', 'NORMAL', 'HIGH', 'URGENT']), f.text('orgUnit'), f.text('assignedSeat'), f.dt('fixedDueAt'), f.text('parentReferenceId'), f.bool('allowDuplicate')],
    hint: 'mod.references.deadlineHint',
  },
  actions: [
    { id: 'assign', label: 'act.assign', path: '/references/{referenceId}/assign', fields: [f.text('seat'), f.text('person')], reason: 'body' },
    // M-051: TransferRequest(toOrgUnit, toSeat, remarks) (ReferenceRestPort.java:26)
    { id: 'transfer', label: 'act.transfer', path: '/references/{referenceId}/transfer', fields: [f.text('toOrgUnit', 'fld.orgUnit'), f.text('toSeat', 'fld.seat')], reason: 'body' },
    { id: 'start', label: 'act.start', path: '/references/{referenceId}/start', noJson: true },
    // M-051: ReplyRequest(text, replyRef) (ReferenceRestPort.java:29), also for the reply below
    { id: 'interim', label: 'act.interimReply', path: '/references/{referenceId}/interim-reply', fields: [f.area('text', 'fld.replyText', true), f.text('replyRef', 'fld.replyRef')] },
    { id: 'draft', label: 'act.saveDraftReply', path: '/references/{referenceId}/draft-reply', fields: [f.area('text', 'fld.replyText', true)] },
    // M-051: ApproveBody(draftHash) (ReferenceController.java:39)
    { id: 'approve', label: 'act.approveReply', path: '/references/{referenceId}/approve-reply', fields: [f.text('draftHash', 'fld.presentedHash', true)], hint: 'mod.references.approveHint' },
    { id: 'reply', label: 'act.sendReply', path: '/references/{referenceId}/reply', fields: [f.area('text', 'fld.replyText', true), f.text('replyRef', 'fld.replyRef')] },
    { id: 'extend', label: 'act.extend', path: '/references/{referenceId}/extend', fields: [f.dt('newDueAt', undefined, true)], reason: 'body' },
    // M-051: FollowUpRequest(subject, fixedDueAt, attributes) (ReferenceRestPort.java:35)
    { id: 'followUp', label: 'act.followUp', path: '/references/{referenceId}/follow-ups', fields: [f.text('subject', undefined, true), f.dt('fixedDueAt'), f.json('attributes')] },
    { id: 'close', label: 'act.close', path: '/references/{referenceId}/close', reason: 'body' },
  ],
};

const pendency: ScreenDef = {
  id: 'pendency',
  title: 'mod.references.pendency',
  list: '/references/pendency',
  unpaged: true,
  filters: [f.text('kind'), f.text('dueSoon', 'fld.dueSoon', false, { description: 'mod.references.dueSoonHint' })],
  columns: [c('kind'), c('open'), c('overdue'), c('dueSoon'), c('avgAgeDays')],
  rowKey: ['kind', 'key'],
};

export function ReferencesPage() {
  return <ModulePage title="mod.references.title" description="mod.references.desc" screens={[references, pendency]} />;
}
