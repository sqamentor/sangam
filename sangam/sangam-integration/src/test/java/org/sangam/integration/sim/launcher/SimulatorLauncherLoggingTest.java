// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.LogManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Owner decisions 324 and 378 for the simulator launcher: {@link SimulatorLauncher#logging} (the first step of {@code main}) makes the console log ECS
 * JSON, one object per line, for the start-up line and for an exception that escapes a thread. A value with CR/LF stays
 * inside one JSON string and cannot start a forged log entry.
 */
class SimulatorLauncherLoggingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FORGED = "x\r\nFAKE 2026-11-16T10:00:00Z INFO signed for mallory";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private Thread.UncaughtExceptionHandler previous;

    @BeforeEach
    void install() {
        previous = Thread.getDefaultUncaughtExceptionHandler();
        SimulatorLauncher.logging(out);
    }

    @AfterEach
    void restore() throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(previous);
        LogManager.getLogManager().readConfiguration();
    }

    @Test
    void everyConsoleLineIsOneEcsJsonObject() throws Exception {
        SimulatorLauncher.logStarted("esp" + FORGED, "127.0.0.1", 9000, -1, java.nio.file.Path.of("data"));
        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
        assertTrue(handler != null, "logging installs a JSON handler for uncaught exceptions");
        handler.uncaughtException(Thread.currentThread(), new IllegalArgumentException("bad option " + FORGED));

        String text = out.toString(StandardCharsets.UTF_8);
        List<JsonNode> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            assertFalse(line.contains("\r"), "no raw CR inside a line: " + line);
            assertFalse(line.stripLeading().startsWith("FAKE"), "no line starts with the forged entry");
            lines.add(JSON.readTree(line));
        }
        assertEquals(2, lines.size(), "two log calls, two lines: " + text);
        JsonNode started = lines.get(0);
        assertEquals("sangam simulator esp" + FORGED + " (DEMO AND TEST ONLY, never production) on 127.0.0.1:9000, data " + java.nio.file.Path.of("data").toAbsolutePath(), started.at("/message").asText(), "the message is intact after parsing");
        assertEquals("INFO", started.at("/log/level").asText());
        assertEquals(SimulatorLauncher.SERVICE, started.at("/service/name").asText());
        JsonNode uncaught = lines.get(1);
        assertEquals("ERROR", uncaught.at("/log/level").asText());
        assertEquals("bad option " + FORGED, uncaught.at("/error/message").asText());
    }
}