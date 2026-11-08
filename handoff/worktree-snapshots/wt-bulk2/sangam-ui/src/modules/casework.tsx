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
    { id: 'stage', label: 'act.moveStage', path: '/case-files/{caseId}/stage', fields: [f.text('stage', undefined, true)], reason: 'body' },
    { id: 'assign', label: 'act.assign', path: '/case-files/{caseId}/assign', fields: [f.text('ownerSeat', undefined, true), f.text('orgUnit')], reason: 'body' },
    { id: 'party', label: 'act.addParty', path: '/case-files/{caseId}/parties', fields: [f.text('partyId', undefined, true), f.text('role', undefined, true)], hint: 'mod.cases.conflictHint' },
    { id: 'document', label: 'act.attachDocument', path: '/case-files/{caseId}/documents', fields: [f.text('documentId', undefined, true), f.text('folderId'), f.text('title')] },
    { id: 'folder', label: 'act.addFolder', path: '/case-files/{caseId}/folders', fields: [f.text('name', undefined, true), f.text('parentId')] },
    { id: 'note', label: 'act.addNote', path: '/case-files/{caseId}/notes', fields: [f.area('text', undefined, true)] },
    { id: 'record', label: 'act.linkRecord', path: '/case-files/{caseId}/records', fields: [f.select('recordType', ['PROCESS_INSTANCE', 'DOCUMENT', 'EXTERNAL'], undefined, true), f.text('recordId', undefined, true), f.text('label')] },
    { id: 'link', label: 'act.linkCase', path: '/case-files/{caseId}/links', fields: [f.text('targetCaseId', undefined, true), f.text('linkType', undefined, true)] },
    { id: 'milestone', label: 'act.recordMilestone', path: '/case-files/{caseId}/milestones', fields: [f.text('milestone', undefined, true), f.dt('at')] },
    { id: 'hearing', label: 'act.scheduleHearing', path: '/case-files/{caseId}/hearings', fields: [f.text('forum', undefined, true), f.dt('scheduledAt', undefined, true), f.text('purpose')] },
    { id: 'merge', label: 'act.merge', path: '/case-files/{caseId}/merge', fields: [f.text('sourceCaseId', undefined, true)], reason: 'body', danger: true },
    { id: 'split', label: 'act.split', path: '/case-files/{caseId}/split', fields: [f.text('title', undefined, true), f.json('partyIds'), f.json('recordIds')], reason: 'body' },
    { id: 'close', label: 'act.close', path: '/case-files/{caseId}/close', reason: 'body', visible: (r) => r.status !== 'CLOSED' },
    { id: 'reopen', label: 'act.reopen', path: '/case-files/{caseId}/reopen', reason: 'body', visible: (r) => r.status === 'CLOSED' },
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
  create: { id: 'register', label: 'act.register', path: '/case-parties', fields: [f.select('kind', ['PERSON', 'ORGANISATION'], undefined, true), f.text('displayName', undefined, true), f.json('identifiers'), f.text('address')], hint: 'mod.cases.duplicateCheckHint' },
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
  actions: [{ id: 'outcome', label: 'act.recordOutcome', path: '/hearings/{hearingId}/outcome', fields: [f.select('outcome', ['HEARD', 'ADJOURNED', 'DECIDED', 'NOT_TAKEN_UP'], undefined, true), f.dt('nextDate'), f.area('remarks')] }],
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
  toolbar: [{ id: 'publish', label: 'act.publish', method: 'PUT', path: '/case-config/{kind}', fields: [f.select('kind', ['case-type', 'reference-kind', 'committee-kind'], undefined, true), f.json('definition', undefined, true)], noJson: true }],
};

export function CaseFilesPage() {
  return <ModulePage title="mod.cases.title" description="mod.cases.desc" screens={[caseFiles, caseParties, hearings, deadlines, caseConfig]} />;
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
    { id: 'member', label: 'act.addMember', path: '/committees/{committeeId}/members', fields: [f.text('personId'), f.text('seatId'), f.select('role', ['CHAIR', 'MEMBER', 'MEMBER_SECRETARY', 'SPECIAL_INVITEE'], undefined, true), f.bool('voting')] },
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
    { id: 'agenda', label: 'act.addAgenda', path: '/meetings/{meetingId}/agenda', fields: [f.text('title', undefined, true), f.text('caseId'), f.text('presenter'), f.area('note')] },
    { id: 'notices', label: 'act.sendNotices', path: '/meetings/{meetingId}/notices', fields: [f.q(f.select('kind', ['NOTICE', 'AGENDA', 'REMINDER', 'CANCELLATION'], undefined, true))], noJson: true, hint: 'mod.committees.noticeHint' },
    { id: 'attendance', label: 'act.markAttendance', method: 'PUT', path: '/meetings/{meetingId}/attendance', fields: [f.text('memberId', undefined, true), f.select('presence', ['IN_PERSON', 'VIRTUAL', 'PROXY', 'ABSENT'], undefined, true), f.text('proxyFor')] },
    { id: 'start', label: 'act.startMeeting', path: '/meetings/{meetingId}/start', noJson: true, hint: 'mod.committees.quorumChecked' },
    { id: 'decision', label: 'act.recordDecision', path: '/meetings/{meetingId}/decisions', fields: [f.text('agendaItemId', undefined, true), f.area('text', 'fld.decisionText', true), f.json('votes'), f.area('dissent')] },
    { id: 'adjourn', label: 'act.adjourn', path: '/meetings/{meetingId}/adjourn', fields: [f.dt('to', 'fld.newDate', true)], reason: 'body' },
    { id: 'end', label: 'act.endMeeting', path: '/meetings/{meetingId}/end', noJson: true },
    { id: 'minutes', label: 'act.draftMinutes', path: '/meetings/{meetingId}/minutes', fields: [f.area('text', 'fld.minutesText', true)], noJson: true },
    { id: 'circulate', label: 'act.circulateMinutes', path: '/meetings/{meetingId}/minutes/circulate', noJson: true },
    { id: 'comment', label: 'act.commentMinutes', path: '/meetings/{meetingId}/minutes/comments', fields: [f.area('text', undefined, true)], noJson: true },
    { id: 'approve', label: 'act.approveMinutes', path: '/meetings/{meetingId}/minutes/approve', fields: [f.text('presentedHash', undefined, true), f.text('esignRef')], hint: 'mod.committees.minutesHashHint' },
    { id: 'issue', label: 'act.issueMinutes', path: '/meetings/{meetingId}/minutes/issue', noJson: true },
    { id: 'carry', label: 'act.carryForward', path: '/meetings/{meetingId}/carry-forward', fields: [f.q(f.text('to', 'fld.targetMeeting', true))], noJson: true },
    { id: 'placeAtr', label: 'act.placeAtr', path: '/meetings/{meetingId}/atr/place', fields: [f.q(f.text('next', 'fld.targetMeeting', true))], noJson: true },
    { id: 'reschedule', label: 'act.reschedule', path: '/meetings/{meetingId}/reschedule', fields: [f.dt('scheduledAt', undefined, true)], reason: 'body' },
    { id: 'cancel', label: 'act.cancel', path: '/meetings/{meetingId}/cancel', reason: 'body', danger: true },
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
    { id: 'atr', label: 'act.submitAtr', path: '/action-items/{actionItemId}/atr', fields: [f.area('text', 'fld.atrText', true), f.json('documentIds')] },
    { id: 'verify', label: 'act.verifyAtr', path: '/action-items/{actionItemId}/verify', fields: [f.select('decision', ['ACCEPT', 'RETURN'], undefined, true), f.area('remarks')] },
    { id: 'reassign', label: 'act.reassign', path: '/action-items/{actionItemId}/reassign', fields: [f.text('ownerSeat', undefined, true)], reason: 'body' },
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
    { id: 'transfer', label: 'act.transfer', path: '/references/{referenceId}/transfer', fields: [f.text('orgUnit'), f.text('seat')], reason: 'body' },
    { id: 'start', label: 'act.start', path: '/references/{referenceId}/start', noJson: true },
    { id: 'interim', label: 'act.interimReply', path: '/references/{referenceId}/interim-reply', fields: [f.area('text', 'fld.replyText', true), f.text('channel')] },
    { id: 'draft', label: 'act.saveDraftReply', path: '/references/{referenceId}/draft-reply', fields: [f.area('text', 'fld.replyText', true)] },
    { id: 'approve', label: 'act.approveReply', path: '/references/{referenceId}/approve-reply', fields: [f.text('presentedHash', undefined, true)], hint: 'mod.references.approveHint' },
    { id: 'reply', label: 'act.sendReply', path: '/references/{referenceId}/reply', fields: [f.area('text', 'fld.replyText', true), f.text('channel')] },
    { id: 'extend', label: 'act.extend', path: '/references/{referenceId}/extend', fields: [f.dt('newDueAt', undefined, true)], reason: 'body' },
    { id: 'followUp', label: 'act.followUp', path: '/references/{referenceId}/follow-ups', fields: [f.text('subject', undefined, true), f.area('body'), f.dt('receivedAt')] },
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
