package org.sangam.spike.experiments.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.api.EngineAdapter.TaskQuery;
import org.sangam.spike.api.EngineAdapter.TaskView;

/**
 * Shared scenario plumbing. A scenario never throws out of {@link #scenario}: its outcome (pass / fail / info, error
 * text, details, workarounds) is recorded and written to the experiment's JSON. The JUnit build fails only on harness
 * errors, not on engine outcomes, because the outcomes are the measurement.
 */
public abstract class ExperimentBase {

    protected EngineHooks hooks;
    protected EngineAdapter adapter;
    protected Results results;

    /** Engine clock, only moved forward. */
    protected Instant now;

    @FunctionalInterface
    public interface Body {
        void run(Ctx ctx) throws Exception;
    }

    /** Per-scenario context: assertions, details and workarounds. */
    public static final class Ctx {
        final Map<String, Object> details = new LinkedHashMap<>();
        final Set<String> workarounds = new LinkedHashSet<>();
        final List<String> checks = new ArrayList<>();
        final List<String> failedChecks = new ArrayList<>();
        boolean info;

        public void detail(String k, Object v) {
            details.put(k, v);
        }

        public void workaround(String w) {
            workarounds.add(w);
        }

        /** Marks the scenario as informational (recorded, not counted as pass/fail). */
        public void info() {
            info = true;
        }

        /** A recorded check; a false check makes the scenario fail but the scenario continues. */
        public boolean check(String what, boolean ok) {
            checks.add((ok ? "OK   " : "FAIL ") + what);
            if (!ok) {
                failedChecks.add(what);
            }
            return ok;
        }

        /** A check that stops the scenario when false. */
        public void require(String what, boolean ok) {
            if (!check(what, ok)) {
                throw new ScenarioAbort(what);
            }
        }
    }

    static final class ScenarioAbort extends RuntimeException {
        ScenarioAbort(String m) {
            super("required check failed: " + m);
        }
    }

    protected void open(String experiment, String title) {
        hooks = Engines.hooks();
        results = new Results(experiment, title);
        adapter = hooks.create(Db.newDatabase(experiment + "_" + hooks.engine() + "_"));
    }

    protected void closeAdapter() {
        if (adapter != null) {
            try {
                hooks.resetClock(adapter);
            } catch (RuntimeException ignored) {
                // best effort
            }
            adapter.close();
        }
    }

    protected Map<String, Object> scenario(String id, String group, String description, List<String> variantFiles, Body body) {
        Ctx ctx = new Ctx();
        String status;
        String error = null;
        long t0 = System.nanoTime();
        try {
            body.run(ctx);
            status = ctx.failedChecks.isEmpty() ? "pass" : "fail";
        } catch (Throwable t) {
            status = "fail";
            error = Results.error(t);
        }
        if (ctx.info) {
            status = "info";
        }
        Map<String, Object> s = Results.map(
                "id", id,
                "group", group,
                "description", description,
                "status", status,
                "variantFiles", variantFiles,
                "workarounds", new ArrayList<>(ctx.workarounds),
                "checks", ctx.checks,
                "details", ctx.details,
                "error", error,
                "millis", (System.nanoTime() - t0) / 1_000_000);
        results.add(s);
        System.out.printf("[%s] %-40s %s%s%n", hooks.engine(), id, status, error == null ? "" : "  " + error);
        return s;
    }

    // ---------------------------------------------------------------- engine helpers

    protected void clock(Instant t) {
        now = t;
        adapter.setClock(t);
    }

    protected void advance(Duration d) {
        clock(now.plus(d));
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
        EngineAdapter.InstanceView v = adapter.getInstance(instanceId);
        return v == null ? List.of() : v.activeActivityIds();
    }

    protected boolean ended(String instanceId) {
        EngineAdapter.InstanceView v = adapter.getInstance(instanceId);
        return v != null && v.ended();
    }

    /**
     * Fetches the work of {@code instanceId} on {@code topic} (others are released), checks the fencing token,
     * completes it and, if the instance did not move on synchronously, runs due jobs (recorded as a workaround).
     */
    protected ExternalWork completeExternal(Ctx ctx, String instanceId, String topic, Map<String, Object> vars,
                                            boolean probeFencing) {
        List<ExternalWork> got = adapter.fetchAndLock("worker-" + topic, topic, 50, Duration.ofMinutes(5));
        ExternalWork mine = null;
        for (ExternalWork w : got) {
            if (instanceId.equals(w.processInstanceId()) && mine == null) {
                mine = w;
            } else {
                adapter.unlockExternal(w.id(), "worker-" + topic, w.lockVersion());
            }
        }
        ctx.require("external work on topic " + topic + " fetched", mine != null);
        ctx.check(topic + ": lockVersion > 0", mine.lockVersion() > 0);
        ctx.check(topic + ": attemptId present", mine.attemptId() != null && !mine.attemptId().isBlank());
        ctx.detail(topic + ".lockVersion", mine.lockVersion());
        if (probeFencing) {
            adapter.extendLock(mine.id(), "worker-" + topic, mine.lockVersion(), Duration.ofMinutes(10));
            boolean rejected;
            try {
                adapter.completeExternal(mine.id(), "worker-" + topic, mine.lockVersion() - 1, vars);
                rejected = false;
            } catch (org.sangam.spike.api.StaleLeaseException e) {
                rejected = true;
            }
            ctx.check(topic + ": stale lockVersion rejected (StaleLeaseException)", rejected);
            boolean otherRejected;
            try {
                adapter.completeExternal(mine.id(), "intruder", mine.lockVersion(), vars);
                otherRejected = false;
            } catch (org.sangam.spike.api.StaleLeaseException e) {
                otherRejected = true;
            }
            ctx.check(topic + ": non-owner rejected (StaleLeaseException)", otherRejected);
        }
        adapter.completeExternal(mine.id(), "worker-" + topic, mine.lockVersion(), vars);
        List<String> act = active(instanceId);
        if (act.contains(mine.activityId())) {
            int n = adapter.executeDueJobs();
            ctx.workaround("external completion continues the instance only through an async job: executeDueJobs() needed"
                    + " after completeExternal (" + topic + ")");
            ctx.detail(topic + ".jobsRunAfterComplete", n);
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

    protected Map<String, Object> summary(List<Map<String, Object>> scenarios) {
        long pass = scenarios.stream().filter(s -> "pass".equals(s.get("status"))).count();
        long fail = scenarios.stream().filter(s -> "fail".equals(s.get("status"))).count();
        long info = scenarios.stream().filter(s -> "info".equals(s.get("status"))).count();
        return Results.map("scenarios", scenarios.size(), "pass", pass, "fail", fail, "info", info);
    }
}
