import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from ed import sub
os.chdir('D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui/src/modules')
p = 'casework.tsx'
RV = "f.num('rowVersion', 'fld.rowVersion', true)"
RVD = "defaults: (r) => ({ rowVersion: r?.rowVersion as number })"
R = [
("""    { id: 'stage', label: 'act.moveStage', path: '/case-files/{caseId}/stage', fields: [f.text('stage', undefined, true)], reason: 'body' },
    { id: 'assign', label: 'act.assign', path: '/case-files/{caseId}/assign', fields: [f.text('ownerSeat', undefined, true), f.text('orgUnit')], reason: 'body' },""",
"""    // M-051: StageRequest(stage, reason, rowVersion), AssignRequest(ownerSeat, orgUnit, rowVersion) (CaseFileRestPort.java:34, :37); a
    // long rowVersion of 0 would be checked as version 0 (CaseFileService.bump, expected >= 0), so the row's version is sent
    { id: 'stage', label: 'act.moveStage', path: '/case-files/{caseId}/stage', fields: [f.text('stage', undefined, true), %s], %s, reason: 'body' },
    { id: 'assign', label: 'act.assign', path: '/case-files/{caseId}/assign', fields: [f.text('ownerSeat', undefined, true), f.text('orgUnit'), %s], %s, reason: 'body' },""" % (RV, RVD, RV, RVD)),
("""    { id: 'close', label: 'act.close', path: '/case-files/{caseId}/close', reason: 'body', visible: (r) => r.status !== 'CLOSED' },
    { id: 'reopen', label: 'act.reopen', path: '/case-files/{caseId}/reopen', reason: 'body', visible: (r) => r.status === 'CLOSED' },""",
"""    // M-051: ReasonRequest(reason, stage, rowVersion) (CaseFileRestPort.java:40)
    { id: 'close', label: 'act.close', path: '/case-files/{caseId}/close', fields: [%s], %s, noJson: true, reason: 'body', visible: (r) => r.status !== 'CLOSED' },
    { id: 'reopen', label: 'act.reopen', path: '/case-files/{caseId}/reopen', fields: [f.text('stage'), %s], %s, noJson: true, reason: 'body', visible: (r) => r.status === 'CLOSED' },""" % (RV, RVD, RV, RVD)),
("""    { id: 'document', label: 'act.attachDocument', path: '/case-files/{caseId}/documents', fields: [f.text('documentId', undefined, true), f.text('folderId'), f.text('title')] },
    { id: 'folder', label: 'act.addFolder', path: '/case-files/{caseId}/folders', fields: [f.text('name', undefined, true), f.text('parentId')] },
    { id: 'note', label: 'act.addNote', path: '/case-files/{caseId}/notes', fields: [f.area('text', undefined, true)] },
    { id: 'record', label: 'act.linkRecord', path: '/case-files/{caseId}/records', fields: [f.select('recordType', ['PROCESS_INSTANCE', 'DOCUMENT', 'EXTERNAL'], undefined, true), f.text('recordId', undefined, true), f.text('label')] },
    { id: 'link', label: 'act.linkCase', path: '/case-files/{caseId}/links', fields: [f.text('targetCaseId', undefined, true), f.text('linkType', undefined, true)] },
    { id: 'milestone', label: 'act.recordMilestone', path: '/case-files/{caseId}/milestones', fields: [f.text('milestone', undefined, true), f.dt('at')] },
    { id: 'hearing', label: 'act.scheduleHearing', path: '/case-files/{caseId}/hearings', fields: [f.text('forum', undefined, true), f.dt('scheduledAt', undefined, true), f.text('purpose')] },
    { id: 'merge', label: 'act.merge', path: '/case-files/{caseId}/merge', fields: [f.text('sourceCaseId', undefined, true)], reason: 'body', danger: true },
    { id: 'split', label: 'act.split', path: '/case-files/{caseId}/split', fields: [f.text('title', undefined, true), f.json('partyIds'), f.json('recordIds')], reason: 'body' },""",
"""    // M-051: DocumentRequest(folderId, contentItemId, title, ...) (CaseFileRestPort.java:53)
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
    { id: 'split', label: 'act.split', path: '/case-files/{caseId}/split', fields: [f.text('title', undefined, true), f.text('newCaseType', 'fld.caseType'), f.json('movePartyIds', 'fld.partyIds'), f.json('copyRecordIds', 'fld.recordIds'), f.json('moveDocumentIds', 'fld.documentIds')], reason: 'body' },"""),
("""  create: { id: 'register', label: 'act.register', path: '/case-parties', fields: [f.select('kind', ['PERSON', 'ORGANISATION'], undefined, true), f.text('displayName', undefined, true), f.json('identifiers'), f.text('address')], hint: 'mod.cases.duplicateCheckHint' },""",
"""  // M-051: PartyInput(kind, displayName, identifiers, contact, externalRef) (PartyService.java:40); the address goes in contact
  create: { id: 'register', label: 'act.register', path: '/case-parties', fields: [f.select('kind', ['PERSON', 'ORGANISATION'], undefined, true), f.text('displayName', undefined, true), f.json('identifiers'), f.json('contact', 'fld.contact')], hint: 'mod.cases.duplicateCheckHint' },"""),
("""  actions: [{ id: 'outcome', label: 'act.recordOutcome', path: '/hearings/{hearingId}/outcome', fields: [f.select('outcome', ['HEARD', 'ADJOURNED', 'DECIDED', 'NOT_TAKEN_UP'], undefined, true), f.dt('nextDate'), f.area('remarks')] }],""",
"""  // M-051: OutcomeRequest(outcome, outcomeText, adjourned, nextDate) (CaseFileRestPort.java:66)
  actions: [{ id: 'outcome', label: 'act.recordOutcome', path: '/hearings/{hearingId}/outcome', fields: [f.select('outcome', ['HEARD', 'ADJOURNED', 'DECIDED', 'NOT_TAKEN_UP'], undefined, true), f.bool('adjourned', 'fld.adjourned'), f.dt('nextDate'), f.area('outcomeText', 'fld.remarks')] }],"""),
("""  toolbar: [{ id: 'publish', label: 'act.publish', method: 'PUT', path: '/case-config/{kind}', fields: [f.select('kind', ['case-type', 'reference-kind', 'committee-kind'], undefined, true), f.json('definition', undefined, true)], noJson: true }],""",
"""  // M-051: the body is the definition itself (@RequestBody Map, CaseFileController.java:70-71); kind is the path only
  toolbar: [{ id: 'publish', label: 'act.publish', method: 'PUT', path: '/case-config/{kind}', fields: [f.path(f.select('kind', ['case-type', 'reference-kind', 'committee-kind'], undefined, true))], hint: 'mod.cases.configBodyHint' }],"""),
("""    { id: 'member', label: 'act.addMember', path: '/committees/{committeeId}/members', fields: [f.text('personId'), f.text('seatId'), f.select('role', ['CHAIR', 'MEMBER', 'MEMBER_SECRETARY', 'SPECIAL_INVITEE'], undefined, true), f.bool('voting')] },""",
"""    // M-051: MemberInput(personRef, seatRef, displayName, role, voting) (CommitteeService.java:44); default roles CHAIR, MEMBER,
    // MEMBER_SECRETARY, INVITEE (CaseConfig.java:318-320), checked at CommitteeService.java:102
    { id: 'member', label: 'act.addMember', path: '/committees/{committeeId}/members', fields: [f.text('personRef', 'fld.personId'), f.text('seatRef', 'fld.seatId'), f.text('displayName'), f.select('role', ['CHAIR', 'MEMBER', 'MEMBER_SECRETARY', 'INVITEE'], undefined, true), f.bool('voting')] },"""),
("""    { id: 'agenda', label: 'act.addAgenda', path: '/meetings/{meetingId}/agenda', fields: [f.text('title', undefined, true), f.text('caseId'), f.text('presenter'), f.area('note')] },""",
"""    // M-051: AgendaRequest(title, description, linkedType, linkedRef, presenter) (MeetingRestPort.java:29)
    { id: 'agenda', label: 'act.addAgenda', path: '/meetings/{meetingId}/agenda', fields: [f.text('title', undefined, true), f.text('linkedType', 'fld.linkedType'), f.text('linkedRef', 'fld.linkedRef'), f.text('presenter'), f.area('description')] },"""),
("""    { id: 'attendance', label: 'act.markAttendance', method: 'PUT', path: '/meetings/{meetingId}/attendance', fields: [f.text('memberId', undefined, true), f.select('presence', ['IN_PERSON', 'VIRTUAL', 'PROXY', 'ABSENT'], undefined, true), f.text('proxyFor')] },""",
"""    // M-051: AttendanceRequest(memberId, attendeeRef, attendance, proxyFor) (MeetingRestPort.java:32); attendance PRESENT | ABSENT | LEAVE |
    // PROXY (MeetingService.java:40, :341)
    { id: 'attendance', label: 'act.markAttendance', method: 'PUT', path: '/meetings/{meetingId}/attendance', fields: [f.text('memberId', undefined, true), f.select('attendance', ['PRESENT', 'ABSENT', 'LEAVE', 'PROXY'], 'fld.presence', true), f.text('proxyFor')] },"""),
("""    { id: 'decision', label: 'act.recordDecision', path: '/meetings/{meetingId}/decisions', fields: [f.text('agendaItemId', undefined, true), f.area('text', 'fld.decisionText', true), f.json('votes'), f.area('dissent')] },
    { id: 'adjourn', label: 'act.adjourn', path: '/meetings/{meetingId}/adjourn', fields: [f.dt('to', 'fld.newDate', true)], reason: 'body' },""",
"""    // M-051: DecisionInput(itemId, text, outcome, votesFor, votesAgainst, abstentions, dissent: list of objects) (MeetingService.java:76-77);
    // outcome required, one of APPROVED, REJECTED, DEFERRED, NOTED, MODIFIED, WITHDRAWN (:41, :445-447)
    { id: 'decision', label: 'act.recordDecision', path: '/meetings/{meetingId}/decisions', fields: [f.text('itemId', 'fld.agendaItemId', true), f.area('text', 'fld.decisionText', true), f.select('outcome', ['APPROVED', 'REJECTED', 'DEFERRED', 'NOTED', 'MODIFIED', 'WITHDRAWN'], undefined, true), f.num('votesFor', 'fld.votesFor'), f.num('votesAgainst', 'fld.votesAgainst'), f.num('abstentions', 'fld.abstentions'), f.json('dissent')] },
    // M-051: AdjournRequest(reason, newAt) (MeetingRestPort.java:35)
    { id: 'adjourn', label: 'act.adjourn', path: '/meetings/{meetingId}/adjourn', fields: [f.dt('newAt', 'fld.newDate', true)], reason: 'body' },"""),
("""    { id: 'approve', label: 'act.approveMinutes', path: '/meetings/{meetingId}/minutes/approve', fields: [f.text('presentedHash', undefined, true), f.text('esignRef')], hint: 'mod.committees.minutesHashHint' },""",
"""    // M-051: ApproveMinutesRequest(version, contentHash, esignRef) (MeetingRestPort.java:38; MeetingService.java:543-551)
    { id: 'approve', label: 'act.approveMinutes', path: '/meetings/{meetingId}/minutes/approve', fields: [f.num('version', undefined, true), f.text('contentHash', 'fld.presentedHash', true), f.text('esignRef')], hint: 'mod.committees.minutesHashHint' },"""),
("""    { id: 'atr', label: 'act.submitAtr', path: '/action-items/{actionItemId}/atr', fields: [f.area('text', 'fld.atrText', true), f.json('documentIds')] },
    { id: 'verify', label: 'act.verifyAtr', path: '/action-items/{actionItemId}/verify', fields: [f.select('decision', ['ACCEPT', 'RETURN'], undefined, true), f.area('remarks')] },
    { id: 'reassign', label: 'act.reassign', path: '/action-items/{actionItemId}/reassign', fields: [f.text('ownerSeat', undefined, true)], reason: 'body' },""",
"""    // M-051: AtrBody(atrText) (MeetingController.java:57); VerifyRequest(accept, reason) (MeetingRestPort.java:41);
    // ReassignBody(ownerSeat, ownerPerson) (MeetingController.java:54)
    { id: 'atr', label: 'act.submitAtr', path: '/action-items/{actionItemId}/atr', fields: [f.area('atrText', 'fld.atrText', true)] },
    { id: 'verify', label: 'act.verifyAtr', path: '/action-items/{actionItemId}/verify', fields: [f.bool('accept', 'fld.acceptAtr'), f.area('reason')] },
    { id: 'reassign', label: 'act.reassign', path: '/action-items/{actionItemId}/reassign', fields: [f.text('ownerSeat', undefined, true), f.text('ownerPerson', 'fld.person')], reason: 'body' },"""),
("""    { id: 'transfer', label: 'act.transfer', path: '/references/{referenceId}/transfer', fields: [f.text('orgUnit'), f.text('seat')], reason: 'body' },""",
"""    // M-051: TransferRequest(toOrgUnit, toSeat, remarks) (ReferenceRestPort.java:26)
    { id: 'transfer', label: 'act.transfer', path: '/references/{referenceId}/transfer', fields: [f.text('toOrgUnit', 'fld.orgUnit'), f.text('toSeat', 'fld.seat')], reason: 'body' },"""),
("""    { id: 'interim', label: 'act.interimReply', path: '/references/{referenceId}/interim-reply', fields: [f.area('text', 'fld.replyText', true), f.text('channel')] },""",
"""    // M-051: ReplyRequest(text, replyRef) (ReferenceRestPort.java:29), also for the reply below
    { id: 'interim', label: 'act.interimReply', path: '/references/{referenceId}/interim-reply', fields: [f.area('text', 'fld.replyText', true), f.text('replyRef', 'fld.replyRef')] },"""),
("""    { id: 'approve', label: 'act.approveReply', path: '/references/{referenceId}/approve-reply', fields: [f.text('presentedHash', undefined, true)], hint: 'mod.references.approveHint' },
    { id: 'reply', label: 'act.sendReply', path: '/references/{referenceId}/reply', fields: [f.area('text', 'fld.replyText', true), f.text('channel')] },""",
"""    // M-051: ApproveBody(draftHash) (ReferenceController.java:39)
    { id: 'approve', label: 'act.approveReply', path: '/references/{referenceId}/approve-reply', fields: [f.text('draftHash', 'fld.presentedHash', true)], hint: 'mod.references.approveHint' },
    { id: 'reply', label: 'act.sendReply', path: '/references/{referenceId}/reply', fields: [f.area('text', 'fld.replyText', true), f.text('replyRef', 'fld.replyRef')] },"""),
("""    { id: 'followUp', label: 'act.followUp', path: '/references/{referenceId}/follow-ups', fields: [f.text('subject', undefined, true), f.area('body'), f.dt('receivedAt')] },""",
"""    // M-051: FollowUpRequest(subject, fixedDueAt, attributes) (ReferenceRestPort.java:35)
    { id: 'followUp', label: 'act.followUp', path: '/references/{referenceId}/follow-ups', fields: [f.text('subject', undefined, true), f.dt('fixedDueAt'), f.json('attributes')] },"""),
]
for old, new in R:
    sub(p, old, new)
