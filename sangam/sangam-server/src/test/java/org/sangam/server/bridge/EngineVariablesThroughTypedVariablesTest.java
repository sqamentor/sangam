// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.sangam.rest.support.ExactJson;

/**
 * Owner decision 381(4) follow-up, guard: an engine variable write that takes its values from JSON never bypasses
 * {@code TypedVariables}. Over the engine variable write paths of {@link CallerVariableWritePathsTest} in every reactor
 * module (decision 387(1)) and the decision evaluations, every file that also reads JSON
 * <ul>
 *   <li>declares no plain Jackson mapper (one that reads a fraction as a binary Double), and</li>
 *   <li>in the modules that see sangam-rest-core, converts the values through {@code TypedVariables.fromJson} or
 *       {@code SlotVariableGuard.callerVariables} (which calls it). The modules below sangam-rest-core
 *       ({@link #BELOW_REST_CORE}) cannot see TypedVariables; they read through their own mapper, and each shared one is
 *       checked below to read fractions exactly.</li>
 * </ul>
 * A file the write-path inventory lists as "not an engine write" (its matches are an LLM completion, an audit drain, an
 * idempotency record) is no engine variable write path and is not checked; {@link #NO_ENGINE_JSON} lists, with its
 * reason, a write path whose JSON call never reaches the engine. A new write path that parses JSON with a plain mapper,
 * or hands parsed JSON to the engine without TypedVariables, fails here by file name.
 */
class EngineVariablesThroughTypedVariablesTest {

    static final Pattern READS_JSON = Pattern.compile("\\.(?:readValue|readTree|convertValue|treeToValue)\\("
            + "|\\bJson\\.(?:map|read|tree|readMap)\\(");
    /**
     * A mapper built without the exact-fraction feature in the same statement: {@code new ObjectMapper()} or a
     * {@code JsonMapper.builder()} (simple or fully qualified name, Jackson 2 or 3).
     */
    static final Pattern PLAIN_MAPPER = Pattern.compile(
            "(?:new\\s+ObjectMapper\\s*\\(\\s*\\)|\\bJsonMapper\\s*\\.\\s*builder\\s*\\(\\s*\\))"
                    + "(?![^;]*USE_BIG_DECIMAL_FOR_FLOATS)");

    @Test
    void thePlainMapperPatternCatchesBothConstructionStyles() {
        assertTrue(PLAIN_MAPPER.matcher("static final ObjectMapper M = new ObjectMapper();").find());
        assertTrue(PLAIN_MAPPER.matcher("ObjectMapper m = JsonMapper.builder().build();").find(), "planted builder");
        assertTrue(PLAIN_MAPPER.matcher("ObjectMapper m = com.fasterxml.jackson.databind.json.JsonMapper.builder()\n"
                + "        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).build();").find(), "planted FQN builder");
        assertTrue(!PLAIN_MAPPER.matcher("ObjectMapper m = JsonMapper.builder()\n"
                + "        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();").find());
        assertTrue(!PLAIN_MAPPER.matcher("ObjectMapper m = new ObjectMapper()"
                + ".enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);").find());
    }
    /**
     * The modules with engine write paths below sangam-rest-core, with the mapper they read JSON through (each shared
     * one checked in {@link #theSharedMappersOfThoseWritePathsReadFractionsExactly}). Any other module must use
     * TypedVariables.
     */
    static final Map<String, String> BELOW_REST_CORE = Map.of(
            "../sangam-integration/", "integration Json.MAPPER",
            "../sangam-flowable-ports/", "ports Json.MAPPER",
            "../sangam-registry/", "registry Json.MAPPER",
            "../sangam-packs/", "Vars' own JsonMapper with USE_BIG_DECIMAL_FOR_FLOATS");
    /** Write paths whose JSON call never reaches the engine, with the reason. */
    static final Map<String, String> NO_ENGINE_JSON = Map.of(
            "../sangam-onboarding/src/main/java/org/sangam/onboarding/flow/OnboardingService.java",
            "convertValue turns the in-memory UpgradeStep records (text and a flag, no fraction, no JSON text) into the"
                    + " onboarding plan document; its engine writes complete tasks with the fixed variables approved"
                    + " and checker");
    /** Engine variable inputs beyond the inventory's writes: a decision evaluation's variables. */
    static final Pattern EVALUATES = Pattern.compile("evaluateDecision(?:Table)?By(?:Key|Id)\\(");

    @Test
    void everyWritePathThatReadsJsonUsesAnExactMapperAndTypedVariables() throws Exception {
        Map<String, String> violations = new TreeMap<>();
        List<String> readers = new ArrayList<>();
        for (Path root : CallerVariableWritePathsTest.roots()) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                    String src = Files.readString(p);
                    boolean writes = CallerVariableWritePathsTest.WRITE.matcher(src).find()
                            || EVALUATES.matcher(src).find();
                    if (!writes || !READS_JSON.matcher(src).find()) {
                        continue;
                    }
                    String name = p.toString().replace('\\', '/');
                    CallerVariableWritePathsTest.Path0 listed = CallerVariableWritePathsTest.PATHS
                            .get(CallerVariableWritePathsTest.relative(p));
                    if (listed != null
                            && listed.disposition().startsWith(CallerVariableWritePathsTest.NOT_AN_ENGINE_WRITE)) {
                        continue;
                    }
                    readers.add(name);
                    if (NO_ENGINE_JSON.containsKey(name)) {
                        continue;
                    }
                    if (PLAIN_MAPPER.matcher(src).find()) {
                        violations.put(name, "declares a plain ObjectMapper (fractions become Double): use "
                                + "ExactJson.mapper() or ExactJson.documents()");
                    } else if (BELOW_REST_CORE.keySet().stream().noneMatch(name::startsWith) &&!src.contains("TypedVariables.fromJson(")
                            && !src.contains("SlotVariableGuard.callerVariables(")) {
                        violations.put(name, "hands parsed JSON to the engine without TypedVariables.fromJson");
                    }
                }
            }
        }
        assertTrue(readers.stream().anyMatch(f -> f.endsWith("CellCrossCellInboxHandler.java")),
                () -> "the scan works: " + readers);
        assertEquals(Map.of(), violations, "engine variable write paths that bypass TypedVariables (decision 381(4))");
        for (String listed : NO_ENGINE_JSON.keySet()) {
            assertTrue(readers.contains(listed), () -> "a stale NO_ENGINE_JSON entry: " + listed);
        }
    }

    @Test
    void theSharedMappersOfThoseWritePathsReadFractionsExactly() {
        Map<String, ObjectMapper> mappers = Map.of(
                "ports Json.MAPPER", org.sangam.ports.support.Json.MAPPER,
                "integration Json.MAPPER", org.sangam.integration.connector.Json.MAPPER,
                "registry Json.MAPPER", org.sangam.registry.support.Json.MAPPER,
                "ExactJson.mapper()", ExactJson.mapper(),
                "ExactJson.documents()", ExactJson.documents());
        mappers.forEach((name, m) -> assertTrue(
                m.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS), name + " reads fractions as Double"));
    }
}
