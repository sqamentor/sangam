package org.sangam.spike.it.t28;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.api.EngineAdapter.ExternalWork;
import org.sangam.spike.corpus.Corpus;
import org.sangam.spike.it.support.EngineKind;
import org.sangam.spike.it.support.Results;
import org.sangam.spike.it.support.Workflows;

/**
 * T28 upgrade with instances in flight, NEW-version phase (Part III §16 T28; R32). Two JVMs, one database:
 *
 * <ol>
 *   <li><b>Old phase</b> ({@code experiments-integration/t28-old}, a standalone Maven project with the OLD engine on its
 *       class path: Operaton 2.1.6 or Flowable 7.2.0): creates the schema with the old engine, deploys WF-1, leaves three
 *       instances in flight (at checker review, at approval, at the payment external task) and writes a state file.</li>
 *   <li><b>New phase</b> (this class, Operaton 2.2.0 / Flowable 8.0.0 through the spike adapters): upgrades the schema
 *       with the engine's own mechanism and finishes every in-flight instance on the new version, plus one new start.</li>
 * </ol>
 * Upgrade mechanism, measured rather than assumed: first the new engine is opened with {@code databaseSchemaUpdate=true}
 * (what both adapters configure). If the schema version recorded in {@code ACT_GE_PROPERTY} did not change, the SQL
 * upgrade scripts shipped inside the new engine jar ({@code db/upgrade/*postgres*}) are applied by hand and that is
 * recorded as a finding ("upgrade needs manual SQL"); nothing is skipped.
 *
 * <p>Runs only with {@code -Dt28.stateFile=<file written by the old phase>} (the run queue, step Q7, sets it), so a plain
 * {@code verify} never runs it. PostgreSQL only (the queue starts a plain postgres:17-alpine container for it). Result:
 * {@code results/T28.json}, one entry per engine and version pair.
 */
@EnabledIfSystemProperty(named = "t28.stateFile", matches = ".+")
class T28UpgradeTest {

    @Test
    void upgradeWithInstancesInFlight() throws Exception {
        canonicaliseDefaultTimeZone();
        Properties state = new Properties();
        try (Reader r = Files.newBufferedReader(Path.of(System.getProperty("t28.stateFile")), StandardCharsets.UTF_8)) {
            state.load(r);
        }
        EngineKind kind = EngineKind.valueOf(state.getProperty("engine").toUpperCase(Locale.ROOT));
        String url = state.getProperty("jdbcUrl");
        String user = state.getProperty("user");
        String password = state.getProperty("password");
        String tenant = state.getProperty("tenant");
        String oldVersion = state.getProperty("oldVersion");

        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> notes = new LinkedHashMap<>();
        List<Map<String, Object>> scenarios = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        m.put("oldVersion", oldVersion);
        m.put("newVersion", kind.label());
        Map<Object, Object> oldPhase = new java.util.TreeMap<>(state);
        oldPhase.remove("password");
        m.put("oldPhase", oldPhase);
        Map<String, String> before = schemaProperties(url, user, password);
        m.put("schemaBefore", before);

        Throwable failure = null;
        EngineAdapter engine = null;
        try {
            // 1. the engine's automatic mechanism (databaseSchemaUpdate=true, as both adapters configure it)
            String upgradePath;
            try {
                engine = kind.create(url, user, password);
                upgradePath = "databaseSchemaUpdate=true on engine start";
            } catch (RuntimeException e) {
                findings.add("new engine did not start on the old schema with databaseSchemaUpdate=true: " + rootMessage(e));
                upgradePath = "engine start failed: " + rootMessage(e);
            }
            Map<String, String> afterAuto = schemaProperties(url, user, password);
            m.put("schemaAfterEngineStart", afterAuto);
            boolean versionMoved = !String.valueOf(before.get(versionKey(kind))).equals(String.valueOf(afterAuto.get(versionKey(kind))));
            List<String> scripts = upgradeScripts(kind);
            m.put("upgradeScriptsInEngineJar", scripts);

            // 2. if the automatic path did not upgrade, the shipped SQL scripts (manual step = finding)
            if (engine == null || !versionMoved) {
                if (engine != null) {
                    engine.close();
                    engine = null;
                }
                List<String> applied = applyScripts(kind, scripts, oldVersion, url, user, password);
                m.put("upgradeScriptsApplied", applied);
                findings.add(kind.label() + ": upgrade from " + oldVersion + " needs MANUAL SQL (" + applied.size()
                        + " script(s) from the engine jar applied by hand: " + applied + "); databaseSchemaUpdate=true did not "
                        + "move the schema version");
                upgradePath = "manual SQL scripts from the engine jar, then engine start";
                engine = kind.create(url, user, password);
                m.put("schemaAfterScripts", schemaProperties(url, user, password));
            }
            m.put("upgradePath", upgradePath);

            // 3. finish the in-flight instances on the new version
            Map<String, Object> finished = new LinkedHashMap<>();
            for (String key : List.of("atChecker", "atApprove", "atPayment")) {
                String pid = state.getProperty("instance." + key);
                finished.put(key, finish(engine, tenant, pid, key));
            }
            // 4. a new start on the upgraded schema
            Map<String, byte[]> res = Corpus.resources(kind.corpusName(), Workflows.WF1_FILES);
            engine.deploy(tenant, "t28-new-" + kind.name().toLowerCase(Locale.ROOT), res);
            String fresh = engine.startProcess(tenant, "wf1-bill", "t28-new", Workflows.wf1Vars("t28-new", 50_000L, "seat-checker", "ACC-NEW"));
            finished.put("newStart", finish(engine, tenant, fresh, "atChecker"));
            m.put("instances", finished);
            m.put("schemaAfter", schemaProperties(url, user, password));
            for (Map.Entry<String, Object> e : finished.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> f = (Map<String, Object>) e.getValue();
                assertEquals(Boolean.TRUE, f.get("ended"), e.getKey() + " ended on the new version: " + f);
            }
            assertTrue(!String.valueOf(before.get(versionKey(kind))).equals(String.valueOf(schemaProperties(url, user, password)
                    .get(versionKey(kind)))), "schema version moved from " + before.get(versionKey(kind)));
        } catch (Throwable t) {
            failure = t;
        } finally {
            if (engine != null) {
                engine.close();
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", "T28-1");
            s.put("name", "Upgrade " + oldVersion + " -> " + kind.label() + " with WF-1 instances in flight (checker, approve, "
                    + "payment external task); finish on the new version");
            s.put("tests", List.of("T28"));
            s.put("counted", true);
            s.put("outcome", failure == null ? "PASS" : "FAIL");
            s.put("pass", failure == null);
            if (failure != null) {
                s.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            s.put("findings", findings);
            s.put("measurements", m);
            scenarios.add(s);
            notes.put("method", "old phase in experiments-integration/t28-old (old engine jar), new phase here; one "
                    + "postgres:17-alpine container started by run-queue.ps1 (Q7)");
            Results.write("T28", kind.label() + " from " + oldVersion, scenarios, notes);
        }
        if (failure != null) {
            throw failure instanceof Exception ex ? ex : new IllegalStateException(failure);
        }
    }

    /** Drives one instance from the given stage to its end on the new engine. */
    private static Map<String, Object> finish(EngineAdapter engine, String tenant, String pid, String stage) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processInstanceId", pid);
        out.put("stage", stage);
        List<String> steps = new ArrayList<>();
        try {
            if (stage.equals("atChecker")) {
                engine.completeTask(Workflows.singleTask(engine, tenant, pid, "wf1_checkerReview").id(), Map.of("checkerDecision", "forward"));
                steps.add("checkerReview");
            }
            if (!stage.equals("atPayment")) {
                engine.completeTask(Workflows.singleTask(engine, tenant, pid, "wf1_approve").id(), Map.of("approverDecision", "approve"));
                steps.add("approve");
            }
            ExternalWork mine = null;
            for (ExternalWork w : engine.fetchAndLock("t28-worker", "payment", 20, Duration.ofMinutes(5))) {
                if (pid.equals(w.processInstanceId()) && mine == null) {
                    mine = w;
                } else {
                    engine.unlockExternal(w.id(), "t28-worker", w.lockVersion());
                }
            }
            if (mine == null) {
                throw new IllegalStateException("no payment work for " + pid);
            }
            engine.completeExternal(mine.id(), "t28-worker", mine.lockVersion(),
                    Map.of("paymentRef", "T28-" + pid, "operationId", "t28-op-" + pid));
            steps.add("payment (external, lockVersion " + mine.lockVersion() + ")");
            int jobs = engine.executeDueJobs();
            out.put("asyncJobsAfterCompletion", jobs);
            EngineAdapter.InstanceView v = engine.getInstance(pid);
            out.put("ended", v != null && v.ended());
        } catch (RuntimeException e) {
            out.put("ended", false);
            out.put("error", rootMessage(e));
        }
        out.put("steps", steps);
        return out;
    }

    // ------------------------------------------------------------------ schema version and upgrade scripts

    static String versionKey(EngineKind kind) {
        return "schema.version";
    }

    /** ACT_GE_PROPERTY rows whose name contains "version" (engine and sub-engine schema versions). */
    static Map<String, String> schemaProperties(String url, String user, String password) {
        Map<String, String> out = new java.util.TreeMap<>();
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT NAME_, VALUE_ FROM ACT_GE_PROPERTY WHERE lower(NAME_) LIKE '%version%'")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** PostgreSQL upgrade scripts inside the new engine jar(s), by resource name (sorted). */
    static List<String> upgradeScripts(EngineKind kind) throws IOException {
        String marker = kind == EngineKind.OPERATON ? "org/operaton/bpm/engine/db/upgrade/" : "org/flowable/";
        TreeSet<String> out = new TreeSet<>();
        Enumeration<URL> roots = T28UpgradeTest.class.getClassLoader().getResources(
                kind == EngineKind.OPERATON ? "org/operaton/bpm/engine" : "org/flowable");
        while (roots.hasMoreElements()) {
            URL u = roots.nextElement();
            if (!"jar".equals(u.getProtocol())) {
                continue;
            }
            String path = u.getPath();
            String file = java.net.URLDecoder.decode(path.substring("file:".length(), path.indexOf("!/")), StandardCharsets.UTF_8);
            if (file.matches("^/[A-Za-z]:/.*")) {
                file = file.substring(1);
            }
            try (JarFile jar = new JarFile(file)) {
                for (Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements(); ) {
                    String n = en.nextElement().getName();
                    if (n.startsWith(marker) && n.contains("upgrade") && n.toLowerCase(Locale.ROOT).contains("postgres")
                            && n.endsWith(".sql")) {
                        out.add(n);
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Applies the scripts that start at the old major.minor (Operaton naming: {@code postgres_engine_2.1_to_2.2.sql}).
     * Flowable upgrades itself on start; a Flowable script is applied only if the automatic path failed, and then every
     * script whose name carries the old version is tried in order.
     */
    static List<String> applyScripts(EngineKind kind, List<String> scripts, String oldVersion, String url, String user,
                                     String password) throws Exception {
        String[] v = oldVersion.split("\\.");
        String from = v[0] + "." + v[1];
        List<String> chosen = new ArrayList<>();
        for (String s : scripts) {
            String name = s.substring(s.lastIndexOf('/') + 1);
            if (kind == EngineKind.OPERATON ? name.contains("_" + from + "_to_") : name.contains(from)) {
                chosen.add(s);
            }
        }
        if (chosen.isEmpty()) {
            throw new IllegalStateException("no upgrade script from " + from + " among " + scripts);
        }
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            for (String s : chosen) {
                try (InputStream in = T28UpgradeTest.class.getClassLoader().getResourceAsStream(s)) {
                    st.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return chosen;
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        return r.getClass().getSimpleName() + ": " + r.getMessage();
    }

    /** Windows JVMs report India as Asia/Calcutta, which postgres:17 rejects at connect. */
    static void canonicaliseDefaultTimeZone() {
        Map<String, String> legacy = Map.of("Asia/Calcutta", "Asia/Kolkata", "Asia/Katmandu", "Asia/Kathmandu",
                "Asia/Saigon", "Asia/Ho_Chi_Minh", "Asia/Rangoon", "Asia/Yangon", "Europe/Kiev", "Europe/Kyiv");
        String canonical = legacy.get(TimeZone.getDefault().getID());
        if (canonical != null) {
            TimeZone.setDefault(TimeZone.getTimeZone(canonical));
        }
    }
}
