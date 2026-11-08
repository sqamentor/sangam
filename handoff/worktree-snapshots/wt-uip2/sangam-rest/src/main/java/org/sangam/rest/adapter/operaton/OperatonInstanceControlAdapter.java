// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.operaton.bpm.engine.ExternalTaskService;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.operaton.bpm.engine.history.HistoricExternalTaskLog;
import org.operaton.bpm.engine.history.HistoricExternalTaskLogQuery;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.HistoryPort;
import org.sangam.rest.port.InstanceControlPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Default {@link InstanceControlPort} on the Operaton runtime and repository API: consequence previews (O03, O08),
 * suspension with optional deadline re-dating (FLOWABLE-INTO-OPERATON D-32), cross-boundary moves (FLOWABLE-INTO-OPERATON FC-17) and the external-task log.
 */
public class OperatonInstanceControlAdapter implements InstanceControlPort {

    static final String SUSPENDED_AT = "sangamSuspendedAt";
    /** R49: a modification above this many instructions needs a batch with four-eyes approval. */
    static final int MODIFY_LIMIT = 50;

    private final RuntimeService runtime;
    private final RepositoryService repository;
    private final HistoryService history;
    private final ExternalTaskService externalTasks;
    private final TaskService tasks;
    private final ProcessPort processes;
    private final TxSupport tx;
    private final AuditPort audit;
    private final RestProperties props;
    private final OperatonOperationIds operationIds;

    public OperatonInstanceControlAdapter(RuntimeService runtime, RepositoryService repository, HistoryService history,
                                          ExternalTaskService externalTasks, TaskService tasks, ProcessPort processes,
                                          TxSupport tx, AuditPort audit, RestProperties props,
                                          OperatonOperationIds operationIds) {
        this.operationIds = operationIds;
        this.runtime = runtime;
        this.repository = repository;
        this.history = history;
        this.externalTasks = externalTasks;
        this.tasks = tasks;
        this.processes = processes;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
    }

    // ------------------------------------------------------------------ previews

    @Override
    public ConsequencePreview cancelPreview(RequestContext ctx, String processInstanceId, String mode) {
        ProcessPort.ProcessInstance pi = processes.get(ctx, processInstanceId);
        if (pi.ended()) {
            return new ConsequencePreview(List.of("The instance has already ended; nothing will change"), List.of(),
                    List.of(), 0, true, "INSTANCE_ENDED", false, 0L, false);
        }
        List<ExternalTask> open = OperatonSupport.call(() ->
                externalTasks.createExternalTaskQuery().processInstanceId(processInstanceId).list());
        long now = System.currentTimeMillis();
        long locked = open.stream().filter(t -> t.getLockExpirationTime() != null
                && t.getLockExpirationTime().getTime() > now).count();
        long offered = open.size() - locked;
        List<String> ops = open.stream().map(t -> operationIds.of(t).toString()).toList();
        long openTasks = OperatonSupport.call(() -> tasks.createTaskQuery().processInstanceId(processInstanceId).count());
        boolean technical = "TECHNICAL".equals(mode);
        long reconOpen = openReconciliation(ctx, processInstanceId);
        List<String> summary = new ArrayList<>();
        summary.add(technical
                ? "Technical delete: runtime rows are removed after open external work is tombstoned (VI.5)"
                : "Business cancel: the model's cancel paths run and open external work is asked to cancel (VI.5)");
        summary.add(openTasks + " open user task(s) will be removed");
        summary.add(locked + " external task(s) locked by a worker go to CANCEL_REQUESTED");
        summary.add(offered + " external task(s) not yet locked are withdrawn");
        if (!ops.isEmpty()) {
            summary.add("Late results for " + ops.size() + " operation(s) will be audited and reconciled, never replayed");
        }
        if (reconOpen > 0) {
            summary.add(reconOpen + " open reconciliation item(s) must be resolved first");
        }
        return new ConsequencePreview(summary, List.of(new OpenTaskCount("LOCKED", locked),
                new OpenTaskCount("OFFERED", offered)), ops, open.size(), reconOpen > 0,
                reconOpen > 0 ? "OPEN_RECONCILIATION_ITEMS" : null, false, 1L, false);
    }

    /** Open reconciliation items for one root; 0 when the product tables are not available. */
    private long openReconciliation(RequestContext ctx, String rootId) {
        try {
            return tx.withConnection(c -> Jdbc.one(c,
                    "SELECT COUNT(*) FROM RECONCILIATION_ITEM WHERE tenant_id=? AND root_id=? AND state='OPEN'",
                    rs -> rs.getLong(1), ctx.tenantId(), rootId).orElse(0L));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public ConsequencePreview modifyPreview(RequestContext ctx, String processInstanceId, ModifyPreviewRequest r) {
        ProcessPort.ProcessInstance pi = processes.get(ctx, processInstanceId);
        if (pi.ended()) {
            throw ApiProblem.conflict("INSTANCE_ENDED", "The instance has ended and cannot be modified");
        }
        BpmnModelInstance model = OperatonSupport.call(() -> repository.getBpmnModelInstance(pi.processDefinitionId()));
        List<String> summary = new ArrayList<>();
        String blockedReason = null;
        List<ExternalTask> open = OperatonSupport.call(() ->
                externalTasks.createExternalTaskQuery().processInstanceId(processInstanceId).list());
        List<String> cancelledOps = new ArrayList<>();
        for (ProcessPort.ModificationInstruction i : r.instructions()) {
            String type = i.type() == null ? "" : i.type();
            switch (type) {
                case "startBeforeActivity", "startAfterActivity" -> {
                    if (i.activityId() == null || model.getModelElementById(i.activityId()) == null) {
                        blockedReason = "UNKNOWN_ACTIVITY";
                        summary.add("Unknown activity " + i.activityId());
                    } else {
                        summary.add((type.equals("startBeforeActivity") ? "Start before " : "Start after ")
                                + i.activityId());
                    }
                }
                case "startTransition" -> summary.add("Start transition " + i.transitionInstanceId());
                case "cancelActivity", "cancel" -> {
                    if (i.activityId() == null || model.getModelElementById(i.activityId()) == null) {
                        blockedReason = "UNKNOWN_ACTIVITY";
                        summary.add("Unknown activity " + i.activityId());
                    } else {
                        summary.add("Cancel all instances of " + i.activityId());
                        open.stream().filter(t -> i.activityId().equals(t.getActivityId()))
                                .forEach(t -> cancelledOps.add(operationIds.of(t).toString()));
                    }
                }
                case "cancelActivityInstance" -> {
                    summary.add("Cancel activity instance " + i.activityInstanceId());
                    open.stream().filter(t -> Objects.equals(i.activityInstanceId(), t.getActivityInstanceId()))
                            .forEach(t -> cancelledOps.add(operationIds.of(t).toString()));
                }
                case "cancelTransitionInstance" -> summary.add("Cancel transition instance " + i.transitionInstanceId());
                default -> {
                    blockedReason = "UNKNOWN_INSTRUCTION";
                    summary.add("Unknown instruction type " + type);
                }
            }
            if (i.targetProcessInstanceId() != null) {
                summary.add("  (applied to instance " + i.targetProcessInstanceId() + ", FLOWABLE-INTO-OPERATON FC-17)");
            }
        }
        if (!cancelledOps.isEmpty()) {
            summary.add(cancelledOps.size() + " external operation(s) in cancelled activities go to CANCEL_REQUESTED");
        }
        boolean limit = r.instructions().size() > MODIFY_LIMIT;
        if (limit) {
            summary.add("More than " + MODIFY_LIMIT + " instructions: run it as a batch with four-eyes approval (R49)");
        }
        return new ConsequencePreview(summary, List.of(new OpenTaskCount("LOCKED", cancelledOps.size())), cancelledOps,
                0, blockedReason != null, blockedReason, limit, 1L, limit);
    }

    // ------------------------------------------------------------------ suspension

    @Override
    public ProcessPort.ProcessInstance setSuspension(RequestContext ctx, String processInstanceId, SuspensionRequest r) {
        ProcessPort.ProcessInstance pi = processes.get(ctx, processInstanceId);
        if (pi.ended()) {
            throw ApiProblem.conflict("INSTANCE_ENDED", "The instance has ended");
        }
        tx.runInTx(() -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("reason", r.reason());
            if (r.suspended()) {
                OperatonSupport.run(() -> {
                    runtime.updateProcessInstanceSuspensionState().byProcessInstanceId(processInstanceId).suspend();
                    runtime.setVariable(processInstanceId, SUSPENDED_AT, new Date());
                });
                audit.record(ctx, processInstanceId, "INSTANCE_SUSPENDED", p);
            } else {
                Object at = OperatonSupport.call(() -> runtime.getVariable(processInstanceId, SUSPENDED_AT));
                OperatonSupport.run(() ->
                        runtime.updateProcessInstanceSuspensionState().byProcessInstanceId(processInstanceId).activate());
                int redated = 0;
                if (Boolean.TRUE.equals(r.redateDeadlines()) && at instanceof Date since) {
                    long shift = System.currentTimeMillis() - since.getTime();
                    for (var t : OperatonSupport.call(() ->
                            tasks.createTaskQuery().processInstanceId(processInstanceId).list())) {
                        if (t.getDueDate() != null) {
                            t.setDueDate(new Date(t.getDueDate().getTime() + shift));
                            OperatonSupport.run(() -> tasks.saveTask(t));
                            redated++;
                        }
                    }
                    p.put("deadline_shift_ms", shift);
                }
                OperatonSupport.run(() -> runtime.removeVariable(processInstanceId, SUSPENDED_AT));
                p.put("redated_tasks", redated);
                audit.record(ctx, processInstanceId, "INSTANCE_RESUMED", p);
            }
        });
        return processes.get(ctx, processInstanceId);
    }

    @Override
    public ProcessPort.ProcessDefinition setDefinitionSuspension(RequestContext ctx, String definitionId,
                                                                 SuspensionRequest r) {
        ProcessPort.ProcessDefinition d = processes.definition(ctx, definitionId);
        if (d.tenantId() == null && !ctx.inGroup("sangam-admin")) {
            throw ApiProblem.forbidden("ACTION_NOT_PERMITTED", "Shared definitions are suspended by a platform admin");
        }
        boolean include = Boolean.TRUE.equals(r.includeInstances());
        tx.runInTx(() -> {
            OperatonSupport.run(() -> {
                var b = repository.updateProcessDefinitionSuspensionState().byProcessDefinitionId(definitionId)
                        .includeProcessInstances(include);
                if (r.suspended()) {
                    b.suspend();
                } else {
                    b.activate();
                }
            });
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("definition_id", definitionId);
            p.put("definition_key", d.key());
            p.put("include_instances", include);
            p.put("reason", r.reason());
            audit.record(ctx, "sec:" + ctx.tenantId(), r.suspended() ? "DEFINITION_SUSPENDED" : "DEFINITION_ACTIVATED", p);
        });
        return processes.definition(ctx, definitionId);
    }

    // ------------------------------------------------------------------ FLOWABLE-INTO-OPERATON FC-17

    @Override
    public ProcessPort.ProcessInstance crossBoundaryMove(RequestContext ctx, String processInstanceId,
                                                         CrossBoundaryMoveRequest r) {
        ProcessPort.ProcessInstance source = processes.get(ctx, processInstanceId);
        ProcessPort.ProcessInstance target = processes.get(ctx, r.targetInstanceId());
        if (source.ended() || target.ended()) {
            throw ApiProblem.conflict("INSTANCE_ENDED", "Both instances must still be running");
        }
        String sourceRoot = source.rootProcessInstanceId() != null ? source.rootProcessInstanceId() : source.id();
        String targetRoot = target.rootProcessInstanceId() != null ? target.rootProcessInstanceId() : target.id();
        if (!sourceRoot.equals(targetRoot)) {
            throw ApiProblem.unprocessable("NOT_SAME_ROOT",
                    "A cross-boundary move is allowed only inside one call hierarchy (same root instance)");
        }
        BpmnModelInstance model = OperatonSupport.call(() -> repository.getBpmnModelInstance(target.processDefinitionId()));
        if (model.getModelElementById(r.targetActivityId()) == null) {
            throw ApiProblem.unprocessable("UNKNOWN_ACTIVITY", "Activity " + r.targetActivityId()
                    + " is not in the target instance's model");
        }
        tx.runInTx(() -> {
            // Start in the target first, so cancelling the last token of a sub-instance cannot end the hierarchy.
            OperatonSupport.run(() -> runtime.createProcessInstanceModification(r.targetInstanceId())
                    .startBeforeActivity(r.targetActivityId()).setAnnotation("MOVE: " + r.reason()).execute());
            OperatonSupport.run(() -> runtime.createProcessInstanceModification(processInstanceId)
                    .cancelActivityInstance(r.fromActivityInstanceId()).setAnnotation("MOVE: " + r.reason()).execute());
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("kind", "CROSS_BOUNDARY_MOVE");
            p.put("from_instance_id", processInstanceId);
            p.put("from_activity_instance_id", r.fromActivityInstanceId());
            p.put("to_instance_id", r.targetInstanceId());
            p.put("to_activity_id", r.targetActivityId());
            p.put("reason", r.reason());
            audit.record(ctx, processInstanceId, "INSTANCE_MODIFIED", p);
            audit.record(ctx, r.targetInstanceId(), "INSTANCE_MODIFIED", p);
        });
        return processes.get(ctx, processInstanceId);
    }

    // ------------------------------------------------------------------ history

    @Override
    public Page<ExternalTaskLogEntry> externalTaskLog(RequestContext ctx, HistoryPort.HistoryFilter f,
                                                      Page.PageRequest page) {
        HistoricExternalTaskLogQuery q = history.createHistoricExternalTaskLogQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId());
        }
        if (f != null) {
            if (f.processInstanceId() != null) {
                q.processInstanceId(f.processInstanceId());
            }
            if (f.processDefinitionKey() != null) {
                q.processDefinitionKey(f.processDefinitionKey());
            }
            if (f.activityId() != null) {
                q.activityIdIn(f.activityId());
            }
            if (f.activityInstanceId() != null) {
                q.activityInstanceIdIn(f.activityInstanceId());
            }
        }
        q.orderByTimestamp().desc();
        return OperatonSupport.page(q, page, props.getCountCap(), OperatonInstanceControlAdapter::map);
    }

    private static ExternalTaskLogEntry map(HistoricExternalTaskLog l) {
        String state = l.isCreationLog() ? "CREATED" : l.isFailureLog() ? "FAILED" : l.isSuccessLog() ? "SUCCEEDED"
                : l.isDeletionLog() ? "DELETED" : "UNKNOWN";
        return new ExternalTaskLogEntry(l.getId(), l.getExternalTaskId(), l.getTopicName(), l.getWorkerId(), state,
                l.getProcessInstanceId(), l.getActivityId(), OperatonSupport.instant(l.getTimestamp()), l.getRetries(),
                l.getErrorMessage());
    }
}
