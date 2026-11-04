package org.sangam.spike.govc.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.api.EngineAdapter.TaskQuery;
import org.sangam.spike.api.EngineAdapter.TaskView;

/**
 * Scenario plumbing for E16-c / E17-c. Every scenario runs on a FRESH engine and database (so clocks, timer start
 * events and job queues never leak between scenarios) and never throws: its outcome is recorded.
 *
 * <p>Result per item: PASS (all checks hold, the engine provides the capability through its model support and its
 * public API, native API included); PASS-WITH-WORKAROUND (all checks hold, but engine-specific compensation was needed:
 * product-side arithmetic or bookkeeping the engine does not do, an extra engine-specific call, internal classes);
 * FAIL (a check failed or the scenario threw); UNSUPPORTED (the engine has no way to provide the capability).
 */
public abstract class GovBase {

    protected Native nat;
    protected EngineAdapter adapter;
    protected Instant now;
    private final List<Map<String, Object>> scenarios = new ArrayList<>();
    private String experiment;
    private String title;
    private String engineId = "?";

    @FunctionalInterface
    public interface Body {
        void run(Ctx ctx) throws Exception;
    }

    public static final class Ctx {
        final Map<String, Object> details = new LinkedHashMap<>();
        final List<Map<String, Object>> workarounds = new ArrayList<>();
        final List<String> checks = new ArrayList<>();
        final List<String> failedChecks = new ArrayList<>();
        final Set<String> features = new LinkedHashSet<>();
        final Set<String> variants = new LinkedHashSet<>();
        String unsupported;

        public void detail(String k, Object v) {
            details.put(k, v);
        }

        public void feature(String... f) {
            features.addAll(List.of(f));
        }

        /** Engine-specific compensation, with the number of code lines it costs. */
        public void workaround(String what, int lines) {
            for (Map<String, Object> w : workarounds) {
                if (what.equals(w.get("what"))) {
                    return;
                }
            }
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("what", what);
            w.put("lines", lines);
            workarounds.add(w);
        }

        public void unsupported(String why) {
            unsupported = why;
        }

        public boolean check(String what, boolean ok) {
            checks.add((ok ? "OK   " : "FAIL ") + what);
            if (!ok) {
                failedChecks.add(what);
            }
            return ok;
        }

        public void require(String what, boolean ok) {
            if (!check(what, ok)) {
                throw new IllegalStateException("required check failed: " + what);
            }
        }

        void variants(List<String> v) {
            variants.addAll(v);
        }
    }

    private Ctx current;

    protected void open(String experiment, String title) {
        this.experiment = experiment;
        this.title = title;
        this.nat = Engines.nat();
    }

    /** Deploys the given model files (vendor tokens expanded) and records their variant list on the scenario. */
    protected void deploy(String... models) {
        Models.Loaded l = Models.load(nat.dialect(), models);
        current.variants(l.variants());
        adapter.deploy(null, String.join("+", models), l.resources());
    }

    protected Map<String, Object> scenario(String id, String name, Body body) {
        Ctx ctx = new Ctx();
        current = ctx;
        String status;
        String error = null;
        long boot0 = System.nanoTime();
        long bootMs;
        long t0;
        NativeUse.begin();
        try {
            adapter = nat.create(Db.newDatabase(experiment.replace("-", "") + "_" + id.replace("-", "") + "_" + nat.engine() + "_"));
            engineId = adapter.engineId();
        } catch (Throwable t) {
            adapter = null;
        }
        bootMs = (System.nanoTime() - boot0) / 1_000_000;
        t0 = System.nanoTime();
        try {
            if (adapter == null) {
                throw new IllegalStateException("engine did not start");
            }
            clock(Instant.parse("2027-01-04T04:30:00Z"));
            body.run(ctx);
            status = ctx.failedChecks.isEmpty() ? (ctx.workarounds.isEmpty() ? "PASS" : "PASS-WITH-WORKAROUND") : "FAIL";
        } catch (Throwable t) {
            status = "FAIL";
            error = error(t);
        }
        long millis = (System.nanoTime() - t0) / 1_000_000;
        if (ctx.unsupported != null) {
            status = "UNSUPPORTED";
        }
        Set<String> apis = NativeUse.end();
        if (adapter != null) {
            try {
                nat.resetClock(adapter);
            } catch (RuntimeException ignored) {
                // best effort
            }
            try {
                adapter.close();
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        int wl = 0;
        for (Map<String, Object> w : ctx.workarounds) {
            wl += (Integer) w.get("lines");
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", id);
        s.put("name", name);
        s.put("result", status);
        s.put("unsupportedReason", ctx.unsupported);
        s.put("features", new ArrayList<>(ctx.features));
        s.put("nativeApis", new ArrayList<>(apis));
        s.put("variants", new ArrayList<>(ctx.variants));
        s.put("workarounds", ctx.workarounds);
        s.put("workaroundLines", wl);
        s.put("checks", ctx.checks);
        s.put("details", ctx.details);
        s.put("error", error);
        s.put("engineBootMs", bootMs);
        s.put("scenarioMs", millis);
        scenarios.add(s);
        System.out.printf("[%s] %-28s %s%s%n", nat.engine(), id, status, error == null ? "" : "  " + error);
        current = null;
        return s;
    }

    protected void write() {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String r : List.of("PASS", "PASS-WITH-WORKAROUND", "FAIL", "UNSUPPORTED")) {
            counts.put(r, scenarios.stream().filter(s -> r.equals(s.get("result"))).count());
        }
        Map<String, Object> frag = new LinkedHashMap<>();
        frag.put("engineId", engineId);
        frag.put("generatedAt", Instant.now().toString());
        frag.put("jdk", System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version"));
        frag.put("database", Db.description());
        frag.put("configuration", nat.configurationNotes());
        frag.put("summary", counts);
        frag.put("items", scenarios);
        try {
            Path fragDir = Path.of(System.getProperty("gov.fragmentDir", "target/gov-results")).resolve(Db.kind());
            Files.createDirectories(fragDir);
            Files.writeString(fragDir.resolve(experiment + "-" + nat.engine() + ".json"), Json.write(frag), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"experiment\": \"").append(experiment).append("\",\n");
            sb.append("  \"title\": \"").append(title.replace("\"", "'")).append("\",\n");
            sb.append("  \"engines\": {");
            boolean first = true;
            List<Path> frags;
            try (var st = Files.list(fragDir)) {
                frags = st.filter(p -> p.getFileName().toString().startsWith(experiment + "-")
                        && p.getFileName().toString().endsWith(".json")).sorted().toList();
            }
            for (Path f : frags) {
                String fn = f.getFileName().toString();
                String e = fn.substring(experiment.length() + 1, fn.length() - ".json".length());
                String body = Files.readString(f, StandardCharsets.UTF_8).replace("\n", "\n    ");
                sb.append(first ? "\n" : ",\n").append("    \"").append(e).append("\": ").append(body);
                first = false;
            }
            sb.append("\n  }\n}\n");
            Path out = Db.isPostgres()
                    ? Path.of(System.getProperty("gov.resultsDir", "../results"))
                    : fragDir; // development runs never touch results/
            Files.createDirectories(out);
            Files.writeString(out.resolve(experiment + ".json"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ helpers

    protected void clock(Instant t) {
        now = t;
        adapter.setClock(t);
    }

    protected void advance(Duration d) {
        clock(now.plus(d));
    }

    /** Moves the clock and fires what is due. */
    protected int advanceAndRun(Duration d) {
        advance(d);
        return adapter.executeDueJobs();
    }

    protected List<TaskView> tasks(String instanceId, String key) {
        return adapter.queryTasks(new TaskQuery(null, null, null, instanceId, key, 0, 0));
    }

    protected long countForGroup(String instanceId, String key, String group) {
        return adapter.countTasks(new TaskQuery(null, null, List.of(group), instanceId, key, 0, 0));
    }

    protected TaskView single(Ctx ctx, String instanceId, String key) {
        List<TaskView> t = tasks(instanceId, key);
        ctx.require("exactly one task " + key + " (found " + t.size() + ", active=" + active(instanceId) + ")", t.size() == 1);
        return t.get(0);
    }

    protected void complete(Ctx ctx, String instanceId, String key, String group, Map<String, Object> vars) {
        TaskView t = single(ctx, instanceId, key);
        if (group != null) {
            ctx.check(key + " offered to group " + group, countForGroup(instanceId, key, group) == 1);
        }
        adapter.claimTask(t.id(), "user-" + (group == null ? "x" : group));
        adapter.completeTask(t.id(), vars == null ? Map.of() : vars);
    }

    protected List<String> active(String instanceId) {
        EngineAdapter.InstanceView v = adapter.getInstance(instanceId);
        return v == null ? List.of() : v.activeActivityIds();
    }

    protected boolean ended(String instanceId) {
        EngineAdapter.InstanceView v = adapter.getInstance(instanceId);
        return v != null && v.ended();
    }

    protected Object var(String instanceId, String name) {
        EngineAdapter.InstanceView v = adapter.getInstance(instanceId);
        return v == null ? null : v.variables().get(name);
    }

    protected boolean visited(String instanceId, String activityId) {
        return nat.historicActivityIds(adapter, instanceId).contains(activityId);
    }

    /** Fetches and completes the external work of {@code instanceId} on {@code topic} (others are released). */
    protected ExternalWork fetchOne(Ctx ctx, String instanceId, String topic) {
        List<ExternalWork> got = adapter.fetchAndLock("w-" + topic, topic, 100, Duration.ofMinutes(5));
        ExternalWork mine = null;
        for (ExternalWork w : got) {
            if ((instanceId == null || instanceId.equals(w.processInstanceId())) && mine == null) {
                mine = w;
            } else {
                adapter.unlockExternal(w.id(), "w-" + topic, w.lockVersion());
            }
        }
        ctx.require("external work on topic " + topic + " fetched", mine != null);
        return mine;
    }

    protected void completeWork(ExternalWork w, Map<String, Object> vars) {
        adapter.completeExternal(w.id(), "w-" + w.topic(), w.lockVersion(), vars);
    }

    protected void work(Ctx ctx, String instanceId, String topic, Map<String, Object> vars) {
        completeWork(fetchOne(ctx, instanceId, topic), vars);
    }

    protected boolean throwsOn(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    protected static boolean waitFor(BooleanSupplier s) {
        return s.getAsBoolean();
    }

    protected static Map<String, Object> vars(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    protected static java.util.Date date(Instant i) {
        return java.util.Date.from(i);
    }

    // ------------------------------------------------------------------ timers / history / numbers

    protected Native.Timer timerAt(String pid, String activityId) {
        for (Native.Timer t : nat.timers(adapter, pid)) {
            if (activityId.equals(t.activityId())) {
                return t;
            }
        }
        return null;
    }

    protected boolean dueIs(Native.Timer t, Instant expected) {
        return t != null && t.due() != null && Math.abs(Duration.between(t.due(), expected).toSeconds()) <= 1;
    }

    protected long countVisits(String pid, String activityId) {
        return nat.historicActivityIds(adapter, pid).stream().filter(activityId::equals).count();
    }

    protected static long num(Object o) {
        return o instanceof Number n ? n.longValue() : Long.MIN_VALUE;
    }

    /** Delivers a message to the multi-instance branch whose loopCounter is {@code index} (local variables there). */
    protected boolean deliverToBranch(String pid, String messageName, int index, Map<String, Object> localVars) {
        for (String ex : nat.messageSubscriptions(adapter, pid, messageName)) {
            Object lc = nat.getVariable(adapter, ex, "loopCounter");
            if (lc instanceof Number n && n.intValue() == index) {
                String scope = nat.executionWithLocalVariable(adapter, pid, "loopCounter", index);
                nat.deliverMessage(adapter, ex, messageName, scope, localVars);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ product SQL (engine transaction)

    protected void sql(String s) {
        adapter.inEngineTransaction(c -> {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute(s);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(s, e);
            }
            return null;
        });
    }

    protected long count(String q) {
        return adapter.inEngineTransaction(c -> {
            try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(q)) {
                return rs.next() ? rs.getLong(1) : -1L;
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(q, e);
            }
        });
    }

    protected static List<Integer> queryInts(java.sql.Connection c, String q) {
        List<Integer> out = new ArrayList<>();
        try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(q)) {
            while (rs.next()) {
                out.add(rs.getInt(1));
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(q, e);
        }
        return java.util.Collections.unmodifiableList(out);
    }

    public static String error(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int depth = 0;
        while (c != null && depth < 4) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            String msg = c.getMessage();
            if (msg != null && msg.length() > 400) {
                msg = msg.substring(0, 400) + "...";
            }
            sb.append(c.getClass().getName()).append(": ").append(msg);
            if (c.getCause() == c) {
                break;
            }
            c = c.getCause();
            depth++;
        }
        StackTraceElement[] st = t.getStackTrace();
        for (StackTraceElement e : st) {
            if (e.getClassName().startsWith("org.sangam.spike.govc")) {
                sb.append(" @ ").append(e.getFileName()).append(':').append(e.getLineNumber());
                break;
            }
        }
        return sb.toString();
    }
}
