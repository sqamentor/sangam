package org.sangam.spike.load;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.api.EngineAdapter.TaskQuery;
import org.sangam.spike.api.EngineAdapter.TaskView;
import org.sangam.spike.corpus.Corpus;
import org.sangam.spike.sim.db.Databases;

/**
 * E9 (SANGAM.md XI.4, T31): functional-scale benchmark on PostgreSQL, one engine per JVM ({@code -De9.engine}).
 *
 * <p><b>FUNCTIONAL SCALE on an 8-CPU dev machine with the database co-located in Docker. NOT the reference scale of
 * R22.</b>
 *
 * <ul>
 *   <li>WF-R1: start → one external work item (fetch, lock, complete by an in-process worker) → end.</li>
 *   <li>WF-R5-lite: WF-1 (corpus) with the DMN delegation-of-powers decision, its two user tasks (checker review,
 *       approve) completed by a synthetic actor, and the payment external work completed by an in-process worker.</li>
 * </ul>
 * Fixed thread counts for both engines: {@value #STARTERS} starters, {@value #ACTORS} actors (R5 only),
 * one fetcher (fetch up to {@value #FETCH_MAX}) feeding {@value #WORKERS} completer threads; at most {@value #IN_FLIGHT} open instances. Warm-up,
 * then a steady window; only the steady window is measured. Completed instances/s counts instances whose last
 * command (the external completion that ends the instance) committed inside the window.
 */
class E9BenchmarkRun {

    static final int STARTERS = 4;
    static final int ACTORS = 8;
    static final int WORKERS = 4;  // completer threads behind ONE fetcher
    static final int FETCH_MAX = 10;
    static final int IN_FLIGHT = 200;
    static final int POOL = 24;

    private static final Map<String, Object> HEADER = new LinkedHashMap<>();

    @Test
    void e9() {
        String engine = System.getProperty("e9.engine", "flowable");
        int warmup = Integer.getInteger("e9.warmupSeconds", 20);
        int steady = Integer.getInteger("e9.steadySeconds", 60);
        List<String> levels = List.of(System.getProperty("e9.levels", "full,audit").split(","));
        List<String> workflows = List.of(System.getProperty("e9.workflows", "r1,r5").split(","));
        HEADER.put("experiment", "E9 functional-scale benchmark (SANGAM.md XI.4; T31)");
        HEADER.put("label", "FUNCTIONAL SCALE on an 8-CPU dev machine, with the DB co-located in Docker. "
                + "NOT the reference scale of R22.");
        HEADER.put("design", Map.of(
                "threads", Map.of("starters", STARTERS, "actorsR5", ACTORS, "fetcher", 1, "completers", WORKERS,
                        "fetchMaxItems", FETCH_MAX, "maxOpenInstances", IN_FLIGHT, "dbPool", POOL),
                "warmupSeconds", warmup, "steadySeconds", steady,
                "database", "PostgreSQL 17 (Testcontainers postgres:17), one fresh database per (engine, workflow, level)",
                "historyLevels", "each workflow runs at every listed level on both engines; the level is set on the "
                        + "built engine by a test hook and verified by counting ACT_HI_DETAIL rows (FULL only)",
                "notInPath", "product layer (authorization filter, outbox, idempotency, audit chain) is NOT in the measured "
                        + "path: these are adapter (engine) numbers only",
                "metric", "completed instances/s = instances whose final command committed in the steady window"));
        HEADER.put("environment", Results.environment());

        try (Databases.Running pg = Databases.start("postgres")) {
            for (String level : levels) {
                for (String wf : workflows) {
                    log(engine + " " + wf + " level=" + level);
                    Map<String, Object> r;
                    try {
                        r = runOne(pg, engine, wf, level, warmup, steady);
                    } catch (Throwable e) {
                        r = new LinkedHashMap<>();
                        r.put("status", "FAILED");
                        r.put("error", E6ConcurrencyRun.stack(e));
                    }
                    Results.merge("E9.json", HEADER, r, wf, level, engine);
                    log(engine + " " + wf + " level=" + level + " -> " + r.get("summary"));
                }
            }
        }
    }

    private Map<String, Object> runOne(Databases.Running pg, String engine, String wf, String level, int warmup,
                                       int steady) throws Exception {
        Map<String, Object> res = new LinkedHashMap<>();
        boolean r5 = "r5".equals(wf);
        try (DbSetup.Isolated iso = DbSetup.create(pg, "e9_" + engine + "_" + wf + "_" + level, POOL,
                Engines.autoCommit(engine));
             EngineAdapter a = Engines.create(engine, iso.dataSource(), iso.jdbcUrl())) {
            res.put("engine", a.engineId());
            res.put("historyLevelConfiguredByAdapter", Engines.historyLevel(a));
            Engines.setHistoryLevel(a, level);
            res.put("historyLevelUsed", Engines.historyLevel(a));
            String processKey;
            String topic;
            if (r5) {
                a.deploy(null, "wf1", Corpus.resources(engine, "wf1-bill.bpmn", "wf1-delegation-of-powers.dmn"));
                processKey = "wf1-bill";
                topic = "payment";
            } else {
                a.deploy(null, "r1", Map.of(LoadModels.R1_KEY + ".bpmn", LoadModels.r1(engine)));
                processKey = LoadModels.R1_KEY;
                topic = "r1";
            }

            // Flowable continues the process after an external-worker completion in an async job
            // (external-worker-complete); the adapter builds the engine with the async executor off, so E9 starts it.
            res.put("asyncExecutor", Engines.startAsyncExecutorIfFlowable(a));

            Run run = new Run(a, iso.dialect(), r5, processKey, topic);
            run.startThreads();
            Thread.sleep(warmup * 1000L);
            // steady window
            long ended0 = endedCount(a);
            run.window(true);
            Results.CpuSampler cpu = new Results.CpuSampler(pg.container().getContainerId());
            long w0 = System.nanoTime();
            Thread.sleep(steady * 1000L);
            run.window(false);
            long windowNanos = System.nanoTime() - w0;
            long ended1 = endedCount(a);
            Map<String, Object> cpuMap = cpu.stop();
            // drain
            run.stopStarting();
            long drainUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (endedCount(a) < run.startedTotal.get() && System.nanoTime() < drainUntil) {
                Thread.sleep(200);
            }
            run.stopAll();

            double seconds = windowNanos / 1e9;
            long completedInWindow = ended1 - ended0;
            long commandsInWindow = run.commandsInWindow.get();
            long failedInWindow = run.failedInWindow.get();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("completedInstancesPerSecond", round2(completedInWindow / seconds));
            summary.put("startedInstancesPerSecond", round2(run.startedInWindow.get() / seconds));
            summary.put("commandsPerSecond", round2(commandsInWindow / seconds));
            @SuppressWarnings("unchecked")
            Map<String, Object> all = (Map<String, Object>) run.latencies.toMap().get("ALL_COMMANDS");
            summary.put("commandLatency_p50_ms", all.get("p50_ms"));
            summary.put("commandLatency_p95_ms", all.get("p95_ms"));
            summary.put("commandLatency_p99_ms", all.get("p99_ms"));
            summary.put("errorRate", commandsInWindow == 0 ? 0 : (double) failedInWindow / commandsInWindow);
            summary.put("jvmCpuPercentOfMachine", cpuMap.get("jvmProcessCpuPercentOfMachine"));
            summary.put("dbContainerCpuPercentOneCore", cpuMap.get("dbContainerCpuPercentAvg_dockerStats"));
            res.put("summary", summary);
            res.put("steadyWindowSeconds", round2(seconds));
            res.put("warmupSeconds", warmup);
            res.put("completedInWindow", completedInWindow);
            res.put("startedInWindow", run.startedInWindow.get());
            res.put("commandsInWindow", commandsInWindow);
            res.put("unrecoveredCommandsInWindow", failedInWindow);
            res.put("commandLatency", run.latencies.toMap());
            res.put("endToEndInstanceLatency_startToEnd_fromHistory", e2e(a, ended0, ended1));
            res.put("externalCompletionsInWindow", run.completedInWindow.get());
            res.put("retry", run.retry.toMap());
            res.put("cpu", cpuMap);
            res.put("emptyFetches", run.emptyFetches.get());
            res.put("actorProtocolErrors", run.actorProtocolErrors.get());
            res.put("openInstancesAtEnd", run.open());
            // verification: completions seen by the driver == ended instances in history; history level effect
            long ended = Engines.count(a, "select count(*) from ACT_HI_PROCINST where END_TIME_ is not null");
            long startedTotal = Engines.count(a, "select count(*) from ACT_HI_PROCINST");
            long details = Engines.count(a, "select count(*) from ACT_HI_DETAIL");
            Map<String, Object> ver = new LinkedHashMap<>();
            ver.put("instancesStartedTotal", run.startedTotal.get());
            ver.put("externalCompletionsTotalByDriver", run.completedTotal.get());
            ver.put("historicInstances", startedTotal);
            ver.put("historicInstancesEnded", ended);
            ver.put("allStartedInstancesEndedAfterDrain", ended == run.startedTotal.get());
            ver.put("completionsMatchHistory", ended == run.completedTotal.get());
            ver.put("actHiDetailRows", details);
            ver.put("historyLevelEffective", details > 0 ? "full" : "audit-or-lower");
            res.put("verification", ver);
            res.put("status", "RAN");
        }
        return res;
    }

    static long endedCount(EngineAdapter a) {
        return Engines.count(a, "select count(*) from ACT_HI_PROCINST where END_TIME_ is not null");
    }

    /** Start-to-end latency of the instances that ended in the steady window (by end order), from ACT_HI_PROCINST. */
    static Map<String, Object> e2e(EngineAdapter a, long from, long to) {
        List<Long> micros = a.inEngineTransaction(c -> {
            List<Long> out = new ArrayList<>();
            try (var ps = c.prepareStatement("select START_TIME_, END_TIME_ from ACT_HI_PROCINST "
                    + "where END_TIME_ is not null order by END_TIME_");
                 var rs = ps.executeQuery()) {
                long i = 0;
                while (rs.next()) {
                    if (i >= from && i < to) {
                        out.add((rs.getTimestamp(2).getTime() - rs.getTimestamp(1).getTime()) * 1000);
                    }
                    i++;
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            return out;
        });
        return Results.percentiles(micros);
    }

    /** The driver threads for one run. */
    static final class Run {
        final EngineAdapter a;
        final boolean r5;
        final String processKey;
        final String topic;
        final Retry retry;
        final Results.Latencies latencies = new Results.Latencies();
        final Semaphore inFlight = new Semaphore(IN_FLIGHT);
        final LinkedBlockingQueue<String> actorQueue = new LinkedBlockingQueue<>();
        final Map<String, Long> startNanos = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Long> e2eMicros = new ConcurrentLinkedQueue<>();
        final AtomicLong completedInWindow = new AtomicLong();
        final AtomicLong startedInWindow = new AtomicLong();
        final AtomicLong commandsInWindow = new AtomicLong();
        final AtomicLong failedInWindow = new AtomicLong();
        final AtomicLong completedTotal = new AtomicLong();
        final AtomicLong startedTotal = new AtomicLong();
        final AtomicLong emptyFetches = new AtomicLong();
        final AtomicLong actorProtocolErrors = new AtomicLong();
        final List<Thread> threads = new ArrayList<>();
        volatile boolean measuring;
        volatile boolean starting = true;
        volatile boolean running = true;
        final AtomicLong seq = new AtomicLong();

        Run(EngineAdapter a, String dialect, boolean r5, String processKey, String topic) {
            this.a = a;
            this.r5 = r5;
            this.processKey = processKey;
            this.topic = topic;
            this.retry = new Retry(dialect);
        }

        long open() {
            return IN_FLIGHT - inFlight.availablePermits();
        }

        void window(boolean on) {
            measuring = on;
            latencies.recording(on);
        }

        void stopStarting() {
            starting = false;
        }

        void stopAll() throws InterruptedException {
            running = false;
            starting = false;
            for (Thread t : threads) {
                t.join(10_000);
            }
        }

        /** Runs one command with retry, timing it (including retries) and counting it in the window. */
        <T> T cmd(String name, java.util.concurrent.Callable<T> c) {
            long t0 = System.nanoTime();
            boolean inWindow = measuring;
            try {
                T r = retry.call(c);
                long t1 = System.nanoTime();
                if (inWindow && measuring) {
                    latencies.record(name, t1 - t0);
                    commandsInWindow.incrementAndGet();
                }
                return r;
            } catch (Retry.Unrecovered u) {
                if (inWindow && measuring) {
                    commandsInWindow.incrementAndGet();
                    failedInWindow.incrementAndGet();
                }
                throw u;
            }
        }

        /** Releases in-flight permits as instances END (history), not when the worker's command returns. */
        void monitor() {
            long released = 0;
            while (running) {
                try {
                    long n = endedCount(a);
                    if (n > released) {
                        inFlight.release((int) (n - released));
                        released = n;
                    }
                } catch (RuntimeException e) {
                    // transient; try again
                }
                Results.sleep(200);
            }
        }

        void startThreads() {
            threads.add(Thread.ofPlatform().name("monitor").start(this::monitor));
            for (int i = 0; i < STARTERS; i++) {
                threads.add(Thread.ofPlatform().name("starter-" + i).start(this::starter));
            }
            if (r5) {
                for (int i = 0; i < ACTORS; i++) {
                    threads.add(Thread.ofPlatform().name("actor-" + i).start(this::actor));
                }
            }
            // One fetcher (one worker identity, one fetchAndLock at a time) hands locked work to WORKERS completer
            // threads. Concurrent fetchAndLock calls are avoided on purpose: on Operaton they race in the adapter's
            // SPIKE_LEASE grant (see the report, finding F-E9-2).
            threads.add(Thread.ofPlatform().name("fetcher").start(() -> fetcher(WORKER_ID)));
            for (int i = 0; i < WORKERS; i++) {
                threads.add(Thread.ofPlatform().name("completer-" + i).start(() -> completer(WORKER_ID)));
            }
        }

        static final String WORKER_ID = "worker-0";
        final LinkedBlockingQueue<ExternalWork> locked = new LinkedBlockingQueue<>();

        void fetcher(String workerId) {
            while (running) {
                if (locked.size() >= FETCH_MAX * 2) {
                    Results.sleep(2);
                    continue;
                }
                List<ExternalWork> work;
                try {
                    work = cmd("fetchAndLock", () -> a.fetchAndLock(workerId, topic, FETCH_MAX, Duration.ofSeconds(60)));
                } catch (RuntimeException e) {
                    continue;
                }
                if (work.isEmpty()) {
                    emptyFetches.incrementAndGet();
                    Results.sleep(5);
                    continue;
                }
                locked.addAll(work);
            }
        }

        void completer(String workerId) {
            while (running) {
                ExternalWork w;
                try {
                    w = locked.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                if (w == null) {
                    continue;
                }
                try {
                    cmd("completeExternal", () -> {
                        a.completeExternal(w.id(), workerId, w.lockVersion(), Map.of("paid", true));
                        return null;
                    });
                    completedTotal.incrementAndGet();
                    if (measuring) {
                        completedInWindow.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    // counted by cmd()
                }
            }
        }

        private static final String PAYLOAD = "x".repeat(64);
        private static final int[] AMOUNTS = {50_000, 500_000, 5_000_000, 20_000_000};

        void starter() {
            while (starting) {
                try {
                    if (!inFlight.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                        continue;
                    }
                } catch (InterruptedException e) {
                    return;
                }
                long n = seq.incrementAndGet();
                Map<String, Object> vars = new LinkedHashMap<>();
                if (r5) {
                    vars.put("checkerGroup", "CHECKER");
                    vars.put("initiatorSeat", "SEAT_INIT");
                    vars.put("amount", AMOUNTS[(int) (n % AMOUNTS.length)]);
                    vars.put("budgetHead", "2054-GENERAL");
                    for (int i = 0; i < 10; i++) {
                        vars.put("p" + i, PAYLOAD);
                    }
                } else {
                    vars.put("n", n);
                }
                long t0 = System.nanoTime();
                try {
                    String pid = cmd("startProcess", () -> a.startProcess(null, processKey, "e9-" + n, vars));
                    startNanos.put(pid, t0);
                    startedTotal.incrementAndGet();
                    if (measuring) {
                        startedInWindow.incrementAndGet();
                    }
                    if (r5) {
                        actorQueue.add(pid);
                    }
                } catch (RuntimeException e) {
                    inFlight.release();
                }
            }
        }

        void actor() {
            while (running) {
                String pid;
                try {
                    pid = actorQueue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                if (pid == null) {
                    continue;
                }
                try {
                    TaskView checker = single(cmd("queryTasks", () -> a.queryTasks(
                            new TaskQuery(null, null, List.of("CHECKER"), pid, null, 0, 10))));
                    cmd("completeTask", () -> {
                        a.completeTask(checker.id(), Map.of("checkerDecision", "forward"));
                        return null;
                    });
                    TaskView approve = single(cmd("queryTasks", () -> a.queryTasks(
                            new TaskQuery(null, null, null, pid, "wf1_approve", 0, 10))));
                    cmd("completeTask", () -> {
                        a.completeTask(approve.id(), Map.of("approverDecision", "approve"));
                        return null;
                    });
                } catch (RuntimeException e) {
                    // engine errors are counted by cmd(); the instance stays open
                    if (!(e instanceof Retry.Unrecovered)) {
                        actorProtocolErrors.incrementAndGet();
                    }
                }
            }
        }

        private static TaskView single(List<TaskView> tasks) {
            if (tasks.size() != 1) {
                throw new IllegalStateException("expected one task, found " + tasks.size());
            }
            return tasks.get(0);
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static void log(String s) {
        System.out.println("[E9 " + java.time.LocalTime.now().withNano(0) + "] " + s);
    }

}
