package org.sangam.spike.govb.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.opentest4j.TestAbortedException;

/**
 * Runs pattern/model bodies, keeps their {@link Rec}s and merges them into {@code <dir>/<experiment>.json}
 * (one section per engine, so the second engine's run completes the file). PostgreSQL runs write to
 * {@code gov.results.dir} (../results); H2 runs write to {@code target/gov-results}.
 */
public final class Report {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final String experiment;
    private final String title;
    private final List<Rec> recs = new ArrayList<>();

    public Report(String experiment, String title) {
        this.experiment = experiment;
        this.title = title;
    }

    @FunctionalInterface
    public interface Body {
        void run(Rec r) throws Exception;
    }

    /**
     * Runs one body. An exception records FAIL with its message and is rethrown (the JUnit test fails too). With no
     * explicit result the record is PASS, or PASS-WITH-WORKAROUND when it lists a workaround.
     */
    public void run(String id, String name, Body body) {
        Rec r = new Rec(id, name);
        recs.add(r);
        Rec.bind(r);
        r.startedNanos = System.nanoTime();
        try {
            body.run(r);
            if (r.result == null) {
                r.result = r.workarounds.isEmpty() ? Rec.Result.PASS : Rec.Result.PASS_WITH_WORKAROUND;
            }
        } catch (TestAbortedException e) {
            throw e;
        } catch (Throwable t) {
            r.result = Rec.Result.FAIL;
            r.reason = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).lines().findFirst().orElse("");
            if (t instanceof Error err) {
                throw err;
            }
            throw new AssertionError(id + " failed", t);
        } finally {
            r.totalMs = (System.nanoTime() - r.startedNanos) / 1_000_000;
            Rec.unbind();
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized void write(Gov g) {
        Path dir = Env.isPostgres()
                ? Path.of(System.getProperty("gov.results.dir", "../results"))
                : Path.of("target", "gov-results");
        Path file = dir.resolve(experiment + ".json");
        try {
            Files.createDirectories(dir);
            Map<String, Object> root = Files.exists(file)
                    ? JSON.readValue(file.toFile(), LinkedHashMap.class) : new LinkedHashMap<>();
            root.put("experiment", experiment);
            root.put("title", title);
            Map<String, Object> runs = (Map<String, Object>) root.computeIfAbsent("runs", k -> new LinkedHashMap<>());
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("engineId", g.adapter().engineId());
            run.put("database", Env.dbDescription());
            run.put("jdbcUrl", g.adapter().jdbcUrl().replaceAll("password=[^&;]*", "password=***"));
            run.put("jdk", System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version"));
            run.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
            run.put("threads", Env.THREADS);
            run.put("generatedAt", Instant.now().toString());
            runs.put(g.engine(), run);
            Map<String, Object> items = (Map<String, Object>) root.computeIfAbsent("items", k -> new TreeMap<>());
            for (Rec r : recs) {
                Map<String, Object> item = (Map<String, Object>) items.computeIfAbsent(r.id, k -> new LinkedHashMap<>());
                item.put("name", r.name);
                Map<String, Object> m = r.toMap();
                m.remove("name");
                item.put(g.engine(), m);
            }
            root.put("items", new TreeMap<>(items));
            root.put("summary", summary(items));
            JSON.writeValue(file.toFile(), root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(Map<String, Object> items) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String engine : List.of("operaton", "flowable")) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Rec.Result res : Rec.Result.values()) {
                counts.put(res.name().replace('_', '-'), 0);
            }
            int nativeUses = 0;
            int variants = 0;
            int lines = 0;
            boolean any = false;
            for (Object o : items.values()) {
                Map<String, Object> item = (Map<String, Object>) o;
                Map<String, Object> e = (Map<String, Object>) item.get(engine);
                if (e == null) {
                    continue;
                }
                any = true;
                counts.merge(String.valueOf(e.get("result")), 1, Integer::sum);
                nativeUses += ((List<?>) e.get("nativeApi")).size();
                variants += ((List<?>) e.get("variants")).size();
                lines += ((Number) e.get("workaroundLines")).intValue();
            }
            if (any) {
                Map<String, Object> s = new LinkedHashMap<>(counts);
                s.put("nativeApiUses", nativeUses);
                s.put("variantElements", variants);
                s.put("workaroundLines", lines);
                out.put(engine, s);
            }
        }
        return out;
    }
}
