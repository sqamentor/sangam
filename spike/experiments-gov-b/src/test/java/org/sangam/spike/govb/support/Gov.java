package org.sangam.spike.govb.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import com.zaxxer.hikari.HikariDataSource;

import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.api.EngineAdapter.TaskQuery;
import org.sangam.spike.api.EngineAdapter.TaskView;
import org.sangam.spike.flowable.FlowableEngineAdapter;
import org.sangam.spike.operaton.OperatonEngineAdapter;

/** One engine on one fresh database, plus the helpers every pattern uses. */
public final class Gov implements AutoCloseable {

    private final String engine;
    private final HikariDataSource ds;
    private final EngineAdapter adapter;
    private final Native nat;

    private Gov(String engine, HikariDataSource ds, EngineAdapter adapter, Native nat) {
        this.engine = engine;
        this.ds = ds;
        this.adapter = adapter;
        this.nat = nat;
    }

    public static Gov open(String name) {
        HikariDataSource ds = Env.newDatabase(name + "_" + Env.engine());
        EngineAdapter a = Env.newAdapter(ds);
        Native n = a instanceof OperatonEngineAdapter o ? new NativeOperaton(o.processEngine())
                : new NativeFlowable((FlowableEngineAdapter) a);
        return new Gov(Env.engine(), ds, a, n);
    }

    public String engine() {
        return engine;
    }

    public boolean operaton() {
        return "operaton".equals(engine);
    }

    public EngineAdapter adapter() {
        return adapter;
    }

    public Native nat() {
        return nat;
    }

    public HikariDataSource dataSource() {
        return ds;
    }

    /** Deploys rendered models to one tenant and records the variant elements on the current record. */
    public String deploy(String tenant, String... files) {
        Map<String, byte[]> res = new LinkedHashMap<>();
        Rec r = Rec.current();
        for (String f : files) {
            Models.Rendered m = Models.render(engine, f);
            res.put(f, m.bytes());
            if (r != null) {
                m.variants().forEach(r::variant);
            }
        }
        return adapter.deploy(tenant, String.join("+", files), res);
    }

    public String start(String tenant, String key, String bk, Map<String, Object> vars) {
        return adapter.startProcess(tenant, key, bk, vars);
    }

    public List<TaskView> tasksOf(String instanceId) {
        return adapter.queryTasks(new TaskQuery(null, null, List.of(), instanceId, null, 0, 0));
    }

    public List<TaskView> tasks(String tenant, String instanceId, String key) {
        return adapter.queryTasks(new TaskQuery(tenant, null, List.of(), instanceId, key, 0, 0));
    }

    public long count(String tenant, String key) {
        return adapter.countTasks(new TaskQuery(tenant, null, List.of(), null, key, 0, 0));
    }

    public List<TaskView> forGroups(String tenant, String... groups) {
        return adapter.queryTasks(new TaskQuery(tenant, null, Arrays.asList(groups), null, null, 0, 0));
    }

    public List<TaskView> forUser(String tenant, String user) {
        return adapter.queryTasks(new TaskQuery(tenant, user, List.of(), null, null, 0, 0));
    }

    /** The single open task {@code key} of an instance; fails when there is not exactly one. */
    public TaskView task(String instanceId, String key) {
        List<TaskView> t = tasks(null, instanceId, key);
        if (t.size() != 1) {
            throw new AssertionError("expected 1 task '" + key + "' in " + instanceId + ", found " + t.size()
                    + " (open: " + tasksOf(instanceId).stream().map(TaskView::taskDefinitionKey).toList() + ")");
        }
        return t.get(0);
    }

    public void complete(String instanceId, String key, Map<String, Object> vars) {
        adapter.completeTask(task(instanceId, key).id(), vars);
    }

    public void completeAs(String instanceId, String key, String user, Map<String, Object> vars) {
        TaskView t = task(instanceId, key);
        adapter.claimTask(t.id(), user);
        adapter.completeTask(t.id(), vars);
    }

    /**
     * Correlates through the adapter. If the adapter finds no waiting execution (Flowable adapter: its business-key
     * filter matches only executions that carry the key, not child executions of a parallel branch or an event
     * gateway), the engine's own correlation over child executions is used and recorded as a workaround.
     */
    public String correlate(String tenant, String message, String bk, Map<String, Object> vars) {
        try {
            return adapter.correlateMessage(tenant, message, bk, vars);
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).contains("matched 0")) {
                throw e;
            }
            Rec r = Rec.current();
            if (r != null && r.workarounds.stream().noneMatch(w -> String.valueOf(w.get("what")).startsWith("CORR"))) {
                r.workaround("CORR: adapter correlateMessage matched 0 executions (business key only on the instance"
                        + " row); test calls the engine's correlation over child executions (Native.correlateAnyExecution)",
                        14);
            }
            return nat.correlateAnyExecution(tenant, message, bk, vars);
        }
    }

    public void cancelQuietly(String id) {
        try {
            adapter.cancelInstance(id, "test cleanup");
        } catch (RuntimeException ignored) {
            // already ended
        }
    }

    public int jobs() {
        return adapter.executeDueJobs();
    }

    public void clock(Instant t) {
        adapter.setClock(t);
    }

    public EngineAdapter.InstanceView instance(String id) {
        return adapter.getInstance(id);
    }

    public boolean ended(String id) {
        EngineAdapter.InstanceView v = adapter.getInstance(id);
        return v != null && v.ended();
    }

    public Object var(String id, String name) {
        EngineAdapter.InstanceView v = adapter.getInstance(id);
        return v == null ? null : v.variables().get(name);
    }

    /**
     * Runs {@code n} items on {@link Env#THREADS} threads, retrying an item up to 5 times on an engine
     * optimistic-locking conflict; returns the number of retries.
     */
    public long parallel(int n, IntConsumer item) {
        AtomicLong retries = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(Env.THREADS);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit(() -> {
                    for (int attempt = 1; ; attempt++) {
                        try {
                            item.accept(k);
                            return;
                        } catch (RuntimeException e) {
                            if (attempt >= 5 || !isConflict(e)) {
                                throw e;
                            }
                            retries.incrementAndGet();
                        }
                    }
                }));
            }
            for (Future<?> f : fs) {
                f.get();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
        } finally {
            pool.shutdownNow();
        }
        return retries.get();
    }

    static boolean isConflict(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String n = t.getClass().getSimpleName();
            if (n.contains("OptimisticLocking") || n.contains("Deadlock")) {
                return true;
            }
        }
        return false;
    }

    public static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @Override
    public void close() {
        try {
            adapter.close();
        } finally {
            ds.close();
        }
    }
}
