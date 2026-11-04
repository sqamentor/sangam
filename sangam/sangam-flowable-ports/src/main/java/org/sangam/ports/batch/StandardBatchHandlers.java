// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.batch;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.task.Task;

import java.util.Map;

/**
 * Item handlers for {@link ProductBatchService}:
 * <ul>
 *   <li>{@code task-complete} (D-34 bulk completion, Flowable {@code TaskService.bulkComplete}): item = task id; config
 *       {@code variables} (map) and optional {@code assignee} that the task must have (guard against completing tasks
 *       re-assigned since the selection). Already-completed tasks count as done (idempotent).</li>
 *   <li>{@code message-correlate}: item = business key; config {@code messageName}, {@code variables}, {@code tenantId}.</li>
 *   <li>{@code process-cancel}: item = process instance id; config {@code reason}. Missing instances count as done.</li>
 * </ul>
 * Authorization is checked when the batch is created (the selection is an authorised query, VI.4).
 */
public final class StandardBatchHandlers {

    private StandardBatchHandlers() {
    }

    @SuppressWarnings("unchecked")
    public static void registerAll(ProductBatchService batches, ProcessEngine engine) {
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
            var b = engine.getRuntimeService().createMessageCorrelation(String.valueOf(cfg.get("messageName")))
                    .processInstanceBusinessKey(ref).setVariables((Map<String, Object>) cfg.getOrDefault("variables", Map.of()));
            if (tenant != null && !tenant.isEmpty()) {
                b.tenantId(tenant);
            }
            return "correlated " + b.correlateAllWithResult().size();
        });
        batches.register("process-cancel", (tenant, ref, cfg) -> {
            if (engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(ref).count() == 0) {
                return "already ended";
            }
            engine.getRuntimeService().deleteProcessInstance(ref, String.valueOf(cfg.getOrDefault("reason", "bulk cancel")));
            return "cancelled";
        });
    }
}
