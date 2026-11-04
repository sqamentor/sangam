package org.sangam.spike.it.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Merges one engine's scenario results into {@code results/<experiment>.json}; the other engine's entry is kept. */
public final class Results {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final Map<String, String> TITLES = Map.of(
            "E2", "Authorization equivalence over engine task tables (VI.4 + A-3)",
            "E3", "Worker lease + operationId + unknown outcome (VI.3 + A-1/A-2/A-8)",
            "E4", "Idempotency atomic with engine state (X.2.2)",
            "E5", "Outbox / inbox with engine state (VI.2)",
            "E7", "Cancel/delete with external work in flight (VI.5)",
            "T08", "Poison outbox event: ordered block + repair, unordered gap (VI.2.3, Part III T08)",
            "T35", "Restore to an older snapshot while the broker holds newer messages (Part III T35)");

    private Results() {
    }

    public static Path dir() {
        return Path.of(System.getProperty("results.dir", "../results"));
    }

    public static synchronized void write(String experiment, String key, List<Map<String, Object>> scenarios,
                                          Map<String, Object> notes) {
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            // Oracle / SQL Server runs go to <E>-oracle.json / <E>-sqlserver.json; the PostgreSQL file of Stage B stays as is.
            Path file = dir.resolve(experiment + Db.resultSuffix() + ".json");
            ObjectNode root = Files.exists(file) ? (ObjectNode) JSON.readTree(file.toFile()) : JSON.createObjectNode();
            root.put("experiment", experiment);
            root.put("title", TITLES.getOrDefault(experiment, experiment));
            root.put("database", Db.description());
            root.put("module", "spike/experiments-integration");
            ObjectNode engines = root.has("engines") ? (ObjectNode) root.get("engines") : root.putObject("engines");
            ObjectNode e = engines.putObject(key);
            e.put("generatedAt", OffsetDateTime.now().toString());
            long counted = scenarios.stream().filter(s -> Boolean.TRUE.equals(s.get("counted"))).count();
            long passed = scenarios.stream()
                    .filter(s -> Boolean.TRUE.equals(s.get("counted")) && Boolean.TRUE.equals(s.get("pass"))).count();
            e.put("scenariosCounted", counted);
            e.put("scenariosPassed", passed);
            e.put("passRatePercent", counted == 0 ? 0.0 : Math.round(1000.0 * passed / counted) / 10.0);
            e.set("scenarios", JSON.valueToTree(scenarios));
            e.set("notes", JSON.valueToTree(notes));
            JSON.writeValue(file.toFile(), root);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot write results for " + experiment, ex);
        }
    }
}
