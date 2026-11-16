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
 * {@code TypedVariables}. Over the engine variable write paths of {@link CallerVariableWritePathsTest} (sangam-server,
 * sangam-rest-core, sangam-rest, sangam-integration) and the decision evaluations, every file that also reads JSON
 * <ul>
 *   <li>declares no plain Jackson mapper (one that reads a fraction as a binary Double), and</li>
 *   <li>in the modules that see sangam-rest-core, converts the values through {@code TypedVariables.fromJson} or
 *       {@code SlotVariableGuard.callerVariables} (which calls it). sangam-integration sits below sangam-rest-core and
 *       reads through its own exact mapper, checked below.</li>
 * </ul>
 * and every shared mapper such a path reads with reads fractions exactly. A new write path that parses JSON with a
 * plain mapper, or hands parsed JSON to the engine without TypedVariables, fails here by file name.
 */
class EngineVariablesThroughTypedVariablesTest {

    static final Pattern READS_JSON = Pattern.compile("\\.(?:readValue|readTree|convertValue|treeToValue)\\("
            + "|\\bJson\\.(?:map|read|tree|readMap)\\(");
    /** A mapper built without the exact-fraction feature in the same statement. */
    static final Pattern PLAIN_MAPPER = Pattern.compile("new ObjectMapper\\(\\)(?![^;]*USE_BIG_DECIMAL_FOR_FLOATS)");
    static final String INTEGRATION = "../sangam-integration/";
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
                    readers.add(name);
                    if (PLAIN_MAPPER.matcher(src).find()) {
                        violations.put(name, "declares a plain ObjectMapper (fractions become Double): use "
                                + "ExactJson.mapper() or ExactJson.documents()");
                    } else if (!name.startsWith(INTEGRATION) && !src.contains("TypedVariables.fromJson(")
                            && !src.contains("SlotVariableGuard.callerVariables(")) {
                        violations.put(name, "hands parsed JSON to the engine without TypedVariables.fromJson");
                    }
                }
            }
        }
        assertTrue(readers.stream().anyMatch(f -> f.endsWith("CellCrossCellInboxHandler.java")),
                () -> "the scan works: " + readers);
        assertEquals(Map.of(), violations, "engine variable write paths that bypass TypedVariables (decision 381(4))");
    }

    @Test
    void theSharedMappersOfThoseWritePathsReadFractionsExactly() {
        Map<String, ObjectMapper> mappers = Map.of(
                "ports Json.MAPPER", org.sangam.ports.support.Json.MAPPER,
                "integration Json.MAPPER", org.sangam.integration.connector.Json.MAPPER,
                "ExactJson.mapper()", ExactJson.mapper(),
                "ExactJson.documents()", ExactJson.documents());
        mappers.forEach((name, m) -> assertTrue(
                m.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS), name + " reads fractions as Double"));
    }
}
