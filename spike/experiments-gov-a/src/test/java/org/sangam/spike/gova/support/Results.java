package org.sangam.spike.gova.support;

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
import java.util.TreeMap;

/**
 * Collects the results of one experiment on one engine (one JVM), writes the fragment
 * {@code <gov.fragmentDir>/<db>/<E>-<engine>.json} and rebuilds the combined file from every fragment present.
 * The combined file goes to {@code ../results/<E>.json} only for the PostgreSQL run; H2 pre-runs stay in target.
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

    public synchronized void add(Map<String, Object> scenario) {
        scenarios.add(scenario);
    }

    public synchronized void put(String k, Object v) {
        extra.put(k, v);
    }

    public synchronized void write(Kit kit, String engineId) {
        Map<String, Long> counts = new TreeMap<>();
        for (String r : List.of(Rec.PASS, Rec.WORKAROUND, Rec.FAIL, Rec.UNSUPPORTED)) {
            counts.put(r, 0L);
        }
        List<Map<String, Object>> sorted = new ArrayList<>(scenarios);
        sorted.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
        for (Map<String, Object> s : sorted) {
            counts.merge(String.valueOf(s.get("result")), 1L, Long::sum);
        }
        Map<String, Object> frag = new LinkedHashMap<>();
        frag.put("engineId", engineId);
        frag.put("generatedAt", Instant.now().toString());
        frag.put("jdk", System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version"));
        frag.put("jvm", "-Xmx2g, user.timezone=" + System.getProperty("user.timezone"));
        frag.put("database", Db.description());
        frag.put("historyLevel", kit.historyLevel());
        frag.put("engineConfigurationAsUsed", kit.configurationNotes());
        frag.put("summary", counts);
        frag.putAll(extra);
        frag.put("scenarios", sorted);
        try {
            Path fragDir = Path.of(System.getProperty("gov.fragmentDir", "target/gov-results")).resolve(Db.kind());
            Files.createDirectories(fragDir);
            Files.writeString(fragDir.resolve(experiment + "-" + kit.engine() + ".json"), Json.write(frag),
                    StandardCharsets.UTF_8);

            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"experiment\": \"").append(experiment).append("\",\n");
            sb.append("  \"title\": \"").append(title.replace("\"", "'")).append("\",\n");
            sb.append("  \"engines\": {");
            boolean first = true;
            List<Path> frags;
            try (var s = Files.list(fragDir)) {
                frags = s.filter(p -> {
                    String fn = p.getFileName().toString();
                    return fn.startsWith(experiment + "-") && fn.endsWith(".json")
                            && (fn.endsWith("-flowable.json") || fn.endsWith("-operaton.json"));
                }).sorted().toList();
            }
            for (Path f : frags) {
                String fn = f.getFileName().toString();
                String e = fn.substring(experiment.length() + 1, fn.length() - ".json".length());
                String body = Files.readString(f, StandardCharsets.UTF_8).replace("\n", "\n    ");
                sb.append(first ? "\n" : ",\n").append("    \"").append(e).append("\": ").append(body);
                first = false;
            }
            sb.append("\n  }\n}\n");
            Files.writeString(fragDir.resolve(experiment + ".json"), sb.toString(), StandardCharsets.UTF_8);
            if (Db.isPostgres()) {
                Path resultsDir = Path.of(System.getProperty("gov.resultsDir", "../results"));
                Files.createDirectories(resultsDir);
                Files.writeString(resultsDir.resolve(experiment + ".json"), sb.toString(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
