// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.task.Attachment;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;

/**
 * Documents on Operaton task/process attachments (default until sangam-flowable-ports provides the R74 document
 * service). Each version is an immutable attachment; the attachment description holds the Sangam metadata as JSON:
 * {@code tenant, sha256, size, contentType, version, previous, documentId, metadata}. K12 limits: content-type allow
 * list and 25 MB. No malware scanner is wired here, so {@code scanStatus} is {@code NOT_SCANNED}.
 */
public class OperatonDocumentAdapter implements DocumentPort {

    static final long MAX_BYTES = 25L * 1024 * 1024;
    static final Set<String> ALLOWED = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/tiff", "application/xml", "text/xml",
            "application/json", "text/csv", "text/plain",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.oasis.opendocument.text");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CAP = 10_000;

    private final TaskService tasks;
    private final TxSupport tx;
    private final AuditPort audit;

    public OperatonDocumentAdapter(TaskService tasks, TxSupport tx, AuditPort audit) {
        this.tasks = tasks;
        this.tx = tx;
        this.audit = audit;
    }

    @Override
    public Document upload(RequestContext ctx, UploadRequest r) {
        validate(r);
        if (r.taskId() == null && r.processInstanceId() == null) {
            throw ApiProblem.badRequest("SCOPE_REQUIRED", "taskId or processInstanceId is required");
        }
        return tx.inTx(() -> store(ctx, r, null, 1));
    }

    @Override
    public Document newVersion(RequestContext ctx, String documentId, UploadRequest r) {
        validate(r);
        return tx.inTx(() -> {
            Attachment prev = require(ctx, documentId);
            Map<String, Object> meta = meta(prev);
            int version = ((Number) meta.getOrDefault("version", 1)).intValue() + 1;
            String rootDoc = String.valueOf(meta.getOrDefault("documentId", prev.getId()));
            UploadRequest scoped = new UploadRequest(r.name() == null ? prev.getName() : r.name(), r.contentType(),
                    r.content(), prev.getTaskId(), prev.getProcessInstanceId(), null,
                    r.metadata() == null ? castMap(meta.get("metadata")) : r.metadata());
            Document d = store(ctx, scoped, prev.getId(), version);
            // Keep the logical document id of version 1 so all versions can be found together.
            Attachment created = tasks.getAttachment(d.id());
            Map<String, Object> m = meta(created);
            m.put("documentId", rootDoc);
            created.setDescription(write(m));
            tasks.saveAttachment(created);
            return d;
        });
    }

    private Document store(RequestContext ctx, UploadRequest r, String previous, int version) {
        if (r.taskId() != null) {
            org.operaton.bpm.engine.task.Task t = OperatonSupport.call(() ->
                    tasks.createTaskQuery().taskId(r.taskId()).singleResult());
            if (t == null) {
                throw ApiProblem.notFound("task", r.taskId());
            }
            OperatonSupport.checkTenant(ctx, t.getTenantId(), "task", r.taskId());
        }
        String sha = Hashes.hex(Hashes.sha256(r.content()));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tenant", ctx.tenantId());
        meta.put("sha256", sha);
        meta.put("size", r.content().length);
        meta.put("contentType", r.contentType());
        meta.put("version", version);
        meta.put("previous", previous);
        meta.put("createdBy", ctx.principalId());
        meta.put("scanStatus", "NOT_SCANNED");
        meta.put("metadata", r.metadata() == null ? Map.of() : r.metadata());
        meta.values().removeIf(Objects::isNull);
        String description = write(meta);
        Attachment a = OperatonSupport.call(() -> tasks.createAttachment(r.contentType(), r.taskId(),
                r.taskId() == null ? r.processInstanceId() : null, r.name(), description,
                new ByteArrayInputStream(r.content())));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("document_id", a.getId());
        p.put("name", r.name());
        p.put("sha256", sha);
        p.put("size", r.content().length);
        p.put("content_type", r.contentType());
        p.put("version", version);
        p.put("previous", previous);
        p.put("task_id", r.taskId());
        p.put("scan_status", "NOT_SCANNED");
        p.put("actor", ctx.actor());
        p.values().removeIf(Objects::isNull);
        String chain = a.getProcessInstanceId() != null ? a.getProcessInstanceId()
                : r.processInstanceId() != null ? r.processInstanceId() : "task:" + r.taskId();
        audit.record(ctx, chain, "DOCUMENT_UPLOADED", p);
        return map(a);
    }

    @Override
    public Page<Document> list(RequestContext ctx, DocumentFilter f, Page.PageRequest page) {
        if (f == null || (f.taskId() == null && f.processInstanceId() == null)) {
            if (f != null && f.caseInstanceId() != null) {
                throw ApiProblem.unprocessable("UNSUPPORTED_SCOPE", "case attachments need sangam-flowable-ports");
            }
            throw ApiProblem.badRequest("FILTER_REQUIRED", "taskId or processInstanceId is required");
        }
        List<Attachment> all = new ArrayList<>();
        if (f.taskId() != null) {
            all.addAll(OperatonSupport.call(() -> tasks.getTaskAttachments(f.taskId())));
        }
        if (f.processInstanceId() != null) {
            all.addAll(OperatonSupport.call(() -> tasks.getProcessInstanceAttachments(f.processInstanceId())));
        }
        List<Document> docs = all.stream()
                .filter(a -> visible(ctx, a))
                .filter(a -> f.name() == null || (a.getName() != null && a.getName().contains(f.name())))
                .map(OperatonDocumentAdapter::map)
                .sorted(Comparator.comparing(Document::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return Page.slice(docs, page, CAP);
    }

    @Override
    public Document get(RequestContext ctx, String documentId) {
        return map(require(ctx, documentId));
    }

    @Override
    public Download content(RequestContext ctx, String documentId, Integer version) {
        Attachment a = require(ctx, documentId);
        if (version != null) {
            a = findVersion(ctx, a, version);
        }
        String id = a.getId();
        try (InputStream in = OperatonSupport.call(() -> tasks.getAttachmentContent(id))) {
            if (in == null) {
                throw ApiProblem.notFound("document content", id);
            }
            byte[] bytes = in.readAllBytes();
            Map<String, Object> meta = meta(a);
            Object expected = meta.get("sha256");
            if (expected != null && !expected.equals(Hashes.hex(Hashes.sha256(bytes)))) {
                throw new ApiProblem(500, "DOCUMENT_CHECKSUM_MISMATCH", "stored content does not match its checksum");
            }
            String ct = a.getType() == null ? "application/octet-stream" : a.getType();
            return new Download(ct, a.getName() == null ? id : a.getName(), bytes);
        } catch (IOException e) {
            throw new ApiProblem(500, "RESOURCE_READ_FAILED", e.getMessage());
        }
    }

    private Attachment findVersion(RequestContext ctx, Attachment latest, int version) {
        Attachment cur = latest;
        for (int guard = 0; guard < 1000 && cur != null; guard++) {
            Map<String, Object> m = meta(cur);
            int v = ((Number) m.getOrDefault("version", 1)).intValue();
            if (v == version) {
                return cur;
            }
            Object prev = m.get("previous");
            cur = prev == null ? null : tasks.getAttachment(String.valueOf(prev));
        }
        throw ApiProblem.notFound("document version", latest.getId() + "@" + version);
    }

    @Override
    public void delete(RequestContext ctx, String documentId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "A reason is required to delete a document");
        }
        tx.runInTx(() -> {
            Attachment a = require(ctx, documentId);
            if (Boolean.TRUE.equals(meta(a).get("legalHold"))) {
                throw ApiProblem.conflict("LEGAL_HOLD", "Document " + documentId + " is under legal hold (R61) and cannot be deleted");
            }
            OperatonSupport.run(() -> tasks.deleteAttachment(documentId));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("document_id", documentId);
            p.put("sha256", meta(a).get("sha256"));
            p.put("reason", reason);
            p.put("actor", ctx.actor());
            p.values().removeIf(Objects::isNull);
            String chain = a.getProcessInstanceId() != null ? a.getProcessInstanceId() : "task:" + a.getTaskId();
            audit.record(ctx, chain, "DOCUMENT_DELETED", p);
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void validate(UploadRequest r) {
        if (r == null || r.content() == null || r.content().length == 0) {
            throw ApiProblem.badRequest("CONTENT_REQUIRED", "the document content is required");
        }
        if (r.caseInstanceId() != null) {
            throw ApiProblem.unprocessable("UNSUPPORTED_SCOPE",
                    "Operaton attachments cannot be bound to a case instance; sangam-flowable-ports adds this");
        }
        String ct = r.contentType() == null ? "" : r.contentType().toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        if (semi >= 0) {
            ct = ct.substring(0, semi).trim();
        }
        if (!ALLOWED.contains(ct)) {
            throw ApiProblem.unprocessable("DOCUMENT_REJECTED", "content type '" + r.contentType() + "' is not allowed");
        }
        if (r.content().length > MAX_BYTES) {
            throw ApiProblem.unprocessable("DOCUMENT_REJECTED", "documents above 25 MB are not accepted");
        }
        if (r.name() == null || r.name().isBlank() || r.name().contains("/") || r.name().contains("\\")) {
            throw ApiProblem.badRequest("INVALID_NAME", "a plain file name is required");
        }
    }

    private Attachment require(RequestContext ctx, String id) {
        Attachment a = OperatonSupport.call(() -> tasks.getAttachment(id));
        if (a == null || !visible(ctx, a)) {
            throw ApiProblem.notFound("document", id);
        }
        return a;
    }

    static boolean visible(RequestContext ctx, Attachment a) {
        Object tenant = meta(a).get("tenant");
        return ctx.tenantId() == null || ctx.tenantId().equals(tenant);
    }

    static Document map(Attachment a) {
        Map<String, Object> m = meta(a);
        return new Document(a.getId(), m.get("tenant") == null ? null : String.valueOf(m.get("tenant")), a.getName(),
                a.getType(), ((Number) m.getOrDefault("size", 0)).longValue(),
                m.get("sha256") == null ? null : String.valueOf(m.get("sha256")),
                ((Number) m.getOrDefault("version", 1)).intValue(), a.getTaskId(), a.getProcessInstanceId(), null,
                String.valueOf(m.getOrDefault("scanStatus", "NOT_SCANNED")),
                m.get("createdBy") == null ? null : String.valueOf(m.get("createdBy")),
                OperatonSupport.instant(a.getCreateTime()), castMap(m.get("metadata")));
    }

    static Map<String, Object> meta(Attachment a) {
        String d = a.getDescription();
        if (d == null || d.isBlank() || !d.trim().startsWith("{")) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(d, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static String write(Map<String, Object> m) {
        try {
            return JSON.writeValueAsString(m);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
