// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.operaton.bpm.engine.ExternalTaskService;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstanceQuery;
import org.operaton.bpm.engine.repository.ProcessDefinitionQuery;
import org.operaton.bpm.engine.runtime.Execution;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.bpm.engine.runtime.MessageCorrelationResult;
import org.operaton.bpm.engine.runtime.ProcessInstanceModificationBuilder;
import org.operaton.bpm.engine.runtime.ProcessInstanceModificationInstantiationBuilder;
import org.operaton.bpm.engine.runtime.ProcessInstantiationBuilder;
import org.operaton.bpm.engine.runtime.SignalEventReceivedBuilder;
import org.sangam.core.api.LeaseTable;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Tombstones;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.TypedVariables;

/**
 * Process instances, definitions, messages and signals on the Operaton {@link RuntimeService}.
 * <ul>
 *   <li><b>Business cancel</b> (VI.5): every locked external task gets a CANCEL_REQUESTED tombstone, then the instance
 *       is deleted as externally terminated <em>with</em> custom listeners. Operaton has no separate cancel path: a
 *       model's compensation/cancel behaviour runs through its execution end listeners.</li>
 *   <li><b>Technical delete</b>: the same tombstones, plus an {@code INSTANCE_DELETED} event with the open operation
 *       ids; listeners and IO mappings are skipped.</li>
 *   <li><b>Resubmit</b> (R43): case version + 1, payload hash and diff hash recorded as {@code CASE_RESUBMITTED}.</li>
 * </ul>
 */
public class OperatonProcessAdapter implements ProcessPort {

    private static final List<String> VERSION_EVENTS = List.of("CASE_SUBMITTED", "CASE_RETURNED", "CASE_RESUBMITTED");

    private final RuntimeService runtime;
    private final RepositoryService repository;
    private final HistoryService history;
    private final ExternalTaskService externalTasks;
    private final TxSupport tx;
    private final AuditPort audit;
    private final RestProperties props;
    private final Tombstones tombstones;
    private final AtomicBoolean leaseTableReady = new AtomicBoolean();
    private final OperatonOperationIds operationIds;

    public OperatonProcessAdapter(RuntimeService runtime, RepositoryService repository, HistoryService history,
                                  ExternalTaskService externalTasks, TxSupport tx, AuditPort audit,
                                  RestProperties props, OperatonOperationIds operationIds) {
        this.operationIds = operationIds;
        this.runtime = runtime;
        this.repository = repository;
        this.history = history;
        this.externalTasks = externalTasks;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
        AuditChain chain = new AuditChain(tx.dialect());
        this.tombstones = new Tombstones(tx.dialect(), new ReconciliationQueue(tx.dialect(), chain), chain);
    }

    // ------------------------------------------------------------------ start / read

    @Override
    public ProcessInstance start(RequestContext ctx, StartProcessRequest r) {
        if (r == null || r.key() == null || r.key().isBlank()) {
            throw ApiProblem.badRequest("KEY_REQUIRED", "the process definition key is required");
        }
        Map<String, Object> vars = TypedVariables.fromJson(r.variables());
        vars.put(OperatonTaskAdapter.INITIATOR, ctx.principalId());
        // Pack contract (sangam-packs README §4): initiator person and seat for SoD (K5).
        vars.putIfAbsent("initiator", ctx.principalId());
        vars.putIfAbsent("initiatorPerson", ctx.principalId());
        String seat = ctx.actingForSeatId() != null ? ctx.actingForSeatId() : ctx.seatId();
        if (seat != null) {
            vars.putIfAbsent("initiatorSeat", seat);
            vars.putIfAbsent("actingSeat", seat);
        }
        vars.put(OperatonTaskAdapter.CASE_VERSION, 1L);
        if (r.packageHash() != null) {
            vars.put("sangamPackageHash", r.packageHash());
        }
        if (r.formVersion() != null) {
            vars.put(OperatonTaskAdapter.FORM_VERSION, r.formVersion());
        }
        return tx.inTx(() -> {
            ProcessInstantiationBuilder b;
            if (r.version() != null) {
                ProcessDefinitionQuery dq = repository.createProcessDefinitionQuery().processDefinitionKey(r.key())
                        .processDefinitionVersion(r.version());
                if (ctx.tenantId() != null) {
                    dq.tenantIdIn(ctx.tenantId()).includeProcessDefinitionsWithoutTenantId();
                }
                List<org.operaton.bpm.engine.repository.ProcessDefinition> defs = OperatonSupport.call(dq::list);
                if (defs.isEmpty()) {
                    throw ApiProblem.notFound("process definition", r.key() + ":" + r.version());
                }
                org.operaton.bpm.engine.repository.ProcessDefinition def = defs.stream()
                        .filter(d -> Objects.equals(d.getTenantId(), ctx.tenantId())).findFirst().orElse(defs.get(0));
                b = runtime.createProcessInstanceById(def.getId());
            } else {
                b = runtime.createProcessInstanceByKey(r.key());
                if (ctx.tenantId() != null && hasTenantDefinition(ctx.tenantId(), r.key())) {
                    b = b.processDefinitionTenantId(ctx.tenantId());
                } else {
                    b = b.processDefinitionWithoutTenantId();
                }
            }
            if (r.businessKey() != null) {
                b = b.businessKey(r.businessKey());
            }
            if (r.startBeforeActivityIds() != null) {
                for (String a : r.startBeforeActivityIds()) {
                    b = b.startBeforeActivity(a);
                }
            }
            b = b.setVariables(vars);
            ProcessInstantiationBuilder fb = b;
            org.operaton.bpm.engine.runtime.ProcessInstance pi = OperatonSupport.call(fb::execute);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("process_definition_id", pi.getProcessDefinitionId());
            p.put("business_key", r.businessKey());
            p.put("package_hash", r.packageHash());
            p.put("actor", ctx.actor());
            p.values().removeIf(Objects::isNull);
            audit.record(ctx, pi.getId(), "INSTANCE_STARTED", p);
            Map<String, Object> s = new LinkedHashMap<>(p);
            s.put("version", 1L);
            s.put("payload_hash", PayloadHash.hash(vars));
            s.put("form_version", r.formVersion());
            s.values().removeIf(Objects::isNull);
            audit.record(ctx, pi.getId(), "CASE_SUBMITTED", s);
            return get(ctx, pi.getId());
        });
    }

    private boolean hasTenantDefinition(String tenant, String key) {
        return OperatonSupport.call(() -> repository.createProcessDefinitionQuery().processDefinitionKey(key)
                .tenantIdIn(tenant).count()) > 0;
    }

    @Override
    public Page<ProcessInstance> list(RequestContext ctx, ProcessFilter f, Page.PageRequest page) {
        HistoricProcessInstanceQuery q = history.createHistoricProcessInstanceQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        if (f != null) {
            if (f.key() != null) {
                q.processDefinitionKey(f.key());
            }
            if (f.businessKey() != null) {
                q.processInstanceBusinessKey(f.businessKey());
            }
            if (f.startedAfter() != null) {
                q.startedAfter(OperatonSupport.date(f.startedAfter()));
            }
            if (f.startedBefore() != null) {
                q.startedBefore(OperatonSupport.date(f.startedBefore()));
            }
            if (Boolean.TRUE.equals(f.withIncidents())) {
                q.withIncidents();
            }
            if (f.superProcessInstanceId() != null) {
                q.superProcessInstanceId(f.superProcessInstanceId());
            }
            if (f.state() != null) {
                switch (f.state().toUpperCase(java.util.Locale.ROOT)) {
                    case "ACTIVE" -> q.active();
                    case "SUSPENDED" -> q.suspended();
                    case "COMPLETED" -> q.completed();
                    case "CANCELLED", "DELETED" -> q.externallyTerminated();
                    default -> throw ApiProblem.badRequest("INVALID_STATE", "unknown state " + f.state());
                }
            }
        }
        q.orderByProcessInstanceStartTime().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), h -> fromHistory(h, List.of(), 0));
    }

    @Override
    public ProcessInstance get(RequestContext ctx, String id) {
        org.operaton.bpm.engine.runtime.ProcessInstance pi = OperatonSupport.call(() ->
                runtime.createProcessInstanceQuery().processInstanceId(id).singleResult());
        HistoricProcessInstance h = OperatonSupport.call(() ->
                history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult());
        if (pi == null && h == null) {
            throw ApiProblem.notFound("process instance", id);
        }
        String tenant = pi != null ? pi.getTenantId() : h.getTenantId();
        OperatonSupport.checkTenant(ctx, tenant, "process instance", id);
        if (pi != null) {
            List<String> active = OperatonSupport.call(() -> runtime.getActiveActivityIds(id));
            Map<String, Object> vars = OperatonSupport.call(() -> runtime.getVariables(id));
            long version = OperatonTaskAdapter.caseVersion(vars);
            Object status = vars.get("sangamBusinessStatus");
            String defId = pi.getProcessDefinitionId();
            org.operaton.bpm.engine.repository.ProcessDefinition def = OperatonSupport.call(() ->
                    repository.getProcessDefinition(defId));
            return new ProcessInstance(pi.getId(), defId, def.getKey(), def.getVersion(), pi.getBusinessKey(),
                    pi.getTenantId(), pi.isSuspended() ? "SUSPENDED" : "ACTIVE", false,
                    h == null ? null : OperatonSupport.instant(h.getStartTime()), null, active, version,
                    h == null ? stringOrNull(vars.get(OperatonTaskAdapter.INITIATOR)) : h.getStartUserId(),
                    status == null ? null : String.valueOf(status),
                    h == null ? null : h.getSuperProcessInstanceId(), pi.getRootProcessInstanceId());
        }
        return fromHistory(h, List.of(), 0);
    }

    private static ProcessInstance fromHistory(HistoricProcessInstance h, List<String> active, long version) {
        String state = switch (h.getState() == null ? "" : h.getState()) {
            case "ACTIVE" -> "ACTIVE";
            case "SUSPENDED" -> "SUSPENDED";
            case "COMPLETED" -> "COMPLETED";
            case "EXTERNALLY_TERMINATED", "INTERNALLY_TERMINATED" -> "CANCELLED";
            default -> h.getEndTime() == null ? "ACTIVE" : (h.getDeleteReason() != null ? "CANCELLED" : "COMPLETED");
        };
        boolean ended = h.getEndTime() != null;
        return new ProcessInstance(h.getId(), h.getProcessDefinitionId(), h.getProcessDefinitionKey(),
                h.getProcessDefinitionVersion(), h.getBusinessKey(), h.getTenantId(), state, ended,
                OperatonSupport.instant(h.getStartTime()), OperatonSupport.instant(h.getEndTime()), active, version,
                h.getStartUserId(), null, h.getSuperProcessInstanceId(), h.getRootProcessInstanceId());
    }

    @Override
    public ActivityInstance activityTree(RequestContext ctx, String id) {
        requireRuntime(ctx, id);
        org.operaton.bpm.engine.runtime.ActivityInstance tree = OperatonSupport.call(() -> runtime.getActivityInstance(id));
        if (tree == null) {
            throw ApiProblem.notFound("process instance", id);
        }
        return mapTree(tree);
    }

    private static ActivityInstance mapTree(org.operaton.bpm.engine.runtime.ActivityInstance a) {
        List<ActivityInstance> children = new ArrayList<>();
        for (org.operaton.bpm.engine.runtime.ActivityInstance c : a.getChildActivityInstances()) {
            children.add(mapTree(c));
        }
        return new ActivityInstance(a.getId(), a.getActivityId(), a.getActivityName(), a.getActivityType(),
                a.getParentActivityInstanceId(), List.of(a.getExecutionIds()), children);
    }

    @Override
    public Map<String, Object> variables(RequestContext ctx, String id) {
        org.operaton.bpm.engine.runtime.ProcessInstance pi = OperatonSupport.call(() ->
                runtime.createProcessInstanceQuery().processInstanceId(id).singleResult());
        if (pi == null) {
            HistoricProcessInstance h = OperatonSupport.call(() ->
                    history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult());
            if (h == null) {
                throw ApiProblem.notFound("process instance", id);
            }
            OperatonSupport.checkTenant(ctx, h.getTenantId(), "process instance", id);
            Map<String, Object> out = new LinkedHashMap<>();
            OperatonSupport.call(() -> history.createHistoricVariableInstanceQuery().processInstanceId(id).list())
                    .forEach(v -> out.put(v.getName(), v.getValue()));
            return TypedVariables.toJson(out);
        }
        OperatonSupport.checkTenant(ctx, pi.getTenantId(), "process instance", id);
        return TypedVariables.toJson(OperatonSupport.call(() -> runtime.getVariables(id)));
    }

    @Override
    public Map<String, Object> variables(RequestContext ctx, String id, String activityInstanceId) {
        if (activityInstanceId == null || activityInstanceId.isBlank()) {
            return variables(ctx, id);
        }
        org.operaton.bpm.engine.runtime.ProcessInstance pi = OperatonSupport.call(() ->
                runtime.createProcessInstanceQuery().processInstanceId(id).singleResult());
        Map<String, Object> out = new LinkedHashMap<>();
        if (pi == null) {
            HistoricProcessInstance h = OperatonSupport.call(() ->
                    history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult());
            if (h == null) {
                throw ApiProblem.notFound("process instance", id);
            }
            OperatonSupport.checkTenant(ctx, h.getTenantId(), "process instance", id);
            OperatonSupport.call(() -> history.createHistoricVariableInstanceQuery().processInstanceId(id)
                    .activityInstanceIdIn(activityInstanceId).list()).forEach(v -> out.put(v.getName(), v.getValue()));
            return TypedVariables.toJson(out);
        }
        OperatonSupport.checkTenant(ctx, pi.getTenantId(), "process instance", id);
        // processInstanceIdIn bounds the scope to this instance: another instance's activity id matches nothing
        OperatonSupport.call(() -> runtime.createVariableInstanceQuery().processInstanceIdIn(id)
                .activityInstanceIdIn(activityInstanceId).list()).forEach(v -> out.put(v.getName(), v.getValue()));
        return TypedVariables.toJson(out);
    }

    @Override
    public Map<String, Object> setVariables(RequestContext ctx, String id, Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            throw ApiProblem.badRequest("VARIABLES_REQUIRED", "at least one variable is required");
        }
        if (variables.keySet().stream().anyMatch(k -> k.startsWith("sangam"))) {
            throw ApiProblem.unprocessable("RESERVED_VARIABLE", "variables starting with 'sangam' are managed by the platform");
        }
        return tx.inTx(() -> {
            requireRuntime(ctx, id);
            Map<String, Object> vars = TypedVariables.fromJson(variables);
            OperatonSupport.run(() -> runtime.setVariables(id, vars));
            audit.record(ctx, id, "VARIABLES_UPDATED", Map.of("names", new ArrayList<>(new java.util.TreeSet<>(vars.keySet())),
                    "actor", ctx.actor()));
            return TypedVariables.toJson(OperatonSupport.call(() -> runtime.getVariables(id)));
        });
    }

    // ------------------------------------------------------------------ cancel / delete (VI.5)

    @Override
    public TerminationResult cancel(RequestContext ctx, String id, TerminateRequest r) {
        return terminate(ctx, id, r, false);
    }

    @Override
    public TerminationResult delete(RequestContext ctx, String id, TerminateRequest r) {
        return terminate(ctx, id, r, true);
    }

    private TerminationResult terminate(RequestContext ctx, String id, TerminateRequest r, boolean technical) {
        if (r == null || r.reason() == null || r.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "A reason is required to " + (technical ? "delete" : "cancel")
                    + " an instance");
        }
        org.sangam.core.engine.SangamEngineAdapter engine = operationIds.engine();
        if (engine != null) {
            // VI.5: the engine adapter's terminate is the one delete path (its lease table, its T35 operation ids,
            // its engine clock for the remaining lease, L2 on the root, INSTANCE_DELETED with the open operation ids).
            return tx.inTx(() -> {
                org.operaton.bpm.engine.runtime.ProcessInstance pi = requireRuntime(ctx, id);
                org.sangam.core.engine.SangamEngineAdapter.Termination t =
                        engine.terminateInstance(id, ctx.principalId(), r.reason(), technical);
                List<String> ops = t.openOperationIds().stream().map(UUID::toString).toList();
                if (!technical) {
                    recordCancelled(ctx, id, r, pi, ops);
                }
                return new TerminationResult(id, technical ? "DELETED" : "CANCELLED", ops, t.cancelRequested(),
                        t.cancelRequested());
            });
        }
        ensureLeaseTable();
        return tx.inTx(() -> {
            org.operaton.bpm.engine.runtime.ProcessInstance pi = requireRuntime(ctx, id);
            Connection c = tx.connection();
            List<UUID> openOps = new ArrayList<>();
            int cancelRequested = 0;
            Instant now = Instant.now();
            List<ExternalTask> open = OperatonSupport.call(() ->
                    externalTasks.createExternalTaskQuery().processInstanceId(id).list());
            for (ExternalTask t : open) {
                Instant lockEnd = OperatonSupport.instant(t.getLockExpirationTime());
                boolean locked = t.getWorkerId() != null && lockEnd != null && lockEnd.isAfter(now);
                if (!locked) {
                    continue;
                }
                LeaseTable.Row lease = LeaseTable.lock(c, t.getId());
                long lockVersion = lease == null ? 0 : lease.lockVersion();
                UUID op = operationIds.of(t);
                tombstones.write(c, ctx.tenantId(), t.getId(), op, id, lockVersion, Duration.between(now, lockEnd));
                openOps.add(op);
                cancelRequested++;
            }
            String reason = (technical ? "DELETED: " : "CANCELLED: ") + r.reason();
            if (technical) {
                OperatonSupport.run(() -> runtime.deleteProcessInstance(id, reason, true, true, true));
                tombstones.recordDeletion(c, ctx.tenantId(), id, ctx.principalId(), r.reason(), openOps);
            } else {
                OperatonSupport.run(() -> runtime.deleteProcessInstance(id, reason, false, true, false));
                recordCancelled(ctx, id, r, pi, openOps.stream().map(UUID::toString).toList());
            }
            return new TerminationResult(id, technical ? "DELETED" : "CANCELLED",
                    openOps.stream().map(UUID::toString).toList(), cancelRequested, cancelRequested);
        });
    }

    private void recordCancelled(RequestContext ctx, String id, TerminateRequest r,
                                 org.operaton.bpm.engine.runtime.ProcessInstance pi, List<String> openOps) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("actor", ctx.actor());
        p.put("reason", r.reason());
        p.put("reason_code", r.reasonCode());
        p.put("business_key", pi.getBusinessKey());
        p.put("openOperationIds", openOps);
        p.values().removeIf(Objects::isNull);
        audit.record(ctx, id, "INSTANCE_CANCELLED", p);
    }

    private void ensureLeaseTable() {
        if (leaseTableReady.get()) {
            return;
        }
        tx.inNewTx(() -> {
            LeaseTable.createIfMissing(tx.connection());
            return null;
        });
        leaseTableReady.set(true);
    }

    // ------------------------------------------------------------------ resubmit / versions (R43)

    @Override
    public CaseVersion resubmit(RequestContext ctx, String id, ResubmitRequest r) {
        if (r == null || r.variables() == null) {
            throw ApiProblem.badRequest("VARIABLES_REQUIRED", "the resubmitted payload is required");
        }
        return tx.inTx(() -> {
            requireRuntime(ctx, id);
            Map<String, Object> before = OperatonSupport.call(() -> runtime.getVariables(id));
            long current = OperatonTaskAdapter.caseVersion(before);
            if (r.expectedVersion() != null && r.expectedVersion() != current) {
                throw ApiProblem.conflict("FORM_VERSION_STALE", "This case changed since you opened it (version "
                        + r.expectedVersion() + " to " + current + ")").with("currentVersion", current);
            }
            Map<String, Object> incoming = TypedVariables.fromJson(r.variables());
            incoming.keySet().removeIf(k -> k.startsWith("sangam"));
            Map<String, Object> after = new HashMap<>(before);
            after.putAll(incoming);
            List<String> changed = PayloadHash.changedFields(before, after);
            String diffHash = PayloadHash.diffHash(before, after, changed);
            String hash = PayloadHash.hash(after);
            long next = current + 1;
            Map<String, Object> set = new HashMap<>(incoming);
            set.put(OperatonTaskAdapter.CASE_VERSION, next);
            OperatonSupport.run(() -> runtime.setVariables(id, set));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("version", next);
            p.put("previous_version", current);
            p.put("payload_hash", hash);
            p.put("diff_hash", diffHash);
            p.put("changed_fields", changed);
            // Material fields only, so the field diff (S09, CX-2) can show from/to; non-material values stay out of audit.
            Map<String, Object> materialBefore = PayloadHash.material(before);
            Map<String, Object> materialAfter = PayloadHash.material(after);
            Map<String, Object> oldValues = new LinkedHashMap<>();
            Map<String, Object> newValues = new LinkedHashMap<>();
            for (String f : changed) {
                if (materialBefore.containsKey(f) || materialAfter.containsKey(f)) {
                    oldValues.put(f, PayloadHash.jsonSafe(materialBefore.get(f)));
                    newValues.put(f, PayloadHash.jsonSafe(materialAfter.get(f)));
                }
            }
            p.put("old_values", oldValues);
            p.put("new_values", newValues);
            p.put("reason", r.reason());
            p.put("actor", ctx.actor());
            p.values().removeIf(Objects::isNull);
            audit.record(ctx, id, "CASE_RESUBMITTED", p);
            return new CaseVersion(next, "RESUBMITTED", hash, diffHash, changed, ctx.principalId(), ctx.seatId(),
                    r.reason(), Instant.now());
        });
    }

    @Override
    public List<CaseVersion> versions(RequestContext ctx, String id) {
        get(ctx, id);
        List<CaseVersion> out = new ArrayList<>();
        int pageNo = 0;
        while (true) {
            Page<AuditPort.AuditEntry> page = audit.search(ctx, new AuditPort.AuditFilter(id, null, null, null, null),
                    new Page.PageRequest(pageNo, Page.PageRequest.MAX_SIZE));
            for (AuditPort.AuditEntry e : page.items()) {
                if (!VERSION_EVENTS.contains(e.type())) {
                    continue;
                }
                Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
                long version = toLong(p.get("version"), 1);
                String kind = e.type().substring("CASE_".length());
                out.add(new CaseVersion(version, kind, stringOrNull(p.get("payload_hash")),
                        stringOrNull(p.get("diff_hash")), toStringList(p.get("changed_fields")), e.actor(),
                        e.seatId(), stringOrNull(p.get("reason")), e.occurredAt()));
            }
            if (page.items().size() < Page.PageRequest.MAX_SIZE) {
                break;
            }
            pageNo++;
        }
        out.sort(java.util.Comparator.comparing(CaseVersion::at, java.util.Comparator.nullsFirst(
                java.util.Comparator.naturalOrder())));
        return out;
    }

    // ------------------------------------------------------------------ modify / trigger

    @Override
    public ProcessInstance modify(RequestContext ctx, String id, ModifyRequest r) {
        if (r == null || r.reason() == null || r.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "A reason is required to modify an instance (O03)");
        }
        if (r.instructions() == null || r.instructions().isEmpty()) {
            throw ApiProblem.badRequest("INSTRUCTIONS_REQUIRED", "at least one modification instruction is required");
        }
        return tx.inTx(() -> {
            requireRuntime(ctx, id);
            Map<String, List<ModificationInstruction>> byInstance = new LinkedHashMap<>();
            byInstance.put(id, new ArrayList<>());
            for (ModificationInstruction i : r.instructions()) {
                String target = i.targetProcessInstanceId() == null ? id : i.targetProcessInstanceId();
                if (!target.equals(id)) {
                    requireRuntime(ctx, target);   // FLOWABLE-INTO-OPERATON FC-17: cross-instance moves stay inside the tenant
                }
                byInstance.computeIfAbsent(target, k -> new ArrayList<>()).add(i);
            }
            for (Map.Entry<String, List<ModificationInstruction>> e : byInstance.entrySet()) {
                if (e.getValue().isEmpty()) {
                    continue;
                }
                ProcessInstanceModificationBuilder b = runtime.createProcessInstanceModification(e.getKey());
                for (ModificationInstruction i : e.getValue()) {
                    b = apply(b, i);
                }
                b = b.setAnnotation(r.reason());
                ProcessInstanceModificationBuilder fb = b;
                OperatonSupport.run(() -> fb.execute(r.skipCustomListeners(), r.skipIoMappings()));
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("reason", r.reason());
                p.put("actor", ctx.actor());
                p.put("instructions", e.getValue().stream().map(OperatonProcessAdapter::describe).toList());
                p.put("cross_instance", !e.getKey().equals(id));
                audit.record(ctx, e.getKey(), "INSTANCE_MODIFIED", p);
            }
            return get(ctx, id);
        });
    }

    private static ProcessInstanceModificationBuilder apply(ProcessInstanceModificationBuilder b, ModificationInstruction i) {
        String type = i.type() == null ? "" : i.type();
        Map<String, Object> vars = TypedVariables.fromJson(i.variables());
        switch (type) {
            case "startBeforeActivity", "startAfterActivity", "startTransition" -> {
                String target = "startTransition".equals(type) ? i.transitionInstanceId() : i.activityId();
                if (target == null) {
                    throw ApiProblem.badRequest("INVALID_INSTRUCTION", type + " needs an activityId/transition id");
                }
                ProcessInstanceModificationInstantiationBuilder ib = switch (type) {
                    case "startBeforeActivity" -> i.activityInstanceId() == null ? b.startBeforeActivity(target)
                            : b.startBeforeActivity(target, i.activityInstanceId());
                    case "startAfterActivity" -> i.activityInstanceId() == null ? b.startAfterActivity(target)
                            : b.startAfterActivity(target, i.activityInstanceId());
                    default -> b.startTransition(target);
                };
                if (!vars.isEmpty()) {
                    ib = ib.setVariables(vars);
                }
                return ib;
            }
            case "cancelActivity" -> {
                require(i.activityId(), type);
                return b.cancelAllForActivity(i.activityId());
            }
            case "cancelActivityInstance" -> {
                require(i.activityInstanceId(), type);
                return b.cancelActivityInstance(i.activityInstanceId());
            }
            case "cancelTransitionInstance" -> {
                require(i.transitionInstanceId(), type);
                return b.cancelTransitionInstance(i.transitionInstanceId());
            }
            default -> throw ApiProblem.badRequest("INVALID_INSTRUCTION", "unknown instruction type '" + type + "'");
        }
    }

    private static void require(String value, String type) {
        if (value == null || value.isBlank()) {
            throw ApiProblem.badRequest("INVALID_INSTRUCTION", type + " is missing its target id");
        }
    }

    private static Map<String, Object> describe(ModificationInstruction i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", i.type());
        m.put("activityId", i.activityId());
        m.put("activityInstanceId", i.activityInstanceId());
        m.put("transitionInstanceId", i.transitionInstanceId());
        m.values().removeIf(Objects::isNull);
        return m;
    }

    @Override
    public ProcessInstance trigger(RequestContext ctx, String id, TriggerRequest r) {
        if (r == null || (r.executionId() == null && r.activityId() == null)) {
            throw ApiProblem.badRequest("TRIGGER_TARGET_REQUIRED", "executionId or activityId is required");
        }
        return tx.inTx(() -> {
            requireRuntime(ctx, id);
            String executionId = r.executionId();
            if (executionId == null) {
                List<Execution> ex = OperatonSupport.call(() ->
                        runtime.createExecutionQuery().processInstanceId(id).activityId(r.activityId()).list());
                if (ex.isEmpty()) {
                    throw ApiProblem.notFound("waiting execution at activity", r.activityId());
                }
                if (ex.size() > 1) {
                    throw ApiProblem.conflict("AMBIGUOUS_EXECUTION", "several executions wait at " + r.activityId()
                            + "; pass executionId");
                }
                executionId = ex.get(0).getId();
            } else {
                String eid = executionId;
                Execution e = OperatonSupport.call(() -> runtime.createExecutionQuery().executionId(eid).singleResult());
                if (e == null || !id.equals(e.getProcessInstanceId())) {
                    throw ApiProblem.notFound("execution", eid);
                }
            }
            String target = executionId;
            OperatonSupport.run(() -> runtime.signal(target, TypedVariables.fromJson(r.variables())));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("execution_id", target);
            p.put("activity_id", r.activityId());
            p.put("reason", r.reason());
            p.put("actor", ctx.actor());
            p.values().removeIf(Objects::isNull);
            audit.record(ctx, id, "INSTANCE_TRIGGERED", p);
            return get(ctx, id);
        });
    }

    // ------------------------------------------------------------------ definitions

    @Override
    public Page<ProcessDefinition> definitions(RequestContext ctx, DefinitionFilter f, Page.PageRequest page) {
        ProcessDefinitionQuery q = repository.createProcessDefinitionQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId()).includeProcessDefinitionsWithoutTenantId();
        }
        if (f != null) {
            if (f.key() != null) {
                q.processDefinitionKey(f.key());
            }
            if (Boolean.TRUE.equals(f.latest())) {
                q.latestVersion();
            }
            if (f.deploymentId() != null) {
                q.deploymentId(f.deploymentId());
            }
        }
        q.orderByProcessDefinitionKey().asc().orderByProcessDefinitionVersion().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), OperatonProcessAdapter::mapDefinition);
    }

    @Override
    public ProcessDefinition definition(RequestContext ctx, String definitionId) {
        return mapDefinition(requireDefinition(ctx, definitionId));
    }

    @Override
    public String definitionXml(RequestContext ctx, String definitionId) {
        requireDefinition(ctx, definitionId);
        try (InputStream in = OperatonSupport.call(() -> repository.getProcessModel(definitionId))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiProblem(500, "RESOURCE_READ_FAILED", e.getMessage());
        }
    }

    private org.operaton.bpm.engine.repository.ProcessDefinition requireDefinition(RequestContext ctx, String id) {
        org.operaton.bpm.engine.repository.ProcessDefinition d = OperatonSupport.call(() ->
                repository.createProcessDefinitionQuery().processDefinitionId(id).singleResult());
        if (d == null) {
            throw ApiProblem.notFound("process definition", id);
        }
        if (d.getTenantId() != null) {
            OperatonSupport.checkTenant(ctx, d.getTenantId(), "process definition", id);
        }
        return d;
    }

    private static ProcessDefinition mapDefinition(org.operaton.bpm.engine.repository.ProcessDefinition d) {
        return new ProcessDefinition(d.getId(), d.getKey(), d.getName(), d.getVersion(), d.getDeploymentId(),
                d.getTenantId(), d.getVersionTag(), d.isSuspended(), d.getResourceName(), d.isStartableInTasklist());
    }

    // ------------------------------------------------------------------ messages / signals

    @Override
    public MessageResult correlate(RequestContext ctx, MessageRequest r) {
        if (r == null || r.messageName() == null || r.messageName().isBlank()) {
            throw ApiProblem.badRequest("MESSAGE_NAME_REQUIRED", "messageName is required");
        }
        return tx.inTx(() -> {
            if (r.processInstanceId() != null) {
                requireRuntime(ctx, r.processInstanceId());
            }
            MessageCorrelationBuilder b = runtime.createMessageCorrelation(r.messageName());
            if (ctx.tenantId() != null) {
                b = b.tenantId(ctx.tenantId());
            }
            if (r.businessKey() != null) {
                b = b.processInstanceBusinessKey(r.businessKey());
            }
            if (r.processInstanceId() != null) {
                b = b.processInstanceId(r.processInstanceId());
            }
            if (r.correlationKeys() != null) {
                for (Map.Entry<String, Object> e : TypedVariables.fromJson(r.correlationKeys()).entrySet()) {
                    b = b.processInstanceVariableEquals(e.getKey(), e.getValue());
                }
            }
            Map<String, Object> vars = TypedVariables.fromJson(r.variables());
            if (!vars.isEmpty()) {
                b = b.setVariables(vars);
            }
            MessageCorrelationBuilder fb = b;
            List<MessageCorrelationResult> results = r.all()
                    ? OperatonSupport.call(fb::correlateAllWithResult)
                    : List.of(OperatonSupport.call(fb::correlateWithResult));
            List<String> instances = new ArrayList<>();
            List<String> executions = new ArrayList<>();
            for (MessageCorrelationResult res : results) {
                String pid = res.getProcessInstance() != null ? res.getProcessInstance().getId()
                        : res.getExecution() != null ? res.getExecution().getProcessInstanceId() : null;
                if (pid != null && !instances.contains(pid)) {
                    instances.add(pid);
                }
                if (res.getExecution() != null) {
                    executions.add(res.getExecution().getId());
                }
            }
            for (String pid : instances) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("message_name", r.messageName());
                p.put("business_key", r.businessKey());
                p.put("variables", new ArrayList<>(new java.util.TreeSet<>(vars.keySet())));
                p.put("actor", ctx.actor());
                p.values().removeIf(Objects::isNull);
                audit.record(ctx, pid, "MESSAGE_CORRELATED", p);
            }
            return new MessageResult(r.messageName(), instances, executions);
        });
    }

    @Override
    public void signal(RequestContext ctx, SignalRequest r) {
        if (r == null || r.name() == null || r.name().isBlank()) {
            throw ApiProblem.badRequest("SIGNAL_NAME_REQUIRED", "name is required");
        }
        tx.runInTx(() -> {
            SignalEventReceivedBuilder b = runtime.createSignalEvent(r.name());
            if (r.executionId() != null) {
                Execution e = OperatonSupport.call(() ->
                        runtime.createExecutionQuery().executionId(r.executionId()).singleResult());
                if (e == null) {
                    throw ApiProblem.notFound("execution", r.executionId());
                }
                requireRuntime(ctx, e.getProcessInstanceId());
                b = b.executionId(r.executionId());
            } else if (ctx.tenantId() != null) {
                b = b.tenantId(ctx.tenantId());
            }
            Map<String, Object> vars = TypedVariables.fromJson(r.variables());
            if (!vars.isEmpty()) {
                b = b.setVariables(vars);
            }
            SignalEventReceivedBuilder fb = b;
            OperatonSupport.run(fb::send);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("signal", r.name());
            p.put("execution_id", r.executionId());
            p.put("actor", ctx.actor());
            p.values().removeIf(Objects::isNull);
            audit.record(ctx, "sec:" + ctx.tenantId(), "SIGNAL_THROWN", p);
        });
    }

    // ------------------------------------------------------------------ helpers

    private org.operaton.bpm.engine.runtime.ProcessInstance requireRuntime(RequestContext ctx, String id) {
        org.operaton.bpm.engine.runtime.ProcessInstance pi = OperatonSupport.call(() ->
                runtime.createProcessInstanceQuery().processInstanceId(id).singleResult());
        if (pi == null) {
            boolean historic = OperatonSupport.call(() ->
                    history.createHistoricProcessInstanceQuery().processInstanceId(id).count()) > 0;
            if (historic) {
                throw ApiProblem.gone("INSTANCE_ENDED", "Process instance " + id + " has already ended");
            }
            throw ApiProblem.notFound("process instance", id);
        }
        OperatonSupport.checkTenant(ctx, pi.getTenantId(), "process instance", id);
        return pi;
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long toLong(Object o, long dflt) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o != null) {
            try {
                return Long.parseLong(String.valueOf(o));
            } catch (NumberFormatException ignored) {
                return dflt;
            }
        }
        return dflt;
    }

    private static List<String> toStringList(Object o) {
        if (o instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
