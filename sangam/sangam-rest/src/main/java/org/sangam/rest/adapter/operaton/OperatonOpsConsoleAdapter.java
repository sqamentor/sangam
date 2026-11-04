// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import org.operaton.bpm.engine.ExternalTaskService;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.runtime.Incident;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.OperationLedger;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.InstanceControlPort;
import org.sangam.rest.port.OpsConsolePort;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.BlobStore;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Default {@link OpsConsolePort} on the Operaton Java API, the product tables (INBOX, RECONCILIATION_ITEM) and the
 * {@link TrackingWorkPort} worker registry of this node.
 * <ul>
 *   <li>Incident and reconciliation claims and notes are REST_DOCUMENT kinds {@code ops:incident-claim} /
 *       {@code ops:recon-claim} (doc id = incident / item id, owner = principal); evidence files are REST_BLOB kind
 *       {@code recon-evidence} (owner_ref = item id) with metadata in REST_DOCUMENT kind {@code ops:evidence}
 *       (ref_id = item id); notification failures are REST_DOCUMENT kind {@code ops:notification-failure}
 *       (state OPEN / RESENT), merged with an optional live source (sangam-integration dead letters). Nothing is
 *       held in memory; every action is a class-B audit event.</li>
 *   <li>Downstream lookup (capability class b, A-2) and notification resend (R60) are bound by sangam-server to
 *       sangam-integration ({@link #setLookup}, {@link #setResender}); without them they return
 *       {@code 501 CAPABILITY_NOT_INSTALLED}.</li>
 *   <li>Job partitions (R39) belong to sangam-engine; {@code byPartition} is empty here.</li>
 * </ul>
 */
public class OperatonOpsConsoleAdapter implements OpsConsolePort {

    private static final int SAMPLE = 10_000;

    private final RuntimeService runtime;
    private final ManagementService management;
    private final HistoryService history;
    private final ExternalTaskService externalTasks;
    private final TaskService tasks;
    private final RepositoryService repository;
    private final OpsPort ops;
    private final TrackingWorkPort tracking;
    private final TxSupport tx;
    private final AuditPort audit;
    private final RestProperties props;
    private final BooleanSupplier jobExecutorActive;

    static final String INCIDENT_CLAIM = "ops:incident-claim";
    static final String RECON_CLAIM = "ops:recon-claim";
    static final String EVIDENCE = "ops:evidence";
    static final String EVIDENCE_BLOB = "recon-evidence";
    static final String FAILURE = "ops:notification-failure";

    private final DocumentStore docs;
    private final BlobStore blobs;
    private volatile java.util.function.Function<String, List<NotificationFailure>> failureSource = t -> List.of();
    private volatile BiConsumer<RequestContext, NotificationFailure> resender;
    private volatile java.util.function.BiFunction<RequestContext, OpsPort.ReconciliationItem, LookupResult> lookup;

    public OperatonOpsConsoleAdapter(RuntimeService runtime, ManagementService management, HistoryService history,
                                     ExternalTaskService externalTasks, TaskService tasks, RepositoryService repository,
                                     OpsPort ops, TrackingWorkPort tracking, TxSupport tx, AuditPort audit,
                                     RestProperties props, BooleanSupplier jobExecutorActive, DocumentStore docs,
                                     BlobStore blobs) {
        this.docs = java.util.Objects.requireNonNull(docs, "docs");
        this.blobs = java.util.Objects.requireNonNull(blobs, "blobs");
        this.runtime = runtime;
        this.management = management;
        this.history = history;
        this.externalTasks = externalTasks;
        this.tasks = tasks;
        this.repository = repository;
        this.ops = ops;
        this.tracking = tracking;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
        this.jobExecutorActive = jobExecutorActive == null ? () -> false : jobExecutorActive;
    }

    // ------------------------------------------------------------------ extension hooks for sangam-integration

    /** sangam-integration records delivery failures of notifications (R60) here (persisted, state OPEN). */
    public void record(String tenant, NotificationFailure failure) {
        String id = failure.id() != null ? failure.id() : Hashes.uuidV7().toString();
        NotificationFailure f = new NotificationFailure(id, failure.at() != null ? failure.at() : Instant.now(),
                failure.channel(), failure.reason(), failure.templateId(), failure.businessKey(), failure.recipientMasked());
        if (docs.get(tenant, FAILURE, id).isEmpty()) {
            docs.insert(tenant, FAILURE, id, f.templateId(), "OPEN", null, f.businessKey(), f, "system");
        }
    }

    /**
     * A live source of failures per tenant (e.g. sangam-integration dead letters of notification destinations). Its
     * entries are listed with the recorded ones until they are resent.
     */
    public void setFailureSource(java.util.function.Function<String, List<NotificationFailure>> source) {
        this.failureSource = source == null ? t -> List.of() : source;
    }

    public void setResender(BiConsumer<RequestContext, NotificationFailure> resender) {
        this.resender = resender;
    }

    /** Registers the capability-class (b) downstream lookup (A-2). */
    public void setLookup(java.util.function.BiFunction<RequestContext, OpsPort.ReconciliationItem, LookupResult> lookup) {
        this.lookup = lookup;
    }

    // ------------------------------------------------------------------ dashboard

    @Override
    public Dashboard dashboard(RequestContext ctx) {
        String t = ctx.tenantId();
        Instant now = Instant.now();
        List<NodeStatus> nodes = List.of(new NodeStatus(props.getNode(), "UP", jobExecutorActive.getAsBoolean(), now));
        List<TopicBacklog> backlog = backlog(t, now);
        OpsPort.OutboxStats os = ops.outboxStats(ctx);
        OutboxUsage outbox = new OutboxUsage(os.pending() + os.inFlight(), os.budget(), null);
        long openIncidents = OperatonSupport.call(() -> runtime.createIncidentQuery().tenantIdIn(t).count());
        List<AgeBucket> recon = reconBuckets(t, now);
        long slaBreaches = OperatonSupport.call(() ->
                tasks.createTaskQuery().tenantIdIn(t).dueBefore(Date.from(now)).count());
        List<Metric> metrics = new ArrayList<>();
        metrics.add(new Metric("process_instances_active",
                OperatonSupport.call(() -> runtime.createProcessInstanceQuery().tenantIdIn(t).active().count()), null));
        metrics.add(new Metric("external_tasks_locked",
                OperatonSupport.call(() -> externalTasks.createExternalTaskQuery().tenantIdIn(t).locked().count()), null));
        metrics.add(new Metric("jobs_failed",
                OperatonSupport.call(() -> management.createJobQuery().tenantIdIn(t).withException().count()), null));
        metrics.add(new Metric("outbox_used_percent", os.usedPercent(), "%"));
        return new Dashboard(nodes, backlog, outbox, openIncidents, recon, slaBreaches, failureRate(t), List.of(),
                metrics);
    }

    private Double failureRate(String tenant) {
        long n = openFailures(tenant).size();
        return n == 0 ? null : (double) n;
    }

    /** OPEN recorded failures plus live-source failures that were not resent yet, newest first. */
    private List<NotificationFailure> openFailures(String tenant) {
        Map<String, NotificationFailure> all = new LinkedHashMap<>();
        for (DocumentStore.Doc d : docs.all(tenant, FAILURE, DocumentStore.Filter.state("OPEN"))) {
            all.put(d.id(), d.as(NotificationFailure.class));
        }
        List<NotificationFailure> live;
        try {
            live = failureSource.apply(tenant);
        } catch (RuntimeException e) {
            live = List.of();
        }
        for (NotificationFailure f : live) {
            if (f.id() != null && !all.containsKey(f.id()) && docs.get(tenant, FAILURE, f.id()).isEmpty()) {
                all.put(f.id(), f);
            }
        }
        List<NotificationFailure> out = new ArrayList<>(all.values());
        out.sort(Comparator.comparing(NotificationFailure::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    private List<TopicBacklog> backlog(String tenant, Instant now) {
        List<ExternalTask> open = OperatonSupport.call(() ->
                externalTasks.createExternalTaskQuery().tenantIdIn(tenant).notLocked().listPage(0, SAMPLE));
        Map<String, long[]> byTopic = new TreeMap<>();
        for (ExternalTask e : open) {
            long[] v = byTopic.computeIfAbsent(e.getTopicName(), k -> new long[]{0, 0});
            v[0]++;
            if (e.getCreateTime() != null) {
                long age = Math.max(0, Duration.between(e.getCreateTime().toInstant(), now).toSeconds());
                v[1] = Math.max(v[1], age);
            }
        }
        List<TopicBacklog> out = new ArrayList<>();
        byTopic.forEach((k, v) -> out.add(new TopicBacklog(k, v[0], v[1])));
        return out;
    }

    private List<AgeBucket> reconBuckets(String tenant, Instant now) {
        long[] b = new long[4];
        List<Instant> created = tx.withConnection(c -> limited(c,
                "SELECT created_at FROM RECONCILIATION_ITEM WHERE tenant_id=? AND state='OPEN'", SAMPLE,
                rs -> Jdbc.instant(rs, "created_at"), tenant));
        for (Instant i : created) {
            long h = i == null ? 0 : Duration.between(i, now).toHours();
            b[h < 1 ? 0 : h < 24 ? 1 : h < 24 * 7 ? 2 : 3]++;
        }
        return List.of(new AgeBucket("<1h", b[0]), new AgeBucket("1-24h", b[1]), new AgeBucket("1-7d", b[2]),
                new AgeBucket(">7d", b[3]));
    }

    // ------------------------------------------------------------------ jobs

    @Override
    public JobSummary jobSummary(RequestContext ctx) {
        String t = ctx.tenantId();
        Date now = new Date();
        long executable = OperatonSupport.call(() -> management.createJobQuery().tenantIdIn(t).executable().count());
        long failed = OperatonSupport.call(() -> management.createJobQuery().tenantIdIn(t).withException().count());
        long timersDue = OperatonSupport.call(() ->
                management.createJobQuery().tenantIdIn(t).timers().duedateLowerThan(now).count());
        long locked = lockedJobs(t);
        return new JobSummary(executable, failed, timersDue, locked, List.of());
    }

    /** The job query has no "locked" filter: read the engine's lock owner column (read-only). */
    private long lockedJobs(String tenant) {
        try {
            return tx.withConnection(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM ACT_RU_JOB WHERE LOCK_OWNER_ IS NOT NULL AND TENANT_ID_ = ?")) {
                    ps.setString(1, tenant);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                } catch (SQLException e) {
                    return 0L;
                }
            });
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    @Override
    public String jobStacktrace(RequestContext ctx, String jobId) {
        var job = OperatonSupport.call(() -> management.createJobQuery().jobId(jobId).singleResult());
        if (job == null) {
            throw ApiProblem.notFound("job", jobId);
        }
        OperatonSupport.checkTenant(ctx, job.getTenantId(), "job", jobId);
        String trace = OperatonSupport.call(() -> management.getJobExceptionStacktrace(jobId));
        return trace == null ? "" : trace;
    }

    // ------------------------------------------------------------------ inbox

    @Override
    public Page<InboxEntry> inbox(RequestContext ctx, String status, Page.PageRequest page) {
        String state = status == null ? null : switch (status) {
            case "CORRELATED" -> "PROCESSED";
            case "RECEIVED", "BUFFERED", "EXPIRED" -> status;
            default -> throw ApiProblem.badRequest("VALIDATION_FAILED",
                    "status must be RECEIVED | BUFFERED | CORRELATED | EXPIRED");
        };
        String where = " WHERE tenant_id=?" + (state == null ? "" : " AND state=?");
        Object[] params = state == null ? new Object[]{ctx.tenantId()} : new Object[]{ctx.tenantId(), state};
        long cap = props.getCountCap();
        return tx.withConnection(c -> {
            List<InboxEntry> rows = limited(c, "SELECT source, message_id, state, received_at, correlation_key FROM INBOX"
                            + where + " ORDER BY received_at DESC", page.firstResult() + page.size(),
                    rs -> new InboxEntry(rs.getString(1) + ":" + rs.getString(2), rs.getString(1), rs.getString(2),
                            inboxStatus(rs.getString(3)), Jdbc.instant(rs, "received_at"), rs.getString(5)), params);
            List<InboxEntry> items = rows.subList(Math.min(rows.size(), page.firstResult()), rows.size());
            long n = limited(c, "SELECT message_id FROM INBOX" + where, (int) Math.min(Integer.MAX_VALUE, cap + 1),
                    rs -> 1, params).size();
            return Page.of(items, Math.min(n, cap), n > cap);
        });
    }

    private static String inboxStatus(String s) {
        return switch (s == null ? "" : s) {
            case "PROCESSED" -> "CORRELATED";
            case "CLAIMED" -> "RECEIVED";
            default -> s;
        };
    }

    // ------------------------------------------------------------------ workers and topics

    @Override
    public List<WorkerInfo> workers(RequestContext ctx) {
        return tracking.workers(ctx.tenantId()).stream()
                .map(w -> new WorkerInfo(w.workerId(), w.sdk(), w.sdkVersion(), w.language(), w.topics(),
                        w.activeLeases(), w.heartbeatAgeSec(), w.errorRate(), w.staleLeaseCount()))
                .toList();
    }

    @Override
    public List<TopicInfo> topics(RequestContext ctx) {
        String t = ctx.tenantId();
        Map<String, Long> backlog = new TreeMap<>();
        for (TopicBacklog b : backlog(t, Instant.now())) {
            backlog.put(b.topic(), b.count());
        }
        Map<String, Long> known = tracking.knownTopics(t);
        java.util.Set<String> names = new java.util.TreeSet<>(backlog.keySet());
        names.addAll(known.keySet());
        names.addAll(tracking.pausedTopics(t));
        List<TopicInfo> out = new ArrayList<>();
        for (String n : names) {
            Long lock = known.get(n);
            out.add(new TopicInfo(n, props.getIrreversibleTopics().contains(n) ? "c" : null, tracking.isPaused(t, n),
                    backlog.getOrDefault(n, 0L), lock == null || lock < 0 ? null : lock));
        }
        return out;
    }

    @Override
    public TopicInfo setTopicPaused(RequestContext ctx, String topic, boolean paused, String reason) {
        requireReason(reason);
        if (topic == null || topic.isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "topic is required");
        }
        tracking.pauseTopic(ctx.tenantId(), topic, paused);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("topic", topic);
        p.put("reason", reason);
        p.put("node", props.getNode());
        audit.record(ctx, "sec:" + ctx.tenantId(), paused ? "TOPIC_PAUSED" : "TOPIC_RESUMED", p);
        return topics(ctx).stream().filter(x -> x.name().equals(topic)).findFirst()
                .orElse(new TopicInfo(topic, null, paused, 0, null));
    }

    // ------------------------------------------------------------------ incidents

    @Override
    public InstanceControlPort.ConsequencePreview incidentPreview(RequestContext ctx, String incidentId, String action) {
        Incident i = incident(ctx, incidentId);
        List<String> ops = openOperationIds(ctx, i);
        List<String> summary = new ArrayList<>();
        boolean blocked = false;
        String blockedReason = null;
        switch (action == null ? "" : action) {
            case "retry" -> {
                summary.add("Sets the retries of " + ("failedExternalTask".equals(i.getIncidentType())
                        ? "external task " : "job ") + i.getConfiguration() + " to 1; it runs again at once.");
                if (!ops.isEmpty()) {
                    summary.add("The worker is offered the same operationId again; the downstream system must "
                            + "deduplicate on it (VI.3 rule 4).");
                }
                if (props.getIrreversibleTopics().stream().anyMatch(tp -> i.getIncidentMessage() != null
                        && i.getIncidentMessage().contains(tp))) {
                    blocked = true;
                    blockedReason = "The topic is irreversible (VI.3 rule 5): resolve through reconciliation instead.";
                }
            }
            case "skip" -> summary.add("Moves the token past activity " + i.getActivityId()
                    + " of instance " + i.getProcessInstanceId() + " (instance modification, R49); the activity's "
                    + "effect does not happen.");
            case "cancel" -> {
                summary.add("Cancels instance " + i.getProcessInstanceId() + " (business cancel, VI.5).");
                summary.add("Open external work becomes CANCEL_REQUESTED.");
            }
            default -> throw ApiProblem.badRequest("VALIDATION_FAILED", "action must be retry | skip | cancel");
        }
        return new InstanceControlPort.ConsequencePreview(summary, List.of(), ops, null, blocked, blockedReason,
                false, 1L, false);
    }

    private List<String> openOperationIds(RequestContext ctx, Incident i) {
        if (!"failedExternalTask".equals(i.getIncidentType()) || i.getConfiguration() == null) {
            return List.of();
        }
        ExternalTask e = OperatonSupport.call(() ->
                externalTasks.createExternalTaskQuery().externalTaskId(i.getConfiguration()).singleResult());
        if (e == null || e.getActivityInstanceId() == null) {
            return List.of();
        }
        return List.of(OperationLedger.operationId(ctx.tenantId(), e.getProcessInstanceId(), e.getActivityInstanceId(), 0)
                .toString());
    }

    @Override
    public OpsPort.Incident claimIncident(RequestContext ctx, String incidentId) {
        Incident i = incident(ctx, incidentId);
        String prev = claim(ctx, INCIDENT_CLAIM, incidentId, i.getProcessInstanceId());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("incident_id", incidentId);
        p.put("previous_owner", prev);
        p.values().removeIf(Objects::isNull);
        audit.record(ctx, chain(ctx, i.getProcessInstanceId()), "INCIDENT_CLAIMED", p);
        return ops.incident(ctx, incidentId);
    }

    @Override
    public OpsPort.Incident noteIncident(RequestContext ctx, String incidentId, NoteRequest request) {
        if (request == null || request.note() == null || request.note().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "note is required");
        }
        Incident i = incident(ctx, incidentId);
        tx.runInTx(() -> {
            OperatonSupport.run(() -> runtime.setAnnotationForIncidentById(incidentId, request.note()));
            note(ctx, INCIDENT_CLAIM, incidentId, i.getProcessInstanceId(), request.note());
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("incident_id", incidentId);
            p.put("note", request.note());
            p.put("reason", request.reason());
            p.values().removeIf(Objects::isNull);
            audit.record(ctx, chain(ctx, i.getProcessInstanceId()), "INCIDENT_NOTED", p);
        });
        return ops.incident(ctx, incidentId);
    }

    private Incident incident(RequestContext ctx, String id) {
        Incident i = OperatonSupport.call(() -> runtime.createIncidentQuery().incidentId(id).singleResult());
        if (i == null) {
            throw ApiProblem.notFound("incident", id);
        }
        OperatonSupport.checkTenant(ctx, i.getTenantId(), "incident", id);
        return i;
    }

    /** Owner of a claimed incident (null if unclaimed). */
    public String incidentOwner(String tenant, String incidentId) {
        return docs.get(tenant, INCIDENT_CLAIM, incidentId).map(DocumentStore.Doc::ownerId).orElse(null);
    }

    /** Claims {@code id} for the caller; returns the previous owner. The claim document keeps earlier notes. */
    private String claim(RequestContext ctx, String kind, String id, String ref) {
        return tx.inTx(() -> {
            var existing = docs.get(ctx.tenantId(), kind, id);
            Map<String, Object> body = existing.map(d -> (Map<String, Object>) new LinkedHashMap<>(d.body()))
                    .orElseGet(LinkedHashMap::new);
            String prev = existing.map(DocumentStore.Doc::ownerId).orElse(null);
            body.put("owner", ctx.principalId());
            body.put("claimedAt", Instant.now().toString());
            body.put("previousOwner", prev);
            docs.upsert(ctx.tenantId(), kind, id, null, "CLAIMED", ctx.principalId(), ref, body, ctx.principalId());
            return prev;
        });
    }

    /** Appends a note to the claim document (created unclaimed if absent). */
    @SuppressWarnings("unchecked")
    private void note(RequestContext ctx, String kind, String id, String ref, String note) {
        var existing = docs.get(ctx.tenantId(), kind, id);
        Map<String, Object> body = existing.map(d -> (Map<String, Object>) new LinkedHashMap<>(d.body()))
                .orElseGet(LinkedHashMap::new);
        List<Object> notes = body.get("notes") instanceof List<?> l ? new ArrayList<>((List<Object>) l) : new ArrayList<>();
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("by", ctx.principalId());
        n.put("at", Instant.now().toString());
        n.put("text", note);
        notes.add(n);
        body.put("notes", notes);
        docs.upsert(ctx.tenantId(), kind, id, null, existing.map(DocumentStore.Doc::state).orElse("UNCLAIMED"),
                existing.map(DocumentStore.Doc::ownerId).orElse(null), ref, body, ctx.principalId());
    }

    // ------------------------------------------------------------------ reconciliation

    @Override
    public OpsPort.ReconciliationItem claimReconciliation(RequestContext ctx, String itemId) {
        OpsPort.ReconciliationItem item = ops.reconciliationItem(ctx, itemId);
        if (!"OPEN".equals(item.state())) {
            throw ApiProblem.conflict("ALREADY_RESOLVED", "Reconciliation item " + itemId + " is " + item.state());
        }
        String prev = claim(ctx, RECON_CLAIM, itemId, item.rootId());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("item_id", itemId);
        p.put("operation_id", item.operationId());
        p.put("previous_owner", prev);
        p.values().removeIf(Objects::isNull);
        audit.record(ctx, chain(ctx, item.rootId()), "RECON_ITEM_CLAIMED", p);
        return item;
    }

    /** Owner of a claimed reconciliation item (null if unclaimed). */
    public String reconciliationOwner(String tenant, String itemId) {
        return docs.get(tenant, RECON_CLAIM, itemId).map(DocumentStore.Doc::ownerId).orElse(null);
    }

    @Override
    public LookupResult lookupReconciliation(RequestContext ctx, String itemId) {
        OpsPort.ReconciliationItem item = ops.reconciliationItem(ctx, itemId);
        var l = lookup;
        if (l == null) {
            throw ApiProblem.notInstalled("downstream lookup (A-2 class b)", "sangam-integration");
        }
        LookupResult r = l.apply(ctx, item);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("item_id", itemId);
        p.put("operation_id", item.operationId());
        p.put("result", r.result());
        audit.record(ctx, chain(ctx, item.rootId()), "RECON_LOOKUP", p);
        return r;
    }

    @Override
    public Evidence addReconciliationEvidence(RequestContext ctx, String itemId, String kind, String fileName,
                                              String contentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw ApiProblem.badRequest("CONTENT_REQUIRED", "the evidence file is required");
        }
        if (content.length > OperatonDocumentAdapter.MAX_BYTES) {
            throw ApiProblem.unprocessable("DOCUMENT_REJECTED", "evidence above 25 MB is not accepted");
        }
        if (kind == null || kind.isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "kind is required");
        }
        OpsPort.ReconciliationItem item = ops.reconciliationItem(ctx, itemId);
        String name = fileName == null || fileName.isBlank() ? "evidence" : fileName.replaceAll("[/\\\\]", "_");
        Evidence ev = tx.inTx(() -> {
            BlobStore.BlobRef blob = blobs.put(ctx.tenantId(), EVIDENCE_BLOB, itemId, name, contentType, content,
                    ctx.principalId());
            Evidence e = new Evidence(blob.id(), kind, name, ctx.principalId(), blob.createdAt(), blob.sha256());
            docs.insert(ctx.tenantId(), EVIDENCE, blob.id(), name, kind, ctx.principalId(), itemId, e, ctx.principalId());
            return e;
        });
        String sha = ev.sha256();
        tx.runInTx(() -> {
            if (item.rootId() != null && isProcessInstance(item.rootId())) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("tenant", ctx.tenantId());
                meta.put("sha256", sha);
                meta.put("size", content.length);
                meta.put("contentType", contentType);
                meta.put("version", 1);
                meta.put("createdBy", ctx.principalId());
                meta.put("scanStatus", "NOT_SCANNED");
                meta.put("metadata", Map.of("reconciliationItemId", itemId, "evidenceKind", kind));
                meta.values().removeIf(Objects::isNull);
                OperatonSupport.call(() -> tasks.createAttachment(contentType == null ? "application/octet-stream"
                                : contentType, null, item.rootId(), name, OperatonDocumentAdapter.write(meta),
                        new ByteArrayInputStream(content)));
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("item_id", itemId);
            p.put("evidence_id", ev.id());
            p.put("kind", kind);
            p.put("name", name);
            p.put("sha256", sha);
            audit.record(ctx, chain(ctx, item.rootId()), "RECON_EVIDENCE_ADDED", p);
        });
        return ev;
    }

    /** Evidence recorded for an item (metadata; the files are in REST_BLOB). */
    public List<Evidence> evidence(String tenant, String itemId) {
        return docs.all(tenant, EVIDENCE, DocumentStore.Filter.ref(itemId)).stream()
                .map(d -> d.as(Evidence.class)).toList();
    }

    /** The evidence file itself. */
    public java.util.Optional<BlobStore.Blob> evidenceContent(String tenant, String evidenceId) {
        return blobs.get(tenant, evidenceId);
    }

    private boolean isProcessInstance(String id) {
        return OperatonSupport.call(() -> history.createHistoricProcessInstanceQuery().processInstanceId(id).count()) > 0
                || OperatonSupport.call(() -> runtime.createProcessInstanceQuery().processInstanceId(id).count()) > 0;
    }

    // ------------------------------------------------------------------ retention

    @Override
    public OpsPort.CleanupStatus setHistoryTtl(RequestContext ctx, String definitionKey, TtlRequest request) {
        if (request == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "ttlDays and reason are required");
        }
        requireReason(request.reason());
        if (request.ttlDays() < 0 || request.ttlDays() > 36_500) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "ttlDays must be 0..36500");
        }
        List<ProcessDefinition> defs = OperatonSupport.call(() -> repository.createProcessDefinitionQuery()
                .processDefinitionKey(definitionKey).tenantIdIn(ctx.tenantId()).list());
        if (defs.isEmpty()) {
            throw ApiProblem.notFound("process definition", definitionKey);
        }
        tx.runInTx(() -> {
            for (ProcessDefinition d : defs) {
                OperatonSupport.run(() -> repository.updateProcessDefinitionHistoryTimeToLive(d.getId(), request.ttlDays()));
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("definition_key", definitionKey);
            p.put("ttl_days", request.ttlDays());
            p.put("versions", defs.size());
            p.put("reason", request.reason());
            audit.record(ctx, "sec:" + ctx.tenantId(), "RETENTION_CHANGED", p);
        });
        return ops.cleanupStatus(ctx);
    }

    // ------------------------------------------------------------------ notification failures (R60)

    @Override
    public Page<NotificationFailure> notificationFailures(RequestContext ctx, Page.PageRequest page) {
        return Page.slice(openFailures(ctx.tenantId()), page, props.getCountCap());
    }

    @Override
    public void resendNotification(RequestContext ctx, String failureId, String reason) {
        requireReason(reason);
        NotificationFailure f = openFailures(ctx.tenantId()).stream()
                .filter(x -> x.id().equals(failureId)).findFirst()
                .orElseThrow(() -> ApiProblem.notFound("notification failure", failureId));
        var r = resender;
        if (r == null) {
            throw ApiProblem.notInstalled("notification resend (R60)", "sangam-integration");
        }
        r.accept(ctx, f);
        tx.runInTx(() -> {
            Map<String, Object> body = DocumentStore.toMap(f);
            body.put("resentBy", ctx.principalId());
            body.put("resentAt", Instant.now().toString());
            body.put("resendReason", reason);
            docs.upsert(ctx.tenantId(), FAILURE, f.id(), f.templateId(), "RESENT", ctx.principalId(), f.businessKey(),
                    body, ctx.principalId());
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("failure_id", failureId);
            p.put("channel", f.channel());
            p.put("reason", reason);
            audit.record(ctx, f.businessKey() != null ? f.businessKey() : "sec:" + ctx.tenantId(), "NOTIFICATION_RESENT", p);
        });
    }

    // ------------------------------------------------------------------ helpers

    private static String chain(RequestContext ctx, String rootId) {
        return rootId == null || rootId.isBlank() ? "sec:" + ctx.tenantId() : rootId;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "This action requires a reason (R63, R42)");
        }
    }

    private static <T> List<T> limited(Connection c, String sql, int maxRows, Jdbc.RowMapper<T> mapper, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setMaxRows(Math.max(0, maxRows));
            Jdbc.bind(ps, 1, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException(sql, e);
        }
    }
}
