// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.task.Task;

/**
 * Drives a case on the in-memory engine: runs due jobs (asynchronous continuations), and completes each open user task
 * of the business key with the variables the scenario returns for its task definition key (actingPerson and
 * actingSeat are set task-locally, as the REST layer does).
 */
final class PackDriver {

    private final ProcessEngine engine;
    private long offsetMillis;
    private org.sangam.packs.worker.EmbeddedWorkerRunner workers;

    /**
     * The driver also completes external tasks (GP-47 / GP-28: admit cards and offer letters), through the external
     * task service with the pack workers, whose connectors are the simulator here: it stands in for the worker
     * process of a deployment. A topic with no worker, or a worker that fails, stops the run loudly.
     */
    PackDriver withWorkers() {
        this.workers = new org.sangam.packs.worker.EmbeddedWorkerRunner(engine, "acceptance-driver", 60_000L);
        return this;
    }

    /** Fetches, handles and completes the due external tasks; fails on a task nobody can or did handle. */
    int runExternalTasks(String businessKey) {
        int n = workers.pollOnce(100);
        List<String> pids = engine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceBusinessKeyLike(businessKey + "%").list().stream().map(p -> p.getId()).toList();
        for (var t : engine.getExternalTaskService().createExternalTaskQuery().list()) {
            if (!pids.contains(t.getProcessInstanceId())) {
                continue;
            }
            if (org.sangam.packs.worker.PackWorkers.forTopic(t.getTopicName()).isEmpty()) {
                throw new IllegalStateException("external task " + t.getActivityId() + ": no simulator worker for topic "
                        + t.getTopicName() + " (never invented)");
            }
            if (t.getErrorMessage() != null) {
                throw new IllegalStateException("external task " + t.getActivityId() + " (" + t.getTopicName()
                        + ") failed: " + t.getErrorMessage());
            }
        }
        return n;
    }

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
        return run(businessKey, answers, maxSteps, done -> false);
    }

    /**
     * As {@link #run(String, Function, int)}, with a hook asked first on every turn (SL-32 {@code run.steps}): it sees
     * the tasks completed so far and returns true when it moved the case (clock, message, variables), so the turn
     * starts again.
     */
    List<String> run(String businessKey, Function<Task, Answer> answers, int maxSteps,
                     java.util.function.Predicate<List<String>> beforeTurn) {
        List<String> done = new java.util.ArrayList<>();
        for (int step = 0; step < maxSteps; step++) {
            if (beforeTurn.test(java.util.Collections.unmodifiableList(done))) {
                continue;
            }
            if (runJobs() > 0) {
                continue;
            }
            if (workers != null && runExternalTasks(businessKey) > 0) {
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
        java.util.Date soon = new java.util.Date(ClockUtil.getCurrentTime().getTime() + 5000);
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

    /** True when a job is due now (an async continuation or a timer that the clock has reached). */
    boolean hasDueJobs() {
        return !dueJobs().isEmpty();
    }

    private List<Job> dueJobs() {
        return engine.getManagementService().createJobQuery().executable().list().stream()
                .filter(j -> j.getDuedate() == null || !j.getDuedate().after(ClockUtil.getCurrentTime()))
                .filter(j -> j.getRetries() > 0).toList();
    }

    /** Executes every job without a future due date (async continuations); returns how many ran. */
    int runJobs() {
        int n = 0;
        for (int i = 0; i < 200; i++) {
            List<Job> jobs = dueJobs();
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

    /**
     * The scenario clock (SL-32 {@code advance}): moves the engine clock (timer due dates, job execution) and, through
     * {@link #clock()}, the pack clock (SLA plans, deadlines) forward by {@code by}, cumulatively.
     */
    void advance(java.time.Duration by) {
        if (by.isNegative()) {
            throw new IllegalArgumentException("the scenario clock only moves forward: " + by);
        }
        offsetMillis += by.toMillis();
        ClockUtil.offset(offsetMillis);
    }

    /** Back to the system time (end of a scenario). */
    void resetClock() {
        offsetMillis = 0;
        ClockUtil.reset();
    }

    long offsetMillis() {
        return offsetMillis;
    }

    /** A pack clock that reads the engine clock, so pack deadlines and engine timers move together. */
    static java.time.Clock clock() {
        return new EngineClock(java.time.ZoneOffset.UTC);
    }

    private static final class EngineClock extends java.time.Clock {
        private final java.time.ZoneId zone;

        EngineClock(java.time.ZoneId zone) {
            this.zone = zone;
        }

        @Override
        public java.time.ZoneId getZone() {
            return zone;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId z) {
            return new EngineClock(z);
        }

        @Override
        public java.time.Instant instant() {
            return ClockUtil.getCurrentTime().toInstant();
        }
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
