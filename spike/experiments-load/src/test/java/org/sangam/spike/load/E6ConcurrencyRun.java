package org.sangam.spike.load;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.api.EngineAdapter.TaskQuery;
import org.sangam.spike.api.EngineAdapter.TaskView;
import org.sangam.spike.corpus.Corpus;
import org.sangam.spike.sim.db.Databases;

/**
 * E6 (SANGAM.md XI.4, T10/T33): concurrency on one root, per engine × database.
 *
 * <ul>
 *   <li>(a1) one root waiting on {@value #TASK_FANOUT} parallel user tasks, completed from {@value #THREADS} threads at
 *       once, {@value #TASK_ROOTS} roots;</li>
 *   <li>(a2) one root waiting on {@value #MSG_FANOUT} parallel message catch events, correlated from
 *       {@value #THREADS} threads at once, {@value #MSG_ROOTS} roots;</li>
 *   <li>(b) WF-2 parallel-join storm: both comment branches completed at the same instant (barrier per pair),
 *       {@value #WF2_INSTANCES} instances, {@value #THREADS} threads (16 pairs in flight).</li>
 * </ul>
 * Every concurrent command is wrapped in {@link Retry} (max 5 retries, jitter). Lost updates are checked on the final
 * state: every variable written by a successful command is present, the activity after the join ran exactly once,
 * no branch is left, and every instance ended exactly once (runtime + ACT_HI_PROCINST + ACT_HI_ACTINST end event).
 *
 * <p>Databases run strictly one at a time in the order PostgreSQL, Oracle Free, SQL Server; each container is
 * stopped before the next one starts. Both engines use the same container, each in its own database/schema.
 */
class E6ConcurrencyRun {

    static final int THREADS = 32;
    static final int[] THREAD_COUNTS = {16, 32};
    static final int TASK_FANOUT = 50;
    static final int TASK_ROOTS = 6;
    static final int MSG_FANOUT = 32;
    static final int MSG_ROOTS = 10;
    static final int WF2_INSTANCES = 100;
    static final List<String> DB_ORDER = List.of("postgres", "oracle", "sqlserver");

    private static final Map<String, Object> HEADER = new LinkedHashMap<>();

    @Test
    void e6() {
        List<String> wanted = List.of(System.getProperty("e6.dbs", "postgres,oracle,sqlserver").split(","));
        HEADER.put("experiment", "E6 concurrency (SANGAM.md XI.4; T10/T33)");
        HEADER.put("environment", Results.environment());
        HEADER.put("design", Map.of(
                "threads", THREADS,
                "threadsPerVariant", "one-root scenarios run once with 16 and once with 32 threads",
                "oneRootUserTasks", TASK_ROOTS + " roots x " + TASK_FANOUT + " parallel user tasks (fork/join), completed concurrently",
                "oneRootMessages", MSG_ROOTS + " roots x " + MSG_FANOUT + " parallel message catch events (fork/join), correlated concurrently",
                "lostUpdateCheck", "after the concurrent phase: every variable written by a command that reported success must be "
                        + "present; then commands that failed after their retries are re-run sequentially (drain, not counted as "
                        + "successes) so the instance can finish, and the join must have fired exactly once and the instance "
                        + "ended exactly once (runtime, ACT_HI_PROCINST, ACT_HI_ACTINST)",
                "wf2JoinStorm", WF2_INSTANCES + " WF-2 instances; both comment branches completed at the same instant (barrier per pair)",
                "retry", "max " + Retry.MAX_RETRIES + " retries, exponential back-off with full jitter, base 20 ms, cap 1 s (VI.1.1 rule 7)",
                "classification", "PG 40P01 deadlock / 40001 serialization / 55P03 lock timeout; Oracle ORA-00060 deadlock / "
                        + "ORA-08177 / ORA-30006,00054; SQL Server 1205 deadlock / 3960 / 1222; engine *OptimisticLockingException = OLE",
                "historyLevel", "as configured by the adapters: Flowable engine default (audit), Operaton FULL (set by the adapter)",
                "pass", "0 lost updates and 0 unrecovered failures after retries (raw OLE/deadlock counts are reported, not judged)"));
        for (String db : DB_ORDER) {
            if (!wanted.contains(db)) {
                continue;
            }
            runDatabase(db);
        }
    }

    private void runDatabase(String db) {
        Map<String, Object> dbInfo = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        Databases.Running running;
        try {
            log("starting database " + db);
            running = Databases.start(db);
        } catch (Throwable e) {
            dbInfo.put("status", "NOT_RUN");
            dbInfo.put("reason", "container did not start: " + e);
            dbInfo.put("secondsUntilFailure", secs(System.nanoTime() - t0));
            Results.merge("E6.json", HEADER, dbInfo, db, "_database");
            log("database " + db + " did not start: " + e);
            return;
        }
        try (running) {
            dbInfo.put("status", "STARTED");
            dbInfo.put("image", running.container().getDockerImageName());
            dbInfo.put("startupSeconds", secs(System.nanoTime() - t0));
            String[] st = Results.dockerStats(running.container().getContainerId());
            dbInfo.put("containerMemAfterStart", st == null ? null : st[1]);
            Results.merge("E6.json", HEADER, dbInfo, db, "_database");
            for (String engine : Engines.ALL) {
                log("E6 " + db + " / " + engine);
                Map<String, Object> res = runEngine(running, engine);
                st = Results.dockerStats(running.container().getContainerId());
                res.put("containerMemAfterRun", st == null ? null : st[1]);
                Results.merge("E6.json", HEADER, res, db, engine);
                log("E6 " + db + " / " + engine + " -> " + res.get("summary"));
            }
        } catch (Throwable e) {
            dbInfo.put("status", "FAILED_DURING_RUN");
            dbInfo.put("reason", stack(e));
            Results.merge("E6.json", HEADER, dbInfo, db, "_database");
        }
        log("stopped database " + db);
    }

    private Map<String, Object> runEngine(Databases.Running running, String engine) {
        Map<String, Object> res = new LinkedHashMap<>();
        String dialect = running.dialect();
        long t0 = System.nanoTime();
        try (DbSetup.Isolated iso = DbSetup.create(running, "e6_" + engine, THREADS + 8, Engines.autoCommit(engine));
             EngineAdapter a = Engines.create(engine, iso.dataSource(), iso.jdbcUrl())) {
            res.put("engine", a.engineId());
            res.put("historyLevel", Engines.historyLevel(a));
            res.put("database", iso.notes());
            res.put("engineStartupSeconds", secs(System.nanoTime() - t0));
            a.deploy(null, "e6-tasks", Map.of(LoadModels.TASKS_KEY + ".bpmn", LoadModels.fanout(TASK_FANOUT, false)));
            a.deploy(null, "e6-msgs", Map.of(LoadModels.MSGS_KEY + ".bpmn", LoadModels.fanout(MSG_FANOUT, true)));
            a.deploy(null, "wf2", Corpus.resources(engine, "wf2-file.bpmn"));

            Retry setup = new Retry(dialect);
            Map<String, Object> scenarios = new LinkedHashMap<>();
            res.put("adapterCorrelateMessageOnParallelBranch", correlateDiagnostic(a, engine));
            for (int threads : THREAD_COUNTS) {
                scenarios.put("oneRootUserTasks_" + threads + "threads",
                        fanoutScenario(a, dialect, engine, false, threads, setup));
                scenarios.put("oneRootMessages_" + threads + "threads",
                        fanoutScenario(a, dialect, engine, true, threads, setup));
            }
            scenarios.put("wf2JoinStorm_" + THREADS + "threads", wf2Scenario(a, dialect, engine, setup));
            res.put("scenarios", scenarios);
            res.put("setupAndVerifyCommands", Map.of("operations", setup.ops(), "unrecoveredFailures", setup.unrecovered(),
                    "OLE", setup.kind(Retry.Kind.OLE), "deadlocks", setup.kind(Retry.Kind.DEADLOCK)));
            res.put("summary", summarise(scenarios));
            res.put("sqlServerShimRewrites", DbSetup.SHIM_REWRITES.get());
            res.put("status", "RAN");
        } catch (Throwable e) {
            res.put("status", "FAILED");
            res.put("error", stack(e));
        }
        res.put("totalSeconds", secs(System.nanoTime() - t0));
        return res;
    }

    // ------------------------------------------------------------------ (a) one root, many commands

    private Map<String, Object> fanoutScenario(EngineAdapter a, String dialect, String engine, boolean messages,
                                               int threads, Retry setup) {
        int fanout = messages ? MSG_FANOUT : TASK_FANOUT;
        int roots = messages ? MSG_ROOTS : TASK_ROOTS;
        String key = messages ? LoadModels.MSGS_KEY : LoadModels.TASKS_KEY;
        Retry retry = new Retry(dialect);
        Verification v = new Verification();
        long wall = 0;
        long t0 = System.nanoTime();
        for (int root = 0; root < roots; root++) {
            String bk = (messages ? "e6m-" : "e6t-") + engine + "-" + threads + "-" + root;
            String pid = setup.call(() -> a.startProcess(null, key, bk, Map.of()));
            List<Op> ops = new ArrayList<>();
            if (messages) {
                for (int i = 0; i < fanout; i++) {
                    int idx = i;
                    ops.add(new Op(null, "m_" + i,
                            () -> correlate(a, engine, pid, bk, "e6msg_" + idx, Map.of("m_" + idx, idx))));
                }
            } else {
                List<TaskView> tasks = setup.call(() -> a.queryTasks(byInstance(pid)));
                if (tasks.size() != fanout) {
                    v.problems.add(pid + ": expected " + fanout + " branch tasks, found " + tasks.size());
                }
                int i = 0;
                for (TaskView t : tasks) {
                    int idx = i++;
                    String var = "r_" + t.taskDefinitionKey();
                    ops.add(new Op(null, var, () -> {
                        a.completeTask(t.id(), Map.of(var, idx));
                        return null;
                    }));
                }
            }
            wall += runConcurrently(ops, threads, retry, v);
            checkSuccessfulWrites(a, pid, ops, v, setup);
            drain(ops, v, setup);
            verifyAndFinish(a, pid, ops, LoadModels.AFTER, LoadModels.END, v, setup);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roots", roots);
        m.put("parallelBranchesPerRoot", fanout);
        m.put("threads", threads);
        m.putAll(retry.toMap());
        m.put("concurrentPhaseWallSeconds", secs(wall));
        m.put("scenarioWallSecondsInclSetup", secs(System.nanoTime() - t0));
        m.put("verification", v.toMap(roots));
        return m;
    }

    // ------------------------------------------------------------------ (b) WF-2 join storm

    private Map<String, Object> wf2Scenario(EngineAdapter a, String dialect, String engine, Retry setup) {
        Retry retry = new Retry(dialect);
        Verification v = new Verification();
        long t0 = System.nanoTime();
        List<String> pids = new ArrayList<>();
        List<Op> ops = new ArrayList<>();
        Map<String, List<Op>> pairs = new LinkedHashMap<>();
        for (int i = 0; i < WF2_INSTANCES; i++) {
            String bk = "e6wf2-" + engine + "-" + i;
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("initiatorSeat", "SEAT_INIT");
            vars.put("l1Group", "L1");
            vars.put("l2Group", "L2");
            vars.put("l3Group", "L3");
            vars.put("sectionAGroup", "SECTION_A");
            vars.put("sectionBGroup", "SECTION_B");
            vars.put("disposalGroup", "DISPOSAL");
            String pid = setup.call(() -> a.startProcess(null, "wf2-file", bk, vars));
            pids.add(pid);
            completeOne(a, pid, "wf2_l1Review", Map.of("l1Decision", "forward"), setup);
            completeOne(a, pid, "wf2_l2Review", Map.of("l2Decision", "forward"), setup);
            completeOne(a, pid, "wf2_l3Review", Map.of("l3Decision", "forward"), setup);
            List<TaskView> tasks = setup.call(() -> a.queryTasks(byInstance(pid)));
            TaskView ta = find(tasks, "wf2_commentsSectionA");
            TaskView tb = find(tasks, "wf2_commentsSectionB");
            if (ta == null || tb == null) {
                v.problems.add(pid + ": comment tasks not found: " + tasks);
                continue;
            }
            CyclicBarrier pair = new CyclicBarrier(2);
            Op opA = new Op(pair, "commentA", () -> {
                a.completeTask(ta.id(), Map.of("commentA", "A-" + bk));
                return null;
            });
            Op opB = new Op(pair, "commentB", () -> {
                a.completeTask(tb.id(), Map.of("commentB", "B-" + bk));
                return null;
            });
            ops.add(opA);
            ops.add(opB);
            pairs.put(pid, List.of(opA, opB));
        }
        double setupSeconds = secs(System.nanoTime() - t0);
        long wall = runConcurrently(ops, THREADS, retry, v);
        for (Map.Entry<String, List<Op>> e : pairs.entrySet()) {
            checkSuccessfulWrites(a, e.getKey(), e.getValue(), v, setup);
            drain(e.getValue(), v, setup);
            verifyAndFinish(a, e.getKey(), e.getValue(), "wf2_finalDisposal", "wf2_disposedEnd", v, setup);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instances", WF2_INSTANCES);
        m.put("threads", THREADS);
        m.putAll(retry.toMap());
        m.put("concurrentPhaseWallSeconds", secs(wall));
        m.put("setupSeconds", setupSeconds);
        m.put("scenarioWallSecondsInclSetup", secs(System.nanoTime() - t0));
        m.put("verification", v.toMap(WF2_INSTANCES));
        return m;
    }

    private static void completeOne(EngineAdapter a, String pid, String taskKey, Map<String, Object> vars, Retry setup) {
        setup.run(() -> {
            List<TaskView> tasks = a.queryTasks(new TaskQuery(null, null, null, pid, taskKey, 0, 0));
            if (tasks.size() != 1) {
                throw new IllegalStateException(pid + ": expected one " + taskKey + ", found " + tasks.size());
            }
            a.completeTask(tasks.get(0).id(), vars);
        });
    }

    // ------------------------------------------------------------------ verification (lost updates)

    static final class Verification {
        long lostSuccessfulWrites;
        long drainedOps;
        long drainFailures;
        long missingVariables;
        long joinNotExactlyOnce;
        long leftoverBranches;
        long endedNotExactlyOnce;
        long unrecoveredOps;
        long barrierTimeouts;
        final List<String> problems = new ArrayList<>();

        long lostUpdates() {
            return lostSuccessfulWrites + missingVariables + joinNotExactlyOnce + leftoverBranches + endedNotExactlyOnce;
        }

        Map<String, Object> toMap(int instances) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("instancesChecked", instances);
            m.put("lostUpdates", lostUpdates());
            m.put("lostSuccessfulWrites", lostSuccessfulWrites);
            m.put("unrecoveredOpsReRunSequentially", drainedOps);
            m.put("drainFailures", drainFailures);
            m.put("finalStateConclusive", drainFailures == 0);
            m.put("missingVariablesAfterDrain", missingVariables);
            m.put("joinNotExactlyOnce", joinNotExactlyOnce);
            m.put("leftoverBranchesOrSubscriptions", leftoverBranches);
            m.put("endedNotExactlyOnce", endedNotExactlyOnce);
            m.put("barrierTimeouts", barrierTimeouts);
            m.put("problems", problems.size() > 20 ? problems.subList(0, 20) : problems);
            return m;
        }
    }

    /** Lost update = a command reported success but its variable is not in the instance. */
    private void checkSuccessfulWrites(EngineAdapter a, String pid, List<Op> ops, Verification v, Retry setup) {
        EngineAdapter.InstanceView iv = setup.call(() -> a.getInstance(pid));
        for (Op op : ops) {
            if (op.ok().get() && (iv == null || !iv.variables().containsKey(op.var()))) {
                v.lostSuccessfulWrites++;
                v.problems.add(pid + ": LOST: successful write " + op.var() + " not present");
            }
        }
    }

    /** Re-runs the commands that failed after their retries, one by one, so the instance can finish. */
    private void drain(List<Op> ops, Verification v, Retry setup) {
        for (Op op : ops) {
            if (!op.ok().get()) {
                try {
                    setup.call(op.action());
                    op.drained().set(true);
                    v.drainedOps++;
                } catch (Retry.Unrecovered u) {
                    v.drainFailures++;
                    v.problems.add("drain failed for " + op.var() + ": " + Retry.rootMessage(u.getCause()));
                }
            }
        }
    }

    private void verifyAndFinish(EngineAdapter a, String pid, List<Op> ops, String afterKey, String endId,
                                 Verification v, Retry setup) {
        EngineAdapter.InstanceView iv = setup.call(() -> a.getInstance(pid));
        for (Op op : ops) {
            String var = op.var();
            if (!op.ok().get() && !op.drained().get()) {
                continue;
            }
            if (iv == null || !iv.variables().containsKey(var)) {
                v.missingVariables++;
                v.problems.add(pid + ": variable " + var + " missing");
            }
        }
        List<TaskView> rest = setup.call(() -> a.queryTasks(byInstance(pid)));
        List<TaskView> after = rest.stream().filter(t -> afterKey.equals(t.taskDefinitionKey())).toList();
        long others = rest.size() - after.size();
        long subscriptions = Engines.count(a, "select count(*) from ACT_RU_EVENT_SUBSCR where PROC_INST_ID_ = ?", pid);
        long afterHist = Engines.count(a, "select count(*) from ACT_HI_ACTINST where PROC_INST_ID_ = ? and ACT_ID_ = ?",
                pid, afterKey);
        if (after.size() != 1 || afterHist != 1) {
            v.joinNotExactlyOnce++;
            v.problems.add(pid + ": '" + afterKey + "' runtime tasks=" + after.size() + ", history=" + afterHist);
        }
        if (others != 0 || subscriptions != 0) {
            v.leftoverBranches += others + subscriptions;
            v.problems.add(pid + ": leftover tasks=" + others + ", subscriptions=" + subscriptions);
        }
        for (TaskView t : after) {
            setup.run(() -> a.completeTask(t.id(), Map.of()));
        }
        EngineAdapter.InstanceView end = setup.call(() -> a.getInstance(pid));
        long endHist = Engines.count(a, "select count(*) from ACT_HI_ACTINST where PROC_INST_ID_ = ? and ACT_ID_ = ?",
                pid, endId);
        long procEnded = Engines.count(a,
                "select count(*) from ACT_HI_PROCINST where PROC_INST_ID_ = ? and END_TIME_ is not null", pid);
        if (end == null || !end.ended() || endHist != 1 || procEnded != 1) {
            v.endedNotExactlyOnce++;
            v.problems.add(pid + ": ended=" + (end != null && end.ended()) + ", end events=" + endHist
                    + ", ended history rows=" + procEnded);
        }
    }

    // ------------------------------------------------------------------ concurrency driver

    record Op(CyclicBarrier pair, String var, Callable<?> action, java.util.concurrent.atomic.AtomicBoolean ok,
              java.util.concurrent.atomic.AtomicBoolean drained) {
        Op(CyclicBarrier pair, String var, Callable<?> action) {
            this(pair, var, action, new java.util.concurrent.atomic.AtomicBoolean(),
                    new java.util.concurrent.atomic.AtomicBoolean());
        }
    }

    /**
     * Message correlation to one parallel branch. Operaton: the adapter. Flowable: the adapter's correlateMessage
     * filters executions by processInstanceBusinessKey, which matches no child execution of a parallel branch (see
     * {@link #correlateDiagnostic}), so the harness triggers the subscription of that instance directly through
     * Flowable's RuntimeService (query + messageEventReceived in one engine command).
     */
    private static Object correlate(EngineAdapter a, String engine, String pid, String bk, String msg,
                                    Map<String, Object> vars) {
        if ("flowable".equals(engine)) {
            return Engines.flowableCorrelateInInstance(a, pid, msg, vars);
        }
        return a.correlateMessage(null, msg, bk, vars);
    }

    /** Does EngineAdapter.correlateMessage reach a message catch event inside a parallel branch? */
    private static Map<String, Object> correlateDiagnostic(EngineAdapter a, String engine) {
        Map<String, Object> m = new LinkedHashMap<>();
        String bk = "e6diag-" + engine;
        try {
            String pid = a.startProcess(null, LoadModels.MSGS_KEY, bk, Map.of());
            try {
                a.correlateMessage(null, "e6msg_0", bk, Map.of());
                m.put("works", true);
            } catch (RuntimeException e) {
                m.put("works", false);
                m.put("error", Retry.rootMessage(e));
            }
            a.cancelInstance(pid, "diagnostic");
        } catch (RuntimeException e) {
            m.put("diagnosticError", Retry.rootMessage(e));
        }
        m.put("pathUsedForE6", "flowable".equals(engine)
                ? "harness: Flowable RuntimeService execution query by processInstanceId + messageEventReceived, one command"
                : "EngineAdapter.correlateMessage");
        return m;
    }

    /** Releases {@value #THREADS} threads at once over the shared op queue; returns the wall time in nanos. */
    private long runConcurrently(List<Op> ops, int threads, Retry retry, Verification v) {
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        ConcurrentLinkedQueue<Op> queue = new ConcurrentLinkedQueue<>(ops);
        CountDownLatch go = new CountDownLatch(1);
        AtomicLong barrierTimeouts = new AtomicLong();
        AtomicLong unrecovered = new AtomicLong();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(ex.submit(() -> {
                go.await();
                Op op;
                while ((op = queue.poll()) != null) {
                    if (op.pair() != null) {
                        try {
                            op.pair().await(30, TimeUnit.SECONDS);
                        } catch (TimeoutException | BrokenBarrierException e) {
                            barrierTimeouts.incrementAndGet();
                        }
                    }
                    try {
                        retry.call(op.action());
                        op.ok().set(true);
                    } catch (Retry.Unrecovered u) {
                        unrecovered.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        long t0 = System.nanoTime();
        go.countDown();
        try {
            for (Future<?> f : fs) {
                f.get(30, TimeUnit.MINUTES);
            }
        } catch (Exception e) {
            throw new IllegalStateException("concurrent phase failed", e);
        } finally {
            ex.shutdownNow();
        }
        long wall = System.nanoTime() - t0;
        v.barrierTimeouts += barrierTimeouts.get();
        v.unrecoveredOps += unrecovered.get();
        return wall;
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summarise(Map<String, Object> scenarios) {
        long ops = 0, ok = 0, ole = 0, dl = 0, ser = 0, lt = 0, other = 0, retries = 0, unrec = 0, lost = 0;
        double wall = 0;
        for (Object o : scenarios.values()) {
            Map<String, Object> s = (Map<String, Object>) o;
            ops += (Long) s.get("operations");
            ok += (Long) s.get("successes");
            retries += (Long) s.get("retries");
            unrec += (Long) s.get("unrecoveredFailures");
            Map<String, Object> k = (Map<String, Object>) s.get("exceptionsByKind");
            ole += (Long) k.get("OLE");
            dl += (Long) k.get("DEADLOCK");
            ser += (Long) k.get("SERIALIZATION");
            lt += (Long) k.get("LOCK_TIMEOUT");
            other += (Long) k.get("OTHER");
            lost += (Long) ((Map<String, Object>) s.get("verification")).get("lostUpdates");
            wall += (Double) s.get("concurrentPhaseWallSeconds");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operations", ops);
        m.put("successes", ok);
        m.put("optimisticLockExceptions", ole);
        m.put("deadlocks", dl);
        m.put("serializationFailures", ser);
        m.put("lockTimeouts", lt);
        m.put("otherErrors", other);
        m.put("retries", retries);
        m.put("unrecoveredFailures", unrec);
        m.put("lostUpdates", lost);
        m.put("concurrentWallSeconds", Math.round(wall * 100) / 100.0);
        m.put("pass_T10_T33", lost == 0 && unrec == 0);
        return m;
    }

    private static TaskQuery byInstance(String pid) {
        return new TaskQuery(null, null, null, pid, null, 0, 0);
    }

    private static TaskView find(List<TaskView> tasks, String key) {
        return tasks.stream().filter(t -> key.equals(t.taskDefinitionKey())).findFirst().orElse(null);
    }

    static double secs(long nanos) {
        return Math.round(nanos / 1e7) / 100.0;
    }

    static String stack(Throwable e) {
        StringWriter sw = new StringWriter();
        sw.append("ROOT CAUSE: ").append(Retry.rootMessage(e)).append(System.lineSeparator());
        e.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 4000 ? s.substring(0, 4000) + "..." : s;
    }

    static void log(String s) {
        System.out.println("[E6 " + java.time.LocalTime.now().withNano(0) + "] " + s);
    }
}
