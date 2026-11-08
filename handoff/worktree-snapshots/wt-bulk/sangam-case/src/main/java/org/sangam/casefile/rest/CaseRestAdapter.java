// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.rest;

import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.CaseModule;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.config.CaseConfigRegistry;
import org.sangam.casefile.deadline.DeadlineService;
import org.sangam.casefile.file.CaseContentsService;
import org.sangam.casefile.file.CaseFileService;
import org.sangam.casefile.file.CaseLinkService;
import org.sangam.casefile.file.CaseTimelineService;
import org.sangam.casefile.file.HearingService;
import org.sangam.casefile.file.PartyService;
import org.sangam.casefile.meeting.ActionItemService;
import org.sangam.casefile.meeting.CommitteeService;
import org.sangam.casefile.meeting.MeetingService;
import org.sangam.casefile.reference.ReferenceService;
import org.sangam.casefile.rest.CaseApi.ApiContext;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.security.SecurityPorts;
import org.sangam.ports.support.Paging;

import java.io.InputStream;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static org.sangam.casefile.rest.CaseApi.ALL;
import static org.sangam.casefile.rest.CaseApi.CASE_FILE;
import static org.sangam.casefile.rest.CaseApi.CASE_REFERENCE;
import static org.sangam.casefile.rest.CaseApi.COMMITTEE;

/**
 * Implements the three REST ports over the case module, one transaction per call. Every call runs the VI.4 object
 * check in the same transaction ({@link SecurityPorts.Authorization#require}, K3); lists use the compiled predicate
 * ({@code visible}, R19, no post-filtering). Permissions: READ, MODIFY (write the file), START (open / register /
 * schedule, module-wide id {@code *}), ASSIGN, APPROVE (minutes, replies, ATR verification), ADMIN (configuration,
 * committee constitution), READ_SENSITIVE (unmasked party identifiers and masked parties' names).
 */
public class CaseRestAdapter implements CaseFileRestPort, MeetingRestPort, ReferenceRestPort {

    private final CaseModule m;
    private final SecurityPorts.Authorization auth;

    public CaseRestAdapter(CaseModule module, SecurityPorts.Authorization authorization) {
        this.m = Objects.requireNonNull(module, "module");
        this.auth = Objects.requireNonNull(authorization, "authorization");
    }

    private <T> T tx(Function<Connection, T> work) {
        return m.tx().inTransaction(work);
    }

    private void require(Connection c, ApiContext ctx, String type, String permission, String id) {
        auth.require(c, ctx.identity(), type, permission, id);
    }

    private String committeeOfMeeting(Connection c, ApiContext ctx, String meetingId) {
        return m.meetings().get(c, ctx.tenant(), meetingId).committeeId();
    }

    private void requireMeeting(Connection c, ApiContext ctx, String permission, String meetingId) {
        require(c, ctx, COMMITTEE, permission, committeeOfMeeting(c, ctx, meetingId));
    }

    private void requireAction(Connection c, ApiContext ctx, String permission, String actionId) {
        ActionItemService.ActionItem a = m.actions().get(c, ctx.tenant(), actionId);
        if (a.committeeId() != null) {
            require(c, ctx, COMMITTEE, permission, a.committeeId());
        } else {
            require(c, ctx, COMMITTEE, permission, ALL);
        }
    }

    // =================================================================== case file

    @Override
    public int publishConfig(ApiContext ctx, String kind, Map<String, Object> definition) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "ADMIN", ALL);
            return m.config().publish(c, ctx.tenant(), kind, definition, ctx.actor());
        });
    }

    @Override
    public List<CaseConfigRegistry.ConfigEntry> configs(ApiContext ctx, String kind) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "READ", ALL);
            return m.config().list(c, ctx.tenant(), kind);
        });
    }

    @Override
    public CaseFileService.CaseFile open(ApiContext ctx, CaseFileService.OpenRequest request) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "START", ALL);
            return m.cases().open(c, ctx.tenant(), request, ctx.actor());
        });
    }

    @Override
    public Paging.PageResult<CaseFileService.CaseFile> list(ApiContext ctx, CaseFileService.Filter filter, Paging.PageRequest page) {
        return tx(c -> {
            SqlPredicate visible = auth.visible(c, ctx.identity(), CASE_FILE, "READ", "cf");
            return m.cases().list(c, ctx.tenant(), filter, visible, page);
        });
    }

    @Override
    public CaseView get(ApiContext ctx, String caseId) {
        return tx(c -> view(c, ctx, m.caseLinks().resolve(c, ctx.tenant(), caseId).caseId()));
    }

    @Override
    public CaseView byNumber(ApiContext ctx, String caseNumber) {
        return tx(c -> {
            CaseFileService.CaseFile cf = m.cases().byNumber(c, ctx.tenant(), caseNumber)
                    .orElseThrow(() -> CaseFileException.notFound("case", caseNumber));
            return view(c, ctx, m.caseLinks().resolve(c, ctx.tenant(), cf.caseId()).caseId());
        });
    }

    private CaseView view(Connection c, ApiContext ctx, String caseId) {
        require(c, ctx, CASE_FILE, "READ", caseId);
        String t = ctx.tenant();
        boolean sensitive = auth.allow(c, ctx.identity(), CASE_FILE, "READ_SENSITIVE", caseId);
        List<PartyService.CaseParty> parties = new ArrayList<>();
        for (PartyService.CaseParty p : m.parties().caseParties(c, t, caseId, false)) {
            parties.add(sensitive ? p : mask(p));
        }
        return new CaseView(m.cases().get(c, t, caseId), parties, m.contents().records(c, t, caseId), m.contents().folders(c, t, caseId),
                m.contents().documents(c, t, caseId, null, false), m.caseLinks().links(c, t, caseId),
                m.deadlines().forScope(c, t, CaseEvents.CASE, caseId), m.hearings().forCase(c, t, caseId), !sensitive);
    }

    /** R19 party masks: identifiers and contact values hidden; a masked party's name replaced. */
    static PartyService.CaseParty mask(PartyService.CaseParty p) {
        PartyService.Party party = p.party();
        if (party == null) {
            return p;
        }
        Map<String, Object> ids = new LinkedHashMap<>();
        party.identifiers().forEach((k, v) -> ids.put(k, maskValue(v)));
        Map<String, Object> contact = new LinkedHashMap<>();
        party.contact().forEach((k, v) -> contact.put(k, "***"));
        String name = p.masked() ? "Party " + p.casePartyId().substring(Math.max(0, p.casePartyId().length() - 6)) : party.displayName();
        PartyService.Party masked = new PartyService.Party(party.tenantId(), party.partyId(), party.kind(), name, ids, contact,
                p.masked() ? null : party.externalRef(), party.createdBy(), party.createdAt());
        return new PartyService.CaseParty(p.tenantId(), p.casePartyId(), p.caseId(), p.partyId(), p.role(), p.capacity(), p.representedBy(),
                p.masked(), p.validFrom(), p.validTo(), p.addedBy(), p.addedAt(), masked);
    }

    private static String maskValue(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.length() <= 4 ? "****" : "*".repeat(s.length() - 4) + s.substring(s.length() - 4);
    }

    @Override
    public CaseFileService.CaseFile update(ApiContext ctx, String caseId, UpdateRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            CaseFileService.CaseFile cf = m.cases().get(c, ctx.tenant(), caseId);
            long version = r.rowVersion();
            if (r.title() != null || r.priority() != null || r.confidentiality() != null) {
                cf = m.cases().retitle(c, ctx.tenant(), caseId, r.title(), r.priority(), r.confidentiality(), version, ctx.actor());
                version = cf.rowVersion();
            }
            if (r.attributes() != null) {
                cf = m.cases().updateAttributes(c, ctx.tenant(), caseId, r.attributes(), r.replace(), version, ctx.actor());
            }
            return cf;
        });
    }

    @Override
    public CaseFileService.CaseFile moveStage(ApiContext ctx, String caseId, StageRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            return m.cases().moveStage(c, ctx.tenant(), caseId, r.stage(), r.reason(), r.rowVersion(), ctx.actor());
        });
    }

    @Override
    public CaseFileService.CaseFile assign(ApiContext ctx, String caseId, CaseFileRestPort.AssignRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "ASSIGN", caseId);
            return m.cases().assign(c, ctx.tenant(), caseId, r.ownerSeat(), r.orgUnit(), r.rowVersion(), ctx.actor());
        });
    }

    @Override
    public CaseFileService.CaseFile close(ApiContext ctx, String caseId, ReasonRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            return m.cases().close(c, ctx.tenant(), caseId, r.reason(), r.rowVersion(), ctx.actor());
        });
    }

    @Override
    public CaseFileService.CaseFile reopen(ApiContext ctx, String caseId, ReasonRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "APPROVE", caseId);
            return m.cases().reopen(c, ctx.tenant(), caseId, r.reason(), r.stage(), r.rowVersion(), ctx.actor());
        });
    }

    @Override
    public void milestone(ApiContext ctx, String caseId, String milestone, Instant occurredAt) {
        tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            m.cases().milestone(c, ctx.tenant(), caseId, milestone, occurredAt, ctx.actor());
            return null;
        });
    }

    @Override
    public PartyService.Party registerParty(ApiContext ctx, PartyService.PartyInput input) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "START", ALL);
            return m.parties().register(c, ctx.tenant(), input, ctx.actor());
        });
    }

    @Override
    public List<PartyService.Party> partyCandidates(ApiContext ctx, Map<String, Object> identifiers, String name) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "READ_SENSITIVE", ALL);
            return m.parties().candidates(c, ctx.tenant(), identifiers, name);
        });
    }

    @Override
    public PartyResult addParty(ApiContext ctx, String caseId, AddPartyRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            String t = ctx.tenant();
            String partyId = r.partyId();
            if (partyId == null) {
                if (r.newParty() == null) {
                    throw CaseFileException.invalid("partyId or newParty is required");
                }
                partyId = m.parties().registerOrMatch(c, t, r.newParty(), ctx.actor()).partyId();
            }
            CaseFileService.CaseFile cf = m.cases().get(c, t, caseId);
            List<PartyService.Conflict> conflicts = m.parties().conflictCheck(c, t, cf.caseType(), caseId, partyId, r.role());
            PartyService.CaseParty cp = m.parties().addToCase(c, t, caseId, partyId, r.role(), r.capacity(), r.representedBy(), r.masked(),
                    ctx.actor());
            return new PartyResult(cp, conflicts);
        });
    }

    @Override
    public PartyService.CaseParty removeParty(ApiContext ctx, String caseId, String casePartyId, String reason) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            PartyService.CaseParty p = m.parties().caseParty(c, ctx.tenant(), casePartyId);
            if (!p.caseId().equals(caseId)) {
                throw CaseFileException.notFound("case party", casePartyId);
            }
            return m.parties().removeFromCase(c, ctx.tenant(), casePartyId, reason, ctx.actor());
        });
    }

    @Override
    public List<PartyService.Conflict> conflicts(ApiContext ctx, String caseId, String partyId, String role) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "READ", caseId);
            CaseFileService.CaseFile cf = m.cases().get(c, ctx.tenant(), caseId);
            List<PartyService.Conflict> out = new ArrayList<>();
            for (PartyService.Conflict x : m.parties().conflictCheck(c, ctx.tenant(), cf.caseType(), caseId, partyId, role)) {
                boolean visible = x.caseId().equals(caseId) || auth.allow(c, ctx.identity(), CASE_FILE, "READ", x.caseId());
                out.add(visible ? x : new PartyService.Conflict(x.kind(), null, null, x.partyId(), x.role(), x.otherRole()));
            }
            return out;
        });
    }

    @Override
    public List<PartyService.DuplicateCandidate> duplicates(ApiContext ctx, String caseId) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "READ", caseId);
            CaseFileService.CaseFile cf = m.cases().get(c, ctx.tenant(), caseId);
            List<String> partyIds = m.parties().caseParties(c, ctx.tenant(), caseId, false).stream().map(PartyService.CaseParty::partyId).toList();
            return m.parties().duplicates(c, ctx.tenant(), cf.caseType(), caseId, partyIds).stream()
                    .filter(d -> auth.allow(c, ctx.identity(), CASE_FILE, "READ", d.caseId())).toList();
        });
    }

    @Override
    public List<PartyService.CaseParty> casesOfParty(ApiContext ctx, String partyId) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "READ_SENSITIVE", ALL);
            return m.parties().casesOf(c, ctx.tenant(), partyId).stream()
                    .filter(p -> auth.allow(c, ctx.identity(), CASE_FILE, "READ", p.caseId())).toList();
        });
    }

    @Override
    public CaseContentsService.LinkedRecord linkRecord(ApiContext ctx, String caseId, RecordRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            return m.contents().linkRecord(c, ctx.tenant(), caseId, r.recordType(), r.recordRef(), r.relation(), r.title(), ctx.actor());
        });
    }

    @Override
    public void unlinkRecord(ApiContext ctx, String caseId, String recordId) {
        tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            m.contents().unlinkRecord(c, ctx.tenant(), recordId, ctx.actor());
            return null;
        });
    }

    @Override
    public CaseContentsService.Folder createFolder(ApiContext ctx, String caseId, String parentFolderId, String name) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            return m.contents().createFolder(c, ctx.tenant(), caseId, parentFolderId, name, ctx.actor());
        });
    }

    @Override
    public CaseContentsService.Document attachDocument(ApiContext ctx, String caseId, DocumentRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            return m.contents().attach(c, ctx.tenant(), caseId, r.folderId(), r.contentItemId(), r.title(), r.docType(), r.confidentiality(),
                    r.exhibit(), r.exhibitRef(), ctx.actor());
        });
    }

    @Override
    public CaseContentsService.Document uploadDocument(ApiContext ctx, String caseId, DocumentRequest r, String fileName, String mimeType,
                                                       InputStream bytes) {
        tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            return null;
        });
        return m.contents().upload(ctx.tenant(), caseId, r.folderId(), fileName, mimeType, bytes, r.title(), r.docType(), r.confidentiality(),
                r.exhibit(), r.exhibitRef(), ctx.actor());
    }

    @Override
    public InputStream openDocument(ApiContext ctx, String caseId, String documentId, int version) {
        tx(c -> {
            require(c, ctx, CASE_FILE, "READ", caseId);
            CaseContentsService.Document d = m.contents().document(c, ctx.tenant(), documentId);
            if (!d.caseId().equals(caseId)) {
                throw CaseFileException.notFound("document", documentId);
            }
            if (!"NORMAL".equals(d.confidentiality())) {
                require(c, ctx, CASE_FILE, "READ_SENSITIVE", caseId);
            }
            return null;
        });
        return m.contents().open(ctx.tenant(), documentId, version);
    }

    @Override
    public void removeDocument(ApiContext ctx, String caseId, String documentId, String reason) {
        tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            m.contents().removeDocument(c, ctx.tenant(), documentId, reason, ctx.actor());
            return null;
        });
    }

    @Override
    public List<CaseContentsService.Note> notes(ApiContext ctx, String caseId) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "READ", caseId);
            boolean officer = auth.allow(c, ctx.identity(), CASE_FILE, "MODIFY", caseId);
            return m.contents().notes(c, ctx.tenant(), caseId, ctx.actor(), officer);
        });
    }

    @Override
    public CaseContentsService.Note addNote(ApiContext ctx, String caseId, String body, String visibility) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            return m.contents().addNote(c, ctx.tenant(), caseId, body, visibility, ctx.actor());
        });
    }

    @Override
    public CaseContentsService.Note editNote(ApiContext ctx, String caseId, String noteId, String body) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            return m.contents().editNote(c, ctx.tenant(), noteId, body, ctx.actor());
        });
    }

    @Override
    public List<CaseTimelineService.Entry> timeline(ApiContext ctx, String caseId, int limit, boolean includeEngineHistory) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "READ", caseId);
            if (includeEngineHistory) {
                require(c, ctx, CASE_FILE, "READ_HISTORY", caseId);
            }
            return m.timeline().timeline(c, ctx.tenant(), caseId, limit, includeEngineHistory);
        });
    }

    @Override
    public CaseLinkService.CaseLink link(ApiContext ctx, String caseId, LinkRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            require(c, ctx, CASE_FILE, "READ", r.toCaseId());
            return m.caseLinks().link(c, ctx.tenant(), caseId, r.toCaseId(), r.linkType(), r.reason(), ctx.actor());
        });
    }

    @Override
    public void unlink(ApiContext ctx, String caseId, String linkId) {
        tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            m.caseLinks().unlink(c, ctx.tenant(), linkId, ctx.actor());
            return null;
        });
    }

    @Override
    public CaseFileService.CaseFile merge(ApiContext ctx, String survivingCaseId, MergeRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "APPROVE", survivingCaseId);
            require(c, ctx, CASE_FILE, "APPROVE", r.mergedCaseId());
            return m.caseLinks().merge(c, ctx.tenant(), survivingCaseId, r.mergedCaseId(), r.reason(), ctx.actor());
        });
    }

    @Override
    public CaseFileService.CaseFile split(ApiContext ctx, String caseId, CaseLinkService.SplitRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "APPROVE", caseId);
            require(c, ctx, CASE_FILE, "START", ALL);
            return m.caseLinks().split(c, ctx.tenant(), caseId, r, ctx.actor());
        });
    }

    @Override
    public HearingService.Hearing scheduleHearing(ApiContext ctx, String caseId, HearingRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_FILE, "MODIFY", caseId);
            return m.hearings().schedule(c, ctx.tenant(), caseId, r.kind(), r.forum(), r.scheduledAt(), r.externalRef(), ctx.actor());
        });
    }

    @Override
    public HearingService.Hearing recordOutcome(ApiContext ctx, String hearingId, OutcomeRequest r) {
        return tx(c -> {
            HearingService.Hearing h = m.hearings().get(c, ctx.tenant(), hearingId);
            require(c, ctx, CASE_FILE, "MODIFY", h.caseId());
            return m.hearings().recordOutcome(c, ctx.tenant(), hearingId, r.outcome(), r.outcomeText(), r.adjourned(), r.nextDate(), ctx.actor());
        });
    }

    @Override
    public List<HearingService.Hearing> causeList(ApiContext ctx, String forum, Instant from, Instant to) {
        return tx(c -> m.hearings().causeList(c, ctx.tenant(), forum, from, to).stream()
                .filter(h -> auth.allow(c, ctx.identity(), CASE_FILE, "READ", h.caseId())).toList());
    }

    @Override
    public DeadlineService.Deadline extendDeadline(ApiContext ctx, String deadlineId, CaseFileRestPort.ExtendRequest r) {
        return tx(c -> {
            DeadlineService.Deadline d = m.deadlines().get(c, ctx.tenant(), deadlineId)
                    .orElseThrow(() -> CaseFileException.notFound("deadline", deadlineId));
            requireScope(c, ctx, "APPROVE", d.scopeType(), d.scopeId());
            return m.deadlines().extend(c, ctx.tenant(), deadlineId, r.newDueAt(), r.reason(), ctx.actor(), -1);
        });
    }

    @Override
    public List<DeadlineService.Deadline> dueBefore(ApiContext ctx, String scopeType, Instant before, int limit) {
        return tx(c -> m.deadlines().dueBefore(c, ctx.tenant(), scopeType, before, limit).stream()
                .filter(d -> allowScope(c, ctx, "READ", d.scopeType(), d.scopeId())).toList());
    }

    private void requireScope(Connection c, ApiContext ctx, String permission, String scopeType, String scopeId) {
        if (!allowScope(c, ctx, permission, scopeType, scopeId)) {
            throw new CaseFileException(CaseFileException.Code.ACCESS_DENIED, "not permitted");
        }
    }

    private boolean allowScope(Connection c, ApiContext ctx, String permission, String scopeType, String scopeId) {
        return switch (scopeType) {
            case CaseEvents.CASE -> auth.allow(c, ctx.identity(), CASE_FILE, permission, scopeId);
            case CaseEvents.REFERENCE -> auth.allow(c, ctx.identity(), CASE_REFERENCE, permission, scopeId);
            case CaseEvents.MEETING -> auth.allow(c, ctx.identity(), COMMITTEE, permission, committeeOfMeeting(c, ctx, scopeId));
            case CaseEvents.ACTION -> {
                ActionItemService.ActionItem a = m.actions().get(c, ctx.tenant(), scopeId);
                yield auth.allow(c, ctx.identity(), COMMITTEE, permission, a.committeeId() == null ? ALL : a.committeeId());
            }
            default -> auth.allow(c, ctx.identity(), CASE_FILE, permission, ALL);
        };
    }

    // =================================================================== committees and meetings

    @Override
    public CommitteeService.Committee constitute(ApiContext ctx, CommitteeService.ConstituteRequest request) {
        return tx(c -> {
            require(c, ctx, COMMITTEE, "ADMIN", ALL);
            return m.committees().constitute(c, ctx.tenant(), request, ctx.actor());
        });
    }

    @Override
    public List<CommitteeService.Committee> committees(ApiContext ctx, String kind, boolean activeOnly) {
        return tx(c -> m.committees().list(c, ctx.tenant(), kind, activeOnly).stream()
                .filter(x -> auth.allow(c, ctx.identity(), COMMITTEE, "READ", x.committeeId())).toList());
    }

    @Override
    public CommitteeView committee(ApiContext ctx, String committeeId) {
        return tx(c -> {
            require(c, ctx, COMMITTEE, "READ", committeeId);
            return new CommitteeView(m.committees().get(c, ctx.tenant(), committeeId), m.committees().members(c, ctx.tenant(), committeeId, null),
                    m.committees().quorumRequired(c, ctx.tenant(), committeeId));
        });
    }

    @Override
    public CommitteeService.Member addMember(ApiContext ctx, String committeeId, CommitteeService.MemberInput member) {
        return tx(c -> {
            require(c, ctx, COMMITTEE, "ADMIN", committeeId);
            return m.committees().addMember(c, ctx.tenant(), committeeId, member, ctx.actor());
        });
    }

    @Override
    public void removeMember(ApiContext ctx, String committeeId, String memberId, String reason) {
        tx(c -> {
            require(c, ctx, COMMITTEE, "ADMIN", committeeId);
            if (!m.committees().member(c, ctx.tenant(), memberId).committeeId().equals(committeeId)) {
                throw CaseFileException.notFound("member", memberId);
            }
            m.committees().removeMember(c, ctx.tenant(), memberId, reason, ctx.actor());
            return null;
        });
    }

    @Override
    public CommitteeService.Committee reconstitute(ApiContext ctx, String committeeId, ReconstituteRequest r) {
        return tx(c -> {
            require(c, ctx, COMMITTEE, "ADMIN", committeeId);
            return m.committees().reconstitute(c, ctx.tenant(), committeeId, r.constitutionRef(), r.members(), ctx.actor());
        });
    }

    @Override
    public CommitteeService.Committee dissolve(ApiContext ctx, String committeeId, String reason) {
        return tx(c -> {
            require(c, ctx, COMMITTEE, "ADMIN", committeeId);
            return m.committees().dissolve(c, ctx.tenant(), committeeId, reason, ctx.actor());
        });
    }

    @Override
    public MeetingService.Meeting schedule(ApiContext ctx, MeetingService.ScheduleRequest request) {
        return tx(c -> {
            require(c, ctx, COMMITTEE, "START", request.committeeId());
            return m.meetings().schedule(c, ctx.tenant(), request, ctx.actor());
        });
    }

    @Override
    public List<MeetingService.Meeting> meetings(ApiContext ctx, String committeeId, Instant from, Instant to) {
        return tx(c -> {
            if (committeeId != null) {
                require(c, ctx, COMMITTEE, "READ", committeeId);
                return m.meetings().list(c, ctx.tenant(), committeeId, from, to);
            }
            return m.meetings().list(c, ctx.tenant(), null, from, to).stream()
                    .filter(x -> auth.allow(c, ctx.identity(), COMMITTEE, "READ", x.committeeId())).toList();
        });
    }

    @Override
    public MeetingView meeting(ApiContext ctx, String meetingId) {
        return tx(c -> {
            requireMeeting(c, ctx, "READ", meetingId);
            String t = ctx.tenant();
            return new MeetingView(m.meetings().get(c, t, meetingId), m.meetings().agenda(c, t, meetingId), m.meetings().attendance(c, t, meetingId),
                    m.meetings().quorum(c, t, meetingId), m.meetings().decisions(c, t, meetingId),
                    m.meetings().latestMinutes(c, t, meetingId).orElse(null), m.actions().list(c, t, meetingId, null, null, false));
        });
    }

    @Override
    public MeetingService.Meeting reschedule(ApiContext ctx, String meetingId, RescheduleRequest r) {
        return tx(c -> {
            requireMeeting(c, ctx, "MODIFY", meetingId);
            return m.meetings().reschedule(c, ctx.tenant(), meetingId, r.scheduledAt(), r.endsAt(), r.venue(), r.reason(), ctx.actor());
        });
    }

    @Override
    public MeetingService.Meeting cancel(ApiContext ctx, String meetingId, String reason) {
        return tx(c -> {
            requireMeeting(c, ctx, "CANCEL", meetingId);
            return m.meetings().cancel(c, ctx.tenant(), meetingId, reason, ctx.actor());
        });
    }

    @Override
    public MeetingService.Meeting start(ApiContext ctx, String meetingId) {
        return tx(c -> {
            requireMeeting(c, ctx, "MODIFY", meetingId);
            return m.meetings().start(c, ctx.tenant(), meetingId, ctx.actor());
        });
    }

    @Override
    public MeetingService.Meeting adjourn(ApiContext ctx, String meetingId, AdjournRequest r) {
        return tx(c -> {
            requireMeeting(c, ctx, "MODIFY", meetingId);
            return m.meetings().adjourn(c, ctx.tenant(), meetingId, r.reason(), r.newAt(), ctx.actor());
        });
    }

    @Override
    public MeetingService.Meeting end(ApiContext ctx, String meetingId) {
        return tx(c -> {
            requireMeeting(c, ctx, "MODIFY", meetingId);
            return m.meetings().end(c, ctx.tenant(), meetingId, ctx.actor());
        });
    }

    @Override
    public MeetingService.AgendaItem addAgendaItem(ApiContext ctx, String meetingId, AgendaRequest r) {
        return tx(c -> {
            requireMeeting(c, ctx, "MODIFY", meetingId);
            if ("case".equals(r.linkedType()) && r.linkedRef() != null) {
                require(c, ctx, CASE_FILE, "READ", r.linkedRef());
            }
            return m.meetings().addAgendaItem(c, ctx.tenant(), meetingId, r.title(), r.description(), r.linkedType(), r.linkedRef(),
                    r.presenter(), ctx.actor());
        });
    }

    @Override
    public void withdrawAgendaItem(ApiContext ctx, String meetingId, String itemId, String reason) {
        tx(c -> {
            requireMeeting(c, ctx, "MODIFY", meetingId);
            m.meetings().withdrawAgendaItem(c, ctx.tenant(), itemId, reason, ctx.actor());
            return null;
        });
    }

    @Override
    public void reorderAgenda(ApiContext ctx, String meetingId, List<String> itemIds) {
        tx(c -> {
            requireMeeting(c, ctx, "MODIFY", meetingId);
            m.meetings().reorder(c, ctx.tenant(), meetingId, itemIds, ctx.actor());
            return null;
        });
    }

    @Override
    public int carryForward(ApiContext ctx, String fromMeetingId, String toMeetingId) {
        return tx(c -> {
            requireMeeting(c, ctx, "MODIFY", toMeetingId);
            return m.meetings().carryForward(c, ctx.tenant(), fromMeetingId, toMeetingId, ctx.actor());
        });
    }

    @Override
    public List<MeetingService.Notice> sendNotices(ApiContext ctx, String meetingId, String kind) {
        return tx(c -> {
            requireMeeting(c, ctx, "MODIFY", meetingId);
            return m.meetings().sendNotices(c, ctx.tenant(), meetingId, kind == null ? "NOTICE" : kind, ctx.actor());
        });
    }

    @Override
    public void acknowledgeNotice(ApiContext ctx, String noticeId) {
        tx(c -> {
            m.meetings().acknowledgeNotice(c, ctx.tenant(), noticeId);
            return null;
        });
    }

    @Override
    public MeetingService.Attendance markAttendance(ApiContext ctx, String meetingId, AttendanceRequest r) {
        return tx(c -> {
            requireMeeting(c, ctx, "MODIFY", meetingId);
            return m.meetings().markAttendance(c, ctx.tenant(), meetingId, r.memberId(), r.attendeeRef(), r.attendance(), r.proxyFor(), ctx.actor());
        });
    }

    @Override
    public MeetingService.QuorumStatus quorum(ApiContext ctx, String meetingId) {
        return tx(c -> {
            requireMeeting(c, ctx, "READ", meetingId);
            return m.meetings().quorum(c, ctx.tenant(), meetingId);
        });
    }

    @Override
    public MeetingService.Decision recordDecision(ApiContext ctx, String meetingId, MeetingService.DecisionInput input) {
        return tx(c -> {
            requireMeeting(c, ctx, "MODIFY", meetingId);
            return m.meetings().recordDecision(c, ctx.tenant(), meetingId, input, ctx.actor());
        });
    }

    @Override
    public MeetingService.Minutes draftMinutes(ApiContext ctx, String meetingId, String body) {
        return tx(c -> {
            requireMeeting(c, ctx, "MODIFY", meetingId);
            return m.meetings().draftMinutes(c, ctx.tenant(), meetingId, body, ctx.actor());
        });
    }

    @Override
    public MeetingService.Minutes circulateMinutes(ApiContext ctx, String meetingId) {
        return tx(c -> {
            requireMeeting(c, ctx, "MODIFY", meetingId);
            return m.meetings().circulateForComment(c, ctx.tenant(), meetingId, ctx.actor());
        });
    }

    @Override
    public MeetingService.Minutes commentMinutes(ApiContext ctx, String meetingId, String text) {
        return tx(c -> {
            requireMeeting(c, ctx, "READ", meetingId);
            return m.meetings().comment(c, ctx.tenant(), meetingId, ctx.actor(), text, ctx.actor());
        });
    }

    @Override
    public MeetingService.Minutes approveMinutes(ApiContext ctx, String meetingId, ApproveMinutesRequest r) {
        return tx(c -> {
            requireMeeting(c, ctx, "APPROVE", meetingId);
            return m.meetings().approveMinutes(c, ctx.tenant(), meetingId, r.version(), r.contentHash(), ctx.actor(), ctx.identity().seats(),
                    r.esignRef());
        });
    }

    @Override
    public MeetingService.Minutes issueMinutes(ApiContext ctx, String meetingId) {
        return tx(c -> {
            requireMeeting(c, ctx, "MODIFY", meetingId);
            return m.meetings().issueMinutes(c, ctx.tenant(), meetingId, ctx.actor());
        });
    }

    @Override
    public ActionItemService.ActionItem createAction(ApiContext ctx, ActionItemService.CreateRequest r) {
        return tx(c -> {
            if (r.meetingId() != null) {
                requireMeeting(c, ctx, "MODIFY", r.meetingId());
            } else {
                require(c, ctx, COMMITTEE, "MODIFY", r.committeeId() == null ? ALL : r.committeeId());
            }
            return m.actions().create(c, ctx.tenant(), r, ctx.actor());
        });
    }

    @Override
    public List<ActionItemService.ActionItem> actions(ApiContext ctx, String meetingId, String committeeId, String ownerSeat, boolean openOnly) {
        return tx(c -> {
            boolean ownSeat = ownerSeat != null && ctx.identity().seats().contains(ownerSeat);
            if (!ownSeat) {
                if (meetingId != null) {
                    requireMeeting(c, ctx, "READ", meetingId);
                } else {
                    require(c, ctx, COMMITTEE, "READ", committeeId == null ? ALL : committeeId);
                }
            }
            return m.actions().list(c, ctx.tenant(), meetingId, committeeId, ownerSeat, openOnly);
        });
    }

    private ActionItemService.ActionItem ownerOr(Connection c, ApiContext ctx, String actionId, String permission) {
        ActionItemService.ActionItem a = m.actions().get(c, ctx.tenant(), actionId);
        boolean owner = (a.ownerSeat() != null && ctx.identity().seats().contains(a.ownerSeat())) || ctx.actor().equals(a.ownerPerson());
        if (!owner) {
            requireAction(c, ctx, permission, actionId);
        }
        return a;
    }

    @Override
    public ActionItemService.ActionItem startAction(ApiContext ctx, String actionId) {
        return tx(c -> {
            ownerOr(c, ctx, actionId, "MODIFY");
            return m.actions().startWork(c, ctx.tenant(), actionId, ctx.actor());
        });
    }

    @Override
    public ActionItemService.ActionItem submitAtr(ApiContext ctx, String actionId, String atrText) {
        return tx(c -> {
            ownerOr(c, ctx, actionId, "MODIFY");
            return m.actions().submitAtr(c, ctx.tenant(), actionId, atrText, ctx.actor());
        });
    }

    @Override
    public ActionItemService.ActionItem verifyAtr(ApiContext ctx, String actionId, VerifyRequest r) {
        return tx(c -> {
            requireAction(c, ctx, "APPROVE", actionId);
            return m.actions().verify(c, ctx.tenant(), actionId, r.accept(), r.reason(), ctx.actor());
        });
    }

    @Override
    public ActionItemService.ActionItem dropAction(ApiContext ctx, String actionId, String reason) {
        return tx(c -> {
            requireAction(c, ctx, "APPROVE", actionId);
            return m.actions().drop(c, ctx.tenant(), actionId, reason, ctx.actor());
        });
    }

    @Override
    public ActionItemService.ActionItem reassignAction(ApiContext ctx, String actionId, String ownerSeat, String ownerPerson) {
        return tx(c -> {
            requireAction(c, ctx, "ASSIGN", actionId);
            return m.actions().reassign(c, ctx.tenant(), actionId, ownerSeat, ownerPerson, ctx.actor());
        });
    }

    @Override
    public List<ActionItemService.Pendency> pendency(ApiContext ctx, String committeeId) {
        return tx(c -> {
            require(c, ctx, COMMITTEE, "READ", committeeId == null ? ALL : committeeId);
            return m.actions().pendency(c, ctx.tenant(), committeeId);
        });
    }

    @Override
    public List<ActionItemService.ActionItem> atrReport(ApiContext ctx, String meetingId) {
        return tx(c -> {
            requireMeeting(c, ctx, "READ", meetingId);
            return m.actions().atrReport(c, ctx.tenant(), meetingId);
        });
    }

    @Override
    public MeetingService.AgendaItem placeAtrOnAgenda(ApiContext ctx, String previousMeetingId, String nextMeetingId) {
        return tx(c -> {
            requireMeeting(c, ctx, "MODIFY", nextMeetingId);
            requireMeeting(c, ctx, "READ", previousMeetingId);
            return m.actions().placeAtrOnAgenda(c, ctx.tenant(), previousMeetingId, nextMeetingId, ctx.actor());
        });
    }

    // =================================================================== references

    @Override
    public ReferenceService.Reference register(ApiContext ctx, ReferenceService.RegisterRequest request) {
        return tx(c -> {
            require(c, ctx, CASE_REFERENCE, "START", ALL);
            return m.references().register(c, ctx.tenant(), request, ctx.actor());
        });
    }

    @Override
    public Paging.PageResult<ReferenceService.Reference> references(ApiContext ctx, ReferenceService.Filter filter, Paging.PageRequest page) {
        return tx(c -> m.references().list(c, ctx.tenant(), filter, auth.visible(c, ctx.identity(), CASE_REFERENCE, "READ", "r"), page));
    }

    @Override
    public ReferenceView reference(ApiContext ctx, String referenceId) {
        return tx(c -> {
            require(c, ctx, CASE_REFERENCE, "READ", referenceId);
            String t = ctx.tenant();
            return new ReferenceView(m.references().get(c, t, referenceId), m.references().moves(c, t, referenceId),
                    m.references().children(c, t, referenceId), m.deadlines().forScope(c, t, CaseEvents.REFERENCE, referenceId),
                    m.events().forScope(c, t, CaseEvents.REFERENCE, referenceId, 500));
        });
    }

    @Override
    public ReferenceService.Reference assign(ApiContext ctx, String referenceId, ReferenceRestPort.AssignRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_REFERENCE, "ASSIGN", referenceId);
            return m.references().assign(c, ctx.tenant(), referenceId, r.seat(), r.person(), r.remarks(), ctx.actor());
        });
    }

    @Override
    public ReferenceService.Reference transfer(ApiContext ctx, String referenceId, TransferRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_REFERENCE, "ASSIGN", referenceId);
            return m.references().transfer(c, ctx.tenant(), referenceId, r.toOrgUnit(), r.toSeat(), r.remarks(), ctx.actor());
        });
    }

    @Override
    public ReferenceService.Reference startWork(ApiContext ctx, String referenceId) {
        return tx(c -> {
            require(c, ctx, CASE_REFERENCE, "MODIFY", referenceId);
            return m.references().startWork(c, ctx.tenant(), referenceId, ctx.actor());
        });
    }

    @Override
    public ReferenceService.Reference interimReply(ApiContext ctx, String referenceId, ReplyRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_REFERENCE, "MODIFY", referenceId);
            return m.references().interimReply(c, ctx.tenant(), referenceId, r.text(), r.replyRef(), ctx.actor());
        });
    }

    @Override
    public ReferenceService.Reference draftReply(ApiContext ctx, String referenceId, String text) {
        return tx(c -> {
            require(c, ctx, CASE_REFERENCE, "MODIFY", referenceId);
            return m.references().draftReply(c, ctx.tenant(), referenceId, text, ctx.actor());
        });
    }

    @Override
    public ReferenceService.Reference approveReply(ApiContext ctx, String referenceId, String draftHash) {
        return tx(c -> {
            require(c, ctx, CASE_REFERENCE, "APPROVE", referenceId);
            return m.references().approveReply(c, ctx.tenant(), referenceId, draftHash, ctx.actor());
        });
    }

    @Override
    public ReferenceService.Reference reply(ApiContext ctx, String referenceId, ReplyRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_REFERENCE, "MODIFY", referenceId);
            return m.references().reply(c, ctx.tenant(), referenceId, r.text(), r.replyRef(), ctx.actor());
        });
    }

    @Override
    public ReferenceService.Reference extend(ApiContext ctx, String referenceId, ReferenceRestPort.ExtendRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_REFERENCE, "APPROVE", referenceId);
            return m.references().extend(c, ctx.tenant(), referenceId, r.newDueAt(), r.reason(), ctx.actor());
        });
    }

    @Override
    public ReferenceService.Reference followUp(ApiContext ctx, String referenceId, FollowUpRequest r) {
        return tx(c -> {
            require(c, ctx, CASE_REFERENCE, "MODIFY", referenceId);
            require(c, ctx, CASE_REFERENCE, "START", ALL);
            return m.references().spawnFollowUp(c, ctx.tenant(), referenceId, r.subject(), r.fixedDueAt(), r.attributes(), ctx.actor());
        });
    }

    @Override
    public ReferenceService.Reference close(ApiContext ctx, String referenceId, String reason) {
        return tx(c -> {
            require(c, ctx, CASE_REFERENCE, "MODIFY", referenceId);
            return m.references().close(c, ctx.tenant(), referenceId, reason, ctx.actor());
        });
    }

    @Override
    public List<ReferenceService.Pendency> pendency(ApiContext ctx, String kind, Duration dueSoonWindow) {
        return tx(c -> {
            require(c, ctx, CASE_REFERENCE, "READ", ALL);
            return m.references().pendency(c, ctx.tenant(), kind, dueSoonWindow);
        });
    }
}
