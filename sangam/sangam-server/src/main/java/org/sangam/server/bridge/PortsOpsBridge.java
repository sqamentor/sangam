// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.history.HistoricProcessInstanceQuery;
import org.operaton.bpm.engine.migration.MigrationInstructionBuilder;
import org.operaton.bpm.engine.migration.MigrationPlanBuilder;
import org.operaton.bpm.engine.migration.MigrationPlanExecutionBuilder;
import org.operaton.bpm.engine.runtime.ProcessInstanceModificationBuilder;
import org.operaton.bpm.engine.runtime.ProcessInstanceQuery;
import org.operaton.bpm.engine.runtime.RestartProcessInstanceBuilder;
import org.sangam.ports.batch.ProductBatchService;
import org.sangam.ports.support.Paging;
import org.sangam.rest.adapter.operaton.OperatonOpsAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TypedVariables;

/**
 * {@link OpsPort} with the batch part on sangam-flowable-ports' {@link ProductBatchService} (R64, D-34): pause, resume,
 * cancel, four-eyes approve / reject (approver ≠ creator), per-item results, chunked execution with SKIP LOCKED so
 * several nodes share a batch, resumable after a restart (the rows are in {@code PORT_BATCH} / {@code PORT_BATCH_ITEM}).
 * Every other operation (incidents, reconciliation, outbox, jobs, migration planning, history cleanup) delegates to
 * the default {@link OperatonOpsAdapter}.
 *
 * <p><b>Batch types.</b> ProductBatchService ships handlers only for {@code task-complete}, {@code message-correlate}
 * and {@code process-cancel}. This bridge registers one handler per REST batch type, named {@code ops:<TYPE>}
 * (RETRY, CANCEL, DELETE, SUSPEND, RESUME, SET_VARIABLES, MODIFY, MIGRATE, RESTART), executed through the Operaton API
 * one instance per item (VI.1 rule 3); CANCEL and DELETE go through {@link ProcessPort} so the tombstone and
 * {@code CANCEL_REQUESTED} rules of VI.5 apply. The same handlers are also registered for the {@code REST:<TYPE>}
 * rows the default adapter writes, so batches created before this bridge was bound are continued by the ports
 * runner. Only one batch runner works on {@code PORT_BATCH} (the ports runner started by {@code PortsWiring}); the
 * default adapter's own runner is therefore not started while this bridge is bound, because two runners claim items
 * with different state vocabularies.
 *
 * <p>State mapping to the REST contract: PENDING_APPROVAL → AWAITING_APPROVAL, COMPLETED_WITH_FAILURES → COMPLETED
 * (with {@code failed} &gt; 0), REJECTED → CANCELLED; item DONE → SUCCEEDED, RUNNING → PENDING, CANCELLED → FAILED
 * with error {@code cancelled}. {@code CANCEL} on a batch awaiting approval by someone other than its creator is a
 * four-eyes rejection (with the reason). {@link ProductBatchService.BatchStateException} becomes
 * {@code 409 INVALID_BATCH_STATE}.
 */
public final class PortsOpsBridge implements OpsPort, AutoCloseable {

    public static final String TYPE_PREFIX = "ops:";
    static final List<String> TYPES = List.of("RETRY", "CANCEL", "DELETE", "SUSPEND", "RESUME", "SET_VARIABLES",
            "MODIFY", "MIGRATE", "RESTART");
    private static final int SAMPLE = 10;

    private final ProductBatchService batches;
    private final OperatonOpsAdapter defaults;
    private final ProcessEngine engine;
    private final ProcessPort processes;
    private final AuditPort audit;
    private final RestProperties props;
    /** Built Operaton migration plans per batch configuration (a cache: rebuilt from PORT_BATCH.config on demand). */
    private final Map<String, org.operaton.bpm.engine.migration.MigrationPlan> plans = new ConcurrentHashMap<>();

    public PortsOpsBridge(ProductBatchService batches, OperatonOpsAdapter defaults, ProcessEngine engine,
                          ProcessPort processes, AuditPort audit, RestProperties props) {
        this.batches = batches;
        this.defaults = defaults;
        this.engine = engine;
        this.processes = processes;
        this.audit = audit;
        this.props = props;
        for (String type : TYPES) {
            ProductBatchService.ItemHandler h = (tenant, ref, cfg) -> apply(type, tenant, ref, cfg);
            batches.register(TYPE_PREFIX + type, h);
            batches.register("REST:" + type, h);
        }
    }

    // ================================================================== batches → ProductBatchService

    @Override
    public BatchPreview previewBatch(RequestContext ctx, BatchRequest request) {
        String type = type(request.type());
        List<String> roots = resolve(ctx, type, request.filter(), request.params());
        long cap = props.getCountCap();
        boolean capped = roots.size() > cap;
        return new BatchPreview(type, capped ? cap : roots.size(), capped, roots.subList(0, Math.min(SAMPLE, roots.size())),
                batches.fourEyesRequired(ctx.tenantId(), TYPE_PREFIX + type, roots.size()));
    }

    @Override
    public Batch createBatch(RequestContext ctx, BatchRequest request) {
        String type = type(request.type());
        List<String> roots = resolve(ctx, type, request.filter(), request.params());
        if (roots.isEmpty()) {
            throw ApiProblem.unprocessable("BATCH_EMPTY", "The filter matches no instance");
        }
        return create(ctx, type, roots, request.filter(), request.params(), request.reason());
    }

    @Override
    public Page<Batch> batches(RequestContext ctx, String state, Page.PageRequest page) {
        ProductBatchService.State s = state == null || state.isBlank() ? null : portsState(state);
        Paging.PageResult<ProductBatchService.Batch> r = PortsErrors.call(() ->
                batches.list(ctx.tenantId(), s, new Paging.PageRequest(page.page(), page.size())));
        return Page.of(r.items().stream().map(PortsOpsBridge::map).toList(), r.total(), r.capped());
    }

    @Override
    public Batch batch(RequestContext ctx, String id) {
        return map(owned(ctx, id));
    }

    @Override
    public Page<BatchItem> batchItems(RequestContext ctx, String id, String state, Page.PageRequest page) {
        ProductBatchService.Batch b = owned(ctx, id);
        String s = state == null || state.isBlank() ? null : switch (state.toUpperCase(Locale.ROOT)) {
            case "SUCCEEDED" -> "DONE";
            case "PENDING", "FAILED" -> state.toUpperCase(Locale.ROOT);
            default -> throw ApiProblem.badRequest("VALIDATION_FAILED", "state must be PENDING | SUCCEEDED | FAILED");
        };
        List<ProductBatchService.Item> all = new ArrayList<>(PortsErrors.call(() -> batches.items(id, s)));
        if ("PENDING".equals(s)) {
            all.addAll(PortsErrors.call(() -> batches.items(id, "RUNNING")));
        }
        if ("FAILED".equals(s)) {
            all.addAll(PortsErrors.call(() -> batches.items(id, "CANCELLED")));
        }
        String keyBase = String.valueOf(b.config().getOrDefault("itemKeyBase", b.id()));
        List<BatchItem> items = all.stream().map(i -> new BatchItem(i.seq(), i.itemRef(), itemState(i.state()),
                "DONE".equals(i.state()) ? null : "CANCELLED".equals(i.state()) ? "cancelled" : i.result(),
                keyBase + ":" + i.seq(), null)).toList();
        return Page.slice(items, page, props.getCountCap());
    }

    @Override
    public Batch controlBatch(RequestContext ctx, String id, BatchControlRequest request) {
        ProductBatchService.Batch b = owned(ctx, id);
        String action = request.action() == null ? "" : request.action().toUpperCase(Locale.ROOT);
        ProductBatchService.Batch after = switch (action) {
            case "PAUSE" -> PortsErrors.call(() -> batches.pause(id));
            case "RESUME" -> PortsErrors.call(() -> batches.resume(id));
            case "APPROVE" -> PortsErrors.call(() -> batches.approve(id, ctx.principalId()));
            case "CANCEL" -> b.state() == ProductBatchService.State.PENDING_APPROVAL
                    && !ctx.principalId().equals(b.createdBy())
                    ? PortsErrors.call(() -> batches.reject(id, ctx.principalId(), request.reason()))
                    : PortsErrors.call(() -> batches.cancel(id));
            default -> throw ApiProblem.badRequest("VALIDATION_FAILED", "action must be PAUSE | RESUME | CANCEL | APPROVE");
        };
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("batch_id", id);
        p.put("action", action);
        p.put("state", after.state().name());
        p.put("reason", request.reason());
        audit.record(ctx, "sec:" + ctx.tenantId(), "BATCH_" + (after.state() == ProductBatchService.State.REJECTED
                ? "REJECTED" : action.equals("APPROVE") ? "APPROVED" : action + "D"), p);
        return map(after);
    }

    @Override
    public MigrationPlan planMigration(RequestContext ctx, MigrationPlanRequest request) {
        return defaults.planMigration(ctx, request);
    }

    @Override
    public Batch executeMigration(RequestContext ctx, MigrationExecuteRequest request) {
        if (request.plan() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "plan is required");
        }
        MigrationPlan checked = defaults.planMigration(ctx, request.plan());
        if (!checked.valid()) {
            throw ApiProblem.unprocessable("MIGRATION_PLAN_INVALID", String.join("; ", checked.validationErrors()))
                    .with("validationErrors", checked.validationErrors());
        }
        BatchFilter f = request.filter() != null ? request.filter() : new BatchFilter(null,
                request.plan().sourceDefinitionId(), request.processInstanceIds(), null, null, null);
        if (request.processInstanceIds() != null && !request.processInstanceIds().isEmpty()) {
            f = new BatchFilter(null, request.plan().sourceDefinitionId(), request.processInstanceIds(), null, null, null);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("migrationPlan", DocumentStore.toMap(request.plan()));
        params.put("skipCustomListeners", request.skipCustomListeners());
        params.put("skipIoMappings", request.skipIoMappings());
        List<String> roots = resolve(ctx, "MIGRATE", f, params);
        if (roots.isEmpty()) {
            throw ApiProblem.unprocessable("BATCH_EMPTY", "No instance of the source definition matches");
        }
        return create(ctx, "MIGRATE", roots, f, params, request.reason());
    }

    @Override
    public Batch restart(RequestContext ctx, RestartRequest request) {
        if (request.processDefinitionId() == null || request.startBeforeActivityIds() == null
                || request.startBeforeActivityIds().isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "processDefinitionId and startBeforeActivityIds are required");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processDefinitionId", request.processDefinitionId());
        params.put("startBeforeActivityIds", request.startBeforeActivityIds());
        params.put("initialVariables", request.initialVariables());
        params.put("skipCustomListeners", request.skipCustomListeners());
        BatchFilter f = new BatchFilter(null, request.processDefinitionId(), request.processInstanceIds(), null, null, null);
        List<String> roots = resolve(ctx, "RESTART", f, params);
        if (roots.isEmpty()) {
            throw ApiProblem.unprocessable("BATCH_EMPTY", "No finished instance matches");
        }
        return create(ctx, "RESTART", roots, f, params, request.reason());
    }

    private Batch create(RequestContext ctx, String type, List<String> roots, BatchFilter filter,
                         Map<String, Object> params, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "A batch needs a reason (R42, R63)");
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("filter", filter == null ? null : DocumentStore.toMap(filter));
        config.put("params", params == null ? Map.of() : TypedVariables.toJson(params));
        config.put("itemKeyBase", ctx.idempotencyKey());
        String id = PortsErrors.call(() -> batches.create(ctx.tenantId(), TYPE_PREFIX + type, roots, config,
                ctx.principalId(), reason));
        ProductBatchService.Batch b = PortsErrors.call(() -> batches.batch(id)).orElseThrow();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("batch_id", id);
        p.put("type", type);
        p.put("items", roots.size());
        p.put("four_eyes", b.fourEyesRequired());
        p.put("reason", reason);
        audit.record(ctx, "sec:" + ctx.tenantId(), "BATCH_CREATED", p);
        return map(b);
    }

    private ProductBatchService.Batch owned(RequestContext ctx, String id) {
        ProductBatchService.Batch b = PortsErrors.call(() -> batches.batch(id)).orElse(null);
        if (b == null || !ctx.tenantId().equals(b.tenantId())) {
            throw ApiProblem.notFound("batch", id);
        }
        return b;
    }

    // ---------------------------------------------------------------- one item, one instance

    @SuppressWarnings("unchecked")
    private String apply(String type, String tenant, String root, Map<String, Object> config) {
        Map<String, Object> params = config.get("params") instanceof Map<?, ?> m
                ? TypedVariables.fromJson((Map<String, Object>) m) : Map.of();
        RuntimeService runtime = engine.getRuntimeService();
        RequestContext sys = RequestContext.system(tenant, "batch");
        String reason = String.valueOf(params.getOrDefault("reason", "batch"));
        switch (type) {
            case "RETRY" -> {
                int retries = params.get("retries") instanceof Number n ? n.intValue() : 1;
                int n = 0;
                for (org.operaton.bpm.engine.runtime.Job j : engine.getManagementService().createJobQuery().processInstanceId(root).noRetriesLeft().list()) {
                    engine.getManagementService().setJobRetries(j.getId(), retries);
                    n++;
                }
                for (var t : engine.getExternalTaskService().createExternalTaskQuery().processInstanceId(root)
                        .noRetriesLeft().list()) {
                    engine.getExternalTaskService().setRetries(t.getId(), retries);
                    n++;
                }
                return "retried " + n;
            }
            case "CANCEL" -> {
                if (runtime.createProcessInstanceQuery().processInstanceId(root).count() == 0) {
                    return "already ended";
                }
                processes.cancel(sys, root, new ProcessPort.TerminateRequest(reason, "BATCH"));
                return "cancelled";
            }
            case "DELETE" -> {
                if (runtime.createProcessInstanceQuery().processInstanceId(root).count() == 0) {
                    return "already ended";
                }
                processes.delete(sys, root, new ProcessPort.TerminateRequest(reason, "BATCH"));
                return "deleted";
            }
            case "SUSPEND" -> {
                runtime.updateProcessInstanceSuspensionState().byProcessInstanceId(root).suspend();
                return "suspended";
            }
            case "RESUME" -> {
                runtime.updateProcessInstanceSuspensionState().byProcessInstanceId(root).activate();
                return "resumed";
            }
            case "SET_VARIABLES" -> {
                Object vars = params.get("variables");
                if (!(vars instanceof Map<?, ?> vm)) {
                    throw new IllegalArgumentException("params.variables is required");
                }
                runtime.setVariables(root, (Map<String, Object>) vm);
                return "set " + vm.size();
            }
            case "MODIFY" -> {
                ProcessInstanceModificationBuilder b = runtime.createProcessInstanceModification(root);
                Object list = params.get("instructions");
                if (!(list instanceof List<?> instr) || instr.isEmpty()) {
                    throw new IllegalArgumentException("params.instructions is required");
                }
                for (Object o : instr) {
                    Map<String, Object> i = (Map<String, Object>) o;
                    String a = String.valueOf(i.get("activityId"));
                    switch (String.valueOf(i.get("type"))) {
                        case "startBeforeActivity" -> b.startBeforeActivity(a);
                        case "startAfterActivity" -> b.startAfterActivity(a);
                        case "startTransition" -> b.startTransition(a);
                        case "cancelActivity", "cancel" -> b.cancelAllForActivity(a);
                        default -> throw new IllegalArgumentException("unknown instruction type " + i.get("type"));
                    }
                }
                b.execute(Boolean.TRUE.equals(params.get("skipCustomListeners")),
                        Boolean.TRUE.equals(params.get("skipIoMappings")));
                return "modified";
            }
            case "MIGRATE" -> {
                MigrationPlanExecutionBuilder b = runtime.newMigration(plan(params)).processInstanceIds(root);
                if (Boolean.TRUE.equals(params.get("skipCustomListeners"))) {
                    b = b.skipCustomListeners();
                }
                if (Boolean.TRUE.equals(params.get("skipIoMappings"))) {
                    b = b.skipIoMappings();
                }
                b.execute();
                return "migrated";
            }
            case "RESTART" -> {
                RestartProcessInstanceBuilder b = runtime.restartProcessInstances(
                        String.valueOf(params.get("processDefinitionId")));
                for (Object a : (List<Object>) params.getOrDefault("startBeforeActivityIds", List.of())) {
                    b = b.startBeforeActivity(String.valueOf(a));
                }
                b = b.processInstanceIds(root);
                if (Boolean.TRUE.equals(params.get("initialVariables"))) {
                    b = b.initialSetOfVariables();
                }
                if (Boolean.TRUE.equals(params.get("skipCustomListeners"))) {
                    b = b.skipCustomListeners();
                }
                b.execute();
                return "restarted";
            }
            default -> throw new IllegalArgumentException("unknown batch type " + type);
        }
    }

    /** The migration plan of a batch, built once per batch configuration and cached (it is immutable). */
    @SuppressWarnings("unchecked")
    private org.operaton.bpm.engine.migration.MigrationPlan plan(Map<String, Object> params) {
        Map<String, Object> p = (Map<String, Object>) params.get("migrationPlan");
        if (p == null) {
            throw new IllegalArgumentException("params.migrationPlan is required");
        }
        String cacheKey = org.sangam.core.layer.CanonicalJson.write(TypedVariables.toJson(p));
        return plans.computeIfAbsent(cacheKey, k -> {
            MigrationPlanRequest req = DocumentStore.convert(TypedVariables.toJson(p), MigrationPlanRequest.class);
            MigrationPlanBuilder b = engine.getRuntimeService()
                    .createMigrationPlan(req.sourceDefinitionId(), req.targetDefinitionId());
            if (req.mapEqualActivities()) {
                b = b.mapEqualActivities();
            }
            if (req.instructions() != null) {
                for (MigrationInstruction i : req.instructions()) {
                    for (String s : i.sourceActivityIds()) {
                        for (String t : i.targetActivityIds()) {
                            MigrationInstructionBuilder ib = b.mapActivities(s, t);
                            b = i.updateEventTrigger() ? ib.updateEventTrigger() : ib;
                        }
                    }
                }
            }
            return b.build();
        });
    }

    // ---------------------------------------------------------------- resolution (tenant-scoped)

    private List<String> resolve(RequestContext ctx, String type, BatchFilter f, Map<String, Object> params) {
        BatchFilter filter = f == null ? new BatchFilter(null, null, null, null, null, null) : f;
        if (filter.processDefinitionKey() == null && filter.processDefinitionId() == null
                && (filter.processInstanceIds() == null || filter.processInstanceIds().isEmpty())
                && (filter.businessKeys() == null || filter.businessKeys().isEmpty())) {
            throw ApiProblem.badRequest("FILTER_REQUIRED",
                    "a batch needs processDefinitionKey, processDefinitionId, processInstanceIds or businessKeys");
        }
        int max = (int) Math.min(Integer.MAX_VALUE, props.getCountCap() + 1);
        Set<String> out = new LinkedHashSet<>();
        if ("RESTART".equals(type)) {
            List<String> bks = filter.businessKeys() == null || filter.businessKeys().isEmpty()
                    ? java.util.Collections.singletonList(null) : filter.businessKeys();
            for (String bk : bks) {
                HistoricProcessInstanceQuery q = engine.getHistoryService().createHistoricProcessInstanceQuery()
                        .tenantIdIn(ctx.tenantId()).finished();
                if (filter.processDefinitionId() != null) {
                    q.processDefinitionId(filter.processDefinitionId());
                }
                if (filter.processDefinitionKey() != null) {
                    q.processDefinitionKey(filter.processDefinitionKey());
                }
                if (filter.processInstanceIds() != null && !filter.processInstanceIds().isEmpty()) {
                    q.processInstanceIds(new java.util.HashSet<>(filter.processInstanceIds()));
                }
                if (bk != null) {
                    q.processInstanceBusinessKey(bk);
                }
                PortsErrors.call(() -> q.listPage(0, max)).forEach(h -> out.add(h.getId()));
            }
            return new ArrayList<>(out);
        }
        List<String> bks = filter.businessKeys() == null || filter.businessKeys().isEmpty()
                ? java.util.Collections.singletonList(null) : filter.businessKeys();
        for (String bk : bks) {
            ProcessInstanceQuery q = engine.getRuntimeService().createProcessInstanceQuery().tenantIdIn(ctx.tenantId());
            if (filter.processDefinitionId() != null) {
                q.processDefinitionId(filter.processDefinitionId());
            }
            if (filter.processDefinitionKey() != null) {
                q.processDefinitionKey(filter.processDefinitionKey());
            }
            if (filter.processInstanceIds() != null && !filter.processInstanceIds().isEmpty()) {
                q.processInstanceIds(new java.util.HashSet<>(filter.processInstanceIds()));
            }
            if (bk != null) {
                q.processInstanceBusinessKey(bk);
            }
            if (filter.activityId() != null) {
                q.activityIdIn(filter.activityId());
            }
            if (Boolean.TRUE.equals(filter.withIncidents()) || "RETRY".equals(type)) {
                q.withIncident();
            }
            PortsErrors.call(() -> q.listPage(0, max)).forEach(pi -> out.add(pi.getId()));
        }
        return new ArrayList<>(out);
    }

    // ---------------------------------------------------------------- mapping

    private static String type(String t) {
        String type = t == null ? "" : t.toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "type must be one of " + String.join(" | ", TYPES));
        }
        return type;
    }

    private static ProductBatchService.State portsState(String rest) {
        return switch (rest.toUpperCase(Locale.ROOT)) {
            case "AWAITING_APPROVAL" -> ProductBatchService.State.PENDING_APPROVAL;
            case "RUNNING" -> ProductBatchService.State.RUNNING;
            case "PAUSED" -> ProductBatchService.State.PAUSED;
            case "COMPLETED" -> ProductBatchService.State.COMPLETED;
            case "CANCELLED" -> ProductBatchService.State.CANCELLED;
            case "FAILED" -> ProductBatchService.State.COMPLETED_WITH_FAILURES;
            default -> throw ApiProblem.badRequest("VALIDATION_FAILED",
                    "state must be AWAITING_APPROVAL | RUNNING | PAUSED | COMPLETED | CANCELLED | FAILED");
        };
    }

    private static String restState(ProductBatchService.State s) {
        return switch (s) {
            case PENDING_APPROVAL -> "AWAITING_APPROVAL";
            case RUNNING -> "RUNNING";
            case PAUSED -> "PAUSED";
            case COMPLETED, COMPLETED_WITH_FAILURES -> "COMPLETED";
            case CANCELLED, REJECTED -> "CANCELLED";
        };
    }

    private static String itemState(String s) {
        return switch (s == null ? "" : s) {
            case "DONE" -> "SUCCEEDED";
            case "FAILED", "CANCELLED" -> "FAILED";
            default -> "PENDING";
        };
    }

    @SuppressWarnings("unchecked")
    private static Batch map(ProductBatchService.Batch b) {
        String type = b.type();
        if (type.startsWith(TYPE_PREFIX)) {
            type = type.substring(TYPE_PREFIX.length());
        } else if (type.startsWith("REST:")) {
            type = type.substring("REST:".length());
        }
        Map<String, Object> cfg = b.config() == null ? Map.of() : b.config();
        BatchFilter filter = cfg.get("filter") instanceof Map<?, ?> fm ? DocumentStore.convert(fm, BatchFilter.class) : null;
        Map<String, Object> params = cfg.get("params") instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Map.of();
        String reason = b.reason() != null ? b.reason() : (String) cfg.get("reason");
        return new Batch(b.id(), type, restState(b.state()), b.total(), b.done(), b.failed(), b.createdBy(),
                b.approvedBy(), reason, b.createdAt(), b.completedAt(), filter, params, b.fourEyesRequired());
    }

    // ================================================================== delegated to the default adapter

    @Override
    public Page<Incident> incidents(RequestContext ctx, IncidentFilter filter, Page.PageRequest page) {
        return defaults.incidents(ctx, filter, page);
    }

    @Override
    public Incident incident(RequestContext ctx, String id) {
        return defaults.incident(ctx, id);
    }

    @Override
    public Incident retryIncident(RequestContext ctx, String id, IncidentAction action) {
        return defaults.retryIncident(ctx, id, action);
    }

    @Override
    public Incident resolveIncident(RequestContext ctx, String id, IncidentAction action) {
        return defaults.resolveIncident(ctx, id, action);
    }

    @Override
    public Page<ReconciliationItem> reconciliation(RequestContext ctx, String state, String kind, Page.PageRequest page) {
        return defaults.reconciliation(ctx, state, kind, page);
    }

    @Override
    public ReconciliationItem reconciliationItem(RequestContext ctx, String id) {
        return defaults.reconciliationItem(ctx, id);
    }

    @Override
    public ReconciliationItem resolveReconciliation(RequestContext ctx, String id, ResolveRequest request) {
        return defaults.resolveReconciliation(ctx, id, request);
    }

    @Override
    public Page<OutboxDelivery> outbox(RequestContext ctx, String state, String destination, Page.PageRequest page) {
        return defaults.outbox(ctx, state, destination, page);
    }

    @Override
    public OutboxStats outboxStats(RequestContext ctx) {
        return defaults.outboxStats(ctx);
    }

    @Override
    public OutboxDelivery requeueOutbox(RequestContext ctx, String outboxId, OutboxRepairRequest request) {
        return defaults.requeueOutbox(ctx, outboxId, request);
    }

    @Override
    public OutboxDelivery skipOutbox(RequestContext ctx, String outboxId, OutboxRepairRequest request) {
        return defaults.skipOutbox(ctx, outboxId, request);
    }

    @Override
    public Page<Job> jobs(RequestContext ctx, JobFilter filter, Page.PageRequest page) {
        return defaults.jobs(ctx, filter, page);
    }

    @Override
    public Job setJobRetries(RequestContext ctx, String jobId, JobRetriesRequest request) {
        return defaults.setJobRetries(ctx, jobId, request);
    }

    @Override
    public Job executeJob(RequestContext ctx, String jobId, String reason) {
        return defaults.executeJob(ctx, jobId, reason);
    }

    @Override
    public Job setJobSuspension(RequestContext ctx, String jobId, JobSuspensionRequest request) {
        return defaults.setJobSuspension(ctx, jobId, request);
    }

    @Override
    public CleanupStatus cleanupStatus(RequestContext ctx) {
        return defaults.cleanupStatus(ctx);
    }

    @Override
    public CleanupStatus triggerCleanup(RequestContext ctx, String reason) {
        return defaults.triggerCleanup(ctx, reason);
    }

    @Override
    public void close() {
        try {
            defaults.close();
        } catch (Exception e) {
            // shutting down: nothing to recover
        }
    }
}
