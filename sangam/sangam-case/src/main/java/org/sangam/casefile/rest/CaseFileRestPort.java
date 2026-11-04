// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.rest;

import org.sangam.casefile.config.CaseConfigRegistry;
import org.sangam.casefile.deadline.DeadlineService;
import org.sangam.casefile.file.CaseContentsService;
import org.sangam.casefile.file.CaseFileService;
import org.sangam.casefile.file.CaseLinkService;
import org.sangam.casefile.file.CaseTimelineService;
import org.sangam.casefile.file.HearingService;
import org.sangam.casefile.file.PartyService;
import org.sangam.casefile.rest.CaseApi.ApiContext;
import org.sangam.ports.support.Paging;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** REST port of the case file (N04). Routes are relative to {@code /api/v1}. */
public interface CaseFileRestPort {

    /** The whole file as one read (parties masked unless the caller holds READ_SENSITIVE on the case, R19). */
    record CaseView(CaseFileService.CaseFile caseFile, List<PartyService.CaseParty> parties, List<CaseContentsService.LinkedRecord> records,
                    List<CaseContentsService.Folder> folders, List<CaseContentsService.Document> documents,
                    List<CaseLinkService.CaseLink> links, List<DeadlineService.Deadline> deadlines,
                    List<HearingService.Hearing> hearings, boolean partiesMasked) {
    }

    record UpdateRequest(Map<String, Object> attributes, boolean replace, String title, String priority, String confidentiality,
                         long rowVersion) {
    }

    record StageRequest(String stage, String reason, long rowVersion) {
    }

    record AssignRequest(String ownerSeat, String orgUnit, long rowVersion) {
    }

    record ReasonRequest(String reason, String stage, long rowVersion) {
    }

    record AddPartyRequest(String partyId, PartyService.PartyInput newParty, String role, String capacity, String representedBy,
                           boolean masked) {
    }

    record PartyResult(PartyService.CaseParty caseParty, List<PartyService.Conflict> conflicts) {
    }

    record RecordRequest(String recordType, String recordRef, String relation, String title) {
    }

    record DocumentRequest(String folderId, String contentItemId, String title, String docType, String confidentiality, boolean exhibit,
                           String exhibitRef) {
    }

    record LinkRequest(String toCaseId, String linkType, String reason) {
    }

    record MergeRequest(String mergedCaseId, String reason) {
    }

    record HearingRequest(String kind, String forum, Instant scheduledAt, String externalRef) {
    }

    record OutcomeRequest(String outcome, String outcomeText, boolean adjourned, Instant nextDate) {
    }

    record ExtendRequest(Instant newDueAt, String reason) {
    }

    // configuration            PUT /case-config/{kind}  (kind: CASE_TYPE | REFERENCE_KIND | COMMITTEE_KIND), GET /case-config/{kind}
    int publishConfig(ApiContext ctx, String kind, Map<String, Object> definition);

    List<CaseConfigRegistry.ConfigEntry> configs(ApiContext ctx, String kind);

    // cases                    POST /case-files, GET /case-files, GET /case-files/{id}, GET /case-files/by-number/{number}
    CaseFileService.CaseFile open(ApiContext ctx, CaseFileService.OpenRequest request);

    Paging.PageResult<CaseFileService.CaseFile> list(ApiContext ctx, CaseFileService.Filter filter, Paging.PageRequest page);

    CaseView get(ApiContext ctx, String caseId);

    CaseView byNumber(ApiContext ctx, String caseNumber);

    //                          PATCH /case-files/{id}, POST /case-files/{id}/stage|assign|close|reopen|milestones
    CaseFileService.CaseFile update(ApiContext ctx, String caseId, UpdateRequest request);

    CaseFileService.CaseFile moveStage(ApiContext ctx, String caseId, StageRequest request);

    CaseFileService.CaseFile assign(ApiContext ctx, String caseId, AssignRequest request);

    CaseFileService.CaseFile close(ApiContext ctx, String caseId, ReasonRequest request);

    CaseFileService.CaseFile reopen(ApiContext ctx, String caseId, ReasonRequest request);

    void milestone(ApiContext ctx, String caseId, String milestone, Instant occurredAt);

    // parties                  POST /parties, GET /parties/candidates, POST /case-files/{id}/parties,
    //                          DELETE /case-files/{id}/parties/{casePartyId}, GET /case-files/{id}/conflicts?partyId&role,
    //                          GET /case-files/{id}/duplicates, GET /parties/{id}/cases
    PartyService.Party registerParty(ApiContext ctx, PartyService.PartyInput input);

    List<PartyService.Party> partyCandidates(ApiContext ctx, Map<String, Object> identifiers, String name);

    PartyResult addParty(ApiContext ctx, String caseId, AddPartyRequest request);

    PartyService.CaseParty removeParty(ApiContext ctx, String caseId, String casePartyId, String reason);

    List<PartyService.Conflict> conflicts(ApiContext ctx, String caseId, String partyId, String role);

    List<PartyService.DuplicateCandidate> duplicates(ApiContext ctx, String caseId);

    List<PartyService.CaseParty> casesOfParty(ApiContext ctx, String partyId);

    // linked records           POST /case-files/{id}/records, DELETE /case-files/{id}/records/{recordId}
    CaseContentsService.LinkedRecord linkRecord(ApiContext ctx, String caseId, RecordRequest request);

    void unlinkRecord(ApiContext ctx, String caseId, String recordId);

    // documents                POST /case-files/{id}/folders, POST /case-files/{id}/documents (JSON: attach, multipart: upload),
    //                          GET /case-files/{id}/documents/{docId}/content?version, DELETE /case-files/{id}/documents/{docId}
    CaseContentsService.Folder createFolder(ApiContext ctx, String caseId, String parentFolderId, String name);

    CaseContentsService.Document attachDocument(ApiContext ctx, String caseId, DocumentRequest request);

    CaseContentsService.Document uploadDocument(ApiContext ctx, String caseId, DocumentRequest request, String fileName, String mimeType,
                                                InputStream bytes);

    InputStream openDocument(ApiContext ctx, String caseId, String documentId, int version);

    void removeDocument(ApiContext ctx, String caseId, String documentId, String reason);

    // notes                    GET/POST /case-files/{id}/notes, PUT /case-files/{id}/notes/{noteId}
    List<CaseContentsService.Note> notes(ApiContext ctx, String caseId);

    CaseContentsService.Note addNote(ApiContext ctx, String caseId, String body, String visibility);

    CaseContentsService.Note editNote(ApiContext ctx, String caseId, String noteId, String body);

    // timeline                 GET /case-files/{id}/timeline?limit&engine
    List<CaseTimelineService.Entry> timeline(ApiContext ctx, String caseId, int limit, boolean includeEngineHistory);

    // related cases            POST /case-files/{id}/links, DELETE /case-files/{id}/links/{linkId},
    //                          POST /case-files/{id}/merge, POST /case-files/{id}/split
    CaseLinkService.CaseLink link(ApiContext ctx, String caseId, LinkRequest request);

    void unlink(ApiContext ctx, String caseId, String linkId);

    CaseFileService.CaseFile merge(ApiContext ctx, String survivingCaseId, MergeRequest request);

    CaseFileService.CaseFile split(ApiContext ctx, String caseId, CaseLinkService.SplitRequest request);

    // hearings                 POST /case-files/{id}/hearings, POST /hearings/{id}/outcome, GET /hearings/cause-list?forum&from&to
    HearingService.Hearing scheduleHearing(ApiContext ctx, String caseId, HearingRequest request);

    HearingService.Hearing recordOutcome(ApiContext ctx, String hearingId, OutcomeRequest request);

    List<HearingService.Hearing> causeList(ApiContext ctx, String forum, Instant from, Instant to);

    // deadlines                POST /deadlines/{id}/extend, GET /deadlines/due?before
    DeadlineService.Deadline extendDeadline(ApiContext ctx, String deadlineId, ExtendRequest request);

    List<DeadlineService.Deadline> dueBefore(ApiContext ctx, String scopeType, Instant before, int limit);
}
