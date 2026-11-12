// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** The worker side of the harness protocol, against a child JVM that plays the interpreter (no nsjail needed). */
class ScriptRunnerTest {

    private static final ScriptLimits SHORT = new ScriptLimits(5, Duration.ofSeconds(1), 256L << 20, 16, 16L << 20,
            64 << 10, 16 << 10, 256 << 10);

    private static ScriptOutcome run(String mode, ScriptLimits limits) {
        ScriptRunner r = new ScriptRunner(new FakeHarness.FakeSandbox(mode), limits);
        ScriptRequest req = new ScriptRequest(Language.JAVASCRIPT, "1 + 1", "sum", Map.of("x", 5));
        return r.run(req, Map.of("workId", "w-1"), () -> null);
    }

    @Test
    void theRequestReachesTheInterpreterAndTheAnswerComesBack() {
        ScriptOutcome.Completed c = assertInstanceOf(ScriptOutcome.Completed.class, run("echo", SHORT));
        assertEquals(Map.of("x", 5), c.variables().get("seen"));
        assertEquals(Map.of("workId", "w-1"), c.variables().get("context"));
        assertEquals("1 + 1", c.variables().get("sum"), "the value goes under the result variable");
    }

    @Test
    void fractionsComeBackAsBigDecimal() {
        ScriptOutcome.Completed c = assertInstanceOf(ScriptOutcome.Completed.class, run("decimal", SHORT));
        assertEquals(new BigDecimal("12.10"), c.variables().get("amount"));
        assertTrue(!c.variables().containsKey("sum"), "no value, no result variable");
    }

    @Test
    void aBpmnErrorKeepsItsCodeAndVariables() {
        ScriptOutcome.BpmnErrorRaised b = assertInstanceOf(ScriptOutcome.BpmnErrorRaised.class, run("bpmn", SHORT));
        assertEquals("NOT_ELIGIBLE", b.code());
        assertEquals(Map.of("reason", "age"), b.variables());
    }

    @Test
    void aScriptErrorCarriesStderrForTheIncident() {
        ScriptOutcome.ScriptError e = assertInstanceOf(ScriptOutcome.ScriptError.class, run("error", SHORT));
        assertEquals("TypeError: x is undefined", e.message());
        assertTrue(e.details().contains("trace line"));
    }

    @Test
    void anOversizedResultIsALimitNeverATruncatedResult() {
        assertInstanceOf(ScriptOutcome.LimitExceeded.class, run("flood", SHORT));
    }

    @Test
    void theWallLimitIsEnforcedFromOutsideAndTheChildIsKilled() {
        long t0 = System.nanoTime();
        ScriptOutcome o = run("sleep", SHORT);
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        assertInstanceOf(ScriptOutcome.LimitExceeded.class, o);
        assertTrue(ms < 20_000, "killed after wall + grace, took " + ms + " ms");
    }

    @Test
    void cancellationKillsTheChildAtOnce() {
        AtomicInteger polls = new AtomicInteger();
        ScriptRunner r = new ScriptRunner(new FakeHarness.FakeSandbox("sleep"), ScriptLimits.DEFAULTS);
        ScriptOutcome o = r.run(new ScriptRequest(Language.PYTHON, "", null, Map.of()), Map.of(),
                () -> polls.incrementAndGet() > 3 ? "cancel requested by server" : null);
        assertEquals(new ScriptOutcome.Cancelled("cancel requested by server"), o);
    }

    @Test
    void noAnswerAfterASignalIsALimitAndAnyOtherSilenceIsASandboxFailure() {
        assertInstanceOf(ScriptOutcome.LimitExceeded.class, run("killed", SHORT));
        assertInstanceOf(ScriptOutcome.SandboxFailure.class, run("silent", SHORT));
        assertInstanceOf(ScriptOutcome.SandboxFailure.class, run("garbage", SHORT));
    }

    @Test
    void aMissingSandboxBinaryIsASandboxFailure() {
        ScriptRunner r = new ScriptRunner((l, lim) -> List.of("/nonexistent/nsjail-" + System.nanoTime()), SHORT);
        assertInstanceOf(ScriptOutcome.SandboxFailure.class,
                r.run(new ScriptRequest(Language.PYTHON, "", null, Map.of()), Map.of(), () -> null));
    }

    @Test
    void captureKeepsTheCapAndDrainsTheRest() {
        byte[] data = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        ScriptRunner.Capture c = ScriptRunner.capture(new ByteArrayInputStream(data), 4);
        assertEquals("abcd", new String(c.bytes(), StandardCharsets.UTF_8));
        assertTrue(c.overflow());
        ScriptRunner.Capture exact = ScriptRunner.capture(new ByteArrayInputStream(data), 10);
        assertTrue(!exact.overflow());
    }
}
