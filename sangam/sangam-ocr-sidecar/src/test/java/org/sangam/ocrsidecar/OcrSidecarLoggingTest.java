// SPDX-License-Identifier: Apache-2.0
package org.sangam.ocrsidecar;

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
 * Owner decision 324 for the OCR sidecar: {@link OcrSidecarMain#logging} (the first step of {@code main}) makes the
 * console log ECS JSON, one object per line, for the start-up line, for {@link System.Logger} of the service classes
 * and for an exception that escapes a thread. A value with CR/LF stays inside one JSON string and cannot start a
 * forged log entry.
 */
class OcrSidecarLoggingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FORGED = "x\r\nFAKE 2026-11-15T10:00:00Z INFO accepted token of tenant mallory";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private Thread.UncaughtExceptionHandler previous;

    @BeforeEach
    void install() {
        previous = Thread.getDefaultUncaughtExceptionHandler();
        OcrSidecarMain.logging(out);
    }

    @AfterEach
    void restore() throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(previous);
        LogManager.getLogManager().readConfiguration();
    }

    @Test
    void everyConsoleLineIsOneEcsJsonObject() throws Exception {
        OcrSidecarMain.logStarted("0.0.0.0", 8086, "mesh", "tesseract 5", List.of("eng", "hin"), FORGED);
        System.getLogger(OcrService.class.getName()).log(System.Logger.Level.WARNING, "recognition failed " + FORGED,
                new IllegalStateException("engine " + FORGED));
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(),
                new IllegalArgumentException(OcrSidecarMain.LANGUAGES_ENV + " " + FORGED));

        List<JsonNode> lines = lines();
        assertEquals(3, lines.size(), "three log calls, three lines");
        JsonNode started = lines.get(0);
        assertEquals("sangam-ocr-sidecar listening on 0.0.0.0:8086 (mesh) with tesseract 5 for [eng, hin]; NOT READY: "
                + FORGED, started.at("/message").asText(), "the message is intact after parsing");
        assertEquals("INFO", started.at("/log/level").asText());
        assertEquals(OcrSidecarMain.class.getName(), started.at("/log/logger").asText());
        assertEquals(OcrSidecarMain.SERVICE, started.at("/service/name").asText());
        assertEquals("8.11", started.at("/ecs/version").asText());
        assertTrue(started.at("/process/pid").asLong() > 0);
        assertFalse(started.at("/@timestamp").asText().isEmpty());

        JsonNode warned = lines.get(1);
        assertEquals("WARN", warned.at("/log/level").asText());
        assertEquals(IllegalStateException.class.getName(), warned.at("/error/type").asText());
        assertEquals("engine " + FORGED, warned.at("/error/message").asText());
        assertTrue(warned.at("/error/stack_trace").asText().contains("everyConsoleLineIsOneEcsJsonObject"));

        JsonNode uncaught = lines.get(2);
        assertEquals("ERROR", uncaught.at("/log/level").asText());
        assertEquals(IllegalArgumentException.class.getName(), uncaught.at("/error/type").asText());
    }

    private List<JsonNode> lines() throws Exception {
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.endsWith("\n"), "every entry ends its line");
        List<JsonNode> parsed = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            assertFalse(line.contains("\r"), "no raw CR inside a line: " + line);
            assertFalse(line.stripLeading().startsWith("FAKE"), "no line starts with the forged entry");
            parsed.add(JSON.readTree(line));
        }
        return parsed;
    }
}
