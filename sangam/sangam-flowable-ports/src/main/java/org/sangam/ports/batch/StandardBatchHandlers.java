// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.batch;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.MessageCorrelationBuilderImpl;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.ports.UniqueStartPort;
import org.sangam.ports.support.EngineTx;

import java.util.Map;

/**
 * Item handlers for {@link ProductBatchService}:
 * <ul>
 *   <li>{@code task-complete} (FLOWABLE-INTO-OPERATON D-34 bulk completion, Flowable {@code TaskService.bulkComplete}): item = task id; config
 *       {@code variables} (map) and optional {@code assignee} that the task must have (guard against completing tasks
 *       re-assigned since the selection). Already-completed tasks count as done (idempotent).</li>
 *   <li>{@code message-correlate}: item = business key; config {@code messageName}, {@code variables}, optional
 *       {@code start}. The item is correlated to <b>waiting executions only</b> (Operaton's correlateAll would also
 *       fire a message start event and start an instance unguarded). Only with {@code "start": true}, and only when
 *       nothing was waiting, one instance is started through the message start event, and then only through the
 *       unique-start guard on (tenant, process key, business key = item) (FC-04, VI.2.6 rule 4): a repeated
 *       business key reports {@code duplicate start}.</li>
 *   <li>{@code process-cancel}: item = process instance id; config {@code reason}. Missing instances count as done.</li>
 * </ul>
 * Authorization is checked when the batch is created (the selection is an authorised query, VI.4). The batch's
 * default tenant ({@code ""}) is the engine's null tenant (FW-29).
 */
public final class StandardBatchHandlers {

    private StandardBatchHandlers() {
    }

    /** Without a unique-start guard: {@code message-correlate} batches with {@code start=true} are refused. */
    public static void registerAll(ProductBatchService batches, ProcessEngine engine) {
        registerAll(batches, engine, null);
    }

    @SuppressWarnings("unchecked")
    public static void registerAll(ProductBatchService batches, ProcessEngine engine, UniqueStartPort uniqueStartOrNull) {
        batches.register("task-complete", (tenant, ref, cfg) -> {
            Task t = engine.getTaskService().createTaskQuery().taskId(ref).singleResult();
            if (t == null) {
                return "already completed";
            }
            Object expected = cfg.get("assignee");
            if (expected != null && !expected.equals(t.getAssignee())) {
                throw new IllegalStateException("task " + ref + " is assigned to " + t.getAssignee() + ", not " + expected);
            }
            engine.getTaskService().complete(ref, (Map<String, Object>) cfg.getOrDefault("variables", Map.of()));
            return "completed";
        });
        batches.register("message-correlate", (tenant, ref, cfg) -> {
            String message = String.valueOf(cfg.get("messageName"));
            Map<String, Object> vars = (Map<String, Object>) cfg.getOrDefault("variables", Map.of());
            boolean start = Boolean.TRUE.equals(cfg.get("start"));
            String engineTenant = tenant == null || tenant.isEmpty() ? null : tenant;
            if (start && uniqueStartOrNull == null) {
                throw new IllegalStateException("message-correlate with start=true needs a UniqueStartPort");
            }
            return EngineTx.execute(engine, cc -> {
                MessageCorrelationBuilder b = tenant(engine.getRuntimeService().createMessageCorrelation(message)
                        .processInstanceBusinessKey(ref).setVariables(vars), engineTenant);
                ((MessageCorrelationBuilderImpl) b).executionsOnly();
                int n = b.correlateAllWithResult().size();
                if (n > 0 || !start) {
                    return "correlated " + n;
                }
                var q = engine.getRepositoryService().createProcessDefinitionQuery().messageEventSubscriptionName(message).latestVersion();
                if (engineTenant != null) {
                    q.tenantIdIn(engineTenant);
                } else {
                    q.withoutTenantId();
                }
                var defs = q.list();
                if (defs.isEmpty()) {
                    return "correlated 0 (no message start event)";
                }
                String key = defs.get(0).getKey();
                if (!uniqueStartOrNull.claim(EngineTx.connection(), engineTenant, key, ref, "batch:message-correlate", ref)) {
                    return "duplicate start";
                }
                MessageCorrelationBuilder sb = tenant(engine.getRuntimeService().createMessageCorrelation(message)
                        .processInstanceBusinessKey(ref).setVariables(vars), engineTenant);
                String pi = sb.correlateStartMessage().getId();
                uniqueStartOrNull.bind(EngineTx.connection(), engineTenant, key, ref, pi);
                return "started " + pi;
            });
        });
        batches.register("process-cancel", (tenant, ref, cfg) -> {
            if (engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(ref).count() == 0) {
                return "already ended";
            }
            engine.getRuntimeService().deleteProcessInstance(ref, String.valueOf(cfg.getOrDefault("reason", "bulk cancel")));
            return "cancelled";
        });
    }

    private static MessageCorrelationBuilder tenant(MessageCorrelationBuilder b, String engineTenant) {
        return engineTenant != null ? b.tenantId(engineTenant) : b.withoutTenantId();
    }
}
