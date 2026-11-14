// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.replay;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.feel.impl.FeelEngine;
import org.operaton.bpm.engine.variable.Variables;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.feel.SangamFeel;

/**
 * Decision 151 capture side (integrator ruling 2): the {@link CapturedInput} shape W4-G ACKed (names equal to the
 * modeller's {@code ReplayFixture.EventType} and {@code TaskOutcome}, typed payload only), the production refusal,
 * and FEEL {@code now()}/{@code today()} reading the engine clock with one {@code CLOCK_READ} per read.
 * Written, NOT_RUN.
 */
class ReplayCaptureSpiTest {

    private static final Instant T = Instant.parse("2026-11-13T02:30:00Z");

    @Test
    void typeAndOutcomeNamesEqualTheModellerEnums() {
        assertEquals(Set.of("CLOCK_READ", "TIMER_SCHEDULED", "TIMER_FIRED", "MESSAGE_DELIVERED", "EXTERNAL_TASK_RESULT",
                "RANDOM_VALUE", "GENERATED_ID", "EXTERNAL_LOOKUP"), CapturedInput.TYPES);
        assertEquals(Set.of("SUCCESS", "FAILURE", "BPMN_ERROR", "TIMEOUT"), CapturedInput.OUTCOMES);
    }

    @Test
    void anInputIsValidatedAndItsPayloadCopied() {
        Map<String, Object> p = new HashMap<>();
        p.put("amount", new BigDecimal("10.50"));
        p.put("count", 3L);
        p.put("ok", true);
        p.put("name", "x");
        p.put("when", T);
        p.put("local", ZonedDateTime.ofInstant(T, ZoneId.of("Asia/Kolkata")));
        p.put("none", null);
        CapturedInput in = new CapturedInput(CapturedInput.EXTERNAL_TASK_RESULT, "e1", "a1", "ai1", 2, T, "FAILURE", 1, p);
        p.put("late", "added after");
        assertEquals(7, in.payload().size(), "a copy, not a view");
        assertThrows(UnsupportedOperationException.class, () -> in.payload().put("x", "y"));

        assertThrows(IllegalArgumentException.class, () -> new CapturedInput("CLOCK", null, null, null, 1, T, null, null,
                Map.of()), "unknown type");
        assertThrows(NullPointerException.class, () -> new CapturedInput(CapturedInput.CLOCK_READ, null, null, null, 1,
                null, null, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new CapturedInput(CapturedInput.TIMER_FIRED, "e", "a", null, 0,
                T, null, null, Map.of()), "attempt starts at 1");
        assertThrows(IllegalArgumentException.class, () -> new CapturedInput(CapturedInput.EXTERNAL_TASK_RESULT, "e", "a",
                null, 1, T, null, null, Map.of()), "a result needs an outcome");
        assertThrows(IllegalArgumentException.class, () -> new CapturedInput(CapturedInput.EXTERNAL_TASK_RESULT, "e", "a",
                null, 1, T, "LOST", null, Map.of()), "unknown outcome");
        assertThrows(IllegalArgumentException.class, () -> new CapturedInput(CapturedInput.TIMER_FIRED, "e", "a", null, 1,
                T, "SUCCESS", null, Map.of()), "outcome only on external task results");
        assertThrows(IllegalArgumentException.class, () -> new CapturedInput(CapturedInput.MESSAGE_DELIVERED, "e", "a",
                null, 1, T, null, null, Map.of("n", 3)), "an Integer is not a replayable value");
        assertThrows(IllegalArgumentException.class, () -> new CapturedInput(CapturedInput.MESSAGE_DELIVERED, "e", "a",
                null, 1, T, null, null, Map.of("d", 1.5d)), "a double is not a replayable value");
    }

    @Test
    void captureIsRefusedUnlessTheHostSaidNotProduction() {
        CaptureListener l = in -> { };
        assertDoesNotThrow(() -> CaptureListener.requireAllowed(null, null, List.of("prod")), "no listener: nothing to check");
        assertThrows(IllegalStateException.class, () -> CaptureListener.requireAllowed(l, null, List.of()),
                "a missing switch refuses");
        assertThrows(IllegalStateException.class, () -> CaptureListener.requireAllowed(l, true, List.of()));
        assertThrows(IllegalStateException.class, () -> CaptureListener.requireAllowed(l, false, List.of("eu-prod")),
                "a production profile refuses even with the switch off");
        assertDoesNotThrow(() -> CaptureListener.requireAllowed(l, false, List.of("test")));

        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        assertNull(s.getCaptureListener());
        assertNull(s.getCaptureProduction());
        s.enableReplayCapture(l, false);
        assertEquals(l, s.getCaptureListener());
        assertEquals(Boolean.FALSE, s.getCaptureProduction());
    }

    @Test
    void feelNowAndTodayReadTheEngineClockAndEachReadIsCaptured() {
        List<CapturedInput> seen = new ArrayList<>();
        AtomicReference<Instant> engineClock = new AtomicReference<>(T);
        FeelEngine feel = SangamFeel.feelEngine(new CapturingFeelClock(engineClock::get, ZoneId.of("UTC"), seen::add));

        assertEquals(ZonedDateTime.ofInstant(T, ZoneId.of("UTC")),
                feel.evaluateSimpleExpression("now()", Variables.createVariables().asVariableContext()));
        engineClock.set(T.plusSeconds(86_400));
        assertEquals(LocalDate.of(2026, 11, 14),
                feel.evaluateSimpleExpression("today()", Variables.createVariables().asVariableContext()));

        assertEquals(2, seen.size(), "one CLOCK_READ per read");
        assertEquals(CapturedInput.CLOCK_READ, seen.get(0).type());
        assertEquals(T, seen.get(0).at(), "the value read is the event time");
        assertEquals(T.plusSeconds(86_400), seen.get(1).at());
        assertNull(seen.get(0).executionId(), "no execution context outside the engine");
        assertNull(seen.get(0).outcome());
        assertTrue(seen.get(0).payload().isEmpty());
        assertEquals(1, seen.get(0).attempt());
    }
}
