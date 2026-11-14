// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M-125 {@code sangam diff}: offline semantic diff, text and JSON output, {@code --fail-on} exit codes, refusals. */
class ModelDiffCommandTest {

    static final String V1 = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"d\" targetNamespace=\"t\">"
            + "<process id=\"p\"><startEvent id=\"s\"/></process></definitions>";
    static final String V2 = V1.replace("<startEvent id=\"s\"/>", "<startEvent id=\"s\"/><endEvent id=\"e\" name=\"Done\"/>");

    @TempDir
    Path dir;

    final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    int run(String... args) {
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        Main.Http offline = r -> {
            throw new AssertionError("sangam diff must not call the server: " + r.uri());
        };
        return new Main(offline, dir.resolve("cli.json"), out, err, k -> null, null,
                Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC)).run(args);
    }

    String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    Path file(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void textReportExitsZeroWithoutFailOn() throws IOException {
        Path a = file("v1.bpmn", V1);
        Path b = file("order.bpmn", V2);
        assertEquals(Main.OK, run("diff", a.toString(), b.toString()));
        List<String> lines = out().lines().toList();
        assertEquals("order.bpmn (BPMN): 1 change", lines.get(0));
        assertEquals("+ ELEMENT endEvent e \"Done\"", lines.get(1));
        assertEquals(2, lines.size());
    }

    @Test
    void jsonReportIsTheRestShape() throws IOException {
        Path a = file("a.bpmn", V1);
        Path b = file("b.bpmn", V2);
        assertEquals(Main.OK, run("diff", a.toString(), b.toString(), "--json", "--resource", "order.bpmn"));
        JsonNode r = new ObjectMapper().readTree(out());
        assertEquals("order.bpmn", r.get("resource").asText());
        assertEquals("BPMN", r.get("format").asText());
        assertTrue(r.get("changed").asBoolean());
        assertEquals(1, r.get("summary").get("ELEMENT").asInt());
        assertEquals(0, r.get("summary").get("FLOW").asInt());
        assertEquals("ADDED", r.get("changes").get(0).get("kind").asText());
        assertTrue(r.get("changes").get(0).get("before").isNull());
    }

    @Test
    void failOnLevelDecidesTheExitCode() throws IOException {
        Path a = file("a.bpmn", V1);
        Path b = file("b.bpmn", V2);
        assertEquals(Main.REFUSED, run("diff", a.toString(), b.toString(), "--fail-on", "flow,ELEMENT"));
        assertTrue(err().contains("1 change at a --fail-on level"), err());
        assertEquals(Main.OK, run("diff", a.toString(), b.toString(), "--fail-on", "FLOW,EXPRESSION"));
        assertEquals(Main.OK, run("diff", a.toString(), a.toString(), "--fail-on", "ELEMENT,FLOW,EXPRESSION,PROPERTY"));
    }

    @Test
    void emptyFileIsAnAbsentVersion() throws IOException {
        Path none = file("empty", "");
        Path b = file("order.bpmn", V2);
        assertEquals(Main.OK, run("diff", none.toString(), b.toString()));
        assertTrue(out().startsWith("order.bpmn (BPMN): "), out());
        assertTrue(out().contains("+ ELEMENT endEvent e \"Done\""), out());
    }

    @Test
    void refusalsAreUsageErrors() throws IOException {
        Path a = file("a.bpmn", V1);
        Path bad = file("bad.bpmn", "<not xml");
        Path none = file("none.bpmn", "");
        Path missing = dir.resolve("missing.bpmn");
        assertEquals(Main.USAGE, run("diff", a.toString()));
        assertEquals(Main.USAGE, run("diff", a.toString(), a.toString(), "--fail-on"));
        assertEquals(Main.USAGE, run("diff", a.toString(), a.toString(), "--fail-on", "NODE"));
        assertEquals(Main.USAGE, run("diff", a.toString(), a.toString(), "--color"));
        assertEquals(Main.USAGE, run("diff", a.toString(), missing.toString()));
        assertTrue(err().contains("cannot read"), err());
        assertEquals(Main.USAGE, run("diff", a.toString(), bad.toString()));
        assertTrue(err().contains("MODEL_UNREADABLE"), err());
        assertEquals(Main.USAGE, run("diff", none.toString(), none.toString()));
        assertTrue(err().contains("nothing to compare"), err());
    }
}
