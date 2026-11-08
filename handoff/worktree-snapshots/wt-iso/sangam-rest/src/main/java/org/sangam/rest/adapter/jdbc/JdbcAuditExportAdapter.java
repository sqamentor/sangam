// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditExportPort;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**

 */
public class JdbcAuditExportAdapter implements AuditExportPort, AutoCloseable {

    static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final int PAGE = 500;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Set<String> SCOPE_KEYS = Set.of("businessKey", "chainId", "type", "actor", "from", "to");

    private final AuditPort audit;
    private final ProcessPort processes;
    private final DocumentPort documents;
    private final TxSupport tx;
    private final RestProperties props;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sangam-audit-export");
        t.setDaemon(true);
        return t;
    });

    /** REST_DOCUMENT kind of an export job (state QUEUED / RUNNING / READY / FAILED). */
    static final String JOB_KIND = "audit:export-job";
    /** REST_BLOB kind of an export file (owner_ref = job id). */
    static final String BLOB_KIND = "audit-export";

    private final org.sangam.rest.support.DocumentStore docs;
    private final org.sangam.rest.support.BlobStore blobs;

    public JdbcAuditExportAdapter(AuditPort audit, ProcessPort processes, DocumentPort documents, TxSupport tx,
                                  RestProperties props, org.sangam.rest.support.DocumentStore docs,
                                  org.sangam.rest.support.BlobStore blobs) {
        this.audit = audit;
        this.processes = processes;
        this.documents = documents;
        this.tx = tx;
        this.props = props;
        this.docs = java.util.Objects.requireNonNull(docs, "docs");
        this.blobs = java.util.Objects.requireNonNull(blobs, "blobs");
    }

    /**
     * Start-up recovery: QUEUED and RUNNING jobs (a RUNNING job was interrupted by a restart and wrote nothing) are
     * queued again. Call once after start-up.
     *
     * @return the number of jobs re-queued
     */
    public int resume() {
        List<String[]> open = tx.withConnection(c -> org.sangam.core.layer.Jdbc.query(c,
                "SELECT tenant_id, doc_id, owner_id FROM REST_DOCUMENT WHERE kind=? AND state IN ('QUEUED','RUNNING')",
                rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, JOB_KIND));
        for (String[] j : open) {
            RequestContext owner = new RequestContext(j[0], j[2] == null ? "system" : j[2], Set.of(), null, null, null,
                    null, null, "RESUMED");
            executor.submit(() -> run(owner, j[1]));
        }
        return open.size();
    }

    // ------------------------------------------------------------------ async exports

    @Override
    public ExportJob create(RequestContext ctx, ExportRequest request) {
        if (request == null || request.format() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "format is required: CSV | JSON | PDFA");
        }
        String format = request.format().toUpperCase(Locale.ROOT);
        if (!Set.of("CSV", "JSON", "PDFA").contains(format)) {
            throw ApiProblem.badRequest("UNSUPPORTED_FORMAT", "format must be CSV | JSON | PDFA");
        }
        Map<String, Object> scope = new LinkedHashMap<>(request.scope() == null ? Map.of() : request.scope());
        for (String k : scope.keySet()) {
            if (!SCOPE_KEYS.contains(k)) {
                throw ApiProblem.badRequest("VALIDATION_FAILED", "unknown scope key " + k + "; allowed: " + SCOPE_KEYS);
            }
        }
        if (scope.values().stream().allMatch(v -> v == null || String.valueOf(v).isBlank())) {
            throw ApiProblem.badRequest("SCOPE_REQUIRED", "an export must be bounded by at least one scope field");
        }
        String id = Hashes.uuidV7().toString();
        ExportJob queued = new ExportJob(id, "QUEUED", null, Instant.now(), format, Map.copyOf(withoutNulls(scope)), null);
        docs.insert(ctx.tenantId(), JOB_KIND, id, format, "QUEUED", ctx.principalId(), null, body(queued, null, null, null),
                ctx.principalId());
        afterCommit(() -> executor.submit(() -> run(ctx, id)));
        return queued;
    }

    private static Map<String, Object> body(ExportJob job, String blobId, String contentType, String fileName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job", plain(job));
        m.put("blobId", blobId);
        m.put("contentType", contentType);
        m.put("fileName", fileName);
        return m;
    }

    private static ExportJob view(org.sangam.rest.support.DocumentStore.Doc d) {
        return org.sangam.rest.support.DocumentStore.convert(d.body().get("job"), ExportJob.class);
    }

    private void run(RequestContext ctx, String id) {
        var doc = docs.get(ctx.tenantId(), JOB_KIND, id).orElse(null);
        if (doc == null || !Set.of("QUEUED", "RUNNING").contains(doc.state())) {
            return;
        }
        ExportJob v = view(doc);
        save(ctx, new ExportJob(id, "RUNNING", null, v.requestedAt(), v.format(), v.scope(), null), null, null, null);
        try {
            if (v.format().equals("PDFA")) {
                throw ApiProblem.unprocessable("FORMAT_NOT_AVAILABLE", "PDF/A rendering is provided by sangam-integration");
            }
            List<AuditPort.AuditEntry> entries = collect(ctx, v.scope());
            byte[] content = v.format().equals("JSON") ? JSON.writeValueAsBytes(plain(entries)) : csv(entries);
            if (content.length > MAX_BYTES) {
                throw ApiProblem.unprocessable("EXPORT_TOO_LARGE", "the export is above 50 MB; narrow the scope");
            }
            boolean json = v.format().equals("JSON");
            String type = json ? "application/json" : "text/csv; charset=UTF-8";
            String name = "audit-export-" + id + (json ? ".json" : ".csv");
            tx.runInTx(() -> {
                org.sangam.rest.support.BlobStore.BlobRef blob = blobs.put(ctx.tenantId(), BLOB_KIND, id, name, type,
                        content, ctx.principalId());
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("export_id", id);
                p.put("format", v.format());
                p.put("scope", v.scope());
                p.put("events", entries.size());
                p.put("sha256", blob.sha256());
                audit.record(ctx, "sec:" + ctx.tenantId(), "AUDIT_EXPORTED", p);
                save(ctx, new ExportJob(id, "READY", (long) entries.size(), v.requestedAt(), v.format(), v.scope(), null),
                        blob.id(), type, name);
            });
        } catch (ApiProblem e) {
            save(ctx, new ExportJob(id, "FAILED", null, v.requestedAt(), v.format(), v.scope(),
                    e.code() + ": " + e.detail()), null, null, null);
        } catch (Exception e) {
            save(ctx, new ExportJob(id, "FAILED", null, v.requestedAt(), v.format(), v.scope(),
                    "EXPORT_FAILED: " + e.getMessage()), null, null, null);
        }
    }

    private void save(RequestContext ctx, ExportJob job, String blobId, String type, String name) {
        docs.update(ctx.tenantId(), JOB_KIND, job.id(), org.sangam.rest.support.DocumentStore.ANY_REVISION, job.format(),
                job.status(), ctx.principalId(), blobId, body(job, blobId, type, name));
    }

    private static void afterCommit(Runnable r) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            r.run();
                        }
                    });
        } else {
            r.run();
        }
    }

    private List<AuditPort.AuditEntry> collect(RequestContext ctx, Map<String, Object> scope) {
        String bk = str(scope.get("businessKey"));
        long cap = props.getCountCap();
        List<AuditPort.AuditEntry> out = new ArrayList<>();
        if (bk != null && scope.keySet().stream().allMatch(k -> k.equals("businessKey") || scope.get(k) == null)) {
            for (int p = 0; out.size() < cap; p++) {
                Page<AuditPort.AuditEntry> page = audit.timeline(ctx, bk, false, new Page.PageRequest(p, PAGE));
                out.addAll(page.items());
                if (page.items().size() < PAGE) {
                    break;
                }
            }
            return out;
        }
        AuditPort.AuditFilter f = new AuditPort.AuditFilter(bk != null ? bk : str(scope.get("chainId")),
                str(scope.get("type")), str(scope.get("actor")), instant(scope.get("from")), instant(scope.get("to")));
        for (int p = 0; out.size() < cap; p++) {
            Page<AuditPort.AuditEntry> page = audit.search(ctx, f, new Page.PageRequest(p, PAGE));
            out.addAll(page.items());
            if (page.items().size() < PAGE) {
                break;
            }
        }
        return out;
    }

    @Override
    public List<ExportJob> list(RequestContext ctx) {
        return docs.all(ctx.tenantId(), JOB_KIND, org.sangam.rest.support.DocumentStore.Filter.ALL).stream()
                .map(JdbcAuditExportAdapter::view)
                .sorted(Comparator.comparing(ExportJob::requestedAt).reversed()).toList();
    }

    @Override
    public Download content(RequestContext ctx, String exportId) {
        var d = docs.get(ctx.tenantId(), JOB_KIND, exportId)
                .orElseThrow(() -> ApiProblem.notFound("audit export", exportId));
        if (!"READY".equals(d.state())) {
            throw ApiProblem.conflict("EXPORT_NOT_READY", "Export " + exportId + " is " + d.state());
        }
        String blobId = (String) d.body().get("blobId");
        var blob = blobs.get(ctx.tenantId(), blobId)
                .orElseThrow(() -> ApiProblem.gone("EXPORT_CONTENT_GONE", "the content of export " + exportId + " was removed"));
        return new Download((String) d.body().get("contentType"), (String) d.body().get("fileName"), blob.content());
    }

    // ------------------------------------------------------------------ evidence pack

    @Override
    public Download evidencePack(RequestContext ctx, String businessKey) {
        if (businessKey == null || businessKey.isBlank()) {
            throw ApiProblem.badRequest("BUSINESS_KEY_REQUIRED", "businessKey is required");
        }
        List<AuditPort.AuditEntry> timeline = collect(ctx, Map.of("businessKey", businessKey));
        AuditPort.VerifyResult verify = audit.verify(ctx, null, businessKey);
        List<ProcessPort.ProcessInstance> instances = instances(ctx, businessKey);
        if (timeline.isEmpty() && instances.isEmpty()) {
            throw ApiProblem.notFound("business key", businessKey);
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try {
            entries.put("timeline.json", JSON.writeValueAsBytes(plain(timeline)));
            entries.put("verify.json", JSON.writeValueAsBytes(plain(verify)));
            entries.put("instances.json", JSON.writeValueAsBytes(plain(instances)));
            Set<String> definitions = new LinkedHashSet<>();
            instances.forEach(i -> {
                if (i.processDefinitionId() != null) {
                    definitions.add(i.processDefinitionId());
                }
            });
            for (String d : definitions) {
                try {
                    entries.put("definitions/" + safe(d) + ".bpmn",
                            processes.definitionXml(ctx, d).getBytes(StandardCharsets.UTF_8));
                } catch (ApiProblem e) {
                    entries.put("definitions/" + safe(d) + ".missing.txt",
                            (e.code() + ": " + e.detail()).getBytes(StandardCharsets.UTF_8));
                }
            }
            Map<String, Object> docs = new LinkedHashMap<>();
            for (ProcessPort.ProcessInstance i : instances) {
                try {
                    docs.put(i.id(), plain(documents.list(ctx,
                            new DocumentPort.DocumentFilter(null, i.id(), null, null), new Page.PageRequest(0, 500)).items()));
                } catch (ApiProblem e) {
                    docs.put(i.id(), Map.of("error", e.code()));
                }
            }
            entries.put("documents.json", JSON.writeValueAsBytes(docs));
        } catch (IOException e) {
            throw new ApiProblem(500, "EVIDENCE_PACK_FAILED", e.getMessage());
        }
        StringBuilder manifest = new StringBuilder();
        entries.forEach((n, b) -> manifest.append(Hashes.hex(Hashes.sha256(b))).append("  ").append(n).append('\n'));
        entries.put("MANIFEST.sha256", manifest.toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new ApiProblem(500, "EVIDENCE_PACK_FAILED", e.getMessage());
        }
        byte[] zip = bos.toByteArray();
        if (zip.length > MAX_BYTES) {
            throw ApiProblem.unprocessable("EXPORT_TOO_LARGE", "the evidence pack is above 50 MB");
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("businessKey", businessKey);
        p.put("format", "EVIDENCE_PACK");
        p.put("events", timeline.size());
        p.put("instances", instances.size());
        p.put("chain_ok", verify.ok());
        p.put("sha256", Hashes.hex(Hashes.sha256(zip)));
        audit.record(ctx, "sec:" + ctx.tenantId(), "AUDIT_EXPORTED", p);
        return new Download("application/zip", "evidence-" + safe(businessKey) + ".zip", zip);
    }

    private List<ProcessPort.ProcessInstance> instances(RequestContext ctx, String businessKey) {
        Map<String, ProcessPort.ProcessInstance> byId = new LinkedHashMap<>();
        for (String state : new String[]{null, "COMPLETED", "CANCELLED"}) {
            try {
                processes.list(ctx, new ProcessPort.ProcessFilter(null, businessKey, state, null, null, null, null),
                        new Page.PageRequest(0, 500)).items().forEach(i -> byId.putIfAbsent(i.id(), i));
            } catch (ApiProblem e) {
                // a state the adapter does not support: the other queries still apply
            }
        }
        return new ArrayList<>(byId.values());
    }

    // ------------------------------------------------------------------ serialization helpers

    /** Records, maps, collections and times to plain JSON-ready values (no java.time module needed). */
    static Object plain(Object v) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof TemporalAccessor || v instanceof java.util.Date) {
            return v.toString();
        }
        if (v instanceof byte[] b) {
            return java.util.Base64.getEncoder().encodeToString(b);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), plain(x)));
            return out;
        }
        if (v instanceof Collection<?> c) {
            return c.stream().map(JdbcAuditExportAdapter::plain).toList();
        }
        if (v.getClass().isRecord()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (RecordComponent rc : v.getClass().getRecordComponents()) {
                try {
                    out.put(rc.getName(), plain(rc.getAccessor().invoke(v)));
                } catch (ReflectiveOperationException e) {
                    out.put(rc.getName(), null);
                }
            }
            return out;
        }
        return v.toString();
    }

    private static byte[] csv(List<AuditPort.AuditEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("eventId,eventClass,chainId,seq,type,occurredAt,actor,seatId,actedForSeatId,processInstanceId,"
                + "activityId,taskId,erased,prevHash,entryHash,payload\r\n");
        for (AuditPort.AuditEntry e : entries) {
            Object[] cols = {e.eventId(), e.eventClass(), e.chainId(), e.seq(), e.type(), e.occurredAt(), e.actor(),
                    e.seatId(), e.actedForSeatId(), e.processInstanceId(), e.activityId(), e.taskId(), e.erased(),
                    e.prevHash(), e.entryHash(),
                    e.payload() == null ? null : new ObjectMapper().writeValueAsString(plain(e.payload()))};
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(cell(cols[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** RFC 4180 quoting; a leading = + - @ is neutralised (spreadsheet formula injection). */
    private static String cell(Object o) {
        if (o == null) {
            return "";
        }
        String s = o.toString();
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (v != null) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }

    private static Instant instant(Object o) {
        String s = str(o);
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "scope time values must be ISO-8601 instants: " + s);
        }
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
