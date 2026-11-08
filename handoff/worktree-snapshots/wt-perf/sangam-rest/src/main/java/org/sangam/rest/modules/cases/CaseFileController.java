// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.cases;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.sangam.casefile.config.CaseConfigRegistry;
import org.sangam.casefile.deadline.DeadlineService;
import org.sangam.casefile.file.CaseContentsService;
import org.sangam.casefile.file.CaseFileService;
import org.sangam.casefile.file.CaseLinkService;
import org.sangam.casefile.file.CaseTimelineService;
import org.sangam.casefile.file.HearingService;
import org.sangam.casefile.file.PartyService;
import org.sangam.casefile.rest.CaseFileRestPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Case files (N04): configuration, files, parties, contents, notes, timeline, related cases, hearings and deadlines.
 * Routes follow the sangam-case README, except {@code /parties*} → {@code /case-parties*} and {@code /deadlines/*} →
 * {@code /case-deadlines/*} (the generic names belong to the party registry and SLA services).
 */
@RestController
@RequestMapping("/api/v1")
public class CaseFileController {

    private final CaseFileRestPort port;
    private final PermissionPort permissions;

    public CaseFileController(CaseFileRestPort port, PermissionPort permissions) {
        this.port = port;
        this.permissions = permissions;
    }

    public record PublishedConfig(String kind, int version) {
    }

    public record MilestoneRequest(String milestone, Instant occurredAt) {
    }

    public record FolderRequest(String parentFolderId, String name) {
    }

    public record NoteRequest(String body, String visibility) {
    }

    /** Publish a case type, reference kind or committee kind (versioned). */
    @PutMapping("/case-config/{kind}")
    public PublishedConfig publishConfig(RequestContext ctx, @PathVariable String kind, @RequestBody Map<String, Object> body) {
        return new PublishedConfig(kind, port.publishConfig(CaseIdentity.require(permissions, ctx, CaseIdentity.ADMIN), kind, body));
    }

    /** List the published configuration of a kind. */
    @GetMapping("/case-config/{kind}")
    public ResponseEntity<List<CaseConfigRegistry.ConfigEntry>> configs(RequestContext ctx, @PathVariable String kind) {
        return Lists.ok(port.configs(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), kind));
    }

    /** Open a case file. */
    @PostMapping("/case-files")
    public ResponseEntity<CaseFileService.CaseFile> open(RequestContext ctx, @RequestBody CaseFileService.OpenRequest body) {
        CaseFileService.CaseFile f = port.open(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), body);
        return Lists.created("/api/v1/case-files/" + f.caseId(), f.caseId(), f);
    }

    /** List case files visible to the caller (VI.4 predicate). */
    @GetMapping("/case-files")
    public ResponseEntity<List<CaseFileService.CaseFile>> list(RequestContext ctx,
            @RequestParam(required = false) String caseType, @RequestParam(required = false) String status,
            @RequestParam(required = false) String stage, @RequestParam(required = false) String orgUnit,
            @RequestParam(required = false) String ownerSeat, @RequestParam(required = false) String text,
            @RequestParam(required = false) String partyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant openedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant openedTo,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        var filter = new CaseFileService.Filter(caseType, CaseIdentity.enumOf(CaseFileService.Status.class, status, "status"),
                stage, orgUnit, ownerSeat, text, partyId, openedFrom, openedTo);
        return Lists.ok(CaseIdentity.page(port.list(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), filter,
                CaseIdentity.page(page, size))));
    }

    /** The whole case file: parties (masked without READ_SENSITIVE), records, documents, links, deadlines, hearings. */
    @GetMapping("/case-files/{id}")
    public CaseFileRestPort.CaseView get(RequestContext ctx, @PathVariable String id) {
        return port.get(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id);
    }

    /** A case file by its human-readable number. */
    @GetMapping("/case-files/by-number/{number}")
    public CaseFileRestPort.CaseView byNumber(RequestContext ctx, @PathVariable String number) {
        return port.byNumber(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), number);
    }

    /** Update attributes, title, priority or confidentiality (optimistic rowVersion). */
    @PatchMapping("/case-files/{id}")
    public CaseFileService.CaseFile update(RequestContext ctx, @PathVariable String id,
                                           @RequestBody CaseFileRestPort.UpdateRequest body) {
        return port.update(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Move the case to another stage. */
    @PostMapping("/case-files/{id}/stage")
    public CaseFileService.CaseFile stage(RequestContext ctx, @PathVariable String id,
                                          @RequestBody CaseFileRestPort.StageRequest body) {
        return port.moveStage(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Assign the owner seat and org unit. */
    @PostMapping("/case-files/{id}/assign")
    public CaseFileService.CaseFile assign(RequestContext ctx, @PathVariable String id,
                                           @RequestBody CaseFileRestPort.AssignRequest body) {
        return port.assign(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Close the case with a reason. */
    @PostMapping("/case-files/{id}/close")
    public CaseFileService.CaseFile close(RequestContext ctx, @PathVariable String id,
                                          @RequestBody CaseFileRestPort.ReasonRequest body) {
        CaseIdentity.requireReason(body.reason());
        return port.close(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Reopen a closed case with a reason. */
    @PostMapping("/case-files/{id}/reopen")
    public CaseFileService.CaseFile reopen(RequestContext ctx, @PathVariable String id,
                                           @RequestBody CaseFileRestPort.ReasonRequest body) {
        CaseIdentity.requireReason(body.reason());
        return port.reopen(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Record a milestone on the case. */
    @PostMapping("/case-files/{id}/milestones")
    public ResponseEntity<Void> milestone(RequestContext ctx, @PathVariable String id, @RequestBody MilestoneRequest body) {
        port.milestone(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body.milestone(),
                CaseIdentity.at(body.occurredAt()));
        return ResponseEntity.noContent().build();
    }

    /** Register a party in the case module's party book. */
    @PostMapping("/case-parties")
    public ResponseEntity<PartyService.Party> registerParty(RequestContext ctx, @RequestBody PartyService.PartyInput body) {
        PartyService.Party p = port.registerParty(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), body);
        return Lists.created("/api/v1/case-parties/" + p.partyId(), p.partyId(), p);
    }

    /** Search party candidates by identifiers or name (duplicate check before registering). */
    @GetMapping("/case-parties/candidates")
    public ResponseEntity<List<PartyService.Party>> partyCandidates(RequestContext ctx,
            @RequestParam(required = false) String name, @RequestParam Map<String, String> query) {
        Map<String, Object> identifiers = new java.util.LinkedHashMap<>(query);
        identifiers.remove("name");
        return Lists.ok(port.partyCandidates(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), identifiers, name));
    }

    /** The cases a party appears in. */
    @GetMapping("/case-parties/{id}/cases")
    public ResponseEntity<List<PartyService.CaseParty>> casesOfParty(RequestContext ctx, @PathVariable String id) {
        return Lists.ok(port.casesOfParty(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id));
    }

    /** Add a party to the case (with conflict-of-interest results). */
    @PostMapping("/case-files/{id}/parties")
    public CaseFileRestPort.PartyResult addParty(RequestContext ctx, @PathVariable String id,
                                                 @RequestBody CaseFileRestPort.AddPartyRequest body) {
        return port.addParty(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Remove a party from the case. */
    @DeleteMapping("/case-files/{id}/parties/{casePartyId}")
    public PartyService.CaseParty removeParty(RequestContext ctx, @PathVariable String id, @PathVariable String casePartyId,
                                              @RequestParam(required = false) String reason) {
        return port.removeParty(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, casePartyId,
                CaseIdentity.requireReason(reason));
    }

    /** Conflicts of a (candidate) party in a role. */
    @GetMapping("/case-files/{id}/conflicts")
    public ResponseEntity<List<PartyService.Conflict>> conflicts(RequestContext ctx, @PathVariable String id,
            @RequestParam(required = false) String partyId, @RequestParam(required = false) String role) {
        return Lists.ok(port.conflicts(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id, partyId, role));
    }

    /** Possible duplicate cases. */
    @GetMapping("/case-files/{id}/duplicates")
    public ResponseEntity<List<PartyService.DuplicateCandidate>> duplicates(RequestContext ctx, @PathVariable String id) {
        return Lists.ok(port.duplicates(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id));
    }

    /** Link a business record (process instance, document, external record) to the case. */
    @PostMapping("/case-files/{id}/records")
    public CaseContentsService.LinkedRecord linkRecord(RequestContext ctx, @PathVariable String id,
                                                       @RequestBody CaseFileRestPort.RecordRequest body) {
        return port.linkRecord(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Unlink a record from the case. */
    @DeleteMapping("/case-files/{id}/records/{recordId}")
    public ResponseEntity<Void> unlinkRecord(RequestContext ctx, @PathVariable String id, @PathVariable String recordId) {
        port.unlinkRecord(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, recordId);
        return ResponseEntity.noContent().build();
    }

    /** Create a folder in the case file. */
    @PostMapping("/case-files/{id}/folders")
    public CaseContentsService.Folder createFolder(RequestContext ctx, @PathVariable String id, @RequestBody FolderRequest body) {
        return port.createFolder(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body.parentFolderId(),
                body.name());
    }

    /** Attach an existing content item to the case. */
    @PostMapping(value = "/case-files/{id}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CaseContentsService.Document attachDocument(RequestContext ctx, @PathVariable String id,
                                                       @RequestBody CaseFileRestPort.DocumentRequest body) {
        return port.attachDocument(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Upload a new document into the case (multipart: file + metadata fields). */
    @PostMapping(value = "/case-files/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CaseContentsService.Document uploadDocument(RequestContext ctx, @PathVariable String id,
            @RequestPart("file") MultipartFile file, @RequestParam(required = false) String folderId,
            @RequestParam(required = false) String title, @RequestParam(required = false) String docType,
            @RequestParam(required = false) String confidentiality,
            @RequestParam(required = false, defaultValue = "false") boolean exhibit,
            @RequestParam(required = false) String exhibitRef) throws IOException {
        var request = new CaseFileRestPort.DocumentRequest(folderId, null, title, docType, confidentiality, exhibit, exhibitRef);
        try (InputStream in = file.getInputStream()) {
            return port.uploadDocument(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, request,
                    file.getOriginalFilename(), file.getContentType(), in);
        }
    }

    /** Download a case document version (streamed). */
    @GetMapping("/case-files/{id}/documents/{docId}/content")
    public ResponseEntity<StreamingResponseBody> documentContent(RequestContext ctx, @PathVariable String id,
            @PathVariable String docId, @RequestParam(required = false, defaultValue = "0") int version) {
        var api = CaseIdentity.require(permissions, ctx, CaseIdentity.READ);
        InputStream in = port.openDocument(api, id, docId, version);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", ContentDisposition.attachment().filename(docId).build().toString())
                .body(out -> {
                    try (InputStream s = in) {
                        s.transferTo(out);
                    }
                });
    }

    /** Remove a document from the case. */
    @DeleteMapping("/case-files/{id}/documents/{docId}")
    public ResponseEntity<Void> removeDocument(RequestContext ctx, @PathVariable String id, @PathVariable String docId,
                                               @RequestParam(required = false) String reason) {
        port.removeDocument(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, docId,
                CaseIdentity.requireReason(reason));
        return ResponseEntity.noContent().build();
    }

    /** Notes on the case. */
    @GetMapping("/case-files/{id}/notes")
    public ResponseEntity<List<CaseContentsService.Note>> notes(RequestContext ctx, @PathVariable String id) {
        return Lists.ok(port.notes(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id));
    }

    /** Add a note. */
    @PostMapping("/case-files/{id}/notes")
    public CaseContentsService.Note addNote(RequestContext ctx, @PathVariable String id, @RequestBody NoteRequest body) {
        return port.addNote(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body.body(), body.visibility());
    }

    /** Edit a note (versioned). */
    @PutMapping("/case-files/{id}/notes/{noteId}")
    public CaseContentsService.Note editNote(RequestContext ctx, @PathVariable String id, @PathVariable String noteId,
                                             @RequestBody NoteRequest body) {
        return port.editNote(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, noteId, body.body());
    }

    /** The case timeline, optionally merged with the engine history (read model, VI.4 predicate). */
    @GetMapping("/case-files/{id}/timeline")
    public ResponseEntity<List<CaseTimelineService.Entry>> timeline(RequestContext ctx, @PathVariable String id,
            @RequestParam(required = false, defaultValue = "200") int limit,
            @RequestParam(required = false, defaultValue = "true") boolean engine) {
        return Lists.ok(port.timeline(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id,
                Math.max(1, Math.min(limit, 1000)), engine));
    }

    /** Link the case to a related case. */
    @PostMapping("/case-files/{id}/links")
    public CaseLinkService.CaseLink link(RequestContext ctx, @PathVariable String id,
                                         @RequestBody CaseFileRestPort.LinkRequest body) {
        return port.link(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Remove a case link. */
    @DeleteMapping("/case-files/{id}/links/{linkId}")
    public ResponseEntity<Void> unlink(RequestContext ctx, @PathVariable String id, @PathVariable String linkId) {
        port.unlink(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, linkId);
        return ResponseEntity.noContent().build();
    }

    /** Merge another case into this one. */
    @PostMapping("/case-files/{id}/merge")
    public CaseFileService.CaseFile merge(RequestContext ctx, @PathVariable String id,
                                          @RequestBody CaseFileRestPort.MergeRequest body) {
        CaseIdentity.requireReason(body.reason());
        return port.merge(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Split part of this case into a new case. */
    @PostMapping("/case-files/{id}/split")
    public CaseFileService.CaseFile split(RequestContext ctx, @PathVariable String id,
                                          @RequestBody CaseLinkService.SplitRequest body) {
        CaseIdentity.requireReason(body.reason());
        return port.split(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Schedule a hearing. */
    @PostMapping("/case-files/{id}/hearings")
    public HearingService.Hearing scheduleHearing(RequestContext ctx, @PathVariable String id,
                                                  @RequestBody CaseFileRestPort.HearingRequest body) {
        return port.scheduleHearing(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Record the outcome of a hearing (with adjournment and next date). */
    @PostMapping("/hearings/{id}/outcome")
    public HearingService.Hearing recordOutcome(RequestContext ctx, @PathVariable String id,
                                                @RequestBody CaseFileRestPort.OutcomeRequest body) {
        return port.recordOutcome(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Cause list: hearings of a forum in a period. */
    @GetMapping("/hearings/cause-list")
    public ResponseEntity<List<HearingService.Hearing>> causeList(RequestContext ctx, @RequestParam(required = false) String forum,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return Lists.ok(port.causeList(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), forum, from, to));
    }

    /** Extend a case or reference deadline with a reason. */
    @PostMapping("/case-deadlines/{id}/extend")
    public DeadlineService.Deadline extendDeadline(RequestContext ctx, @PathVariable String id,
                                                   @RequestBody CaseFileRestPort.ExtendRequest body) {
        CaseIdentity.requireReason(body.reason());
        return port.extendDeadline(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Deadlines due before an instant. */
    @GetMapping("/case-deadlines/due")
    public ResponseEntity<List<DeadlineService.Deadline>> dueBefore(RequestContext ctx,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false, defaultValue = "200") int limit) {
        return Lists.ok(port.dueBefore(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), scopeType,
                before == null ? Instant.now() : before, Math.max(1, Math.min(limit, 1000))));
    }
}
