// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.file;

import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.deadline.DeadlineService;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Related cases, merge and split (N04 {@code CaseLink}, GP-54). Link types: PARENT_OF, RELATED, DUPLICATE_OF,
 * MERGED_INTO, SPLIT_FROM, plus any data-defined type. Merge moves parties, records, folders, documents, notes, hearings,
 * and open deadlines into the surviving case and marks the merged case MERGED (it keeps its number, so old references
 * resolve, and its own journal, which the surviving case's timeline includes). Split opens a new case of the same (or another) type and moves or copies the selected
 * parties, records and documents into it.
 */
public class CaseLinkService {

    public static final Set<String> SYSTEM_TYPES = Set.of("MERGED_INTO", "SPLIT_FROM");

    public record CaseLink(String tenantId, String linkId, String fromCase, String toCase, String linkType, String reason,
                           String createdBy, Instant createdAt) {
    }

    public record SplitRequest(String newCaseType, String title, Map<String, Object> attributes, List<String> movePartyIds,
                               List<String> copyRecordIds, List<String> moveDocumentIds, String reason) {
    }

    private final Dialect dialect;
    private final CaseFileService cases;
    private final PartyService parties;
    private final CaseContentsService contents;
    private final DeadlineService deadlines;
    private final CaseEvents events;

    public CaseLinkService(Dialect dialect, CaseFileService cases, PartyService parties, CaseContentsService contents,
                           DeadlineService deadlines, CaseEvents events) {
        this.dialect = dialect;
        this.cases = cases;
        this.parties = parties;
        this.contents = contents;
        this.deadlines = deadlines;
        this.events = events;
    }

    public CaseLink link(Connection c, String tenant, String fromCase, String toCase, String linkType, String reason, String actor) {
        String t = Db.tenant(tenant);
        if (fromCase.equals(toCase)) {
            throw CaseFileException.invalid("a case cannot be linked to itself");
        }
        Db.requireText(linkType, "linkType");
        if (SYSTEM_TYPES.contains(linkType)) {
            throw CaseFileException.invalid(linkType + " is set by merge / split only");
        }
        cases.get(c, t, fromCase);
        cases.get(c, t, toCase);
        for (CaseLink l : links(c, t, fromCase)) {
            if (l.toCase().equals(toCase) && l.linkType().equals(linkType)) {
                return l;
            }
        }
        return insert(c, t, fromCase, toCase, linkType, reason, actor);
    }

    public void unlink(Connection c, String tenant, String linkId, String actor) {
        String t = Db.tenant(tenant);
        CaseLink l = Jdbc.one(c, "SELECT * FROM CF_CASE_LINK WHERE tenant_id=? AND link_id=? AND removed_at IS NULL", CaseLinkService::row, t, linkId)
                .orElseThrow(() -> CaseFileException.notFound("case link", linkId));
        if (SYSTEM_TYPES.contains(l.linkType())) {
            throw CaseFileException.state(l.linkType() + " links cannot be removed");
        }
        Jdbc.update(c, "UPDATE CF_CASE_LINK SET removed_at=" + dialect.now() + " WHERE tenant_id=? AND link_id=?", t, linkId);
        events.record(c, t, CaseEvents.CASE, l.fromCase(), "CASE_UNLINKED", actor, l.linkType(), Db.kv("linkId", linkId, "toCase", l.toCase()));
    }

    /** Links in both directions (a link from B to A is listed for A as well). */
    public List<CaseLink> links(Connection c, String tenant, String caseId) {
        return Jdbc.query(c, "SELECT * FROM CF_CASE_LINK WHERE tenant_id=? AND (from_case=? OR to_case=?) AND removed_at IS NULL ORDER BY created_at",
                CaseLinkService::row, Db.tenant(tenant), caseId, caseId);
    }

    /** Merges {@code mergedCase} into {@code survivingCase}. Both must be open. */
    public CaseFileService.CaseFile merge(Connection c, String tenant, String survivingCase, String mergedCase, String reason, String actor) {
        String t = Db.tenant(tenant);
        Db.requireText(reason, "reason");
        if (survivingCase.equals(mergedCase)) {
            throw CaseFileException.invalid("a case cannot be merged into itself");
        }
        CaseFileService.CaseFile keep = cases.requireOpen(c, t, survivingCase);
        CaseFileService.CaseFile gone = cases.requireOpen(c, t, mergedCase);
        int movedParties = parties.moveRoles(c, t, mergedCase, survivingCase, actor);
        int movedItems = contents.moveAll(c, t, mergedCase, survivingCase);
        int movedDeadlines = 0;
        for (DeadlineService.Deadline d : deadlines.forScope(c, t, CaseEvents.CASE, mergedCase)) {
            if (d.metAt() == null && d.state() != DeadlineService.State.CANCELLED) {
                movedDeadlines++;
            }
        }
        deadlines.move(c, t, CaseEvents.CASE, mergedCase, survivingCase);
        Jdbc.update(c, "UPDATE CF_CASE_LINK SET from_case=? WHERE tenant_id=? AND from_case=? AND removed_at IS NULL AND link_type NOT IN ('MERGED_INTO','SPLIT_FROM')",
                survivingCase, t, mergedCase);
        Jdbc.update(c, "UPDATE CF_CASE_LINK SET to_case=? WHERE tenant_id=? AND to_case=? AND removed_at IS NULL AND link_type NOT IN ('MERGED_INTO','SPLIT_FROM')",
                survivingCase, t, mergedCase);
        Instant now = Db.now(c, dialect);
        cases.bump(c, t, mergedCase, -1, "status=?, merged_into=?, closed_at=?, closed_by=?, close_reason=?",
                CaseFileService.Status.MERGED, survivingCase, now, actor, reason);
        insert(c, t, mergedCase, survivingCase, "MERGED_INTO", reason, actor);
        Map<String, Object> payload = Db.kv("survivingCase", survivingCase, "survivingNumber", keep.caseNumber(), "mergedCase", mergedCase,
                "mergedNumber", gone.caseNumber(), "parties", movedParties, "items", movedItems, "deadlines", movedDeadlines, "reason", reason);
        events.record(c, t, CaseEvents.CASE, mergedCase, "CASE_MERGED_INTO", actor, keep.caseNumber(), payload);
        events.record(c, t, CaseEvents.CASE, survivingCase, "CASE_MERGED", actor, gone.caseNumber(), payload);
        return cases.get(c, t, survivingCase);
    }

    /** Splits part of {@code sourceCase} into a new case; returns the new case. */
    public CaseFileService.CaseFile split(Connection c, String tenant, String sourceCase, SplitRequest r, String actor) {
        String t = Db.tenant(tenant);
        Db.requireText(r.reason(), "reason");
        CaseFileService.CaseFile src = cases.requireOpen(c, t, sourceCase);
        String type = r.newCaseType() == null ? src.caseType() : r.newCaseType();
        CaseFileService.CaseFile created = cases.open(c, t, new CaseFileService.OpenRequest(type,
                r.title() == null ? src.title() : r.title(), src.jurisdiction(), src.orgUnit(), null, src.ownerSeat(), src.priority(),
                src.confidentiality(), null, r.attributes() == null ? src.attributes() : r.attributes(), null, null), actor);
        String newId = created.caseId();
        cases.bump(c, t, newId, -1, "split_from=?", sourceCase);
        List<String> partyIds = r.movePartyIds() == null ? List.of() : r.movePartyIds();
        List<String> valid = new ArrayList<>();
        for (String cpId : partyIds) {
            PartyService.CaseParty cp = parties.caseParty(c, t, cpId);
            if (!cp.caseId().equals(sourceCase)) {
                throw CaseFileException.invalid("case party " + cpId + " is not on the source case");
            }
            valid.add(cpId);
        }
        parties.copyRoles(c, t, valid, newId, actor);
        for (String cpId : valid) {
            parties.removeFromCase(c, t, cpId, "split to " + created.caseNumber(), actor);
        }
        contents.copyRecords(c, t, r.copyRecordIds() == null ? List.of() : r.copyRecordIds(), newId, actor);
        List<String> docs = r.moveDocumentIds() == null ? List.of() : r.moveDocumentIds();
        for (String d : docs) {
            if (!contents.document(c, t, d).caseId().equals(sourceCase)) {
                throw CaseFileException.invalid("document " + d + " is not on the source case");
            }
        }
        contents.moveDocuments(c, t, docs, newId);
        insert(c, t, newId, sourceCase, "SPLIT_FROM", r.reason(), actor);
        Map<String, Object> payload = Db.kv("sourceCase", sourceCase, "sourceNumber", src.caseNumber(), "newCase", newId,
                "newNumber", created.caseNumber(), "parties", valid.size(), "documents", docs.size(), "reason", r.reason());
        events.record(c, t, CaseEvents.CASE, sourceCase, "CASE_SPLIT", actor, created.caseNumber(), payload);
        events.record(c, t, CaseEvents.CASE, newId, "CASE_SPLIT_FROM", actor, src.caseNumber(), payload);
        return cases.get(c, t, newId);
    }

    /** Follows MERGED_INTO links to the case that now holds the file. */
    public CaseFileService.CaseFile resolve(Connection c, String tenant, String caseId) {
        CaseFileService.CaseFile cf = cases.get(c, tenant, caseId);
        int guard = 0;
        while (cf.mergedInto() != null && guard++ < 32) {
            cf = cases.get(c, tenant, cf.mergedInto());
        }
        return cf;
    }

    private CaseLink insert(Connection c, String t, String from, String to, String type, String reason, String actor) {
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO CF_CASE_LINK(tenant_id, link_id, from_case, to_case, link_type, reason, created_by, created_at)"
                + " VALUES (?,?,?,?,?,?,?," + dialect.now() + ")", t, id, from, to, type, reason, actor);
        if (!SYSTEM_TYPES.contains(type)) {
            events.record(c, t, CaseEvents.CASE, from, "CASE_LINKED", actor, type, Db.kv("linkId", id, "toCase", to, "linkType", type));
        }
        return Jdbc.one(c, "SELECT * FROM CF_CASE_LINK WHERE tenant_id=? AND link_id=?", CaseLinkService::row, t, id).orElseThrow();
    }

    static CaseLink row(ResultSet rs) throws SQLException {
        return new CaseLink(rs.getString("tenant_id"), rs.getString("link_id"), rs.getString("from_case"), rs.getString("to_case"),
                rs.getString("link_type"), rs.getString("reason"), rs.getString("created_by"), Jdbc.instant(rs, "created_at"));
    }
}
