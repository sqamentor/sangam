package org.sangam.spike.experiments.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects per-scenario results of one experiment on one engine and writes them.
 *
 * <p>Each engine runs in its own JVM (surefire execution). Each run writes a fragment
 * {@code <exp.fragmentDir>/<E>-<engine>.json}, then rebuilds {@code <exp.resultsDir>/<E>.json} from every fragment
 * present, so the second execution produces the file with both engines.
 */
public final class Results {

    private final String experiment;
    private final String title;
    private final List<Map<String, Object>> scenarios = new ArrayList<>();
    private final Map<String, Object> extra = new LinkedHashMap<>();

    public Results(String experiment, String title) {
        this.experiment = experiment;
        this.title = title;
    }

    public synchronized Map<String, Object> add(Map<String, Object> scenario) {
        scenarios.add(scenario);
        return scenario;
    }

    public synchronized void put(String key, Object value) {
        extra.put(key, value);
    }

    public List<Map<String, Object>> scenarios() {
        return scenarios;
    }

    /** Writes the fragment for {@code engineId} and rebuilds the combined file. */
    public synchronized void write(String engineKey, String engineId, Map<String, Object> summary) {
        Map<String, Object> frag = new LinkedHashMap<>();
        frag.put("engineId", engineId);
        frag.put("generatedAt", Instant.now().toString());
        frag.put("jdk", System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version"));
        frag.put("database", Db.description());
        frag.put("summary", summary);
        frag.putAll(extra);
        frag.put("scenarios", scenarios);
        try {
            // Another database (-Dspike.db=oracle|sqlserver, -Dexp.db=h2) writes to its own fragment folder and to
            // <E>-<db>.json, so the PostgreSQL reference files of Stage B are never overwritten.
            Path fragDir = Path.of(System.getProperty("exp.fragmentDir", "target/exp-results"));
            if (!Db.isPostgres()) {
                fragDir = fragDir.resolve(Db.resultSuffix().substring(1));
            }
            Files.createDirectories(fragDir);
            Files.writeString(fragDir.resolve(experiment + "-" + engineKey + ".json"), Json.write(frag),
                    StandardCharsets.UTF_8);

            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"experiment\": \"").append(experiment).append("\",\n");
            sb.append("  \"title\": \"").append(title.replace("\"", "'")).append("\",\n");
            sb.append("  \"engines\": {");
            boolean first = true;
            List<Path> frags;
            try (var s = Files.list(fragDir)) {
                frags = s.filter(p -> p.getFileName().toString().startsWith(experiment + "-")
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
            if (Boolean.parseBoolean(System.getProperty("exp.writeResults", "true"))) {
                Path resultsDir = Path.of(System.getProperty("exp.resultsDir", "../results"));
                Files.createDirectories(resultsDir);
                Files.writeString(resultsDir.resolve(experiment + Db.resultSuffix() + ".json"), sb.toString(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Ordered map helper. */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** First line of a throwable's message chain, for the JSON. */
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
        return sb.toString();
    }
}
