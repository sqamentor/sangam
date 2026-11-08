# SPDX-License-Identifier: Apache-2.0
# Routes of sangam-case, derived from the sangam-rest controllers in org.sangam.rest.modules.cases (WS-REST final integration wave).
# Executed inside gen_openapi.py: schema(), op(), q(), pp() and TAGS come from there.

TAGS.append(('CaseFiles', 'Case files, parties, hearings, deadlines (N04); committees, meetings, minutes, action items and references (N16). sangam-case'))

schema('CfPublishedConfig', 'PublishedConfig (Java CaseFileController.PublishedConfig).',
       'kind:s version:int')
schema('CfConfigEntry', 'ConfigEntry (Java CaseConfigRegistry.ConfigEntry).',
       'tenantId:s kind:s key:s version:int contentHash:s active:bool definedBy:s definedAt:dt definition:obj')
schema('CfOpenRequest', 'OpenRequest (Java CaseFileService.OpenRequest).',
       'caseType:s title:s jurisdiction:s orgUnit:s orgCode:s ownerSeat:s priority:s confidentiality:s businessKey:s attributes:obj cmmnCaseInstanceId:s processInstanceId:s')
schema('CfCaseFile', 'CaseFile (Java CaseFileService.CaseFile).',
       'tenantId:s caseId:s caseType:s typeVersion:int caseNumber:s title:s status:s stage:s jurisdiction:s orgUnit:s ownerSeat:s priority:s confidentiality:s businessKey:s cmmnCaseInstanceId:s processInstanceId:s mergedInto:s splitFrom:s attributes:obj openedBy:s openedAt:dt closedAt:dt closedBy:s closeReason:s rowVersion:long')
schema('CfCaseView', 'CaseView (Java CaseFileRestPort.CaseView).',
       'caseFile:CfCaseFile parties:list:CfCaseParty records:list:CfLinkedRecord folders:list:CfFolder documents:list:CfDocument links:list:CfCaseLink deadlines:list:CfDeadline hearings:list:CfHearing partiesMasked:bool')
schema('CfCaseParty', 'CaseParty (Java PartyService.CaseParty).',
       'tenantId:s casePartyId:s caseId:s partyId:s role:s capacity:s representedBy:s masked:bool validFrom:dt validTo:dt addedBy:s addedAt:dt party:CfParty')
schema('CfParty', 'Party (Java PartyService.Party).',
       'tenantId:s partyId:s kind:s displayName:s identifiers:obj contact:obj externalRef:s createdBy:s createdAt:dt')
schema('CfLinkedRecord', 'LinkedRecord (Java CaseContentsService.LinkedRecord).',
       'tenantId:s recordId:s caseId:s recordType:s recordRef:s relation:s title:s addedBy:s addedAt:dt removedAt:dt')
schema('CfFolder', 'Folder (Java CaseContentsService.Folder).',
       'tenantId:s folderId:s caseId:s parentFolderId:s name:s createdBy:s createdAt:dt')
schema('CfDocument', 'Document (Java CaseContentsService.Document).',
       'tenantId:s documentId:s caseId:s folderId:s contentItemId:s title:s docType:s confidentiality:s exhibit:bool exhibitRef:s addedBy:s addedAt:dt removedAt:dt')
schema('CfCaseLink', 'CaseLink (Java CaseLinkService.CaseLink).',
       'tenantId:s linkId:s fromCase:s toCase:s linkType:s reason:s createdBy:s createdAt:dt')
schema('CfDeadline', 'Deadline (Java DeadlineService.Deadline).',
       'tenantId:s id:s scopeType:s scopeId:s kind:s ownerSeat:s ownerPerson:s startedAt:dt dueAt:dt warnAt:dt state:s escalationLevel:int ladder:list:CfEscalationStep lastEscalatedAt:dt metAt:dt extensionCount:int calendar:s nextCheckAt:dt')
schema('CfEscalationStep', 'EscalationStep (Java CaseConfig.EscalationStep).',
       'after:s target:s messageKind:s')
schema('CfHearing', 'Hearing (Java HearingService.Hearing).',
       'tenantId:s hearingId:s caseId:s kind:s forum:s scheduledAt:dt status:s outcome:s outcomeText:s nextDate:dt externalRef:s recordedBy:s recordedAt:dt')
schema('CfUpdateRequest', 'UpdateRequest (Java CaseFileRestPort.UpdateRequest).',
       'attributes:obj replace:bool title:s priority:s confidentiality:s rowVersion:long')
schema('CfStageRequest', 'StageRequest (Java CaseFileRestPort.StageRequest).',
       'stage:s reason:s rowVersion:long')
schema('CfCaseFileRestPortAssignRequest', 'AssignRequest (Java CaseFileRestPort.AssignRequest).',
       'ownerSeat:s orgUnit:s rowVersion:long')
schema('CfReasonRequest', 'ReasonRequest (Java CaseFileRestPort.ReasonRequest).',
       'reason:s stage:s rowVersion:long')
schema('CfMilestoneRequest', 'MilestoneRequest (Java CaseFileController.MilestoneRequest).',
       'milestone:s occurredAt:dt')
schema('CfPartyInput', 'PartyInput (Java PartyService.PartyInput).',
       'kind:s displayName:s identifiers:obj contact:obj externalRef:s')
schema('CfAddPartyRequest', 'AddPartyRequest (Java CaseFileRestPort.AddPartyRequest).',
       'partyId:s newParty:CfPartyInput role:s capacity:s representedBy:s masked:bool')
schema('CfPartyResult', 'PartyResult (Java CaseFileRestPort.PartyResult).',
       'caseParty:CfCaseParty conflicts:list:CfConflict')
schema('CfConflict', 'Conflict (Java PartyService.Conflict).',
       'kind:s caseId:s caseNumber:s partyId:s role:s otherRole:s')
schema('CfDuplicateCandidate', 'DuplicateCandidate (Java PartyService.DuplicateCandidate).',
       'caseId:s caseNumber:s title:s sharedParties:list:s')
schema('CfRecordRequest', 'RecordRequest (Java CaseFileRestPort.RecordRequest).',
       'recordType:s recordRef:s relation:s title:s')
schema('CfFolderRequest', 'FolderRequest (Java CaseFileController.FolderRequest).',
       'parentFolderId:s name:s')
schema('CfDocumentRequest', 'DocumentRequest (Java CaseFileRestPort.DocumentRequest).',
       'folderId:s contentItemId:s title:s docType:s confidentiality:s exhibit:bool exhibitRef:s')
schema('CfNote', 'Note (Java CaseContentsService.Note).',
       'tenantId:s noteId:s caseId:s body:s visibility:s author:s createdAt:dt supersedes:s superseded:bool')
schema('CfNoteRequest', 'NoteRequest (Java CaseFileController.NoteRequest).',
       'body:s visibility:s')
schema('CfEntry', 'Entry (Java CaseTimelineService.Entry).',
       'at:dt source:s type:s summary:s actor:s scopeId:s detail:obj')
schema('CfLinkRequest', 'LinkRequest (Java CaseFileRestPort.LinkRequest).',
       'toCaseId:s linkType:s reason:s')
schema('CfMergeRequest', 'MergeRequest (Java CaseFileRestPort.MergeRequest).',
       'mergedCaseId:s reason:s')
schema('CfSplitRequest', 'SplitRequest (Java CaseLinkService.SplitRequest).',
       'newCaseType:s title:s attributes:obj movePartyIds:list:s copyRecordIds:list:s moveDocumentIds:list:s reason:s')
schema('CfHearingRequest', 'HearingRequest (Java CaseFileRestPort.HearingRequest).',
       'kind:s forum:s scheduledAt:dt externalRef:s')
schema('CfOutcomeRequest', 'OutcomeRequest (Java CaseFileRestPort.OutcomeRequest).',
       'outcome:s outcomeText:s adjourned:bool nextDate:dt')
schema('CfCaseFileRestPortExtendRequest', 'ExtendRequest (Java CaseFileRestPort.ExtendRequest).',
       'newDueAt:dt reason:s')
schema('CfConstituteRequest', 'ConstituteRequest (Java CommitteeService.ConstituteRequest).',
       'kind:s name:s orgUnit:s constitutionRef:s validFrom:dt validTo:dt quorumRule:s chairRequired:bool minNoticeHours:int terms:obj members:list:CfMemberInput')
schema('CfMemberInput', 'MemberInput (Java CommitteeService.MemberInput).',
       'personRef:s seatRef:s displayName:s role:s voting:bool')
schema('CfCommittee', 'Committee (Java CommitteeService.Committee).',
       'tenantId:s committeeId:s kind:s name:s orgUnit:s constitutionRef:s constitutedBy:s constitutedAt:dt validFrom:dt validTo:dt quorumRule:s chairRequired:bool minNoticeHours:int terms:obj status:s rowVersion:long')
schema('CfCommitteeView', 'CommitteeView (Java MeetingRestPort.CommitteeView).',
       'committee:CfCommittee members:list:CfMember quorumRequired:int')
schema('CfMember', 'Member (Java CommitteeService.Member).',
       'tenantId:s memberId:s committeeId:s personRef:s seatRef:s displayName:s role:s voting:bool validFrom:dt validTo:dt')
schema('CfReconstituteRequest', 'ReconstituteRequest (Java MeetingRestPort.ReconstituteRequest).',
       'constitutionRef:s members:list:CfMemberInput')
schema('CfMeetingControllerReasonBody', 'ReasonBody (Java MeetingController.ReasonBody).',
       'reason:s')
schema('CfActionItemServicePendency', 'Pendency (Java ActionItemService.Pendency).',
       'ownerSeat:s open:int atrSubmitted:int overdue:int')
schema('CfScheduleRequest', 'ScheduleRequest (Java MeetingService.ScheduleRequest).',
       'committeeId:s title:s scheduledAt:dt endsAt:dt venue:s mode:s urgentReason:s caseId:s')
schema('CfMeeting', 'Meeting (Java MeetingService.Meeting).',
       'tenantId:s meetingId:s committeeId:s meetingNumber:s title:s scheduledAt:dt endsAt:dt venue:s venueBookingRef:s mode:s status:s quorumRequired:int quorumPresent:int chairMemberId:s urgentReason:s caseId:s adjournedTo:s startedAt:dt endedAt:dt createdBy:s createdAt:dt rowVersion:long')
schema('CfMeetingView', 'MeetingView (Java MeetingRestPort.MeetingView).',
       'meeting:CfMeeting agenda:list:CfAgendaItem attendance:list:CfAttendance quorum:CfQuorumStatus decisions:list:CfDecision minutes:CfMinutes actions:list:CfActionItem')
schema('CfAgendaItem', 'AgendaItem (Java MeetingService.AgendaItem).',
       'tenantId:s itemId:s meetingId:s seq:int title:s description:s linkedType:s linkedRef:s presenter:s status:s carriedFrom:s addedBy:s addedAt:dt')
schema('CfAttendance', 'Attendance (Java MeetingService.Attendance).',
       'meetingId:s attendeeRef:s memberId:s attendance:s voting:bool proxyFor:s markedBy:s markedAt:dt')
schema('CfQuorumStatus', 'QuorumStatus (Java MeetingService.QuorumStatus).',
       'required:int presentVoting:int chairPresent:bool chairRequired:bool met:bool')
schema('CfDecision', 'Decision (Java MeetingService.Decision).',
       'tenantId:s decisionId:s meetingId:s itemId:s text:s outcome:s votesFor:int votesAgainst:int abstentions:int dissent:list:any recordedBy:s recordedAt:dt')
schema('CfMinutes', 'Minutes (Java MeetingService.Minutes).',
       'meetingId:s version:int body:s status:s contentHash:s draftedBy:s draftedAt:dt comments:list:any approvedBy:s approvedAt:dt esignRef:s circulatedAt:dt')
schema('CfActionItem', 'ActionItem (Java ActionItemService.ActionItem).',
       'tenantId:s actionId:s meetingId:s decisionId:s committeeId:s title:s ownerSeat:s ownerPerson:s dueAt:dt status:s atrText:s atrBy:s atrAt:dt verifiedBy:s verifiedAt:dt returnReason:s deadlineId:s createdBy:s createdAt:dt rowVersion:long')
schema('CfRescheduleRequest', 'RescheduleRequest (Java MeetingRestPort.RescheduleRequest).',
       'scheduledAt:dt endsAt:dt venue:s reason:s')
schema('CfAdjournRequest', 'AdjournRequest (Java MeetingRestPort.AdjournRequest).',
       'reason:s newAt:dt')
schema('CfAgendaRequest', 'AgendaRequest (Java MeetingRestPort.AgendaRequest).',
       'title:s description:s linkedType:s linkedRef:s presenter:s')
schema('CfAgendaOrder', 'AgendaOrder (Java MeetingController.AgendaOrder).',
       'itemIds:list:s')
schema('CfCarryResult', 'CarryResult (Java MeetingController.CarryResult).',
       'carried:int')
schema('CfNotice', 'Notice (Java MeetingService.Notice).',
       'noticeId:s meetingId:s kind:s recipientRef:s channel:s contentHash:s deliveryRef:s sentBy:s sentAt:dt acknowledgedAt:dt')
schema('CfAttendanceRequest', 'AttendanceRequest (Java MeetingRestPort.AttendanceRequest).',
       'memberId:s attendeeRef:s attendance:s proxyFor:s')
schema('CfDecisionInput', 'DecisionInput (Java MeetingService.DecisionInput).',
       'itemId:s text:s outcome:s votesFor:int votesAgainst:int abstentions:int dissent:list:obj')
schema('CfTextBody', 'TextBody (Java MeetingController.TextBody).',
       'text:s')
schema('CfApproveMinutesRequest', 'ApproveMinutesRequest (Java MeetingRestPort.ApproveMinutesRequest).',
       'version:int contentHash:s esignRef:s')
schema('CfCreateRequest', 'CreateRequest (Java ActionItemService.CreateRequest).',
       'meetingId:s decisionId:s committeeId:s title:s ownerSeat:s ownerPerson:s dueAt:dt period:s')
schema('CfAtrBody', 'AtrBody (Java MeetingController.AtrBody).',
       'atrText:s')
schema('CfVerifyRequest', 'VerifyRequest (Java MeetingRestPort.VerifyRequest).',
       'accept:bool reason:s')
schema('CfReassignBody', 'ReassignBody (Java MeetingController.ReassignBody).',
       'ownerSeat:s ownerPerson:s')
schema('CfRegisterRequest', 'RegisterRequest (Java ReferenceService.RegisterRequest).',
       'kind:s sourceName:s sourceCategory:s sourceRef:s subject:s body:s receivedAt:dt receivedVia:s priority:s orgUnit:s orgCode:s assignedSeat:s assignedPerson:s fixedDueAt:dt attributes:obj parentReferenceId:s allowDuplicate:bool')
schema('CfReference', 'Reference (Java ReferenceService.Reference).',
       'tenantId:s referenceId:s kind:s kindVersion:int number:s sourceName:s sourceCategory:s sourceRef:s subject:s body:s receivedAt:dt receivedVia:s priority:s status:s assignedSeat:s assignedPerson:s orgUnit:s dueAt:dt deadlineId:s caseId:s parentReferenceId:s attributes:obj replyDraft:s replyDraftedBy:s replyText:s replyRef:s repliedBy:s repliedAt:dt replyApprovedBy:s late:bool closedAt:dt closeReason:s createdBy:s rowVersion:long')
schema('CfReferenceServicePendency', 'Pendency (Java ReferenceService.Pendency).',
       'kind:s seat:s open:int overdue:int dueSoon:int repliedLate:int')
schema('CfReferenceView', 'ReferenceView (Java ReferenceRestPort.ReferenceView).',
       'reference:CfReference moves:list:CfMove followUps:list:CfReference deadlines:list:CfDeadline history:list:CfEvent')
schema('CfMove', 'Move (Java ReferenceService.Move).',
       'moveId:s referenceId:s fromSeat:s toSeat:s kind:s remarks:s movedBy:s movedAt:dt')
schema('CfEvent', 'Event (Java CaseEvents.Event).',
       'eventId:s scopeType:s scopeId:s eventType:s occurredAt:dt actor:s summary:s payload:obj source:s')
schema('CfReferenceRestPortAssignRequest', 'AssignRequest (Java ReferenceRestPort.AssignRequest).',
       'seat:s person:s remarks:s')
schema('CfTransferRequest', 'TransferRequest (Java ReferenceRestPort.TransferRequest).',
       'toOrgUnit:s toSeat:s remarks:s')
schema('CfReplyRequest', 'ReplyRequest (Java ReferenceRestPort.ReplyRequest).',
       'text:s replyRef:s')
schema('CfDraftBody', 'DraftBody (Java ReferenceController.DraftBody).',
       'text:s')
schema('CfApproveBody', 'ApproveBody (Java ReferenceController.ApproveBody).',
       'draftHash:s')
schema('CfReferenceRestPortExtendRequest', 'ExtendRequest (Java ReferenceRestPort.ExtendRequest).',
       'newDueAt:dt reason:s')
schema('CfFollowUpRequest', 'FollowUpRequest (Java ReferenceRestPort.FollowUpRequest).',
       'subject:s fixedDueAt:dt attributes:obj')
schema('CfReferenceControllerReasonBody', 'ReasonBody (Java ReferenceController.ReasonBody).',
       'reason:s')

op('put', '/case-config/{kind}', 'caseFilePublishConfig', 'CaseFiles', 'Publish a case type, reference kind or committee kind (versioned)', 'Publish a case type, reference kind or committee kind (versioned).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('kind')], body={"required": True, "content": {"application/json": {"schema": typ('obj')}}}, resp=(200, 'CfPublishedConfig'), cmd='case-file.publish-config', perm='case.admin')
op('get', '/case-config/{kind}', 'caseFileConfigs', 'CaseFiles', 'List the published configuration of a kind', 'List the published configuration of a kind.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('kind')], resp=(200, 'array:CfConfigEntry'), perm='case.read')
op('post', '/case-files', 'caseFileOpen', 'CaseFiles', 'Open a case file', 'Open a case file.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   body='CfOpenRequest', resp=(201, 'CfCaseFile'), cmd='case-file.open', perm='case.write', created=True)
op('get', '/case-files', 'caseFileList', 'CaseFiles', 'List case files visible to the caller (VI.4 predicate)', 'List case files visible to the caller (VI.4 predicate).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[q('caseType', 's'), q('status', 's'), q('stage', 's'), q('orgUnit', 's'), q('ownerSeat', 's'), q('text', 's'), q('partyId', 's'), q('openedFrom', 'dt'), q('openedTo', 'dt')], resp=(200, 'array:CfCaseFile'), perm='case.read', list_=True)
op('get', '/case-files/{id}', 'caseFileGet', 'CaseFiles', 'The whole case file: parties (masked without READ_SENSITIVE), records, documents, links, deadlines, hearings', 'The whole case file: parties (masked without READ_SENSITIVE), records, documents, links, deadlines, hearings.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'CfCaseView'), perm='case.read')
op('get', '/case-files/by-number/{number}', 'caseFileByNumber', 'CaseFiles', 'A case file by its human-readable number', 'A case file by its human-readable number.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('number')], resp=(200, 'CfCaseView'), perm='case.read')
op('patch', '/case-files/{id}', 'caseFileUpdate', 'CaseFiles', 'Update attributes, title, priority or confidentiality (optimistic rowVersion)', 'Update attributes, title, priority or confidentiality (optimistic rowVersion).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfUpdateRequest', resp=(200, 'CfCaseFile'), cmd='case-file.update', perm='case.write')
op('post', '/case-files/{id}/stage', 'caseFileStage', 'CaseFiles', 'Move the case to another stage', 'Move the case to another stage.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfStageRequest', resp=(200, 'CfCaseFile'), cmd='case-file.stage', perm='case.write')
op('post', '/case-files/{id}/assign', 'caseFileAssign', 'CaseFiles', 'Assign the owner seat and org unit', 'Assign the owner seat and org unit.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfCaseFileRestPortAssignRequest', resp=(200, 'CfCaseFile'), cmd='case-file.assign', perm='case.write')
op('post', '/case-files/{id}/close', 'caseFileClose', 'CaseFiles', 'Close the case with a reason', 'Close the case with a reason.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfReasonRequest', resp=(200, 'CfCaseFile'), cmd='case-file.close', perm='case.write')
op('post', '/case-files/{id}/reopen', 'caseFileReopen', 'CaseFiles', 'Reopen a closed case with a reason', 'Reopen a closed case with a reason.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfReasonRequest', resp=(200, 'CfCaseFile'), cmd='case-file.reopen', perm='case.write')
op('post', '/case-files/{id}/milestones', 'caseFileMilestone', 'CaseFiles', 'Record a milestone on the case', 'Record a milestone on the case.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfMilestoneRequest', resp=(204, None), cmd='case-file.milestone', perm='case.write')
op('post', '/case-parties', 'caseFileRegisterParty', 'CaseFiles', "Register a party in the case module's party book", "Register a party in the case module's party book.", ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   body='CfPartyInput', resp=(201, 'CfParty'), cmd='case-file.register-party', perm='case.write', created=True)
op('get', '/case-parties/candidates', 'caseFilePartyCandidates', 'CaseFiles', 'Search party candidates by identifiers or name (duplicate check before registering)', 'Search party candidates by identifiers or name (duplicate check before registering).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[q('name', 's')], resp=(200, 'array:CfParty'), perm='case.read')
op('get', '/case-parties/{id}/cases', 'caseFileCasesOfParty', 'CaseFiles', 'The cases a party appears in', 'The cases a party appears in.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'array:CfCaseParty'), perm='case.read')
op('post', '/case-files/{id}/parties', 'caseFileAddParty', 'CaseFiles', 'Add a party to the case (with conflict-of-interest results)', 'Add a party to the case (with conflict-of-interest results).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfAddPartyRequest', resp=(200, 'CfPartyResult'), cmd='case-file.add-party', perm='case.write')
op('delete', '/case-files/{id}/parties/{casePartyId}', 'caseFileRemoveParty', 'CaseFiles', 'Remove a party from the case', 'Remove a party from the case.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), pp('casePartyId'), q('reason', 's')], resp=(200, 'CfCaseParty'), cmd='case-file.remove-party', perm='case.write')
op('get', '/case-files/{id}/conflicts', 'caseFileConflicts', 'CaseFiles', 'Conflicts of a (candidate) party in a role', 'Conflicts of a (candidate) party in a role.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('partyId', 's'), q('role', 's')], resp=(200, 'array:CfConflict'), perm='case.read')
op('get', '/case-files/{id}/duplicates', 'caseFileDuplicates', 'CaseFiles', 'Possible duplicate cases', 'Possible duplicate cases.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'array:CfDuplicateCandidate'), perm='case.read')
op('post', '/case-files/{id}/records', 'caseFileLinkRecord', 'CaseFiles', 'Link a business record (process instance, document, external record) to the case', 'Link a business record (process instance, document, external record) to the case.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfRecordRequest', resp=(200, 'CfLinkedRecord'), cmd='case-file.link-record', perm='case.write')
op('delete', '/case-files/{id}/records/{recordId}', 'caseFileUnlinkRecord', 'CaseFiles', 'Unlink a record from the case', 'Unlink a record from the case.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), pp('recordId')], resp=(204, None), cmd='case-file.unlink-record', perm='case.write')
op('post', '/case-files/{id}/folders', 'caseFileCreateFolder', 'CaseFiles', 'Create a folder in the case file', 'Create a folder in the case file.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfFolderRequest', resp=(200, 'CfFolder'), cmd='case-file.create-folder', perm='case.write')
op('post', '/case-files/{id}/documents', 'caseFileAddDocument', 'CaseFiles', 'Attach or upload a document to the case', 'JSON body (CfDocumentRequest with contentItemId): attach an existing content item. Multipart (file + metadata fields): upload a new document into the content store (FC-40, K12). Both run in one transaction with the case event.', ['N04', 'N16', 'VI.4', 'R41', 'R42', 'FC-40'],
   params=[pp('id')], body={"required": True, "content": {"application/json": {"schema": R('CfDocumentRequest')}, "multipart/form-data": {"schema": {"type": "object", "required": ['file'], "properties": {'file': {"type": "string", "contentMediaType": "application/octet-stream"}, 'folderId': typ('s'), 'title': typ('s'), 'docType': typ('s'), 'confidentiality': typ('s'), 'exhibit': typ('bool'), 'exhibitRef': typ('s')}}}}}, resp=(200, 'CfDocument'), cmd='case-file.add-document', perm='case.write')
op('get', '/case-files/{id}/documents/{docId}/content', 'caseFileDocumentContent', 'CaseFiles', 'Download a case document version (streamed)', 'Download a case document version (streamed).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), pp('docId'), q('version', 'int')], resp=(200, {"application/octet-stream": {"schema": {"type": "string", "contentMediaType": "application/octet-stream"}}}), perm='case.read')
op('delete', '/case-files/{id}/documents/{docId}', 'caseFileRemoveDocument', 'CaseFiles', 'Remove a document from the case', 'Remove a document from the case.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), pp('docId'), q('reason', 's')], resp=(204, None), cmd='case-file.remove-document', perm='case.write')
op('get', '/case-files/{id}/notes', 'caseFileNotes', 'CaseFiles', 'Notes on the case', 'Notes on the case.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'array:CfNote'), perm='case.read')
op('post', '/case-files/{id}/notes', 'caseFileAddNote', 'CaseFiles', 'Add a note', 'Add a note.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfNoteRequest', resp=(200, 'CfNote'), cmd='case-file.add-note', perm='case.write')
op('put', '/case-files/{id}/notes/{noteId}', 'caseFileEditNote', 'CaseFiles', 'Edit a note (versioned)', 'Edit a note (versioned).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), pp('noteId')], body='CfNoteRequest', resp=(200, 'CfNote'), cmd='case-file.edit-note', perm='case.write')
op('get', '/case-files/{id}/timeline', 'caseFileTimeline', 'CaseFiles', 'The case timeline, optionally merged with the engine history (read model, VI.4 predicate)', 'The case timeline, optionally merged with the engine history (read model, VI.4 predicate).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('limit', 'int'), q('engine', 'bool')], resp=(200, 'array:CfEntry'), perm='case.read')
op('post', '/case-files/{id}/links', 'caseFileLink', 'CaseFiles', 'Link the case to a related case', 'Link the case to a related case.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfLinkRequest', resp=(200, 'CfCaseLink'), cmd='case-file.link', perm='case.write')
op('delete', '/case-files/{id}/links/{linkId}', 'caseFileUnlink', 'CaseFiles', 'Remove a case link', 'Remove a case link.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), pp('linkId')], resp=(204, None), cmd='case-file.unlink', perm='case.write')
op('post', '/case-files/{id}/merge', 'caseFileMerge', 'CaseFiles', 'Merge another case into this one', 'Merge another case into this one.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfMergeRequest', resp=(200, 'CfCaseFile'), cmd='case-file.merge', perm='case.write')
op('post', '/case-files/{id}/split', 'caseFileSplit', 'CaseFiles', 'Split part of this case into a new case', 'Split part of this case into a new case.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfSplitRequest', resp=(200, 'CfCaseFile'), cmd='case-file.split', perm='case.write')
op('post', '/case-files/{id}/hearings', 'caseFileScheduleHearing', 'CaseFiles', 'Schedule a hearing', 'Schedule a hearing.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfHearingRequest', resp=(200, 'CfHearing'), cmd='case-file.schedule-hearing', perm='case.write')
op('post', '/hearings/{id}/outcome', 'caseFileRecordOutcome', 'CaseFiles', 'Record the outcome of a hearing (with adjournment and next date)', 'Record the outcome of a hearing (with adjournment and next date).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfOutcomeRequest', resp=(200, 'CfHearing'), cmd='case-file.record-outcome', perm='case.write')
op('get', '/hearings/cause-list', 'caseFileCauseList', 'CaseFiles', 'Cause list: hearings of a forum in a period', 'Cause list: hearings of a forum in a period.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[q('forum', 's'), q('from', 'dt'), q('to', 'dt')], resp=(200, 'array:CfHearing'), perm='case.read')
op('post', '/case-deadlines/{id}/extend', 'caseFileExtendDeadline', 'CaseFiles', 'Extend a case or reference deadline with a reason', 'Extend a case or reference deadline with a reason.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfCaseFileRestPortExtendRequest', resp=(200, 'CfDeadline'), cmd='case-file.extend-deadline', perm='case.write')
op('get', '/case-deadlines/due', 'caseFileDueBefore', 'CaseFiles', 'Deadlines due before an instant', 'Deadlines due before an instant.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[q('scopeType', 's'), q('before', 'dt'), q('limit', 'int')], resp=(200, 'array:CfDeadline'), perm='case.read')
op('post', '/committees', 'caseMeetingConstitute', 'CaseFiles', 'Constitute a committee with its members', 'Constitute a committee with its members.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   body='CfConstituteRequest', resp=(201, 'CfCommittee'), cmd='case-meeting.constitute', perm='case.write', created=True)
op('get', '/committees', 'caseMeetingCommittees', 'CaseFiles', 'Committees, optionally of one kind', 'Committees, optionally of one kind.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[q('kind', 's'), q('activeOnly', 'bool')], resp=(200, 'array:CfCommittee'), perm='case.read')
op('get', '/committees/{id}', 'caseMeetingCommittee', 'CaseFiles', 'A committee with its members and quorum', 'A committee with its members and quorum.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'CfCommitteeView'), perm='case.read')
op('post', '/committees/{id}/members', 'caseMeetingAddMember', 'CaseFiles', 'Add a member', 'Add a member.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfMemberInput', resp=(200, 'CfMember'), cmd='case-meeting.add-member', perm='case.write')
op('delete', '/committees/{id}/members/{memberId}', 'caseMeetingRemoveMember', 'CaseFiles', 'Remove a member with a reason', 'Remove a member with a reason.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), pp('memberId'), q('reason', 's')], resp=(204, None), cmd='case-meeting.remove-member', perm='case.write')
op('post', '/committees/{id}/reconstitute', 'caseMeetingReconstitute', 'CaseFiles', 'Reconstitute a committee under a new constitution order', 'Reconstitute a committee under a new constitution order.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfReconstituteRequest', resp=(200, 'CfCommittee'), cmd='case-meeting.reconstitute', perm='case.write')
op('post', '/committees/{id}/dissolve', 'caseMeetingDissolve', 'CaseFiles', 'Dissolve a committee', 'Dissolve a committee.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfMeetingControllerReasonBody', resp=(200, 'CfCommittee'), cmd='case-meeting.dissolve', perm='case.write')
op('get', '/committees/{id}/pendency', 'caseMeetingPendency', 'CaseFiles', "Pendency of a committee's action items", "Pendency of a committee's action items.", ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'array:CfActionItemServicePendency'), perm='case.read')
op('post', '/meetings', 'caseMeetingSchedule', 'CaseFiles', 'Schedule a meeting (minimum notice per committee kind; urgent meetings need a reason)', 'Schedule a meeting (minimum notice per committee kind; urgent meetings need a reason).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   body='CfScheduleRequest', resp=(201, 'CfMeeting'), cmd='case-meeting.schedule', perm='case.write', created=True)
op('get', '/meetings', 'caseMeetingMeetings', 'CaseFiles', 'Meetings of a committee in a period', 'Meetings of a committee in a period.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[q('committeeId', 's'), q('from', 'dt'), q('to', 'dt')], resp=(200, 'array:CfMeeting'), perm='case.read')
op('get', '/meetings/{id}', 'caseMeetingMeeting', 'CaseFiles', 'A meeting with agenda, attendance, quorum, decisions, minutes and actions', 'A meeting with agenda, attendance, quorum, decisions, minutes and actions.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'CfMeetingView'), perm='case.read')
op('post', '/meetings/{id}/reschedule', 'caseMeetingReschedule', 'CaseFiles', 'Reschedule a meeting', 'Reschedule a meeting.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfRescheduleRequest', resp=(200, 'CfMeeting'), cmd='case-meeting.reschedule', perm='case.write')
op('post', '/meetings/{id}/cancel', 'caseMeetingCancel', 'CaseFiles', 'Cancel a meeting', 'Cancel a meeting.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfMeetingControllerReasonBody', resp=(200, 'CfMeeting'), cmd='case-meeting.cancel', perm='case.write')
op('post', '/meetings/{id}/start', 'caseMeetingStart', 'CaseFiles', 'Start a meeting (quorum checked)', 'Start a meeting (quorum checked).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'CfMeeting'), cmd='case-meeting.start', perm='case.write')
op('post', '/meetings/{id}/adjourn', 'caseMeetingAdjourn', 'CaseFiles', 'Adjourn a meeting to a new date', 'Adjourn a meeting to a new date.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfAdjournRequest', resp=(200, 'CfMeeting'), cmd='case-meeting.adjourn', perm='case.write')
op('post', '/meetings/{id}/end', 'caseMeetingEnd', 'CaseFiles', 'End a meeting', 'End a meeting.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'CfMeeting'), cmd='case-meeting.end', perm='case.write')
op('post', '/meetings/{id}/agenda', 'caseMeetingAddAgendaItem', 'CaseFiles', 'Add an agenda item', 'Add an agenda item.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfAgendaRequest', resp=(200, 'CfAgendaItem'), cmd='case-meeting.add-agenda-item', perm='case.write')
op('delete', '/meetings/{id}/agenda/{itemId}', 'caseMeetingWithdrawAgendaItem', 'CaseFiles', 'Withdraw an agenda item with a reason', 'Withdraw an agenda item with a reason.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), pp('itemId'), q('reason', 's')], resp=(204, None), cmd='case-meeting.withdraw-agenda-item', perm='case.write')
op('put', '/meetings/{id}/agenda/order', 'caseMeetingReorderAgenda', 'CaseFiles', 'Reorder the agenda', 'Reorder the agenda.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfAgendaOrder', resp=(204, None), cmd='case-meeting.reorder-agenda', perm='case.write')
op('post', '/meetings/{id}/carry-forward', 'caseMeetingCarryForward', 'CaseFiles', 'Carry forward the open agenda items to another meeting', 'Carry forward the open agenda items to another meeting.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('to', 's', required=True)], resp=(200, 'CfCarryResult'), cmd='case-meeting.carry-forward', perm='case.write')
op('post', '/meetings/{id}/notices', 'caseMeetingSendNotices', 'CaseFiles', 'Send meeting notices (outbox, exactly once)', 'Send meeting notices (outbox, exactly once).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('kind', 's')], resp=(200, 'array:CfNotice'), cmd='case-meeting.send-notices', perm='case.write')
op('post', '/meeting-notices/{id}/ack', 'caseMeetingAcknowledgeNotice', 'CaseFiles', 'Acknowledge a meeting notice', 'Acknowledge a meeting notice.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(204, None), cmd='case-meeting.acknowledge-notice', perm='case.read')
op('put', '/meetings/{id}/attendance', 'caseMeetingMarkAttendance', 'CaseFiles', 'Mark attendance (in person, virtual, proxy, absent)', 'Mark attendance (in person, virtual, proxy, absent).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfAttendanceRequest', resp=(200, 'CfAttendance'), cmd='case-meeting.mark-attendance', perm='case.write')
op('get', '/meetings/{id}/quorum', 'caseMeetingQuorum', 'CaseFiles', 'Current quorum status', 'Current quorum status.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'CfQuorumStatus'), perm='case.read')
op('post', '/meetings/{id}/decisions', 'caseMeetingRecordDecision', 'CaseFiles', 'Record a decision on an agenda item (votes and dissent)', 'Record a decision on an agenda item (votes and dissent).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfDecisionInput', resp=(200, 'CfDecision'), cmd='case-meeting.record-decision', perm='case.write')
op('post', '/meetings/{id}/minutes', 'caseMeetingDraftMinutes', 'CaseFiles', 'Draft the minutes', 'Draft the minutes.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfTextBody', resp=(200, 'CfMinutes'), cmd='case-meeting.draft-minutes', perm='case.write')
op('post', '/meetings/{id}/minutes/circulate', 'caseMeetingCirculateMinutes', 'CaseFiles', 'Circulate the draft minutes to members', 'Circulate the draft minutes to members.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'CfMinutes'), cmd='case-meeting.circulate-minutes', perm='case.write')
op('post', '/meetings/{id}/minutes/comments', 'caseMeetingCommentMinutes', 'CaseFiles', 'Comment on the circulated minutes', 'Comment on the circulated minutes.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfTextBody', resp=(200, 'CfMinutes'), cmd='case-meeting.comment-minutes', perm='case.read')
op('post', '/meetings/{id}/minutes/approve', 'caseMeetingApproveMinutes', 'CaseFiles', 'Approve the minutes (bound to the content hash; optional eSign reference)', 'Approve the minutes (bound to the content hash; optional eSign reference).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfApproveMinutesRequest', resp=(200, 'CfMinutes'), cmd='case-meeting.approve-minutes', perm='case.write')
op('post', '/meetings/{id}/minutes/issue', 'caseMeetingIssueMinutes', 'CaseFiles', 'Issue the approved minutes', 'Issue the approved minutes.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'CfMinutes'), cmd='case-meeting.issue-minutes', perm='case.write')
op('get', '/meetings/{id}/atr', 'caseMeetingAtrReport', 'CaseFiles', 'Action-taken report of a meeting', 'Action-taken report of a meeting.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'array:CfActionItem'), perm='case.read')
op('post', '/meetings/{id}/atr/place', 'caseMeetingPlaceAtr', 'CaseFiles', "Place the action-taken report on the next meeting's agenda", "Place the action-taken report on the next meeting's agenda.", ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id'), q('next', 's', required=True)], resp=(200, 'CfAgendaItem'), cmd='case-meeting.place-atr', perm='case.write')
op('post', '/action-items', 'caseMeetingCreateAction', 'CaseFiles', 'Create an action item from a decision', 'Create an action item from a decision.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   body='CfCreateRequest', resp=(201, 'CfActionItem'), cmd='case-meeting.create-action', perm='case.write', created=True)
op('get', '/action-items', 'caseMeetingActions', 'CaseFiles', 'Action items by meeting, committee or owner seat', 'Action items by meeting, committee or owner seat.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[q('meetingId', 's'), q('committeeId', 's'), q('ownerSeat', 's'), q('open', 'bool')], resp=(200, 'array:CfActionItem'), perm='case.read')
op('post', '/action-items/{id}/start', 'caseMeetingStartAction', 'CaseFiles', 'Start work on an action item', 'Start work on an action item.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'CfActionItem'), cmd='case-meeting.start-action', perm='case.write')
op('post', '/action-items/{id}/atr', 'caseMeetingSubmitAtr', 'CaseFiles', 'Submit the action-taken report', 'Submit the action-taken report.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfAtrBody', resp=(200, 'CfActionItem'), cmd='case-meeting.submit-atr', perm='case.write')
op('post', '/action-items/{id}/verify', 'caseMeetingVerifyAtr', 'CaseFiles', 'Verify (accept or return) the action-taken report', 'Verify (accept or return) the action-taken report.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfVerifyRequest', resp=(200, 'CfActionItem'), cmd='case-meeting.verify-atr', perm='case.write')
op('post', '/action-items/{id}/drop', 'caseMeetingDropAction', 'CaseFiles', 'Drop an action item with a reason', 'Drop an action item with a reason.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfMeetingControllerReasonBody', resp=(200, 'CfActionItem'), cmd='case-meeting.drop-action', perm='case.write')
op('post', '/action-items/{id}/reassign', 'caseMeetingReassignAction', 'CaseFiles', 'Reassign an action item', 'Reassign an action item.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfReassignBody', resp=(200, 'CfActionItem'), cmd='case-meeting.reassign-action', perm='case.write')
op('post', '/references', 'caseReferenceRegister', 'CaseFiles', 'Register a reference (the deadline follows the reference kind)', 'Register a reference (the deadline follows the reference kind).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   body='CfRegisterRequest', resp=(201, 'CfReference'), cmd='case-reference.register', perm='case.write', created=True)
op('get', '/references', 'caseReferenceReferences', 'CaseFiles', 'References visible to the caller (VI.4 predicate)', 'References visible to the caller (VI.4 predicate).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[q('kind', 's'), q('status', 's'), q('seat', 's'), q('orgUnit', 's'), q('sourceCategory', 's'), q('overdue', 'bool'), q('q', 's')], resp=(200, 'array:CfReference'), perm='case.read', list_=True)
op('get', '/references/pendency', 'caseReferencePendency', 'CaseFiles', 'Pendency of references by kind, with the "due soon" window (ISO-8601 duration, default P7D)', 'Pendency of references by kind, with the "due soon" window (ISO-8601 duration, default P7D).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[q('kind', 's'), q('dueSoon', 's')], resp=(200, 'array:CfReferenceServicePendency'), perm='case.read')
op('get', '/references/{id}', 'caseReferenceReference', 'CaseFiles', 'A reference with its movements, follow-ups, deadlines and history', 'A reference with its movements, follow-ups, deadlines and history.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'CfReferenceView'), perm='case.read')
op('post', '/references/{id}/assign', 'caseReferenceAssign', 'CaseFiles', 'Assign to a seat or person', 'Assign to a seat or person.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfReferenceRestPortAssignRequest', resp=(200, 'CfReference'), cmd='case-reference.assign', perm='case.write')
op('post', '/references/{id}/transfer', 'caseReferenceTransfer', 'CaseFiles', 'Transfer to another org unit or seat', 'Transfer to another org unit or seat.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfTransferRequest', resp=(200, 'CfReference'), cmd='case-reference.transfer', perm='case.write')
op('post', '/references/{id}/start', 'caseReferenceStart', 'CaseFiles', 'Start work', 'Start work.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], resp=(200, 'CfReference'), cmd='case-reference.start', perm='case.write')
op('post', '/references/{id}/interim-reply', 'caseReferenceInterimReply', 'CaseFiles', 'Send an interim reply', 'Send an interim reply.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfReplyRequest', resp=(200, 'CfReference'), cmd='case-reference.interim-reply', perm='case.write')
op('post', '/references/{id}/draft-reply', 'caseReferenceDraftReply', 'CaseFiles', 'Save the draft reply', 'Save the draft reply.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfDraftBody', resp=(200, 'CfReference'), cmd='case-reference.draft-reply', perm='case.write')
op('post', '/references/{id}/approve-reply', 'caseReferenceApproveReply', 'CaseFiles', 'Approve the draft reply (bound to its hash)', 'Approve the draft reply (bound to its hash).', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfApproveBody', resp=(200, 'CfReference'), cmd='case-reference.approve-reply', perm='case.write')
op('post', '/references/{id}/reply', 'caseReferenceReply', 'CaseFiles', 'Send the final reply', 'Send the final reply.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfReplyRequest', resp=(200, 'CfReference'), cmd='case-reference.reply', perm='case.write')
op('post', '/references/{id}/extend', 'caseReferenceExtend', 'CaseFiles', 'Extend the deadline with a reason', 'Extend the deadline with a reason.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfReferenceRestPortExtendRequest', resp=(200, 'CfReference'), cmd='case-reference.extend', perm='case.write')
op('post', '/references/{id}/follow-ups', 'caseReferenceFollowUp', 'CaseFiles', 'Register a follow-up reference', 'Register a follow-up reference.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfFollowUpRequest', resp=(200, 'CfReference'), cmd='case-reference.follow-up', perm='case.write')
op('post', '/references/{id}/close', 'caseReferenceClose', 'CaseFiles', 'Close the reference with a reason', 'Close the reference with a reason.', ['N04', 'N16', 'VI.4', 'R41', 'R42'],
   params=[pp('id')], body='CfReferenceControllerReasonBody', resp=(200, 'CfReference'), cmd='case-reference.close', perm='case.write')
