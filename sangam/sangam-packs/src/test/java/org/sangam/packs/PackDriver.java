// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.task.Task;

/**
 * Drives a case on the in-memory engine: runs due jobs (asynchronous continuations), and completes each open user task
 * of the business key with the variables the scenario returns for its task definition key (actingPerson and
 * actingSeat are set task-locally, as the REST layer does).
 */
final class PackDriver {

    private final ProcessEngine engine;

    PackDriver(ProcessEngine engine) {
        this.engine = engine;
    }

    /** A scenario answer: task-local acting context plus completion variables. */
    record Answer(String person, String seat, Map<String, Object> vars) {
        static Answer of(String person, String seat, Object... kv) {
            Map<String, Object> m = new HashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                m.put(kv[i].toString(), kv[i + 1]);
            }
            return new Answer(person, seat, m);
        }
    }

    /** Runs until nothing moves; returns the task keys completed in order. */
    List<String> run(String businessKey, Function<Task, Answer> answers, int maxSteps) {
        List<String> done = new java.util.ArrayList<>();
        for (int step = 0; step < maxSteps; step++) {
            if (runJobs() > 0) {
                continue;
            }
            List<Task> tasks = engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(businessKey + "%")
                    .list();
            if (tasks.isEmpty()) {
                if (waitForSoonTimer()) {
                    continue;
                }
                break;
            }
            Task t = tasks.get(0);
            Answer a = answers.apply(t);
            if (a == null) {
                break;
            }
            Map<String, Object> local = new HashMap<>();
            local.put("actingPerson", a.person());
            local.put("actingSeat", a.seat());
            engine.getTaskService().setVariablesLocal(t.getId(), local);
            engine.getTaskService().complete(t.getId(), a.vars());
            done.add(t.getTaskDefinitionKey());
        }
        return done;
    }

    /** Sleeps until a timer due within five seconds is due; false when there is none. */
    boolean waitForSoonTimer() {
        java.util.Date soon = new java.util.Date(System.currentTimeMillis() + 5000);
        List<Job> jobs = engine.getManagementService().createJobQuery().timers().list().stream()
                .filter(j -> j.getDuedate() != null && j.getDuedate().before(soon)).toList();
        if (jobs.isEmpty()) {
            return false;
        }
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /** Executes every job without a future due date (async continuations); returns how many ran. */
    int runJobs() {
        int n = 0;
        for (int i = 0; i < 200; i++) {
            List<Job> jobs = engine.getManagementService().createJobQuery().executable().list().stream()
                    .filter(j -> j.getDuedate() == null || !j.getDuedate().after(new java.util.Date()))
                    .filter(j -> j.getRetries() > 0).toList();
            if (jobs.isEmpty()) {
                break;
            }
            for (Job j : jobs) {
                try {
                    engine.getManagementService().executeJob(j.getId());
                } catch (RuntimeException e) {
                    throw new IllegalStateException("job " + j.getId() + " failed: " + e.getMessage(), e);
                }
                n++;
            }
        }
        return n;
    }

    Object var(String businessKey, String name) {
        var h = engine.getHistoryService().createHistoricVariableInstanceQuery().variableName(name).list();
        var pis = engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceBusinessKey(businessKey)
                .list().stream().map(p -> p.getId()).toList();
        return h.stream().filter(v -> pis.contains(v.getProcessInstanceId()))
                .filter(v -> v.getProcessInstanceId().equals(rootId(businessKey))).map(v -> v.getValue())
                .findFirst().orElse(null);
    }

    String rootId(String businessKey) {
        return engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceBusinessKey(businessKey)
                .list().stream().filter(p -> p.getSuperProcessInstanceId() == null).map(p -> p.getId()).findFirst()
                .orElse(null);
    }

    boolean ended(String businessKey) {
        return engine.getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKey(businessKey).count() == 0;
    }
}
