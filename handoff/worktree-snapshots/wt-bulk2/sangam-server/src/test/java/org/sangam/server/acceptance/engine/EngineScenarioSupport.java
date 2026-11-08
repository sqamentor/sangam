// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricActivityInstance;
import org.operaton.bpm.engine.history.HistoricCaseActivityInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.api.EngineAdapter.InstanceView;
import org.sangam.core.api.EngineAdapter.TaskQuery;
import org.sangam.core.api.EngineAdapter.TaskView;
import org.sangam.core.api.StaleLeaseException;
import org.sangam.core.engine.SangamEngineAdapter;
import org.sangam.server.acceptance.AbstractAcceptanceIT;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Scenario plumbing for the Stage B ports (E1, E8, E10, E11, E16, E17) against the real server. The spike's
 * {@code ExperimentBase} recorded outcomes into JSON and never failed; here every scenario is a JUnit assertion: all
 * checks are collected and a scenario with any failed check (or an exception) fails the test, listing every failed
 * check and the recorded details. Engine calls go through Sangam's own {@link SangamEngineAdapter} bean (the fenced
 * adapter the product uses); reads the adapter does not offer (history, jobs, signals, conditional start) use the
 * server's {@link ProcessEngine} bean, as the spike's {@code OperatonHooks} did.
 *
 * <p>The engine clock is JVM-wide (W-7); every test resets it after it ran. Business keys carry a per-run random
 * suffix so reruns on a reused database never collide.
 */
public abstract class EngineScenarioSupport extends AbstractAcceptanceIT {

    /** Per-JVM run suffix for business keys and deployment names. */
    protected static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    protected SangamEngineAdapter adapter;

    @Autowired
    protected ProcessEngine engine;

    /** Engine clock as last set by the test (only meaningful after {@link #clock}). */
    protected Instant now;

    @AfterEach
    void resetEngineClock() {
        adapter.setClock(null);
    }

    // ---------------------------------------------------------------- scenario harness

    @FunctionalInterface
    public interface Body {
        void run(Ctx ctx) throws Exception;
    }

    /** Per-scenario checks, details and workarounds (same vocabulary as the spike). */
    public static final class Ctx {
        final Map<String, Object> details = new LinkedHashMap<>();
        final Set<String> workarounds = new LinkedHashSet<>();
        final List<String> checks = new ArrayList<>();
        final List<String> failed = new ArrayList<>();

        public void detail(String k, Object v) {
            details.put(k, v);
        }

        public Object detail(String k) {
            return details.get(k);
        }

        public void workaround(String w) {
            workarounds.add(w);
        }

        public boolean check(String what, boolean ok) {
            checks.add((ok ? "OK   " : "FAIL ") + what);
            if (!ok) {
                failed.add(what);
            }
            return ok;
        }

        public void require(String what, boolean ok) {
            if (!check(what, ok)) {
                throw new AssertionError("required check failed: " + what);
            }
        }

        public List<String> failedChecks() {
            return failed;
        }
    }

    /** Runs one scenario; throws {@link AssertionError} when a check failed or the body threw. */
    protected void scenario(String id, Body body) {
        Ctx ctx = new Ctx();
        Throwable error = null;
        try {
            body.run(ctx);
        } catch (Throwable t) {
            error = t;
        }
        StringBuilder log = new StringBuilder("[acceptance] ").append(id).append(ctx.failed.isEmpty() && error == null
                ? " PASS" : " FAIL");
        ctx.checks.forEach(c -> log.append("\n    ").append(c));
        if (!ctx.details.isEmpty()) {
            log.append("\n    details: ").append(ctx.details);
        }
        if (!ctx.workarounds.isEmpty()) {
            log.append("\n    workarounds: ").append(ctx.workarounds);
        }
        System.out.println(log);
        if (error != null || !ctx.failed.isEmpty()) {
            AssertionError ae = new AssertionError(id + " failed: " + ctx.failed
                    + (error == null ? "" : " / threw " + error) + "\n" + log);
            if (error != null) {
                ae.initCause(error);
            }
            throw ae;
        }
    }

    // ---------------------------------------------------------------- engine helpers

    protected void clock(Instant t) {
        now = t;
        adapter.setClock(t);
    }

    protected void advance(Duration d) {
        clock(now.plus(d));
    }

    protected static String key(String prefix) {
        return prefix + "-" + RUN + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    /** Reads {@code acceptance/<path>} from the test class path. */
    protected static byte[] resource(String path) {
        try (InputStream in = EngineScenarioSupport.class.getClassLoader().getResourceAsStream("acceptance/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("missing test resource acceptance/" + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Deploys {@code acceptance/corpus/<file>.model} resources under their real names ({@code <file>}). The
     * {@code .model} suffix keeps them out of Operaton's class-path auto-deployment (TestResourcesNotAutoDeployedTest).
     */
    protected String deployCorpus(String name, String... files) {
        Map<String, byte[]> res = new LinkedHashMap<>();
        for (String f : files) {
            res.put(f, resource("corpus/" + f + ".model"));
        }
        return adapter.deploy(null, name + "-" + RUN, res);
    }

    protected List<TaskView> tasks(String instanceId, String key) {
        return adapter.queryTasks(new TaskQuery(null, null, null, instanceId, key, 0, 0));
    }

    protected long countForGroup(String instanceId, String key, String group) {
        return adapter.countTasks(new TaskQuery(null, null, List.of(group), instanceId, key, 0, 0));
    }

    protected TaskView single(Ctx ctx, String instanceId, String key) {
        List<TaskView> t = tasks(instanceId, key);
        ctx.require("exactly one task " + key + " (found " + t.size() + ")", t.size() == 1);
        return t.get(0);
    }

    /** Checks the candidate group, claims and completes the single task {@code key}. */
    protected void complete(Ctx ctx, String instanceId, String key, String group, Map<String, Object> vars) {
        TaskView t = single(ctx, instanceId, key);
        if (group != null) {
            ctx.check(key + " offered to group " + group, countForGroup(instanceId, key, group) == 1);
        }
        adapter.claimTask(t.id(), "user-" + (group == null ? "x" : group));
        adapter.completeTask(t.id(), vars);
    }

    protected List<String> active(String instanceId) {
        InstanceView v = adapter.getInstance(instanceId);
        return v == null ? List.of() : v.activeActivityIds();
    }

    protected boolean ended(String instanceId) {
        InstanceView v = adapter.getInstance(instanceId);
        return v != null && v.ended();
    }

    /**
     * Fetches the work of {@code instanceId} on {@code topic} (others are released), checks the fencing token,
     * completes it and, if the instance did not move on synchronously, runs due jobs (recorded as a workaround).
     */
    protected ExternalWork completeExternal(Ctx ctx, String instanceId, String topic, Map<String, Object> vars,
                                            boolean probeFencing) {
        String worker = "worker-" + topic;
        ExternalWork mine = null;
        for (int round = 0; round < 5 && mine == null; round++) {
            for (ExternalWork w : adapter.fetchAndLock(worker, topic, 50, Duration.ofMinutes(5))) {
                if (instanceId.equals(w.processInstanceId()) && mine == null) {
                    mine = w;
                } else {
                    adapter.unlockExternal(w.id(), worker, w.lockVersion());
                }
            }
        }
        ctx.require("external work on topic " + topic + " fetched", mine != null);
        ctx.check(topic + ": lockVersion > 0", mine.lockVersion() > 0);
        ctx.check(topic + ": attemptId present", mine.attemptId() != null && !mine.attemptId().isBlank());
        if (probeFencing) {
            adapter.extendLock(mine.id(), worker, mine.lockVersion(), Duration.ofMinutes(10));
            boolean rejected;
            try {
                adapter.completeExternal(mine.id(), worker, mine.lockVersion() - 1, vars);
                rejected = false;
            } catch (StaleLeaseException e) {
                rejected = true;
            }
            ctx.check(topic + ": stale lockVersion rejected (StaleLeaseException)", rejected);
            boolean otherRejected;
            try {
                adapter.completeExternal(mine.id(), "intruder", mine.lockVersion(), vars);
                otherRejected = false;
            } catch (StaleLeaseException e) {
                otherRejected = true;
            }
            ctx.check(topic + ": non-owner rejected (StaleLeaseException)", otherRejected);
        }
        adapter.completeExternal(mine.id(), worker, mine.lockVersion(), vars);
        if (active(instanceId).contains(mine.activityId())) {
            adapter.executeDueJobs();
            ctx.workaround("executeDueJobs() after completeExternal (" + topic + ")");
        }
        return mine;
    }

    protected static Map<String, Object> vars(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ---------------------------------------------------------------- engine reads (spike OperatonHooks)

    protected List<String> historicActivityIds(String processInstanceId) {
        List<String> out = new ArrayList<>();
        for (HistoricActivityInstance h : engine.getHistoryService().createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId).orderByHistoricActivityInstanceStartTime().asc()
                .orderPartiallyByOccurrence().asc().list()) {
            out.add(h.getActivityId());
        }
        return out;
    }

    protected List<String> allInstances(String processKey) {
        List<String> out = new ArrayList<>();
        for (HistoricProcessInstance p : engine.getHistoryService().createHistoricProcessInstanceQuery()
                .processDefinitionKey(processKey).list()) {
            out.add(p.getId());
        }
        return out;
    }

    /** Human-task and milestone history of a case, ordered by end time: {@code id:type:state}. */
    protected List<String> caseHistory(String caseInstanceId) {
        List<HistoricCaseActivityInstance> list = new ArrayList<>(engine.getHistoryService()
                .createHistoricCaseActivityInstanceQuery().caseInstanceId(caseInstanceId).list());
        list.removeIf(h -> !("humanTask".equals(h.getCaseActivityType()) || "milestone".equals(h.getCaseActivityType())));
        list.sort(Comparator.comparing(h -> h.getEndTime() == null ? new Date(Long.MAX_VALUE) : h.getEndTime()));
        List<String> out = new ArrayList<>();
        for (HistoricCaseActivityInstance h : list) {
            String state = h.isCompleted() ? "completed" : h.isTerminated() ? "terminated" : h.isActive() ? "active"
                    : h.isAvailable() ? "available" : h.isEnabled() ? "enabled" : h.isDisabled() ? "disabled" : "other";
            out.add(h.getCaseActivityId() + ":" + h.getCaseActivityType() + ":" + state);
        }
        return out;
    }

    protected List<String> timerJobs(String processInstanceId) {
        List<String> out = new ArrayList<>();
        for (Job j : engine.getManagementService().createJobQuery().processInstanceId(processInstanceId).timers().list()) {
            out.add("due=" + (j.getDuedate() == null ? null : j.getDuedate().toInstant()));
        }
        return out;
    }
}
