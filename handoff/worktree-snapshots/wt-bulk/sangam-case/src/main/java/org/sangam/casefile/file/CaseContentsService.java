// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.file;

import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.content.ContentService;
import org.sangam.ports.links.InstanceLinkService;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What a case file holds besides its parties (N04): linked records (process and case instances, tasks, files,
 * applications, other systems' records), a document folder tree over sangam-flowable-ports' versioned content store
 * (FLOWABLE-INTO-OPERATON FC-40: SHA-256, scan hook, S3 or file system), exhibits, and notes (append-only; an edit supersedes).
 */
public class CaseContentsService {

    /** recordType is data: bpmn, cmmn, task, application, file, external ... ; relation: WORKED_BY, RELATES_TO, EVIDENCE ... */
    public record LinkedRecord(String tenantId, String recordId, String caseId, String recordType, String recordRef,
                               String relation, String title, String addedBy, Instant addedAt, Instant removedAt) {
    }

    public record Folder(String tenantId, String folderId, String caseId, String parentFolderId, String name, String createdBy,
                         Instant createdAt) {
    }

    public record Document(String tenantId, String documentId, String caseId, String folderId, String contentItemId, String title,
                           String docType, String confidentiality, boolean exhibit, String exhibitRef, String addedBy,
                           Instant addedAt, Instant removedAt) {
    }

    /** visibility: ALL (everyone who may read the case), INTERNAL (officers only), PRIVATE (author only). */
    public record Note(String tenantId, String noteId, String caseId, String body, String visibility, String author,
                       Instant createdAt, String supersedes, boolean superseded) {
    }

    private static final Set<String> VISIBILITY = Set.of("ALL", "INTERNAL", "PRIVATE");
    private static final Set<String> ENGINE_SCOPES = Set.of("bpmn", "cmmn", "task");

    private final Dialect dialect;
    private final Transactions tx;
    private final CaseEvents events;
    private final ContentService content;
    private final InstanceLinkService links;

    /** @param contentOrNull without a content store only already-stored items can be attached by id */
    public CaseContentsService(Dialect dialect, Transactions tx, CaseEvents events, ContentService contentOrNull,
                               InstanceLinkService linksOrNull) {
        this.dialect = dialect;
        this.tx = tx;
        this.events = events;
        this.content = contentOrNull;
        this.links = linksOrNull;
    }

    // ------------------------------------------------------------------ linked records

    public LinkedRecord linkRecord(Connection c, String tenant, String caseId, String recordType, String recordRef, String relation,
                                   String title, String actor) {
        String t = Db.tenant(tenant);
        requireCase(c, t, caseId);
        Db.requireText(recordType, "recordType");
        Db.requireText(recordRef, "recordRef");
        String rel = relation == null ? "RELATES_TO" : relation;
        List<LinkedRecord> existing = Jdbc.query(c, "SELECT * FROM CF_CASE_RECORD WHERE tenant_id=? AND case_id=? AND record_type=?"
                + " AND record_ref=? AND relation=? AND removed_at IS NULL", CaseContentsService::recordRow, t, caseId, recordType, recordRef, rel);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO CF_CASE_RECORD(tenant_id, record_id, case_id, record_type, record_ref, relation, title, added_by, added_at)"
                + " VALUES (?,?,?,?,?,?,?,?," + dialect.now() + ")", t, id, caseId, recordType, recordRef, rel, title, actor);
        if (links != null && ENGINE_SCOPES.contains(recordType)) {
            links.link(c, t, "REFERENCE", null, "casefile", caseId, recordType, recordRef, actor);
        }
        events.record(c, t, CaseEvents.CASE, caseId, "RECORD_LINKED", actor, recordType + " " + recordRef,
                Db.kv("recordId", id, "recordType", recordType, "recordRef", recordRef, "relation", rel));
        return Jdbc.one(c, "SELECT * FROM CF_CASE_RECORD WHERE tenant_id=? AND record_id=?", CaseContentsService::recordRow, t, id).orElseThrow();
    }

    public void unlinkRecord(Connection c, String tenant, String recordId, String actor) {
        String t = Db.tenant(tenant);
        LinkedRecord r = Jdbc.one(c, "SELECT * FROM CF_CASE_RECORD WHERE tenant_id=? AND record_id=?", CaseContentsService::recordRow, t, recordId)
                .orElseThrow(() -> CaseFileException.notFound("linked record", recordId));
        Jdbc.update(c, "UPDATE CF_CASE_RECORD SET removed_at=" + dialect.now() + " WHERE tenant_id=? AND record_id=?", t, recordId);
        if (links != null && ENGINE_SCOPES.contains(r.recordType())) {
            links.unlink(c, t, "REFERENCE", "casefile", r.caseId(), r.recordType(), r.recordRef());
        }
        events.record(c, t, CaseEvents.CASE, r.caseId(), "RECORD_UNLINKED", actor, r.recordType() + " " + r.recordRef(),
                Db.kv("recordId", recordId));
    }

    public List<LinkedRecord> records(Connection c, String tenant, String caseId) {
        return Jdbc.query(c, "SELECT * FROM CF_CASE_RECORD WHERE tenant_id=? AND case_id=? AND removed_at IS NULL ORDER BY added_at",
                CaseContentsService::recordRow, Db.tenant(tenant), caseId);
    }

    /** Case files that hold a record (for "which files is this application / process on"). */
    public List<String> casesOfRecord(Connection c, String tenant, String recordType, String recordRef) {
        return Jdbc.query(c, "SELECT DISTINCT case_id FROM CF_CASE_RECORD WHERE tenant_id=? AND record_type=? AND record_ref=? AND removed_at IS NULL",
                rs -> rs.getString(1), Db.tenant(tenant), recordType, recordRef);
    }

    /** Engine instance ids on the file (timeline source). */
    public List<String> instanceIds(Connection c, String tenant, String caseId) {
        List<String> out = new ArrayList<>();
        for (LinkedRecord r : records(c, tenant, caseId)) {
            if (("bpmn".equals(r.recordType()) || "cmmn".equals(r.recordType())) && !out.contains(r.recordRef())) {
                out.add(r.recordRef());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ folders and documents

    public Folder createFolder(Connection c, String tenant, String caseId, String parentFolderId, String name, String actor) {
        String t = Db.tenant(tenant);
        requireCase(c, t, caseId);
        Db.requireText(name, "name");
        if (parentFolderId != null) {
            Folder parent = folder(c, t, parentFolderId);
            if (!parent.caseId().equals(caseId)) {
                throw CaseFileException.invalid("parent folder belongs to another case");
            }
        }
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO CF_FOLDER(tenant_id, folder_id, case_id, parent_folder_id, name, created_by, created_at)"
                + " VALUES (?,?,?,?,?,?," + dialect.now() + ")", t, id, caseId, parentFolderId, name, actor);
        events.record(c, t, CaseEvents.CASE, caseId, "FOLDER_CREATED", actor, name, Db.kv("folderId", id, "parentFolderId", parentFolderId));
        return folder(c, t, id);
    }

    public void renameFolder(Connection c, String tenant, String folderId, String name, String actor) {
        Folder f = folder(c, tenant, folderId);
        Jdbc.update(c, "UPDATE CF_FOLDER SET name=? WHERE tenant_id=? AND folder_id=?", Db.requireText(name, "name"), Db.tenant(tenant), folderId);
        events.record(c, tenant, CaseEvents.CASE, f.caseId(), "FOLDER_RENAMED", actor, f.name() + " -> " + name, Db.kv("folderId", folderId));
    }

    public Folder folder(Connection c, String tenant, String folderId) {
        return Jdbc.one(c, "SELECT * FROM CF_FOLDER WHERE tenant_id=? AND folder_id=?", CaseContentsService::folderRow, Db.tenant(tenant), folderId)
                .orElseThrow(() -> CaseFileException.notFound("folder", folderId));
    }

    public List<Folder> folders(Connection c, String tenant, String caseId) {
        return Jdbc.query(c, "SELECT * FROM CF_FOLDER WHERE tenant_id=? AND case_id=? ORDER BY name", CaseContentsService::folderRow,
                Db.tenant(tenant), caseId);
    }

    /** Files an item already in the content store (uploaded through the content API, a task form, a connector). */
    public Document attach(Connection c, String tenant, String caseId, String folderId, String contentItemId, String title,
                           String docType, String confidentiality, boolean exhibit, String exhibitRef, String actor) {
        String t = Db.tenant(tenant);
        requireCase(c, t, caseId);
        Db.requireText(contentItemId, "contentItemId");
        if (content != null) {
            ContentService.ContentItem item = content.item(contentItemId)
                    .orElseThrow(() -> CaseFileException.notFound("content item", contentItemId));
            if (!Db.tenant(item.tenantId()).equals(t) || item.deleted()) {
                throw CaseFileException.notFound("content item", contentItemId);
            }
        }
        if (folderId != null && !folder(c, t, folderId).caseId().equals(caseId)) {
            throw CaseFileException.invalid("folder belongs to another case");
        }
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO CF_DOCUMENT(tenant_id, document_id, case_id, folder_id, content_item_id, title, doc_type, confidentiality,"
                        + " exhibit, exhibit_ref, added_by, added_at) VALUES (?,?,?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                t, id, caseId, folderId, contentItemId, Db.requireText(title, "title"), docType,
                confidentiality == null ? "NORMAL" : confidentiality, Db.small(exhibit), exhibitRef, actor);
        events.record(c, t, CaseEvents.CASE, caseId, exhibit ? "EXHIBIT_ADDED" : "DOCUMENT_ADDED", actor, title,
                Db.kv("documentId", id, "contentItemId", contentItemId, "docType", docType, "exhibitRef", exhibitRef));
        return document(c, t, id);
    }

    /** Stores the bytes (own content transaction, as the content store requires) and files them on the case. */
    public Document upload(String tenant, String caseId, String folderId, String fileName, String mimeType, InputStream bytes,
                           String title, String docType, String confidentiality, boolean exhibit, String exhibitRef, String actor) {
        if (content == null) {
            throw new CaseFileException(CaseFileException.Code.INVALID_STATE, "no content store configured");
        }
        tx.inTransaction(c -> requireCase(c, Db.tenant(tenant), caseId));
        ContentService.ContentItem item = content.create(Db.tenant(tenant), fileName, mimeType, "casefile", caseId, null, bytes, actor);
        return tx.inTransaction(c -> attach(c, tenant, caseId, folderId, item.id(), title == null ? fileName : title, docType,
                confidentiality, exhibit, exhibitRef, actor));
    }

    /** A new version of a filed document's content (FLOWABLE-INTO-OPERATON FC-40 versioning). */
    public ContentService.ContentVersion newVersion(String tenant, String documentId, InputStream bytes, String actor) {
        if (content == null) {
            throw new CaseFileException(CaseFileException.Code.INVALID_STATE, "no content store configured");
        }
        Document d = tx.inTransaction(c -> document(c, tenant, documentId));
        ContentService.ContentVersion v = content.addVersion(d.contentItemId(), bytes, actor);
        tx.inTransaction(c -> events.record(c, tenant, CaseEvents.CASE, d.caseId(), "DOCUMENT_VERSIONED", actor, d.title(),
                Db.kv("documentId", documentId, "version", v.version(), "sha256", v.sha256())));
        return v;
    }

    public void moveDocument(Connection c, String tenant, String documentId, String folderId, String actor) {
        Document d = document(c, tenant, documentId);
        if (folderId != null && !folder(c, tenant, folderId).caseId().equals(d.caseId())) {
            throw CaseFileException.invalid("folder belongs to another case");
        }
        Jdbc.update(c, "UPDATE CF_DOCUMENT SET folder_id=? WHERE tenant_id=? AND document_id=?", folderId, Db.tenant(tenant), documentId);
        events.record(c, tenant, CaseEvents.CASE, d.caseId(), "DOCUMENT_MOVED", actor, d.title(), Db.kv("documentId", documentId, "folderId", folderId));
    }

    /** Removes the document from the file (the content item stays, under its own retention). */
    public void removeDocument(Connection c, String tenant, String documentId, String reason, String actor) {
        Document d = document(c, tenant, documentId);
        Db.requireText(reason, "reason");
        Jdbc.update(c, "UPDATE CF_DOCUMENT SET removed_at=" + dialect.now() + " WHERE tenant_id=? AND document_id=?", Db.tenant(tenant), documentId);
        events.record(c, tenant, CaseEvents.CASE, d.caseId(), "DOCUMENT_REMOVED", actor, d.title(), Db.kv("documentId", documentId, "reason", reason));
    }

    public Document document(Connection c, String tenant, String documentId) {
        return Jdbc.one(c, "SELECT * FROM CF_DOCUMENT WHERE tenant_id=? AND document_id=?", CaseContentsService::documentRow,
                Db.tenant(tenant), documentId).orElseThrow(() -> CaseFileException.notFound("document", documentId));
    }

    public List<Document> documents(Connection c, String tenant, String caseId, String folderIdOrNull, boolean exhibitsOnly) {
        String sql = "SELECT * FROM CF_DOCUMENT WHERE tenant_id=? AND case_id=? AND removed_at IS NULL"
                + (folderIdOrNull == null ? "" : " AND folder_id=?") + (exhibitsOnly ? " AND exhibit=1" : "") + " ORDER BY added_at";
        return folderIdOrNull == null
                ? Jdbc.query(c, sql, CaseContentsService::documentRow, Db.tenant(tenant), caseId)
                : Jdbc.query(c, sql, CaseContentsService::documentRow, Db.tenant(tenant), caseId, folderIdOrNull);
    }

    public InputStream open(String tenant, String documentId, int version) {
        if (content == null) {
            throw new CaseFileException(CaseFileException.Code.INVALID_STATE, "no content store configured");
        }
        Document d = tx.inTransaction(c -> document(c, tenant, documentId));
        return content.open(d.contentItemId(), version);
    }

    // ------------------------------------------------------------------ notes

    public Note addNote(Connection c, String tenant, String caseId, String body, String visibility, String actor) {
        String t = Db.tenant(tenant);
        requireCase(c, t, caseId);
        String v = visibility == null ? "INTERNAL" : visibility;
        if (!VISIBILITY.contains(v)) {
            throw CaseFileException.invalid("visibility must be one of " + VISIBILITY);
        }
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO CF_NOTE(tenant_id, note_id, case_id, body, visibility, author, created_at, superseded)"
                + " VALUES (?,?,?,?,?,?," + dialect.now() + ",0)", t, id, caseId, Db.requireText(body, "body"), v, actor);
        events.record(c, t, CaseEvents.CASE, caseId, "NOTE_ADDED", actor, null, Db.kv("noteId", id, "visibility", v));
        return note(c, t, id);
    }

    /** Edit = a new note that supersedes the old one; only the author may edit. */
    public Note editNote(Connection c, String tenant, String noteId, String body, String actor) {
        String t = Db.tenant(tenant);
        Note old = note(c, t, noteId);
        if (!old.author().equals(actor)) {
            throw new CaseFileException(CaseFileException.Code.ACCESS_DENIED, "only the author can edit a note");
        }
        if (old.superseded()) {
            throw CaseFileException.state("note already superseded");
        }
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO CF_NOTE(tenant_id, note_id, case_id, body, visibility, author, created_at, supersedes, superseded)"
                + " VALUES (?,?,?,?,?,?," + dialect.now() + ",?,0)", t, id, old.caseId(), Db.requireText(body, "body"), old.visibility(), actor, noteId);
        Jdbc.update(c, "UPDATE CF_NOTE SET superseded=1 WHERE tenant_id=? AND note_id=?", t, noteId);
        events.record(c, t, CaseEvents.CASE, old.caseId(), "NOTE_EDITED", actor, null, Db.kv("noteId", id, "supersedes", noteId));
        return note(c, t, id);
    }

    public Note note(Connection c, String tenant, String noteId) {
        return Jdbc.one(c, "SELECT * FROM CF_NOTE WHERE tenant_id=? AND note_id=?", CaseContentsService::noteRow, Db.tenant(tenant), noteId)
                .orElseThrow(() -> CaseFileException.notFound("note", noteId));
    }

    /** Current notes the viewer may see: ALL, INTERNAL when {@code officer}, PRIVATE only the viewer's own. */
    public List<Note> notes(Connection c, String tenant, String caseId, String viewer, boolean officer) {
        List<Note> all = Jdbc.query(c, "SELECT * FROM CF_NOTE WHERE tenant_id=? AND case_id=? AND superseded=0 ORDER BY created_at",
                CaseContentsService::noteRow, Db.tenant(tenant), caseId);
        return all.stream().filter(n -> n.visibility().equals("ALL") || (officer && n.visibility().equals("INTERNAL"))
                || (n.visibility().equals("PRIVATE") && n.author().equals(viewer))).toList();
    }

    // ------------------------------------------------------------------ merge / split support

    int moveAll(Connection c, String tenant, String fromCase, String toCase) {
        String t = Db.tenant(tenant);
        int n = 0;
        for (String table : List.of("CF_CASE_RECORD", "CF_FOLDER", "CF_DOCUMENT", "CF_NOTE", "CF_HEARING")) {
            n += Jdbc.update(c, "UPDATE " + table + " SET case_id=? WHERE tenant_id=? AND case_id=?", toCase, t, fromCase);
        }
        return n;
    }

    int copyRecords(Connection c, String tenant, List<String> recordIds, String toCase, String actor) {
        int n = 0;
        for (String id : recordIds) {
            LinkedRecord r = Jdbc.one(c, "SELECT * FROM CF_CASE_RECORD WHERE tenant_id=? AND record_id=?", CaseContentsService::recordRow,
                    Db.tenant(tenant), id).orElseThrow(() -> CaseFileException.notFound("linked record", id));
            linkRecord(c, tenant, toCase, r.recordType(), r.recordRef(), r.relation(), r.title(), actor);
            n++;
        }
        return n;
    }

    int moveDocuments(Connection c, String tenant, List<String> documentIds, String toCase) {
        int n = 0;
        for (String id : documentIds) {
            n += Jdbc.update(c, "UPDATE CF_DOCUMENT SET case_id=?, folder_id=NULL WHERE tenant_id=? AND document_id=?", toCase, Db.tenant(tenant), id);
        }
        return n;
    }

    private static Void requireCase(Connection c, String t, String caseId) {
        if (Jdbc.one(c, "SELECT COUNT(*) FROM CF_CASE WHERE tenant_id=? AND case_id=?", rs -> rs.getLong(1), t, caseId).orElse(0L) == 0) {
            throw CaseFileException.notFound("case", caseId);
        }
        return null;
    }

    static LinkedRecord recordRow(ResultSet rs) throws SQLException {
        return new LinkedRecord(rs.getString("tenant_id"), rs.getString("record_id"), rs.getString("case_id"), rs.getString("record_type"),
                rs.getString("record_ref"), rs.getString("relation"), rs.getString("title"), rs.getString("added_by"),
                Jdbc.instant(rs, "added_at"), Jdbc.instant(rs, "removed_at"));
    }

    static Folder folderRow(ResultSet rs) throws SQLException {
        return new Folder(rs.getString("tenant_id"), rs.getString("folder_id"), rs.getString("case_id"), rs.getString("parent_folder_id"),
                rs.getString("name"), rs.getString("created_by"), Jdbc.instant(rs, "created_at"));
    }

    static Document documentRow(ResultSet rs) throws SQLException {
        return new Document(rs.getString("tenant_id"), rs.getString("document_id"), rs.getString("case_id"), rs.getString("folder_id"),
                rs.getString("content_item_id"), rs.getString("title"), rs.getString("doc_type"), rs.getString("confidentiality"),
                rs.getInt("exhibit") != 0, rs.getString("exhibit_ref"), rs.getString("added_by"), Jdbc.instant(rs, "added_at"),
                Jdbc.instant(rs, "removed_at"));
    }

    static Note noteRow(ResultSet rs) throws SQLException {
        return new Note(rs.getString("tenant_id"), rs.getString("note_id"), rs.getString("case_id"), rs.getString("body"),
                rs.getString("visibility"), rs.getString("author"), Jdbc.instant(rs, "created_at"), rs.getString("supersedes"),
                rs.getInt("superseded") != 0);
    }

}
