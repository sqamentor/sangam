// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.history.HistoricCaseInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.task.Task;
import org.sangam.ports.content.ContentService;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * {@link DocumentPort} on sangam-flowable-ports' content service (FLOWABLE-INTO-OPERATON FC-40, EC-18, R74): immutable versions with SHA-256
 * verified on read, pluggable store (file system / S3), optional malware scan. Scopes: {@code bpmn} (process
 * instance), {@code cmmn} (case instance) and {@code task} (a task without a process). Attachment limits (K12): type
 * allow-list and 25 MB. A document under legal hold (R61) cannot be deleted ({@code 409 LEGAL_HOLD}). Holds are
 * persisted in the product table {@code LEGAL_HOLD} ({@code root_id} = document id; a hold is active while
 * {@code released_at} is null).
 */
public final class PortsDocumentBridge implements DocumentPort {

    static final long MAX_BYTES = 25L * 1024 * 1024;
    static final Set<String> ALLOWED = Set.of("application/pdf", "image/png", "image/jpeg", "image/tiff",
            "application/xml", "text/xml", "application/json", "text/csv", "text/plain", "text/html",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.oasis.opendocument.text");

    private final ContentService content;
    private final TaskService tasks;
    private final HistoryService history;
    private final AuditPort audit;
    private final RestProperties props;
    private final TxSupport tx;
    private final long maxBytes;

    public PortsDocumentBridge(ContentService content, TaskService tasks, HistoryService history, AuditPort audit,
                               RestProperties props, TxSupport tx) {
        this(content, tasks, history, audit, props, tx, ContentService.DEFAULT_MAX_BYTES);
    }

    /** @param maxBytes {@code sangam.content.max-bytes}: the bound of a download read (audit PR-4, FW-40) */
    public PortsDocumentBridge(ContentService content, TaskService tasks, HistoryService history, AuditPort audit,
                               RestProperties props, TxSupport tx, long maxBytes) {
        this.maxBytes = maxBytes <= 0 ? ContentService.DEFAULT_MAX_BYTES : maxBytes;
        this.tx = tx;
        this.content = content;
        this.tasks = tasks;
        this.history = history;
        this.audit = audit;
        this.props = props;
    }

    @Override
    public Document upload(RequestContext ctx, UploadRequest r) {
        check(r);
        Scope s = scope(ctx, r.taskId(), r.processInstanceId(), r.caseInstanceId());
        ContentService.ContentItem item = PortsErrors.call(() -> content.create(ctx.tenantId(), r.name(), r.contentType(),
                s.type(), s.id(), r.taskId(), new ByteArrayInputStream(r.content()), ctx.principalId()));
        Document d = map(item);
        audit.record(ctx, chain(s), "DOCUMENT_UPLOADED", payload(d));
        return d;
    }

    @Override
    public Document newVersion(RequestContext ctx, String documentId, UploadRequest r) {
        ContentService.ContentItem item = visible(ctx, documentId);
        check(new UploadRequest(r.name() != null ? r.name() : item.name(),
                r.contentType() != null ? r.contentType() : item.mimeType(), r.content(), null, null, null, null));
        PortsErrors.call(() -> content.addVersion(documentId, new ByteArrayInputStream(r.content()), ctx.principalId()));
        Document d = map(visible(ctx, documentId));
        audit.record(ctx, chain(new Scope(item.scopeType(), item.scopeId())), "DOCUMENT_VERSION_ADDED", payload(d));
        return d;
    }

    @Override
    public Page<Document> list(RequestContext ctx, DocumentFilter f, Page.PageRequest page) {
        DocumentFilter filter = f == null ? new DocumentFilter(null, null, null, null) : f;
        if (filter.taskId() == null && filter.processInstanceId() == null && filter.caseInstanceId() == null) {
            throw ApiProblem.badRequest("SCOPE_REQUIRED", "Give taskId, processInstanceId or caseInstanceId");
        }
        Scope s = scope(ctx, filter.taskId(), filter.processInstanceId(), filter.caseInstanceId());
        List<Document> out = new ArrayList<>();
        for (ContentService.ContentItem i : PortsErrors.call(() -> content.byScope(ctx.tenantId(), s.type(), s.id()))) {
            if (i.deleted()) {
                continue;
            }
            if (filter.taskId() != null && !filter.taskId().equals(i.taskId()) && !"task".equals(s.type())) {
                continue;
            }
            if (filter.name() != null && !i.name().toLowerCase(Locale.ROOT).contains(filter.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(map(i));
        }
        return Page.slice(out, page, props.getCountCap());
    }

    @Override
    public Document get(RequestContext ctx, String documentId) {
        return map(visible(ctx, documentId));
    }

    @Override
    public Download content(RequestContext ctx, String documentId, Integer version) {
        ContentService.ContentItem item = visible(ctx, documentId);
        int v = version == null ? item.currentVersion() : version;
        // Audit PR-4: the tenant-checked open (FW-39) and true streaming (FW-40): visibility is checked above, before
        // any byte is sent; the stream is opened (tenant-checked again) when the response is written, copied without
        // buffering, and cut off above sangam.content.max-bytes.
        String type = item.mimeType() == null ? "application/octet-stream" : item.mimeType();
        String tenant = ctx.tenantId();
        long limit = maxBytes;
        return Download.stream(type, item.name(), -1, () -> {
            InputStream in = PortsErrors.call(() -> content.open(tenant, documentId, v));
            return new java.io.FilterInputStream(in) {
                private long read;

                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int n = super.read(buf, off, len);
                    if (n > 0) {
                        count(n);
                    }
                    return n;
                }

                private void count(long n) throws IOException {
                    read += n;
                    if (read > limit) {
                        throw new IOException("document " + documentId + " is larger than sangam.content.max-bytes ("
                                + limit + ")");
                    }
                }
            };
        });
    }

    @Override
    public void delete(RequestContext ctx, String documentId, String reason) {
        PortsErrors.requireReason(reason);
        ContentService.ContentItem item = visible(ctx, documentId);
        if (onHold(ctx.tenantId(), documentId)) {
            throw ApiProblem.conflict("LEGAL_HOLD", "Document " + documentId + " is under legal hold");
        }
        PortsErrors.run(() -> content.delete(documentId));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("document_id", documentId);
        p.put("reason", reason);
        audit.record(ctx, chain(new Scope(item.scopeType(), item.scopeId())), "DOCUMENT_DELETED", p);
    }

    // ---------------------------------------------------------------- shared with PortsDocumentExtrasBridge

    ContentService content() {
        return content;
    }

    ContentService.ContentItem visible(RequestContext ctx, String documentId) {
        ContentService.ContentItem item = PortsErrors.call(() -> content.item(documentId))
                .orElseThrow(() -> ApiProblem.notFound("document", documentId));
        if (item.deleted() || !String.valueOf(item.tenantId()).equals(ctx.tenantId())) {
            throw ApiProblem.notFound("document", documentId);
        }
        return item;
    }

    /** Sets or releases the legal hold; returns true when the state changed. */
    boolean setHold(RequestContext ctx, String documentId, boolean hold, String reason) {
        visible(ctx, documentId);
        return tx.withConnection(c -> {
            boolean active = !Jdbc.query(c, "SELECT hold_id FROM LEGAL_HOLD WHERE tenant_id=? AND root_id=?"
                    + " AND released_at IS NULL", rs -> rs.getString(1), ctx.tenantId(), documentId).isEmpty();
            java.time.Instant now = Jdbc.dbNow(c, tx.dialect());
            if (hold && !active) {
                Jdbc.update(c, "INSERT INTO LEGAL_HOLD(tenant_id, root_id, hold_id, reason, held_by, created_at)"
                                + " VALUES (?,?,?,?,?,?)", ctx.tenantId(), documentId,
                        org.sangam.core.layer.Hashes.uuidV7().toString(), reason, ctx.principalId(), now);
                return true;
            }
            if (!hold && active) {
                Jdbc.update(c, "UPDATE LEGAL_HOLD SET released_at=?, released_by=? WHERE tenant_id=? AND root_id=?"
                        + " AND released_at IS NULL", now, ctx.principalId(), ctx.tenantId(), documentId);
                return true;
            }
            return false;
        });
    }

    boolean onHold(String tenant, String documentId) {
        return tx.withConnection(c -> !Jdbc.query(c, "SELECT hold_id FROM LEGAL_HOLD WHERE tenant_id=? AND root_id=?"
                + " AND released_at IS NULL", rs -> rs.getString(1), tenant, documentId).isEmpty());
    }

    Document map(ContentService.ContentItem i) {
        ContentService.ContentVersion v = PortsErrors.call(() -> content.version(i.id(), i.currentVersion())).orElse(null);
        return mapVersion(i, v);
    }

    Document mapVersion(ContentService.ContentItem i, ContentService.ContentVersion v) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("scopeType", i.scopeType());
        meta.put("scopeId", i.scopeId());
        meta.put("legalHold", onHold(i.tenantId(), i.id()));
        return new Document(i.id(), i.tenantId(), i.name(), i.mimeType(), v == null ? 0 : v.sizeBytes(),
                v == null ? null : v.sha256(), v == null ? i.currentVersion() : v.version(), i.taskId(),
                "bpmn".equals(i.scopeType()) ? i.scopeId() : null, "cmmn".equals(i.scopeType()) ? i.scopeId() : null,
                v == null ? "NOT_SCANNED" : v.scanState().name(), i.createdBy(), i.createdAt(), meta);
    }

    record Scope(String type, String id) {
    }

    String chain(Scope s) {
        return s.id() == null ? "doc" : s.id();
    }

    Scope scope(RequestContext ctx, String taskId, String processInstanceId, String caseInstanceId) {
        if (processInstanceId != null) {
            HistoricProcessInstance p = history.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId)
                    .singleResult();
            if (p == null || !ctx.tenantId().equals(p.getTenantId())) {
                throw ApiProblem.notFound("process instance", processInstanceId);
            }
            return new Scope("bpmn", processInstanceId);
        }
        if (caseInstanceId != null) {
            HistoricCaseInstance c = history.createHistoricCaseInstanceQuery().caseInstanceId(caseInstanceId).singleResult();
            if (c == null || !ctx.tenantId().equals(c.getTenantId())) {
                throw ApiProblem.notFound("case instance", caseInstanceId);
            }
            return new Scope("cmmn", caseInstanceId);
        }
        if (taskId != null) {
            Task t = tasks.createTaskQuery().taskId(taskId).singleResult();
            if (t == null || !ctx.tenantId().equals(t.getTenantId())) {
                throw ApiProblem.notFound("task", taskId);
            }
            if (t.getProcessInstanceId() != null) {
                return new Scope("bpmn", t.getProcessInstanceId());
            }
            if (t.getCaseInstanceId() != null) {
                return new Scope("cmmn", t.getCaseInstanceId());
            }
            return new Scope("task", taskId);
        }
        throw ApiProblem.badRequest("SCOPE_REQUIRED", "Give taskId, processInstanceId or caseInstanceId");
    }

    private static void check(UploadRequest r) {
        if (r == null || r.content() == null || r.name() == null || r.name().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "name and file are required");
        }
        if (r.content().length > MAX_BYTES) {
            throw ApiProblem.unprocessable("DOCUMENT_REJECTED", "Documents are limited to 25 MB (K12)");
        }
        String ct = r.contentType() == null ? "" : r.contentType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (!ALLOWED.contains(ct)) {
            throw ApiProblem.unprocessable("DOCUMENT_REJECTED", "Content type '" + ct + "' is not allowed (K12)");
        }
    }

    private static Map<String, Object> payload(Document d) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("document_id", d.id());
        p.put("name", d.name());
        p.put("sha256", d.sha256());
        p.put("version", d.version());
        p.put("scan_status", d.scanStatus());
        return p;
    }
}
