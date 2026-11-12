// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drift guard: every covered SDK model has exactly the wire fields of its schema in sangam-api.yaml (the generated
 * contract), and no field that is optional there is a Java primitive (a primitive is always sent and cannot be absent).
 */
class ModelContractTest {

    private static final Path CONTRACT = Paths.get("..", "..", "sangam-rest", "api", "sangam-api.yaml");
    private static final Pattern SECTION = Pattern.compile("^ {6}(\\w+):");
    private static final Pattern PROPERTY = Pattern.compile("^ {8}([A-Za-z0-9_$]+):");
    private static final Pattern REQUIRED = Pattern.compile("^ {6}- (\\S+)$");

    record Schema(Set<String> properties, Set<String> required) { }

    static Stream<Arguments> covered() {
        return Stream.of(
                Arguments.of("ExternalWork", ExternalWork.class),
                Arguments.of("TraceContext", TraceContext.class),
                Arguments.of("WorkOperation", WorkOperation.class),
                Arguments.of("WorkOutcome", WorkOutcome.class),
                Arguments.of("LockState", ExtendLockResult.class),
                Arguments.of("OutcomeUnknownResult", OutcomeUnknownResult.class),
                Arguments.of("TopicRequest", FetchTopic.class),
                Arguments.of("FetchAndLockRequest", FetchAndLockRequest.class),
                Arguments.of("WorkCompleteRequest", CompleteWorkRequest.class),
                Arguments.of("WorkFailRequest", FailWorkRequest.class),
                Arguments.of("WorkBpmnErrorRequest", BpmnErrorRequest.class),
                Arguments.of("WorkUnlockRequest", UnlockRequest.class),
                Arguments.of("ExtendLockRequest", ExtendLockRequest.class),
                Arguments.of("OutcomeUnknownRequest", OutcomeUnknownRequest.class),
                Arguments.of("Task", TaskView.class),
                Arguments.of("CompleteTaskRequest", CompleteTaskRequest.class),
                Arguments.of("ReturnTaskRequest", ReturnTaskRequest.class),
                Arguments.of("CompleteTaskResult", CompleteTaskResult.class),
                Arguments.of("AuditEntry", AuditEntry.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("covered")
    void modelMatchesTheContract(String schemaName, Class<? extends Record> model) throws IOException {
        Schema s = schema(contract(), schemaName);
        Set<String> wire = new LinkedHashSet<>();
        List<String> primitivesNotRequired = new ArrayList<>();
        for (var c : model.getRecordComponents()) {
            JsonProperty jp = c.getAccessor().getAnnotation(JsonProperty.class);
            String name = jp != null && !jp.value().isEmpty() ? jp.value() : c.getName();
            wire.add(name);
            if (c.getType().isPrimitive() && !s.required().contains(name)) primitivesNotRequired.add(name);
        }
        assertEquals(s.properties(), wire, schemaName + ": wire fields differ from sangam-api.yaml");
        assertTrue(primitivesNotRequired.isEmpty(),
                schemaName + ": optional in sangam-api.yaml but primitive (always sent) in " + model.getSimpleName() + ": "
                        + primitivesNotRequired);
    }

    private static List<String> contract() throws IOException {
        assertTrue(Files.isRegularFile(CONTRACT), "contract not found at " + CONTRACT.toAbsolutePath()
                + " (the guard runs from the sangam/sdks/java module directory)");
        return Files.readAllLines(CONTRACT, StandardCharsets.UTF_8);
    }

    /** Reads one schema of the PyYAML block layout written by gen_openapi.py (components.schemas.<name>). */
    static Schema schema(List<String> lines, String name) {
        int i = lines.indexOf("  schemas:");
        assertTrue(i >= 0, "no components.schemas in the contract");
        int start = -1;
        for (int k = i + 1; k < lines.size(); k++) {
            if (lines.get(k).equals("    " + name + ":")) {
                start = k;
                break;
            }
        }
        assertTrue(start >= 0, "schema " + name + " not in the contract");
        Set<String> props = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>();
        String section = "";
        for (int k = start + 1; k < lines.size(); k++) {
            String l = lines.get(k);
            if (l.isBlank()) continue;
            if (indent(l) < 6) break;
            Matcher m = SECTION.matcher(l);
            if (m.find()) {
                section = m.group(1);
                continue;
            }
            if (section.equals("properties")) {
                Matcher p = PROPERTY.matcher(l);
                if (p.find()) props.add(p.group(1));
            } else if (section.equals("required")) {
                Matcher r = REQUIRED.matcher(l);
                if (r.find()) required.add(r.group(1));
            }
        }
        assertFalse(props.isEmpty(), "schema " + name + " has no properties (contract layout changed?)");
        return new Schema(props, required);
    }

    private static int indent(String l) {
        int n = 0;
        while (n < l.length() && l.charAt(n) == ' ') n++;
        return n;
    }
}
