// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.ports.support.PortsProblem;

/** FC-36 Flowable external-worker translation: acquire, job response, complete/fail/bpmnError/cmmnTerminate, FW-07 fencing. */
class FlowableWorkerCompatTest {

    private static String code(Runnable r) {
        PortsProblem p = assertThrows(PortsProblem.class, r::run);
        return p.code();
    }

    // ------------------------------------------------------------------ acquire

    @Test
    void acquireIsTranslatedToFetchAndLock() {
        Map<String, Object> out = FlowableWorkerCompat.toFetchAndLock(
                "{\"topic\":\"billing\",\"lockDuration\":\"PT30S\",\"numberOfTasks\":7,\"workerId\":\"w-1\"}");
        assertEquals("w-1", out.get("workerId"));
        assertEquals(7, out.get("maxTasks"));
        @SuppressWarnings("unchecked")
        Map<String, Object> topic = ((List<Map<String, Object>>) out.get("topics")).get(0);
        assertEquals("billing", topic.get("name"));
        assertEquals(30_000L, topic.get("lockDurationMs"));
    }

    @Test
    void acquireDefaultsAndClampsTaskCountAndLockDuration() {
        Map<String, Object> out = FlowableWorkerCompat.toFetchAndLock("{\"topic\":\"t\",\"workerId\":\"w\",\"numberOfTasks\":100000}");
        assertEquals(FlowableWorkerCompat.MAX_TASKS, out.get("maxTasks"));
        @SuppressWarnings("unchecked")
        Map<String, Object> topic = ((List<Map<String, Object>>) out.get("topics")).get(0);
        assertEquals(300_000L, topic.get("lockDurationMs"), "Flowable default lock: 5 minutes");
        assertEquals(1, FlowableWorkerCompat.toFetchAndLock("{\"topic\":\"t\",\"workerId\":\"w\",\"numberOfTasks\":-3}").get("maxTasks"));

        assertEquals("INVALID_INPUT", code(() -> FlowableWorkerCompat.toFetchAndLock("{\"topic\":\"t\",\"workerId\":\"w\",\"lockDuration\":\"PT0.5S\"}")));
        assertEquals("INVALID_INPUT", code(() -> FlowableWorkerCompat.toFetchAndLock("{\"topic\":\"t\",\"workerId\":\"w\",\"lockDuration\":\"P2D\"}")));
        assertEquals("INVALID_INPUT", code(() -> FlowableWorkerCompat.toFetchAndLock("{\"topic\":\"t\",\"workerId\":\"w\",\"lockDuration\":\"soon\"}")));
        assertThrows(RuntimeException.class, () -> FlowableWorkerCompat.toFetchAndLock("{\"topic\":\"t\"}"), "workerId required");
        assertThrows(RuntimeException.class, () -> FlowableWorkerCompat.toFetchAndLock("{\"workerId\":\"w\"}"), "topic required");
        assertThrows(RuntimeException.class, () -> FlowableWorkerCompat.toFetchAndLock("{\"topic\":\"t\",\"workerId\":\"" + "w".repeat(256) + "\"}"));
    }

    // ------------------------------------------------------------------ job response

    @Test
    void leasedWorkBecomesAFlowableJobWithTheFencingToken() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("n", 3);
        vars.put("big", 4L);
        vars.put("ok", true);
        vars.put("when", Date.from(Instant.parse("2026-01-02T03:04:05Z")));
        vars.put("doc", Map.of("a", 1));
        vars.put("none", null);
        ExternalWork w = new ExternalWork("job-1", "billing", "pi-1", "task", "act-1", 2, Instant.parse("2026-01-01T00:00:00Z"),
                17L, "att-9", vars, "op-1");
        Map<String, Object> job = FlowableWorkerCompat.toFlowableJob(w, "acc");
        assertEquals("job-1", job.get("id"));
        assertEquals("op-1", job.get("correlationId"));
        assertEquals("pi-1", job.get("processInstanceId"));
        assertEquals("act-1", job.get("executionId"));
        assertEquals("task", job.get("elementId"));
        assertEquals("acc", job.get("tenantId"));
        assertEquals("2026-01-01T00:00:00Z", job.get("lockExpirationTime"));
        assertEquals(17L, job.get("sangamLockVersion"));
        assertEquals("att-9", job.get("sangamAttemptId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fv = (List<Map<String, Object>>) job.get("variables");
        Map<String, String> types = new LinkedHashMap<>();
        fv.forEach(m -> types.put((String) m.get("name"), (String) m.get("type")));
        assertEquals(Map.of("n", "integer", "big", "long", "ok", "boolean", "when", "date", "doc", "json", "none", "null"), types);
        assertEquals("2026-01-02T03:04:05Z", fv.get(3).get("value"), "dates as ISO instants");
    }

    // ------------------------------------------------------------------ complete / fencing (FW-07)

    @Test
    void completeUsesTheEchoedLockVersionAndTypedVariables() {
        Map<String, Object> out = FlowableWorkerCompat.toComplete("{\"workerId\":\"w\",\"sangamLockVersion\":5,\"sangamAttemptId\":\"a1\","
                + "\"variables\":[{\"name\":\"n\",\"type\":\"integer\",\"value\":42},{\"name\":\"l\",\"type\":\"long\",\"value\":\"9000000000\"},"
                + "{\"name\":\"d\",\"type\":\"date\",\"value\":\"2026-01-02T03:04:05Z\"},{\"name\":\"j\",\"type\":\"json\",\"value\":{\"x\":[1]}},"
                + "{\"name\":\"s\",\"value\":\"text\"},{\"name\":\"z\",\"type\":\"integer\",\"value\":null}]}", -1);
        assertEquals(5L, out.get("lockVersion"));
        assertEquals("a1", out.get("attemptId"));
        assertEquals(5L, FlowableWorkerCompat.fencingToken(out, false));
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) out.get("variables");
        assertEquals(42, v.get("n"));
        assertEquals(9_000_000_000L, v.get("l"));
        assertEquals(Date.from(Instant.parse("2026-01-02T03:04:05Z")), v.get("d"));
        assertEquals("{\"x\":[1]}", org.sangam.ports.support.Json.write(v.get("j")), "json value kept as a structure");
        assertEquals("text", v.get("s"));
        assertTrue(v.containsKey("z"));
        assertNull(v.get("z"));
    }

    @Test
    void anUnfencedCompletionIsRefusedUnlessTheCallerOptsIn() {
        Map<String, Object> out = FlowableWorkerCompat.toComplete("{\"workerId\":\"w\"}", -1);
        assertEquals(FlowableWorkerCompat.UNFENCED, out.get("lockVersion"), "never a guessed version");
        PortsProblem p = assertThrows(PortsProblem.class, () -> FlowableWorkerCompat.fencingToken(out, false));
        assertEquals("FENCING_TOKEN_REQUIRED", p.code());
        assertEquals(409, p.status());
        assertEquals(FlowableWorkerCompat.UNFENCED, FlowableWorkerCompat.fencingToken(out, true));

        assertEquals(8L, FlowableWorkerCompat.toComplete("{\"workerId\":\"w\"}", 8).get("lockVersion"), "caller-known version");
        assertEquals(3L, FlowableWorkerCompat.toComplete("{\"workerId\":\"w\",\"sangamLockVersion\":3}", 8).get("lockVersion"),
                "the echoed token wins over the fallback");
        assertEquals("INVALID_INPUT", code(() -> FlowableWorkerCompat.toComplete("{\"workerId\":\"w\",\"sangamLockVersion\":-2}", -1)));
        assertEquals("INVALID_INPUT", code(() -> FlowableWorkerCompat.toComplete("{\"workerId\":\"w\",\"sangamLockVersion\":\"x\"}", -1)));
    }

    @Test
    void bpmnErrorAndCmmnTerminateTranslations() {
        Map<String, Object> err = FlowableWorkerCompat.toComplete("{\"workerId\":\"w\",\"sangamLockVersion\":1,\"errorCode\":\"E42\"}", -1);
        assertEquals("E42", err.get("errorCode"));
        Map<String, Object> t = FlowableWorkerCompat.toCmmnTerminate(
                "{\"workerId\":\"w\",\"sangamLockVersion\":1,\"variables\":[{\"name\":\"a\",\"value\":\"b\"}]}", -1);
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) t.get("variables");
        assertEquals(Map.of("a", "b", "_cmmnTerminate", true), v);
    }

    @Test
    void variableGuardsRejectReservedNamesTooManyVariablesAndBadValues() {
        assertEquals("RESERVED_VARIABLE", code(() -> FlowableWorkerCompat.toComplete(
                "{\"workerId\":\"w\",\"variables\":[{\"name\":\"_SANGAM_x\",\"value\":\"1\"}]}", 1)));
        assertEquals("RESERVED_VARIABLE", code(() -> FlowableWorkerCompat.toComplete(
                "{\"workerId\":\"w\",\"variables\":[{\"name\":\"_FLOWABLE_x\",\"value\":\"1\"}]}", 1)));
        StringBuilder many = new StringBuilder("{\"workerId\":\"w\",\"variables\":[");
        for (int i = 0; i <= FlowableWorkerCompat.MAX_VARIABLES; i++) {
            many.append(i == 0 ? "" : ",").append("{\"name\":\"v").append(i).append("\",\"value\":1}");
        }
        String tooMany = many.append("]}").toString();
        assertEquals("LIMIT_EXCEEDED", code(() -> FlowableWorkerCompat.toComplete(tooMany, 1)));
        assertEquals("INVALID_INPUT", code(() -> FlowableWorkerCompat.toComplete(
                "{\"workerId\":\"w\",\"variables\":[{\"name\":\"n\",\"type\":\"integer\",\"value\":3000000000}]}", 1)));
        assertEquals("INVALID_INPUT", code(() -> FlowableWorkerCompat.toComplete(
                "{\"workerId\":\"w\",\"variables\":[{\"name\":\"d\",\"type\":\"date\",\"value\":\"yesterday\"}]}", 1)));
    }

    /**
     * A typed value that is not of its type must be refused, not silently replaced: Jackson's {@code asInt/asLong/
     * asDouble/asBoolean} turn {@code "12x"} into {@code 0} / {@code false}, which would complete the job with corrupted
     * business data. Numeric text that parses ({@code "123"}) stays accepted.
     */
    @Test
    void typedValuesThatDoNotParseAreInvalidNotCoercedToZeroOrFalse() {
        for (String bad : List.of(
                "{\"name\":\"n\",\"type\":\"integer\",\"value\":\"12x\"}",
                "{\"name\":\"n\",\"type\":\"integer\",\"value\":1.5}",
                "{\"name\":\"n\",\"type\":\"long\",\"value\":\"abc\"}",
                "{\"name\":\"n\",\"type\":\"double\",\"value\":\"abc\"}",
                "{\"name\":\"n\",\"type\":\"boolean\",\"value\":\"maybe\"}",
                "{\"name\":\"n\",\"type\":\"integer\",\"value\":{\"a\":1}}")) {
            assertEquals("INVALID_INPUT", code(() -> FlowableWorkerCompat.toComplete("{\"workerId\":\"w\",\"variables\":[" + bad + "]}", 1)), bad);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) FlowableWorkerCompat.toComplete("{\"workerId\":\"w\",\"variables\":["
                + "{\"name\":\"i\",\"type\":\"integer\",\"value\":\"123\"},{\"name\":\"i2\",\"type\":\"integer\",\"value\":7.0},"
                + "{\"name\":\"d\",\"type\":\"double\",\"value\":\"2.5\"},{\"name\":\"b\",\"type\":\"boolean\",\"value\":\"TRUE\"},"
                + "{\"name\":\"b2\",\"type\":\"boolean\",\"value\":false}]}", 1).get("variables");
        assertEquals(123, v.get("i"));
        assertEquals(7, v.get("i2"));
        assertEquals(2.5, v.get("d"));
        assertEquals(true, v.get("b"));
        assertEquals(false, v.get("b2"));
    }

    // ------------------------------------------------------------------ fail

    @Test
    void failIsTranslatedWithBoundedMessageRetriesAndBackoff() {
        Map<String, Object> out = FlowableWorkerCompat.toFail("{\"workerId\":\"w\",\"sangamLockVersion\":2,\"errorMessage\":\"boom\","
                + "\"errorDetails\":\"stack\",\"retries\":3,\"retryTimeout\":\"PT10S\"}", -1);
        assertEquals(2L, out.get("lockVersion"));
        assertEquals("boom\nstack", out.get("errorMessage"));
        assertEquals(3, out.get("retries"));
        assertEquals(10_000L, out.get("retryBackoffMs"));

        Map<String, Object> clamped = FlowableWorkerCompat.toFail("{\"workerId\":\"w\",\"errorMessage\":\"" + "x".repeat(5000)
                + "\",\"retries\":1000,\"retryTimeout\":\"P30D\"}", -1);
        assertEquals(FlowableWorkerCompat.MAX_ERROR_CHARS, ((String) clamped.get("errorMessage")).length());
        assertEquals(100, clamped.get("retries"));
        assertEquals(FlowableWorkerCompat.MAX_LOCK_MS, clamped.get("retryBackoffMs"));
        assertEquals(FlowableWorkerCompat.UNFENCED, clamped.get("lockVersion"));
        assertFalse(clamped.containsKey("attemptId"));

        Map<String, Object> neg = FlowableWorkerCompat.toFail("{\"workerId\":\"w\",\"retries\":-5}", 1);
        assertEquals(0, neg.get("retries"));
        assertEquals(0L, neg.get("retryBackoffMs"));
        assertEquals("INVALID_INPUT", code(() -> FlowableWorkerCompat.toFail("{\"workerId\":\"w\",\"retryTimeout\":\"later\"}", 1)));
    }
}
