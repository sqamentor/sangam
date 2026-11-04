// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.worker;

import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ExternalTaskService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sangam.packs.core.Vars;

/**
 * Runs the pack workers inside the server JVM for the h2 developer profile and air-gapped demos. Production workers
 * use the Sangam worker API with lease fencing (VI.3); this runner uses Operaton's native fetch-and-lock and is meant
 * for single-node development only. {@link #pollOnce} is driven by the caller's scheduler.
 */
public class EmbeddedWorkerRunner {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedWorkerRunner.class);

    private final ProcessEngine engine;
    private final String workerId;
    private final long lockMs;

    public EmbeddedWorkerRunner(ProcessEngine engine, String workerId, long lockMs) {
        this.engine = engine;
        this.workerId = workerId;
        this.lockMs = lockMs;
    }

    /** Fetches and handles up to {@code max} tasks of every registered topic; returns how many were handled. */
    public int pollOnce(int max) {
        ExternalTaskService ets = engine.getExternalTaskService();
        int handled = 0;
        for (PackWorker w : PackWorkers.all()) {
            List<LockedExternalTask> tasks = ets.fetchAndLock(max, workerId).topic(w.topic(), lockMs).execute();
            for (LockedExternalTask t : tasks) {
                handled++;
                Map<String, Object> vars = t.getVariables();
                String bk = t.getBusinessKey() == null ? t.getProcessInstanceId() : t.getBusinessKey();
                String opId = Vars.operationId(bk, t.getActivityId() + ":" + w.topic(), 1);
                try {
                    Map<String, Object> out = w.handle(t.getTenantId(), bk, opId, vars);
                    ets.complete(t.getId(), workerId, out);
                } catch (PackWorker.WorkerBpmnError e) {
                    ets.handleBpmnError(t.getId(), workerId, e.code(), e.getMessage());
                } catch (RuntimeException e) {
                    LOG.warn("worker {} failed on {}: {}", w.topic(), t.getId(), e.toString());
                    int retries = t.getRetries() == null ? 3 : t.getRetries() - 1;
                    if ("c".equals(w.capabilityClass())) {
                        retries = 0; // class (c): never repeated automatically; an incident is raised (VI.3 rule 5)
                    }
                    ets.handleFailure(t.getId(), workerId, e.getMessage(), e.toString(), Math.max(0, retries), 30_000L);
                }
            }
        }
        return handled;
    }
}
