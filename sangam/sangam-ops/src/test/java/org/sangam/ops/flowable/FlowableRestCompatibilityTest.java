// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import org.junit.jupiter.api.Test;
import org.sangam.ops.flowable.FlowableRestCompatibility.Entry;
import org.sangam.ops.flowable.FlowableRestCompatibility.Level;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** M-135 REST compatibility classification: every mapped Sangam path exists in the API contract. Written, NOT_RUN. */
class FlowableRestCompatibilityTest {

    /** The module tests run in sangam/sangam-ops; the contract lives in sangam/sangam-rest. */
    private static Set<String> contractPaths() throws Exception {
        Path yaml = Path.of("..", "sangam-rest", "api", "sangam-api.yaml");
        assertTrue(Files.isRegularFile(yaml), "API contract not found at " + yaml.toAbsolutePath());
        Set<String> paths = new HashSet<>();
        Matcher m = Pattern.compile("(?m)^  (/[^:\\s]*):\\s*$").matcher(Files.readString(yaml, StandardCharsets.UTF_8));
        while (m.find()) {
            paths.add(m.group(1));
        }
        return paths;
    }

    @Test
    void everyMappedOrPartialEntryPointsAtARealSangamPath() throws Exception {
        Set<String> paths = contractPaths();
        for (Entry e : FlowableRestCompatibility.ENTRIES) {
            if (e.level() != Level.UNSUPPORTED) {
                assertTrue(paths.contains(e.sangamPath()), e.flowablePath() + " -> " + e.sangamPath() + " is not in sangam-api.yaml");
            }
        }
    }

    @Test
    void entriesAreUniqueAndCoverEveryLevel() {
        Set<String> seen = new HashSet<>();
        for (Entry e : FlowableRestCompatibility.ENTRIES) {
            assertTrue(seen.add(e.method() + " " + e.flowablePath()), "duplicate " + e.flowablePath());
            if (e.level() == Level.PARTIAL || e.level() == Level.UNSUPPORTED) {
                assertNotNull(e.note(), e.flowablePath() + " needs a note");
            }
        }
        for (Level l : Level.values()) {
            assertTrue(FlowableRestCompatibility.ENTRIES.stream().anyMatch(e -> e.level() == l), l.name());
        }
    }

    @Test
    void concreteRequestsAreClassified() {
        assertEquals(Level.MAPPED, FlowableRestCompatibility.level("GET", "/service/runtime/tasks/123"));
        assertEquals("/tasks/{id}", FlowableRestCompatibility.classify("get", "/service/runtime/tasks/123").orElseThrow().sangamPath());
        assertEquals(Level.MAPPED, FlowableRestCompatibility.level("GET", "https://flowable.example/service/runtime/tasks?assignee=bob"));
        assertEquals(Level.PARTIAL, FlowableRestCompatibility.level("DELETE", "/service/runtime/process-instances/42/"));
        assertEquals(Level.UNSUPPORTED, FlowableRestCompatibility.level("GET", "/service/management/engine"));
        assertEquals(Level.UNSUPPORTED, FlowableRestCompatibility.level("GET", "/service/runtime/unknown"), "no entry means UNSUPPORTED");
        assertEquals(Level.UNSUPPORTED, FlowableRestCompatibility.level("PATCH", "/service/runtime/tasks/1"));
        assertTrue(FlowableRestCompatibility.classify("GET", "/service/runtime/tasks//comments").isEmpty(), "an empty path segment is no id");
    }

    @Test
    void unsupportedEntriesHaveNoTargetAndOthersDo() {
        assertThrows(IllegalArgumentException.class, () -> new Entry("GET", "/x", Level.UNSUPPORTED, "GET", "/y", "n"));
        assertThrows(IllegalArgumentException.class, () -> new Entry("GET", "/x", Level.MAPPED, null, null, null));
    }
}
