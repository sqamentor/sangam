// SPDX-License-Identifier: Apache-2.0
package org.sangam.feel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.dmn.feel.impl.FeelEngine;
import org.operaton.bpm.dmn.feel.impl.FeelException;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.context.VariableContext;

/**
 * Decision 151 (integrator ruling, option A): FEEL {@code now()} and {@code today()} read the configured
 * {@link FeelClock}, each read goes to the clock, and without a clock the engine keeps feel-scala's default clock.
 * The rebuilt engine keeps Operaton's settings (external functions off) and the Sangam policies (strict evaluation).
 */
class FeelClockTest {

    private static final ZonedDateTime FIXED = ZonedDateTime.of(2026, 3, 29, 1, 30, 0, 0, ZoneId.of("Asia/Kolkata"));

    private static VariableContext empty() {
        return Variables.createVariables().asVariableContext();
    }

    @Test
    void nowAndTodayReadTheConfiguredClockOnEveryCall() {
        AtomicInteger reads = new AtomicInteger();
        FeelEngine feel = SangamFeel.feelEngine(() -> {
            reads.incrementAndGet();
            return FIXED;
        });
        Object now = feel.evaluateSimpleExpression("now()", empty());
        assertEquals(FIXED, now);
        assertEquals(LocalDate.of(2026, 3, 29), feel.evaluateSimpleExpression("today()", empty()));
        assertEquals(2, reads.get(), "one clock read per built-in call, never cached");
        assertEquals(Boolean.TRUE, feel.evaluateSimpleExpression("now() = date and time(\"2026-03-29T01:30:00@Asia/Kolkata\")",
                empty()));
    }

    @Test
    void withoutAClockTheDefaultClockIsUsed() {
        FeelEngine feel = SangamFeel.feelEngine();
        ZonedDateTime before = ZonedDateTime.now();
        ZonedDateTime now = (ZonedDateTime) feel.evaluateSimpleExpression("now()", empty());
        ZonedDateTime after = ZonedDateTime.now();
        assertTrue(!now.isBefore(before.minus(Duration.ofSeconds(1))) && !now.isAfter(after.plus(Duration.ofSeconds(1))),
                before + " <= " + now + " <= " + after);
    }

    @Test
    void aClockWithoutATimeFailsTheEvaluation() {
        FeelEngine feel = SangamFeel.feelEngine(() -> null);
        assertThrows(FeelException.class, () -> feel.evaluateSimpleExpression("now()", empty()));
    }

    @Test
    void theRebuiltEngineKeepsExternalFunctionsOffAndStrictEvaluation() {
        FeelEngine feel = SangamFeel.feelEngine(() -> FIXED);
        assertThrows(FeelException.class, () -> feel.evaluateSimpleExpression("{ abs: function(x) external { java: "
                + "{ class: \"java.lang.Math\", method signature: \"abs(int)\" } }, r: abs(-1) }.r", empty()),
                "external functions stay disabled, as Operaton builds them");
        assertThrows(FeelException.class, () -> feel.evaluateSimpleExpression("missing + 1", empty()),
                "SG-34 strict evaluation unchanged");
    }

    @Test
    void installWithAClockSetsItAndAPlainInstallKeepsIt() {
        DefaultDmnEngineConfiguration dmn = new DefaultDmnEngineConfiguration();
        SangamFeel.install(dmn);
        assertNull(((SangamFeel.SangamFeelEngineFactory) dmn.getFeelEngineFactory()).clock());
        FeelClock clock = () -> FIXED;
        SangamFeel.install(dmn, clock);
        assertSame(clock, ((SangamFeel.SangamFeelEngineFactory) dmn.getFeelEngineFactory()).clock());
        SangamFeel.install(dmn);
        assertSame(clock, ((SangamFeel.SangamFeelEngineFactory) dmn.getFeelEngineFactory()).clock(),
                "a later plain install keeps the clock");
        assertEquals(FIXED, dmn.getFeelEngineFactory().createInstance().evaluateSimpleExpression("now()", empty()));
        assertThrows(NullPointerException.class, () -> SangamFeel.install(dmn, null));
    }
}
