// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.operaton.bpm.engine.ExternalTaskService;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstanceQuery;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.migration.MigrationInstructionValidationReport;
import org.operaton.bpm.engine.migration.MigrationPlanBuilder;
import org.operaton.bpm.engine.migration.MigrationPlanExecutionBuilder;
import org.operaton.bpm.engine.migration.MigrationPlanValidationException;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.runtime.IncidentQuery;
import org.operaton.bpm.engine.runtime.JobQuery;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstanceModificationBuilder;
import org.operaton.bpm.engine.runtime.ProcessInstanceQuery;
import org.operaton.bpm.engine.runtime.RestartProcessInstanceBuilder;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.rest.adapter.jdbc.JdbcReconciliationSupport;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.TypedVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link OpsPort} on the Operaton Java API plus the core product tables.
 *
 * <p><b>Batches (R64).</b> An in-process batch service: the item list is resolved once from the same tenant-scoped
 * query the preview counts; items run on one worker thread, <b>one root per transaction</b> (VI.1 rule 3), with
 * per-item results, pause/resume/cancel, and four-eyes approval above {@code sangam.rest.batch-four-eyes-threshold}
 * (the approver must be a different principal). Item keys are {@code <Idempotency-Key or batch id>:<index>}
 * (X.2.2.7 rule 3). Batches and their items are stored in {@code PORT_BATCH} / {@code PORT_BATCH_ITEM} (batch_type
 * {@code REST:<type>}); each item's row is updated in the transaction that applies it, and {@link #resume()} continues
 * RUNNING batches after a restart, so no progress is lost. CANCEL and DELETE
 * items go through the {@link ProcessPort} when one is set ({@link #setProcessPort}), so VI.5 tombstone rules apply.
 *
 * <p>Every state-changing call needs a reason (R63) and is audited (R42): instance-level events on the root chain,
 * operator-level events on {@code sec:<tenant>}.
 */
public class OperatonOpsAdapter implements OpsPort, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OperatonOpsAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BATCH_ITEMS = 100_000;
    private static final Set<String> BATCH_TYPES = Set.of("RETRY", "CANCEL", "DELETE", "SUSPEND", "RESUME", "MIGRATE",
            "MODIFY", "SET_VARIABLES", "RESTART");

    private final RuntimeService runtimeService;
    private final ManagementService managementService;
    private final HistoryService historyService;
    private final RepositoryService repositoryService;
    private final ExternalTaskService externalTaskService;
    private final TxSupport tx;
    private final AuditPort audit;
    private final RestProperties props;
    private final JdbcReconciliationSupport product;
    /** Batch ids with a runner on this node (runtime only; batch state itself is in PORT_BATCH). */
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sangam-batch");
        t.setDaemon(true);
        return t;
    });
    private volatile ProcessPort processPort;

    public OperatonOpsAdapter(RuntimeService runtimeService, ManagementService managementService,
                              HistoryService historyService, RepositoryService repositoryService,
                              ExternalTaskService externalTaskService, TxSupport tx, AuditPort audit,
                              RestProperties props,
                              java.util.function.Supplier<org.sangam.core.layer.OutboxRepair> outboxRepair) {
        this.runtimeService = runtimeService;
        this.managementService = managementService;
        this.historyService = historyService;
        this.repositoryService = repositoryService;
        this.externalTaskService = externalTaskService;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
        // required, so no construction site can forget it: repairs go through the deployment's OutboxRepair
        this.product = new JdbcReconciliationSupport(tx, props,
                java.util.Objects.requireNonNull(outboxRepair, "outboxRepair supplier"));
    }

    /** Optional: route batch CANCEL/DELETE through the process port (VI.5 cancel/delete semantics). */
    public void setProcessPort(ProcessPort processPort) {
        this.processPort = processPort;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    // ================================================================== incidents

    @Override
    public Page<Incident> incidents(RequestContext ctx, IncidentFilter filter, Page.PageRequest page) {
        IncidentQuery q = runtimeService.createIncidentQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        if (filter != null) {
            if (filter.type() != null) {
                q.incidentType(filter.type());
            }
            if (filter.processInstanceId() != null) {
                q.processInstanceId(filter.processInstanceId());
            }
            if (filter.processDefinitionKey() != null) {
                q.processDefinitionKeyIn(filter.processDefinitionKey());
            }
            if (filter.activityId() != null) {
                q.activityId(filter.activityId());
            }
        }
        q.orderByIncidentTimestamp().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), OperatonOpsAdapter::toIncident);
    }

    @Override
    public Incident incident(RequestContext ctx, String id) {
        org.operaton.bpm.engine.runtime.Incident i = OperatonSupport.call(
                () -> runtimeService.createIncidentQuery().incidentId(id).singleResult());
        if (i == null) {
            throw ApiProblem.notFound("incident", id);
        }
        OperatonSupport.checkTenant(ctx, i.getTenantId(), "incident", id);
        return toIncident(i);
    }

    @Override
    public Incident retryIncident(RequestContext ctx, String id, IncidentAction action) {
        String reason = requireReason(action == null ? null : action.reason());
        Incident inc = incident(ctx, id);
        int retries = action.retries() == null ? 1 : action.retries();
        if (retries < 1) {
            throw ApiProblem.badRequest("INVALID_RETRIES", "retries must be >= 1");
        }
        tx.runInTx(() -> {
            OperatonSupport.run(() -> {
                switch (inc.type()) {
                    case "failedJob" -> managementService.setJobRetries(inc.jobId(), retries);
                    case "failedExternalTask" -> externalTaskService.setRetries(inc.externalWorkId(), retries);
                    default -> throw ApiProblem.unprocessable("INCIDENT_NOT_RETRYABLE",
                            "Incidents of type " + inc.type() + " cannot be retried; resolve them instead");
                }
            });
            Map<String, Object> p = incidentPayload(inc, reason);
            p.put("retries", retries);
            audit.record(ctx, chainOf(ctx, inc.processInstanceId()), "INCIDENT_RETRIED", p);
        });
        return inc;
    }

    @Override
    public Incident resolveIncident(RequestContext ctx, String id, IncidentAction action) {
        String reason = requireReason(action == null ? null : action.reason());
        Incident inc = incident(ctx, id);
        tx.runInTx(() -> {
            OperatonSupport.run(() -> {
                if (action.annotation() != null) {
                    runtimeService.setAnnotationForIncidentById(id, action.annotation());
                }
                runtimeService.resolveIncident(id);
            });
            audit.record(ctx, chainOf(ctx, inc.processInstanceId()), "INCIDENT_RESOLVED", incidentPayload(inc, reason));
        });
        return inc;
    }

    private static Incident toIncident(org.operaton.bpm.engine.runtime.Incident i) {
        String jobId = "failedJob".equals(i.getIncidentType()) ? i.getConfiguration() : null;
        String extId = "failedExternalTask".equals(i.getIncidentType()) ? i.getConfiguration() : null;
        return new Incident(i.getId(), i.getIncidentType(), i.getIncidentMessage(), i.getProcessInstanceId(),
                i.getProcessDefinitionId(), i.getActivityId(), jobId, extId, i.getCauseIncidentId(),
                i.getRootCauseIncidentId(), OperatonSupport.instant(i.getIncidentTimestamp()), i.getAnnotation(),
                i.getTenantId());
    }

    private static Map<String, Object> incidentPayload(Incident inc, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("incidentId", inc.id());
        p.put("incidentType", inc.type());
        p.put("processInstanceId", inc.processInstanceId());
        p.put("activityId", inc.activityId());
        p.put("reason", reason);
        return p;
    }

    // ================================================================== reconciliation

    @Override
    public Page<ReconciliationItem> reconciliation(RequestContext ctx, String state, String kind, Page.PageRequest page) {
        return product.list(ctx, state == null ? null : state.toUpperCase(Locale.ROOT),
                kind == null ? null : kind.toUpperCase(Locale.ROOT), page);
    }

    @Override
    public ReconciliationItem reconciliationItem(RequestContext ctx, String id) {
        return product.get(ctx, id);
    }

    @Override
    public ReconciliationItem resolveReconciliation(RequestContext ctx, String id, ResolveRequest request) {
        if (request == null || request.action() == null) {
            throw ApiProblem.badRequest("ACTION_REQUIRED", "action is required");
        }
        ReconciliationQueue.Action action;
        try {
            action = ReconciliationQueue.Action.valueOf(request.action().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("INVALID_ACTION",
                    "action must be CONFIRM_SUCCESS, RETRY_SAME_OPERATION or MARK_FAILED");
        }
        if (request.evidence() == null || request.evidence().isBlank()) {
            throw ApiProblem.badRequest("EVIDENCE_REQUIRED", "Resolving an uncertain outcome needs evidence");
        }
        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("evidence", request.evidence());
        resolution.put("result", request.result());
        resolution.put("reason", request.reason());
        String resolutionJson = writeJson(resolution);
        org.sangam.core.layer.ReconciliationResolver r = resolver.get();
        if (r != null) {
            // The one resolve operation (resolution + ledger + release of the held work), never a bare queue update.
            product.get(ctx, id);   // tenant check, 404 otherwise
            long itemId;
            try {
                itemId = Long.parseLong(id);
            } catch (NumberFormatException e) {
                throw ApiProblem.notFound("reconciliation item", id);
            }
            try {
                r.resolve(itemId, action, ctx.principalId(), request.result(), request.reason(), request.evidence());
            } catch (ReconciliationQueue.AlreadyResolvedException e) {
                throw ApiProblem.conflict("ALREADY_RESOLVED", "Reconciliation item " + id + " is already resolved");
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw ApiProblem.unprocessable("RECONCILIATION_ACTION_NOT_ALLOWED", e.getMessage());
            }
        }
        return tx.inTx(() -> {
            ReconciliationItem item = r != null ? product.get(ctx, id) : product.resolve(ctx, id, action, resolutionJson);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("itemId", item.id());
            p.put("kind", item.kind());
            p.put("operationId", item.operationId());
            p.put("action", action.name());
            p.put("evidence", request.evidence());
            p.put("reason", request.reason());
            audit.record(ctx, chainOf(ctx, item.rootId()), "RECON_ITEM_RESOLVED", p);
            return item;
        });
    }

    private volatile java.util.function.Supplier<org.sangam.core.layer.ReconciliationResolver> resolver = () -> null;

    /**
     * The shared resolve operation (sangam-integration's reconciliation engine) when it is bound; looked up per call
     * so the bean graph has no cycle.
     */
    public void setReconciliationResolver(java.util.function.Supplier<org.sangam.core.layer.ReconciliationResolver> resolver) {
        this.resolver = resolver == null ? () -> null : resolver;
    }

    // ================================================================== outbox

    @Override
    public Page<OutboxDelivery> outbox(RequestContext ctx, String state, String destination, Page.PageRequest page) {
        return product.outbox(ctx, state == null ? null : state.toUpperCase(Locale.ROOT), destination, page);
    }

    @Override
    public OutboxStats outboxStats(RequestContext ctx) {
        return product.stats(ctx);
    }

    @Override
    public OutboxDelivery requeueOutbox(RequestContext ctx, String outboxId, OutboxRepairRequest request) {
        return repairOutbox(ctx, outboxId, request, true);
    }

    @Override
    public OutboxDelivery skipOutbox(RequestContext ctx, String outboxId, OutboxRepairRequest request) {
        return repairOutbox(ctx, outboxId, request, false);
    }

    private OutboxDelivery repairOutbox(RequestContext ctx, String outboxId, OutboxRepairRequest request,
                                        boolean requeue) {
        String reason = requireReason(request == null ? null : request.reason());
        OutboxDelivery d = product.repair(ctx, outboxId, request.destination(), requeue, reason);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("outboxId", d.outboxId());
        p.put("destination", d.destination());
        p.put("rootId", d.rootId());
        p.put("rootSeq", d.rootSeq());
        p.put("reason", reason);
        tx.inNewTx(() -> audit.record(ctx, "sec:" + ctx.tenantId(), requeue ? "OUTBOX_REQUEUED" : "OUTBOX_SKIPPED", p));
        return d;
    }

    // ================================================================== jobs

    @Override
    public Page<Job> jobs(RequestContext ctx, JobFilter filter, Page.PageRequest page) {
        JobQuery q = managementService.createJobQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        if (filter != null) {
            if (filter.processInstanceId() != null) {
                q.processInstanceId(filter.processInstanceId());
            }
            if (filter.processDefinitionKey() != null) {
                q.processDefinitionKey(filter.processDefinitionKey());
            }
            if (Boolean.TRUE.equals(filter.withException())) {
                q.withException();
            }
            if (Boolean.TRUE.equals(filter.timers())) {
                q.timers();
            }
            if (Boolean.TRUE.equals(filter.executable())) {
                q.executable();
            }
        }
        q.orderByJobDuedate().asc();
        return OperatonSupport.page(q, page, props.getCountCap(), OperatonOpsAdapter::job);
    }

    private org.operaton.bpm.engine.runtime.Job findJob(RequestContext ctx, String jobId) {
        org.operaton.bpm.engine.runtime.Job j = OperatonSupport.call(
                () -> managementService.createJobQuery().jobId(jobId).singleResult());
        if (j == null) {
            throw ApiProblem.notFound("job", jobId);
        }
        OperatonSupport.checkTenant(ctx, j.getTenantId(), "job", jobId);
        return j;
    }

    @Override
    public Job setJobRetries(RequestContext ctx, String jobId, JobRetriesRequest request) {
        String reason = requireReason(request == null ? null : request.reason());
        if (request.retries() < 0) {
            throw ApiProblem.badRequest("INVALID_RETRIES", "retries must be >= 0");
        }
        org.operaton.bpm.engine.runtime.Job j = findJob(ctx, jobId);
        tx.runInTx(() -> {
            OperatonSupport.run(() -> managementService.setJobRetries(jobId, request.retries()));
            audit.record(ctx, chainOf(ctx, j.getProcessInstanceId()), "JOB_RETRIES_SET",
                    jobPayload(j, reason, Map.of("retries", request.retries())));
        });
        return job(findJob(ctx, jobId));
    }

    @Override
    public Job executeJob(RequestContext ctx, String jobId, String reason) {
        String r = requireReason(reason);
        org.operaton.bpm.engine.runtime.Job j = findJob(ctx, jobId);
        Job before = job(j);
        // Outside the Spring transaction: the engine runs the job and its failure handling in its own commands.
        OperatonSupport.run(() -> managementService.executeJob(jobId));
        tx.inNewTx(() -> audit.record(ctx, chainOf(ctx, j.getProcessInstanceId()), "JOB_EXECUTED",
                jobPayload(j, r, Map.of())));
        org.operaton.bpm.engine.runtime.Job after = managementService.createJobQuery().jobId(jobId).singleResult();
        return after == null ? before : job(after);
    }

    @Override
    public Job setJobSuspension(RequestContext ctx, String jobId, JobSuspensionRequest request) {
        String reason = requireReason(request == null ? null : request.reason());
        org.operaton.bpm.engine.runtime.Job j = findJob(ctx, jobId);
        tx.runInTx(() -> {
            OperatonSupport.run(() -> {
                if (request.suspended()) {
                    managementService.suspendJobById(jobId);
                } else {
                    managementService.activateJobById(jobId);
                }
            });
            audit.record(ctx, chainOf(ctx, j.getProcessInstanceId()), request.suspended() ? "JOB_SUSPENDED" : "JOB_ACTIVATED",
                    jobPayload(j, reason, Map.of()));
        });
        return job(findJob(ctx, jobId));
    }

    private static Job job(org.operaton.bpm.engine.runtime.Job j) {
        String type = j instanceof JobEntity je ? je.getJobHandlerType() : "job";
        return new Job(j.getId(), type, j.getProcessInstanceId(), j.getProcessDefinitionKey(), j.getFailedActivityId(),
                OperatonSupport.instant(j.getDuedate()), j.getRetries(), j.getExceptionMessage(), j.isSuspended(),
                j.getPriority(), j.getTenantId());
    }

    private static Map<String, Object> jobPayload(org.operaton.bpm.engine.runtime.Job j, String reason,
                                                  Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("jobId", j.getId());
        p.put("processInstanceId", j.getProcessInstanceId());
        p.put("reason", reason);
        p.putAll(extra);
        return p;
    }

    // ================================================================== batches (R64), persisted

    /** {@code PORT_BATCH.batch_type} prefix that tells REST batches apart from ports' ProductBatchService rows. */
    static final String TYPE_PREFIX = "REST:";
    private static final String BATCH_COLS = "id, tenant_id, batch_type, state, total_items, done_items, failed_items,"
            + " config, created_by, created_at, updated_at";
    private static final ObjectMapper CONFIG_JSON = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * One batch as stored in {@code PORT_BATCH}; {@code config} holds filter, params, reason, approvedBy,
     * fourEyesRequired, itemKeyBase and completedAt. {@code engineArg} (a built migration plan) is never stored: it is
     * rebuilt from {@code params.migrationPlan} when a batch is resumed after a restart.
     */
    private static final class BatchRow {
        final String id;
        final String tenantId;
        final String type;
        final String state;
        final long total;
        final long succeeded;
        final long failed;
        final String createdBy;
        final String reason;
        final BatchFilter filter;
        final Map<String, Object> params;
        final boolean fourEyes;
        final String keyBase;
        final String approvedBy;
        final Instant createdAt;
        final Instant completedAt;
        Object engineArg;

        BatchRow(String id, String tenantId, String type, String state, long total, long succeeded, long failed,
                 String createdBy, Map<String, Object> config, Instant createdAt) {
            this.id = id;
            this.tenantId = tenantId;
            this.type = type;
            this.state = state;
            this.total = total;
            this.succeeded = succeeded;
            this.failed = failed;
            this.createdBy = createdBy;
            this.createdAt = createdAt;
            this.reason = (String) config.get("reason");
            this.filter = config.get("filter") == null ? null : CONFIG_JSON.convertValue(config.get("filter"), BatchFilter.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> p = config.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            this.params = p;
            this.fourEyes = Boolean.TRUE.equals(config.get("fourEyesRequired"));
            this.keyBase = config.get("itemKeyBase") == null ? id : String.valueOf(config.get("itemKeyBase"));
            this.approvedBy = (String) config.get("approvedBy");
            this.completedAt = config.get("completedAt") == null ? null : Instant.parse(String.valueOf(config.get("completedAt")));
        }

        Batch view() {
            return new Batch(id, type, state, total, succeeded, failed, createdBy, approvedBy, reason, createdAt,
                    completedAt, filter, params, fourEyes);
        }
    }

    private static String configJson(BatchFilter filter, Map<String, Object> params, String reason, String approvedBy,
                                      boolean fourEyes, String keyBase, Instant completedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filter", filter == null ? null : CONFIG_JSON.convertValue(filter, Map.class));
        m.put("params", CONFIG_JSON.convertValue(params == null ? Map.of() : params, Map.class));
        m.put("reason", reason);
        m.put("approvedBy", approvedBy);
        m.put("fourEyesRequired", fourEyes);
        m.put("itemKeyBase", keyBase);
        m.put("completedAt", completedAt == null ? null : completedAt.toString());
        try {
            return CONFIG_JSON.writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiProblem.badRequest("BATCH_PARAMS_INVALID", "batch params are not serialisable: " + e.getOriginalMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        try {
            return json == null ? new LinkedHashMap<>() : CONFIG_JSON.readValue(json, LinkedHashMap.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("stored batch config is not valid JSON", e);
        }
    }

    private static BatchRow batchRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String type = rs.getString("batch_type");
        return new BatchRow(rs.getString("id"), rs.getString("tenant_id"),
                type.startsWith(TYPE_PREFIX) ? type.substring(TYPE_PREFIX.length()) : type, rs.getString("state"),
                rs.getLong("total_items"), rs.getLong("done_items"), rs.getLong("failed_items"),
                rs.getString("created_by"), parse(rs.getString("config")), Jdbc.instant(rs, "created_at"));
    }

    @Override
    public BatchPreview previewBatch(RequestContext ctx, BatchRequest request) {
        String type = batchType(request);
        List<String> roots = resolveRoots(ctx, type, request.filter());
        long cap = props.getCountCap();
        boolean capped = roots.size() > cap;
        return new BatchPreview(type, capped ? cap : roots.size(), capped, roots.subList(0, Math.min(10, roots.size())),
                roots.size() > props.getBatchFourEyesThreshold());
    }

    @Override
    public Batch createBatch(RequestContext ctx, BatchRequest request) {
        String type = batchType(request);
        String reason = requireReason(request.reason());
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        Object engineArg = null;
        if (type.equals("MIGRATE")) {
            engineArg = buildPlan(ctx, migrationRequest(params.get("migrationPlan")));
        } else if (type.equals("RESTART")) {
            Object def = params.get("processDefinitionId");
            if (def == null) {
                throw ApiProblem.badRequest("PROCESS_DEFINITION_REQUIRED", "params.processDefinitionId is required for RESTART");
            }
            checkDefinition(ctx, String.valueOf(def));
        } else if (type.equals("MODIFY") && !(params.get("instructions") instanceof List<?>)) {
            throw ApiProblem.badRequest("INSTRUCTIONS_REQUIRED", "params.instructions is required for MODIFY");
        } else if (type.equals("SET_VARIABLES") && !(params.get("variables") instanceof Map<?, ?>)) {
            throw ApiProblem.badRequest("VARIABLES_REQUIRED", "params.variables is required for SET_VARIABLES");
        }
        List<String> roots = resolveRoots(ctx, type, request.filter());
        return startBatch(ctx, type, reason, request.filter(), params, roots, engineArg);
    }

    /**
     * Writes the batch and all its items (the resolved instance list) in the caller's transaction, then starts the
     * runner after commit, so the runner never sees an uncommitted batch.
     */
    private Batch startBatch(RequestContext ctx, String type, String reason, BatchFilter filter,
                             Map<String, Object> params, List<String> roots, Object engineArg) {
        if (roots.size() > MAX_BATCH_ITEMS) {
            throw ApiProblem.unprocessable("BATCH_TOO_LARGE",
                    "A batch may hold at most " + MAX_BATCH_ITEMS + " items; narrow the filter");
        }
        String id = UUID.randomUUID().toString();
        boolean fourEyes = roots.size() > props.getBatchFourEyesThreshold();
        String keyBase = ctx.idempotencyKey() != null ? ctx.idempotencyKey() : id;
        String state = roots.isEmpty() ? "COMPLETED" : fourEyes ? "AWAITING_APPROVAL" : "RUNNING";
        BatchRow b = tx.withConnection(c -> {
            Instant now = Jdbc.dbNow(c, tx.dialect());
            Jdbc.update(c, "INSERT INTO PORT_BATCH(" + BATCH_COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    id, ctx.tenantId(), TYPE_PREFIX + type, state, roots.size(), 0, 0,
                    configJson(filter, params, reason, null, fourEyes, keyBase, roots.isEmpty() ? now : null),
                    ctx.principalId(), now, now);
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO PORT_BATCH_ITEM(batch_id, item_seq,"
                    + " item_ref, state, attempts, result, updated_at) VALUES (?,?,?,?,?,?,?)")) {
                for (int i = 0; i < roots.size(); i++) {
                    Jdbc.bind(ps, 1, id, i, roots.get(i), "PENDING", 0, itemResult(null, keyBase + ":" + i, null), now);
                    ps.addBatch();
                    if (i % 500 == 499) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            } catch (java.sql.SQLException e) {
                throw new Jdbc.LayerSqlException("PORT_BATCH_ITEM insert", e);
            }
            return loadBatch(c, ctx.tenantId(), id).orElseThrow();
        });
        b.engineArg = engineArg;
        Map<String, Object> p = batchPayload(b);
        tx.inNewTx(() -> audit.record(ctx, "sec:" + ctx.tenantId(), "BATCH_CREATED", p));
        if ("RUNNING".equals(state)) {
            afterCommit(() -> submit(b.tenantId, b.id, engineArg));
        }
        return b.view();
    }

    @Override
    public Page<Batch> batches(RequestContext ctx, String state, Page.PageRequest page) {
        String where = "tenant_id=? AND batch_type LIKE ?" + (state == null ? "" : " AND state=?");
        Object[] params = state == null ? new Object[]{ctx.tenantId(), TYPE_PREFIX + "%"}
                : new Object[]{ctx.tenantId(), TYPE_PREFIX + "%", state.toUpperCase(Locale.ROOT)};
        long cap = props.getCountCap();
        return tx.withConnection(c -> {
            List<Batch> items = limited(c, "SELECT " + BATCH_COLS + " FROM PORT_BATCH WHERE " + where
                    + " ORDER BY created_at DESC, id", page.firstResult(), page.size(), OperatonOpsAdapter::batchRow, params)
                    .stream().map(BatchRow::view).toList();
            long n = limited(c, "SELECT id FROM PORT_BATCH WHERE " + where, 0, (int) Math.min(Integer.MAX_VALUE, cap + 1),
                    rs -> rs.getString(1), params).size();
            return Page.of(items, Math.min(n, cap), n > cap);
        });
    }

    @Override
    public Batch batch(RequestContext ctx, String id) {
        return requireBatch(ctx, id).view();
    }

    @Override
    public Page<BatchItem> batchItems(RequestContext ctx, String id, String state, Page.PageRequest page) {
        requireBatch(ctx, id);
        String s = state == null ? null : state.toUpperCase(Locale.ROOT);
        // IN_PROGRESS is shown as PENDING: the API states are PENDING | SUCCEEDED | FAILED.
        String where = "batch_id=?" + (s == null ? "" : "PENDING".equals(s) ? " AND state IN ('PENDING','IN_PROGRESS')"
                : " AND state=?");
        Object[] params = s == null || "PENDING".equals(s) ? new Object[]{id} : new Object[]{id, s};
        long cap = props.getCountCap();
        return tx.withConnection(c -> {
            List<BatchItem> items = limited(c, "SELECT item_seq, item_ref, state, result FROM PORT_BATCH_ITEM WHERE "
                    + where + " ORDER BY item_seq", page.firstResult(), page.size(), OperatonOpsAdapter::item, params);
            long n = limited(c, "SELECT item_seq FROM PORT_BATCH_ITEM WHERE " + where, 0,
                    (int) Math.min(Integer.MAX_VALUE, cap + 1), rs -> rs.getInt(1), params).size();
            return Page.of(items, Math.min(n, cap), n > cap);
        });
    }

    private static BatchItem item(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> r = parse(rs.getString("result"));
        String st = rs.getString("state");
        return new BatchItem(rs.getInt("item_seq"), rs.getString("item_ref"), "IN_PROGRESS".equals(st) ? "PENDING" : st,
                (String) r.get("error"), (String) r.get("itemKey"),
                r.get("finishedAt") == null ? null : Instant.parse(String.valueOf(r.get("finishedAt"))));
    }

    private static String itemResult(String error, String itemKey, Instant finishedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", error);
        m.put("itemKey", itemKey);
        m.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        try {
            return CONFIG_JSON.writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Batch controlBatch(RequestContext ctx, String id, BatchControlRequest request) {
        if (request == null || request.action() == null) {
            throw ApiProblem.badRequest("ACTION_REQUIRED", "action is required");
        }
        String reason = requireReason(request.reason());
        String action = request.action().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVE", "PAUSE", "RESUME", "CANCEL").contains(action)) {
            throw ApiProblem.badRequest("INVALID_ACTION", "action must be PAUSE, RESUME, CANCEL or APPROVE");
        }
        BatchRow after = tx.withConnection(c -> {
            BatchRow b = Jdbc.one(c, tx.dialect().lockingSelect(BATCH_COLS, "PORT_BATCH",
                            "id=? AND tenant_id=? AND batch_type LIKE ?"), OperatonOpsAdapter::batchRow,
                    id, ctx.tenantId(), TYPE_PREFIX + "%").orElseThrow(() -> ApiProblem.notFound("batch", id));
            String next;
            String approvedBy = b.approvedBy;
            Instant completedAt = b.completedAt;
            switch (action) {
                case "APPROVE" -> {
                    if (!"AWAITING_APPROVAL".equals(b.state)) {
                        throw invalidState(b, action);
                    }
                    if (b.createdBy != null && b.createdBy.equals(ctx.principalId())) {
                        throw ApiProblem.unprocessable("SEGREGATION_OF_DUTIES",
                                "The approver of a batch must differ from its creator (four-eyes, R64)");
                    }
                    approvedBy = ctx.principalId();
                    next = "RUNNING";
                }
                case "PAUSE" -> {
                    if (!"RUNNING".equals(b.state)) {
                        throw invalidState(b, action);
                    }
                    next = "PAUSED";
                }
                case "RESUME" -> {
                    if (!"PAUSED".equals(b.state)) {
                        throw invalidState(b, action);
                    }
                    next = "RUNNING";
                }
                default -> {
                    if (Set.of("COMPLETED", "CANCELLED", "FAILED").contains(b.state)) {
                        throw invalidState(b, action);
                    }
                    next = "CANCELLED";
                    completedAt = Jdbc.dbNow(c, tx.dialect());
                }
            }
            Jdbc.update(c, "UPDATE PORT_BATCH SET state=?, config=?, updated_at=? WHERE id=?", next,
                    configJson(b.filter, b.params, b.reason, approvedBy, b.fourEyes, b.keyBase, completedAt),
                    Jdbc.dbNow(c, tx.dialect()), id);
            return loadBatch(c, ctx.tenantId(), id).orElseThrow();
        });
        Map<String, Object> p = batchPayload(after);
        p.put("action", action);
        p.put("controlReason", reason);
        String event = switch (action) {
            case "APPROVE" -> "BATCH_APPROVED";
            case "PAUSE" -> "BATCH_PAUSED";
            case "RESUME" -> "BATCH_RESUMED";
            default -> "BATCH_CANCELLED";
        };
        tx.inNewTx(() -> audit.record(ctx, "sec:" + ctx.tenantId(), event, p));
        if ("RUNNING".equals(after.state)) {
            afterCommit(() -> submit(after.tenantId, after.id, null));
        }
        return after.view();
    }

    private static ApiProblem invalidState(BatchRow b, String action) {
        return ApiProblem.conflict("INVALID_BATCH_STATE", "Batch " + b.id + " is " + b.state + "; cannot " + action);
    }

    private BatchRow requireBatch(RequestContext ctx, String id) {
        return tx.withConnection(c -> loadBatch(c, ctx.tenantId(), id)).orElseThrow(() -> ApiProblem.notFound("batch", id));
    }

    private static java.util.Optional<BatchRow> loadBatch(java.sql.Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT " + BATCH_COLS + " FROM PORT_BATCH WHERE id=? AND tenant_id=? AND batch_type LIKE ?",
                OperatonOpsAdapter::batchRow, id, tenant, TYPE_PREFIX + "%");
    }

    /**
     * Start-up recovery (nothing is lost on restart): every RUNNING REST batch continues from its PENDING items; items
     * left IN_PROGRESS by a crash go back to PENDING (their transaction rolled back, so they changed nothing).
     * PAUSED and AWAITING_APPROVAL batches stay as they are. Call once, after the engine is up, on one node.
     *
     * @return the number of batches resumed
     */
    public int resume() {
        List<String[]> running = tx.withConnection(c -> Jdbc.query(c,
                "SELECT tenant_id, id FROM PORT_BATCH WHERE state='RUNNING' AND batch_type LIKE ?",
                rs -> new String[]{rs.getString(1), rs.getString(2)}, TYPE_PREFIX + "%"));
        for (String[] b : running) {
            tx.withConnection(c -> Jdbc.update(c, "UPDATE PORT_BATCH_ITEM SET state='PENDING' WHERE batch_id=?"
                    + " AND state='IN_PROGRESS'", b[1]));
            submit(b[0], b[1], null);
        }
        if (!running.isEmpty()) {
            LOG.info("Resumed {} running batches after start-up", running.size());
        }
        return running.size();
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

    private void submit(String tenant, String id, Object engineArg) {
        if (!running.add(id)) {
            return;
        }
        executor.submit(() -> {
            try {
                runBatch(tenant, id, engineArg);
            } catch (RuntimeException e) {
                LOG.error("batch {} runner stopped: {}", id, e.toString());
            } finally {
                running.remove(id);
            }
        });
    }

    /**
     * The runner. Per item: (1) a short transaction claims the next PENDING item under the batch row lock, unless the
     * batch is no longer RUNNING; (2) one transaction applies the item to its root and marks it SUCCEEDED (VI.1 rule 3:
     * one root per transaction; the item row commits with the engine change); (3) on failure that transaction rolls
     * back and a new one marks the item FAILED with the error.
     */
    private void runBatch(String tenant, String id, Object engineArgOrNull) {
        RequestContext sys0 = RequestContext.system(tenant, null);
        BatchRow b = tx.withConnection(c -> loadBatch(c, tenant, id)).orElse(null);
        if (b == null) {
            return;
        }
        RequestContext sys = RequestContext.system(tenant, b.createdBy);
        b.engineArg = engineArgOrNull;
        if (b.engineArg == null && "MIGRATE".equals(b.type)) {
            b.engineArg = buildPlan(sys0, migrationRequest(b.params.get("migrationPlan")));
        }
        while (true) {
            Object[] claimed = tx.inNewTx(() -> {
                java.sql.Connection c = tx.connection();
                BatchRow cur = Jdbc.one(c, tx.dialect().lockingSelect(BATCH_COLS, "PORT_BATCH", "id=?"),
                        OperatonOpsAdapter::batchRow, id).orElse(null);
                if (cur == null || !"RUNNING".equals(cur.state)) {
                    return null;
                }
                List<Object[]> next = limited(c, "SELECT item_seq, item_ref FROM PORT_BATCH_ITEM WHERE batch_id=?"
                        + " AND state='PENDING' ORDER BY item_seq", 0, 1, rs -> new Object[]{rs.getInt(1), rs.getString(2)}, id);
                if (next.isEmpty()) {
                    finish(c, cur);
                    return null;
                }
                Jdbc.update(c, "UPDATE PORT_BATCH_ITEM SET state='IN_PROGRESS', attempts=attempts+1, updated_at=?"
                        + " WHERE batch_id=? AND item_seq=?", Jdbc.dbNow(c, tx.dialect()), id, next.get(0)[0]);
                return next.get(0);
            });
            if (claimed == null) {
                return;
            }
            int index = (Integer) claimed[0];
            String root = (String) claimed[1];
            String itemKey = b.keyBase + ":" + index;
            String error = null;
            try {
                tx.inNewTx(() -> {
                    applyItem(sys, b, index, root);
                    java.sql.Connection c = tx.connection();
                    Instant now = Jdbc.dbNow(c, tx.dialect());
                    Jdbc.update(c, "UPDATE PORT_BATCH_ITEM SET state='SUCCEEDED', result=?, updated_at=? WHERE batch_id=?"
                            + " AND item_seq=? AND state='IN_PROGRESS'", itemResult(null, itemKey, now), now, id, index);
                    Jdbc.update(c, "UPDATE PORT_BATCH SET done_items=done_items+1, updated_at=? WHERE id=?", now, id);
                    return null;
                });
            } catch (ApiProblem e) {
                error = e.code() + ": " + e.getMessage();
            } catch (RuntimeException e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            if (error != null) {
                String err = error;
                tx.inNewTx(() -> {
                    java.sql.Connection c = tx.connection();
                    Instant now = Jdbc.dbNow(c, tx.dialect());
                    Jdbc.update(c, "UPDATE PORT_BATCH_ITEM SET state='FAILED', result=?, updated_at=? WHERE batch_id=?"
                            + " AND item_seq=?", itemResult(err, itemKey, now), now, id, index);
                    Jdbc.update(c, "UPDATE PORT_BATCH SET failed_items=failed_items+1, updated_at=? WHERE id=?", now, id);
                    return null;
                });
            }
        }
    }

    /** Called under the batch row lock when no PENDING item is left: COMPLETED (or FAILED if nothing succeeded). */
    private void finish(java.sql.Connection c, BatchRow cur) {
        long open = limited(c, "SELECT item_seq FROM PORT_BATCH_ITEM WHERE batch_id=? AND state IN ('PENDING','IN_PROGRESS')",
                0, 1, rs -> rs.getInt(1), cur.id).size();
        if (open > 0) {
            return;
        }
        Instant now = Jdbc.dbNow(c, tx.dialect());
        String state = cur.failed > 0 && cur.succeeded == 0 && cur.total > 0 ? "FAILED" : "COMPLETED";
        Jdbc.update(c, "UPDATE PORT_BATCH SET state=?, config=?, updated_at=? WHERE id=? AND state='RUNNING'", state,
                configJson(cur.filter, cur.params, cur.reason, cur.approvedBy, cur.fourEyes, cur.keyBase, now), now, cur.id);
        BatchRow done = loadBatch(c, cur.tenantId, cur.id).orElse(cur);
        Map<String, Object> p = batchPayload(done);
        try {
            audit.record(RequestContext.system(cur.tenantId, cur.createdBy), "sec:" + cur.tenantId, "BATCH_COMPLETED", p);
        } catch (RuntimeException e) {
            LOG.warn("could not audit completion of batch {}: {}", cur.id, e.toString());
        }
    }

    /** Portable paging: fetch firstResult+size rows with setMaxRows and skip the first ones. */
    private static <T> List<T> limited(java.sql.Connection c, String sql, int offset, int limit, Jdbc.RowMapper<T> mapper,
                                       Object... params) {
        try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setMaxRows(Math.addExact(offset, limit));
            Jdbc.bind(ps, 1, params);
            List<T> out = new ArrayList<>();
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                int i = 0;
                while (rs.next()) {
                    if (i++ >= offset) {
                        out.add(mapper.map(rs));
                    }
                }
            }
            return out;
        } catch (java.sql.SQLException e) {
            throw new Jdbc.LayerSqlException("batch query", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyItem(RequestContext sys, BatchRow b, int index, String root) {
        Map<String, Object> params = b.params;
        String event;
        switch (b.type) {
            case "RETRY" -> {
                int retries = params.get("retries") instanceof Number n ? n.intValue() : 1;
                List<String> jobs = managementService.createJobQuery().processInstanceId(root).noRetriesLeft().list()
                        .stream().map(org.operaton.bpm.engine.runtime.Job::getId).toList();
                if (!jobs.isEmpty()) {
                    managementService.setJobRetries(jobs, retries);
                }
                List<String> ext = externalTaskService.createExternalTaskQuery().processInstanceId(root).noRetriesLeft()
                        .list().stream().map(org.operaton.bpm.engine.externaltask.ExternalTask::getId).toList();
                if (!ext.isEmpty()) {
                    externalTaskService.setRetries(ext, retries);
                }
                event = "INCIDENT_RETRIED";
            }
            case "CANCEL", "DELETE" -> {
                ProcessPort pp = processPort;
                if (pp != null) {
                    ProcessPort.TerminateRequest tr = new ProcessPort.TerminateRequest(b.reason, "BATCH");
                    if (b.type.equals("CANCEL")) {
                        pp.cancel(sys, root, tr);
                    } else {
                        pp.delete(sys, root, tr);
                    }
                    return;   // the process port audits INSTANCE_CANCELLED / INSTANCE_DELETED itself
                }
                runtimeService.deleteProcessInstance(root, b.reason);
                event = b.type.equals("CANCEL") ? "INSTANCE_CANCELLED" : "INSTANCE_DELETED";
            }
            case "SUSPEND" -> {
                runtimeService.updateProcessInstanceSuspensionState().byProcessInstanceId(root).suspend();
                event = "INSTANCE_SUSPENDED";
            }
            case "RESUME" -> {
                runtimeService.updateProcessInstanceSuspensionState().byProcessInstanceId(root).activate();
                event = "INSTANCE_RESUMED";
            }
            case "SET_VARIABLES" -> {
                runtimeService.setVariables(root, TypedVariables.fromJson((Map<String, Object>) params.get("variables")));
                event = "VARIABLES_UPDATED";
            }
            case "MODIFY" -> {
                ProcessInstanceModificationBuilder m = runtimeService.createProcessInstanceModification(root);
                for (Object o : (List<Object>) params.get("instructions")) {
                    Map<String, Object> ins = (Map<String, Object>) o;
                    String t = String.valueOf(ins.get("type"));
                    String act = ins.get("activityId") == null ? null : String.valueOf(ins.get("activityId"));
                    String ai = ins.get("activityInstanceId") == null ? null : String.valueOf(ins.get("activityInstanceId"));
                    m = switch (t) {
                        case "startBeforeActivity" -> m.startBeforeActivity(act);
                        case "startAfterActivity" -> m.startAfterActivity(act);
                        case "startTransition" -> m.startTransition(act);
                        case "cancelActivity" -> m.cancelAllForActivity(act);
                        case "cancelActivityInstance" -> m.cancelActivityInstance(ai);
                        case "cancelTransitionInstance" -> m.cancelTransitionInstance(ai);
                        default -> throw ApiProblem.badRequest("INVALID_INSTRUCTION", "unknown instruction type " + t);
                    };
                }
                m.setAnnotation(b.reason);
                m.execute(Boolean.TRUE.equals(params.get("skipCustomListeners")),
                        Boolean.TRUE.equals(params.get("skipIoMappings")));
                event = "INSTANCE_MODIFIED";
            }
            case "MIGRATE" -> {
                MigrationPlanExecutionBuilder mb = runtimeService
                        .newMigration((org.operaton.bpm.engine.migration.MigrationPlan) b.engineArg)
                        .processInstanceIds(root);
                if (Boolean.TRUE.equals(params.get("skipCustomListeners"))) {
                    mb = mb.skipCustomListeners();
                }
                if (Boolean.TRUE.equals(params.get("skipIoMappings"))) {
                    mb = mb.skipIoMappings();
                }
                mb.execute();
                event = "INSTANCE_MIGRATED";
            }
            case "RESTART" -> {
                RestartProcessInstanceBuilder rb = runtimeService.restartProcessInstances(
                        String.valueOf(params.get("processDefinitionId")));
                for (Object a : (List<Object>) params.getOrDefault("startBeforeActivityIds", List.of())) {
                    rb = rb.startBeforeActivity(String.valueOf(a));
                }
                rb = rb.processInstanceIds(root);
                if (Boolean.TRUE.equals(params.get("initialVariables"))) {
                    rb = rb.initialSetOfVariables();
                }
                if (Boolean.TRUE.equals(params.get("skipCustomListeners"))) {
                    rb = rb.skipCustomListeners();
                }
                rb.execute();
                event = "INSTANCE_RESTARTED";
            }
            default -> throw ApiProblem.badRequest("INVALID_BATCH_TYPE", b.type);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("batchId", b.id);
        p.put("itemKey", b.keyBase + ":" + index);
        p.put("processInstanceId", root);
        p.put("reason", b.reason);
        p.put("operator", b.createdBy);
        audit.record(sys, root, event, p);
    }

    private static String batchType(BatchRequest request) {
        if (request == null || request.type() == null) {
            throw ApiProblem.badRequest("BATCH_TYPE_REQUIRED", "type is required");
        }
        String type = request.type().toUpperCase(Locale.ROOT);
        if (!BATCH_TYPES.contains(type)) {
            throw ApiProblem.badRequest("INVALID_BATCH_TYPE", "type must be one of " + new java.util.TreeSet<>(BATCH_TYPES));
        }
        return type;
    }

    /**
     * Item roots from the same tenant-scoped query the preview counts (R64). RESTART targets finished instances
     * (history); every other type targets runtime instances.
     */
    private List<String> resolveRoots(RequestContext ctx, String type, BatchFilter f) {
        if (f == null || (f.processDefinitionKey() == null && f.processDefinitionId() == null
                && empty(f.processInstanceIds()) && empty(f.businessKeys()) && f.activityId() == null
                && f.withIncidents() == null)) {
            throw ApiProblem.badRequest("FILTER_REQUIRED", "A batch needs a filter; an unfiltered batch is refused");
        }
        Set<String> roots = new LinkedHashSet<>();
        List<String> keys = empty(f.businessKeys()) ? Collections.singletonList(null) : f.businessKeys();
        for (String bk : keys) {
            if (roots.size() > MAX_BATCH_ITEMS) {
                break;
            }
            if (type.equals("RESTART")) {
                HistoricProcessInstanceQuery q = historyService.createHistoricProcessInstanceQuery().finished();
                if (ctx.tenantId() != null) {
                    q.tenantIdIn(ctx.tenantId());
                }
                if (f.processDefinitionKey() != null) {
                    q.processDefinitionKey(f.processDefinitionKey());
                }
                if (f.processDefinitionId() != null) {
                    q.processDefinitionId(f.processDefinitionId());
                }
                if (!empty(f.processInstanceIds())) {
                    q.processInstanceIds(new HashSet<>(f.processInstanceIds()));
                }
                if (bk != null) {
                    q.processInstanceBusinessKey(bk);
                }
                if (f.activityId() != null) {
                    q.executedActivityIdIn(f.activityId());
                }
                if (Boolean.TRUE.equals(f.withIncidents())) {
                    q.withIncidents();
                }
                OperatonSupport.call(() -> q.orderByProcessInstanceStartTime().asc().listPage(0, MAX_BATCH_ITEMS + 1))
                        .forEach(h -> roots.add(((HistoricProcessInstance) h).getId()));
            } else {
                ProcessInstanceQuery q = runtimeService.createProcessInstanceQuery();
                if (ctx.tenantId() != null) {
                    q.tenantIdIn(ctx.tenantId());
                }
                if (f.processDefinitionKey() != null) {
                    q.processDefinitionKey(f.processDefinitionKey());
                }
                if (f.processDefinitionId() != null) {
                    q.processDefinitionId(f.processDefinitionId());
                }
                if (!empty(f.processInstanceIds())) {
                    q.processInstanceIds(new HashSet<>(f.processInstanceIds()));
                }
                if (bk != null) {
                    q.processInstanceBusinessKey(bk);
                }
                if (f.activityId() != null) {
                    q.activityIdIn(f.activityId());
                }
                if (Boolean.TRUE.equals(f.withIncidents())) {
                    q.withIncident();
                }
                OperatonSupport.call(() -> q.orderByProcessInstanceId().asc().listPage(0, MAX_BATCH_ITEMS + 1))
                        .forEach(pi -> roots.add(((ProcessInstance) pi).getId()));
            }
        }
        return new ArrayList<>(roots);
    }

    private static boolean empty(List<?> l) {
        return l == null || l.isEmpty();
    }

    private static Map<String, Object> batchPayload(BatchRow b) {
        Batch v = b.view();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("batchId", v.id());
        p.put("type", v.type());
        p.put("state", v.state());
        p.put("total", v.total());
        p.put("succeeded", v.succeeded());
        p.put("failed", v.failed());
        p.put("reason", v.reason());
        p.put("fourEyesRequired", v.fourEyesRequired());
        p.put("approvedBy", v.approvedBy());
        return p;
    }

    // ================================================================== migration (R48, FLOWABLE-INTO-OPERATON FC-18) and restart

    @Override
    public MigrationPlan planMigration(RequestContext ctx, MigrationPlanRequest request) {
        validatePlanRequest(request);
        checkDefinition(ctx, request.sourceDefinitionId());
        checkDefinition(ctx, request.targetDefinitionId());
        long affected = countInstances(ctx, request.sourceDefinitionId());
        try {
            org.operaton.bpm.engine.migration.MigrationPlan plan = buildPlanUnchecked(request);
            return new MigrationPlan(request.sourceDefinitionId(), request.targetDefinitionId(), instructions(plan),
                    true, List.of(), affected);
        } catch (InvalidPlan e) {
            return new MigrationPlan(request.sourceDefinitionId(), request.targetDefinitionId(),
                    request.instructions() == null ? List.of() : request.instructions(), false, failures(e.report),
                    affected);
        }
    }

    @Override
    public Batch executeMigration(RequestContext ctx, MigrationExecuteRequest request) {
        if (request == null || request.plan() == null) {
            throw ApiProblem.badRequest("PLAN_REQUIRED", "plan is required");
        }
        String reason = requireReason(request.reason());
        org.operaton.bpm.engine.migration.MigrationPlan plan = buildPlan(ctx, request.plan());
        BatchFilter f = request.filter();
        BatchFilter scoped = new BatchFilter(null, request.plan().sourceDefinitionId(),
                !empty(request.processInstanceIds()) ? request.processInstanceIds() : f == null ? null : f.processInstanceIds(),
                f == null ? null : f.businessKeys(), f == null ? null : f.activityId(), f == null ? null : f.withIncidents());
        List<String> roots = resolveRoots(ctx, "MIGRATE", scoped);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("migrationPlan", request.plan());
        params.put("skipCustomListeners", request.skipCustomListeners());
        params.put("skipIoMappings", request.skipIoMappings());
        if (request.plan().formMappings() != null) {
            params.put("formMappings", request.plan().formMappings());
        }
        return startBatch(ctx, "MIGRATE", reason, scoped, params, roots, plan);
    }

    @Override
    public Batch restart(RequestContext ctx, RestartRequest request) {
        if (request == null || request.processDefinitionId() == null) {
            throw ApiProblem.badRequest("PROCESS_DEFINITION_REQUIRED", "processDefinitionId is required");
        }
        if (empty(request.startBeforeActivityIds())) {
            throw ApiProblem.badRequest("ACTIVITIES_REQUIRED", "startBeforeActivityIds is required");
        }
        String reason = requireReason(request.reason());
        checkDefinition(ctx, request.processDefinitionId());
        BatchFilter filter = new BatchFilter(null, request.processDefinitionId(), request.processInstanceIds(), null,
                null, null);
        List<String> roots = resolveRoots(ctx, "RESTART", filter);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processDefinitionId", request.processDefinitionId());
        params.put("startBeforeActivityIds", request.startBeforeActivityIds());
        params.put("initialVariables", request.initialVariables());
        params.put("skipCustomListeners", request.skipCustomListeners());
        return startBatch(ctx, "RESTART", reason, filter, params, roots, null);
    }

    private org.operaton.bpm.engine.migration.MigrationPlan buildPlan(RequestContext ctx, MigrationPlanRequest request) {
        validatePlanRequest(request);
        checkDefinition(ctx, request.sourceDefinitionId());
        checkDefinition(ctx, request.targetDefinitionId());
        try {
            return buildPlanUnchecked(request);
        } catch (InvalidPlan e) {
            throw ApiProblem.unprocessable("MIGRATION_PLAN_INVALID", e.getMessage())
                    .with("validationErrors", failures(e.report));
        }
    }

    /**
     * FLOWABLE-INTO-OPERATON FC-18: Operaton migration instructions are 1:1; a 1:N or N:1 instruction is expanded into its pairs, which the
     * engine validates (a source activity may map to one target only, so a real 1:N split is rejected with a clear
     * validation error).
     */
    private org.operaton.bpm.engine.migration.MigrationPlan buildPlanUnchecked(MigrationPlanRequest request) {
        return OperatonSupport.call(() -> {
            MigrationPlanBuilder b = runtimeService.createMigrationPlan(request.sourceDefinitionId(),
                    request.targetDefinitionId());
            if (request.mapEqualActivities()) {
                b = b.mapEqualActivities();
            }
            if (request.instructions() != null) {
                for (MigrationInstruction ins : request.instructions()) {
                    if (empty(ins.sourceActivityIds()) || empty(ins.targetActivityIds())) {
                        throw ApiProblem.badRequest("INVALID_INSTRUCTION",
                                "every instruction needs sourceActivityIds and targetActivityIds");
                    }
                    for (String s : ins.sourceActivityIds()) {
                        for (String t : ins.targetActivityIds()) {
                            var ib = b.mapActivities(s, t);
                            b = ins.updateEventTrigger() ? ib.updateEventTrigger() : ib;
                        }
                    }
                }
            }
            if (request.variables() != null && !request.variables().isEmpty()) {
                b = b.setVariables(TypedVariables.fromJson(request.variables()));
            }
            try {
                return b.build();
            } catch (MigrationPlanValidationException e) {
                throw new InvalidPlan(e);   // an ApiProblem passes OperatonSupport.call unchanged
            }
        });
    }

    /** Carries the engine's validation report out of {@link OperatonSupport#call}. */
    private static final class InvalidPlan extends ApiProblem {
        final transient MigrationPlanValidationException report;

        InvalidPlan(MigrationPlanValidationException e) {
            super(422, "MIGRATION_PLAN_INVALID", e.getMessage());
            this.report = e;
        }
    }

    private static void validatePlanRequest(MigrationPlanRequest request) {
        if (request == null || request.sourceDefinitionId() == null || request.targetDefinitionId() == null) {
            throw ApiProblem.badRequest("DEFINITIONS_REQUIRED", "sourceDefinitionId and targetDefinitionId are required");
        }
    }

    private static List<String> failures(MigrationPlanValidationException e) {
        List<String> out = new ArrayList<>();
        if (e.getValidationReport() != null) {
            for (MigrationInstructionValidationReport r : e.getValidationReport().getInstructionReports()) {
                var mi = r.getMigrationInstruction();
                for (String failure : r.getFailures()) {
                    out.add(mi.getSourceActivityId() + " -> " + mi.getTargetActivityId() + ": " + failure);
                }
            }
        }
        if (out.isEmpty()) {
            out.add(e.getMessage());
        }
        return out;
    }

    private static List<MigrationInstruction> instructions(org.operaton.bpm.engine.migration.MigrationPlan plan) {
        return plan.getInstructions().stream()
                .map(i -> new MigrationInstruction(List.of(i.getSourceActivityId()), List.of(i.getTargetActivityId()),
                        i.isUpdateEventTrigger()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static MigrationPlanRequest migrationRequest(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            throw ApiProblem.badRequest("PLAN_REQUIRED", "params.migrationPlan is required for MIGRATE");
        }
        Map<String, Object> map = (Map<String, Object>) m;
        List<MigrationInstruction> ins = new ArrayList<>();
        if (map.get("instructions") instanceof List<?> l) {
            for (Object x : l) {
                Map<String, Object> im = (Map<String, Object>) x;
                ins.add(new MigrationInstruction(strings(im.get("sourceActivityIds")), strings(im.get("targetActivityIds")),
                        Boolean.TRUE.equals(im.get("updateEventTrigger"))));
            }
        }
        return new MigrationPlanRequest(str(map.get("sourceDefinitionId")), str(map.get("targetDefinitionId")),
                Boolean.TRUE.equals(map.get("mapEqualActivities")), ins,
                (Map<String, Object>) map.get("formMappings"), (Map<String, Object>) map.get("variables"));
    }

    private static List<String> strings(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return o == null ? List.of() : List.of(String.valueOf(o));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private void checkDefinition(RequestContext ctx, String definitionId) {
        ProcessDefinition d = OperatonSupport.call(
                () -> repositoryService.createProcessDefinitionQuery().processDefinitionId(definitionId).singleResult());
        if (d == null) {
            throw ApiProblem.notFound("process definition", definitionId);
        }
        if (d.getTenantId() != null && ctx.tenantId() != null && !ctx.tenantId().equals(d.getTenantId())) {
            throw ApiProblem.notFound("process definition", definitionId);
        }
    }

    private long countInstances(RequestContext ctx, String definitionId) {
        ProcessInstanceQuery q = runtimeService.createProcessInstanceQuery().processDefinitionId(definitionId);
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        return OperatonSupport.call(q::count);
    }

    // ================================================================== history cleanup

    @Override
    public CleanupStatus cleanupStatus(RequestContext ctx) {
        return OperatonSupport.call(() -> {
            List<org.operaton.bpm.engine.runtime.Job> jobs = historyService.findHistoryCleanupJobs();
            org.operaton.bpm.engine.runtime.Job j = jobs.isEmpty() ? null : jobs.get(0);
            var report = historyService.createCleanableHistoricProcessInstanceReport();
            if (ctx.tenantId() != null) {
                report.tenantIdIn(ctx.tenantId());
            }
            Map<String, Long> removable = new LinkedHashMap<>();
            report.compact().list().forEach(r -> removable.merge(
                    r.getProcessDefinitionKey() + ":" + r.getProcessDefinitionVersion(), r.getCleanableProcessInstanceCount(),
                    Long::sum));
            return new CleanupStatus(j == null ? null : j.getId(), j == null ? null : OperatonSupport.instant(j.getDuedate()),
                    j == null ? null : j.getRetries(), j == null ? null : j.getExceptionMessage(), removable);
        });
    }

    @Override
    public CleanupStatus triggerCleanup(RequestContext ctx, String reason) {
        String r = requireReason(reason);
        OperatonSupport.call(() -> historyService.cleanUpHistoryAsync(true));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reason", r);
        tx.inNewTx(() -> audit.record(ctx, "sec:" + ctx.tenantId(), "HISTORY_CLEANUP_TRIGGERED", p));
        return cleanupStatus(ctx);
    }

    // ================================================================== helpers

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "A reason is required for this operation (R63)");
        }
        return reason;
    }

    private static String chainOf(RequestContext ctx, String rootId) {
        return rootId != null ? rootId : "sec:" + ctx.tenantId();
    }

    private static String writeJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiProblem.badRequest("INVALID_JSON", e.getOriginalMessage());
        }
    }
}
