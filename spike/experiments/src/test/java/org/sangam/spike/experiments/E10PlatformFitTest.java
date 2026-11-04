package org.sangam.spike.experiments;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.sangam.spike.experiments.support.Db;
import org.sangam.spike.experiments.support.Engines;
import org.sangam.spike.experiments.support.ExperimentBase;
import org.sangam.spike.experiments.support.Results;

/**
 * E10 platform fit, lite (SANGAM.md XI.4): JDK, engine start on this JDK (on PostgreSQL), JDK 25 status, and the
 * licence checks LIC-1..4 (no GraalJS, ojdbc, MySQL or Db2 driver) on (a) the class path of this engine's JVM and
 * (b) the dependency trees in {@code target/e10/} (README "E10").
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E10PlatformFitTest extends ExperimentBase {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "org\\.graalvm\\.polyglot:js|org\\.graalvm\\.js:|ojdbc|mysql-connector|mysql:mysql|com\\.mysql:|db2jcc|com\\.ibm\\.db2");
    private static final String[][] FORBIDDEN_CLASSES = {
            {"LIC-1 GraalJS", "org.graalvm.polyglot.Context"},
            {"LIC-2 Oracle JDBC", "oracle.jdbc.OracleDriver"},
            {"LIC-3 MySQL JDBC", "com.mysql.cj.jdbc.Driver"},
            {"LIC-4 Db2 JDBC", "com.ibm.db2.jcc.DB2Driver"}};

    private boolean started;
    private String startError;

    @BeforeAll
    void setUp() {
        hooks = Engines.hooks();
        results = new Results("E10", "Platform fit (lite): JDK, engine start, licence exclusions, Jackson, Spring");
        try {
            adapter = hooks.create(Db.newDatabase("e10_" + hooks.engine() + "_"));
            adapter.deploy(null, "e10", Map.of("e10.bpmn", ("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                    + " targetNamespace=\"e10\"><process id=\"e10\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                    + "<sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"/><endEvent id=\"e\"/></process></definitions>")
                    .getBytes(StandardCharsets.UTF_8)));
            String pid = adapter.startProcess(null, "e10", "e10", Map.of());
            started = adapter.getInstance(pid).ended();
        } catch (Throwable t) {
            startError = Results.error(t);
        }
    }

    /** Jar file names on this JVM's test class path (from the manifests, works with surefire's manifest-only jar). */
    private static List<String> classPathJars() throws IOException {
        TreeSet<String> jars = new TreeSet<>();
        Enumeration<URL> e = ClassLoader.getSystemClassLoader().getResources("META-INF/MANIFEST.MF");
        while (e.hasMoreElements()) {
            String u = e.nextElement().toString();
            int bang = u.indexOf("!/");
            if (u.startsWith("jar:") && bang > 0) {
                String p = u.substring(4, bang);
                jars.add(p.substring(p.lastIndexOf('/') + 1));
            }
        }
        return new ArrayList<>(jars);
    }

    @Test
    void platform() throws IOException {
        String e = hooks.engine();
        scenario("E10-" + e, "E10", "JDK / engine start / LIC-1..4 / Jackson / Spring", List.of(), ctx -> {
            ctx.detail("javaVersion", System.getProperty("java.version"));
            ctx.detail("javaRuntime", System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version"));
            ctx.detail("engineId", adapter == null ? null : adapter.engineId());
            ctx.detail("startsOnThisJdk", started);
            ctx.detail("startError", startError);
            ctx.detail("jdk25", "not tested: JDK 25 is not installed on this host (not installed on purpose, per task)");
            ctx.check("engine starts, deploys and runs a process on JDK " + System.getProperty("java.version")
                    + " + " + Db.description(), started);

            List<String> jars = classPathJars();
            ctx.detail("classPathJarCount", jars.size());
            List<String> jackson = jars.stream().filter(j -> j.startsWith("jackson-")).toList();
            List<String> spring = jars.stream().filter(j -> j.startsWith("spring-")).toList();
            List<String> forbiddenJars = jars.stream().filter(j -> j.contains("ojdbc") || j.contains("mysql")
                    || j.contains("db2jcc") || j.matches("(polyglot|js|js-scriptengine)-\\d.*")).toList();
            ctx.detail("jacksonJars", jackson);
            ctx.detail("jackson3Present", classPresent("tools.jackson.databind.ObjectMapper"));
            ctx.detail("jackson2Present", classPresent("com.fasterxml.jackson.databind.ObjectMapper"));
            ctx.detail("springJars", spring);
            ctx.detail("springContextPresent", classPresent("org.springframework.context.ApplicationContext"));
            ctx.detail("forbiddenJarsOnClassPath", forbiddenJars);
            for (String[] f : FORBIDDEN_CLASSES) {
                boolean present = classPresent(f[1]);
                ctx.detail(f[0] + " class " + f[1], present ? "PRESENT" : "absent");
                ctx.check(f[0] + " absent from the test class path", !present);
            }
            ctx.check("no forbidden jar on the class path", forbiddenJars.isEmpty());
            List<String> scriptEngines = new ArrayList<>();
            for (ScriptEngineFactory f : new ScriptEngineManager().getEngineFactories()) {
                scriptEngines.add(f.getEngineName() + " " + f.getEngineVersion());
            }
            ctx.detail("jsr223ScriptEngines", scriptEngines);
            ctx.detail("classPathJars", jars);

            // dependency trees (generated by the build / README commands)
            Path dir = Path.of("target", "e10");
            Map<String, Object> trees = new java.util.LinkedHashMap<>();
            for (String name : List.of("tree-experiments.txt", "tree-adapter-flowable.txt", "tree-adapter-operaton.txt",
                    "runtime-adapter-flowable.txt", "runtime-adapter-operaton.txt")) {
                Path f = dir.resolve(name);
                if (!Files.exists(f)) {
                    trees.put(name, "missing (see README E10)");
                    continue;
                }
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                List<String> bad = lines.stream().filter(l -> FORBIDDEN.matcher(l).find()).toList();
                trees.put(name, Results.map(
                        "forbidden", bad,
                        "jackson", coords(lines, "jackson"),
                        "spring", coords(lines, "org.springframework"),
                        "mybatis", coords(lines, "org.mybatis")));
                ctx.check("no LIC-1..4 artifact in " + name, bad.isEmpty());
            }
            ctx.detail("dependencyTrees", trees);
        });
    }

    private static List<String> coords(List<String> lines, String needle) {
        Pattern p = Pattern.compile("([\\w.\\-]+:[\\w.\\-]+:jar:[\\w.\\-]+(:[a-z]+)?)");
        TreeSet<String> out = new TreeSet<>();
        for (String l : lines) {
            if (l.contains(needle)) {
                Matcher m = p.matcher(l);
                if (m.find()) {
                    out.add(m.group(1));
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @AfterAll
    void tearDown() {
        try {
            results.write(hooks.engine(), adapter == null ? hooks.engine() : adapter.engineId(), summary(results.scenarios()));
        } finally {
            if (adapter != null) {
                adapter.close();
            }
        }
    }
}
