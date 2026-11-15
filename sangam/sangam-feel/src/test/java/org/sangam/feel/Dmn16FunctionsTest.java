// SPDX-License-Identifier: Apache-2.0
package org.sangam.feel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.feel.impl.FeelEngine;
import org.operaton.bpm.dmn.feel.impl.FeelException;
import org.operaton.bpm.dmn.feel.impl.scala.ScalaFeelEngine;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.context.VariableContext;

/**
 * Decision 158 (M-126): {@code date and time(date, time, timezone)} of DMN 1.6 (formal/25-12-02, Table 73) on every
 * Sangam FEEL engine. The pinned FEEL engine itself does not have it ({@link #thePinnedEngineAloneHasNoThreeArgumentForm}
 * pins that fact: when a later engine version brings the function, that test fails and {@link Dmn16Functions} is
 * reviewed).
 */
class Dmn16FunctionsTest {

    private static final FeelEngine FEEL = SangamFeel.feelEngine();

    private static VariableContext empty() {
        return Variables.createVariables().asVariableContext();
    }

    private static Object eval(String expression) {
        return FEEL.evaluateSimpleExpression(expression, empty());
    }

    @Test
    void thePinnedEngineAloneHasNoThreeArgumentForm() {
        FeelEngine stock = new ScalaFeelEngine(List.of());
        Object r;
        try {
            r = stock.evaluateSimpleExpression("date and time(date(\"2024-12-24\"), time(\"23:59:00\"), \"Z\")", empty());
        } catch (RuntimeException e) {
            return; // a failure is the other way the stock engine can say "no such function"
        }
        assertEquals(null, r, "feel-engine 1.22.1 has date and time(from) and date and time(date, time) only; "
                + "a value here means the pinned engine now has the DMN 1.6 form: review Dmn16Functions");
    }

    @Test
    void theSpecificationExamplesHold() {
        assertEquals(Boolean.TRUE, eval("date and time(date(\"2024-12-24\"), time(\"23:59:00\"), \"Z\") "
                + "= date and time(\"2024-12-24T23:59:00Z\")"), "Table 73, offset example");
        assertEquals(Boolean.TRUE, eval("date and time(date(\"2024-12-24\"), time(\"23:59:00\"), \"America/Costa_Rica\") "
                + "= date and time(\"2024-12-24T23:59:00@America/Costa_Rica\")"), "Table 73, IANA example");
    }

    @Test
    void theValueIsADateTimeInTheGivenZone() {
        assertEquals(ZonedDateTime.of(LocalDate.of(2024, 12, 24), LocalTime.of(23, 59), ZoneOffset.ofHoursMinutes(5, 30)),
                eval("date and time(date(\"2024-12-24\"), time(\"23:59:00\"), \"+05:30\")"));
        assertEquals(ZonedDateTime.of(LocalDate.of(2024, 12, 24), LocalTime.of(23, 59), ZoneId.of("Asia/Kolkata")),
                eval("date and time(date(\"2024-12-24\"), time(\"23:59:00\"), \"Asia/Kolkata\")"));
        assertEquals(ZonedDateTime.of(LocalDate.of(2024, 12, 24), LocalTime.of(23, 59), ZoneOffset.ofHours(-6)),
                eval("date and time(date(\"2024-12-24\"), time(\"23:59:00\"), \"-06:00\")"));
    }

    @Test
    void aDateAndTimeGivesItsDateOnly() {
        assertEquals(Boolean.TRUE, eval("date and time(date and time(\"2024-12-24T08:00:00\"), time(\"23:59:00\"), \"Z\") "
                + "= date and time(\"2024-12-24T23:59:00Z\")"), "a local date and time: its time is ignored");
        assertEquals(Boolean.TRUE, eval("date and time(date and time(\"2024-12-24T08:00:00+05:30\"), time(\"23:59:00\"), \"Z\") "
                + "= date and time(\"2024-12-24T23:59:00Z\")"), "a date and time with an offset: time and offset are ignored");
    }

    @Test
    void namedParametersWork() {
        assertEquals(Boolean.TRUE, eval("date and time(date: date(\"2024-12-24\"), time: time(\"23:59:00\"), timezone: \"Z\") "
                + "= date and time(\"2024-12-24T23:59:00Z\")"));
    }

    @Test
    void theBuiltInFormsAreUntouched() {
        assertEquals(Boolean.TRUE, eval("date and time(\"2012-12-24T23:59:00\") "
                + "= date and time(date(\"2012-12-24\"), time(\"23:59:00\"))"), "Table 73, two-argument example");
        assertEquals(Boolean.TRUE, eval("date and time(\"2012-12-24T23:59:00\") + duration(\"PT1M\") "
                + "= date and time(\"2012-12-25T00:00:00\")"), "Table 73, one-argument example");
    }

    @Test
    void argumentsOutsideTheDomainFailTheEvaluation() {
        for (String bad : List.of(
                "date and time(date(\"2024-12-24\"), time(\"23:59:00Z\"), \"Z\")",            // time with an offset
                "date and time(date(\"2024-12-24\"), time(\"23:59:00@Asia/Kolkata\"), \"Z\")", // time with a zone
                "date and time(date(\"2024-12-24\"), time(\"23:59:00\"), \"Mars/Olympus\")",   // unknown zone
                "date and time(date(\"2024-12-24\"), time(\"23:59:00\"), \"UTC+1\")",          // neither offset nor IANA
                "date and time(date(\"2024-12-24\"), time(\"23:59:00\"), \"+25:00\")",         // offset out of range
                "date and time(date(\"2024-12-24\"), time(\"23:59:00\"), 5)",                  // not a string
                "date and time(\"2024-12-24\", time(\"23:59:00\"), \"Z\")",                    // a string is not a date
                "date and time(date(\"2024-12-24\"), null, \"Z\")")) {
            FeelException e = assertThrows(FeelException.class, () -> eval(bad), bad);
            assertTrue(e.getMessage().contains(Dmn16Functions.CODE), bad + ": " + e.getMessage());
        }
    }

    @Test
    void theFunctionItselfChecksItsArguments() {
        assertEquals(ZonedDateTime.of(LocalDate.of(2024, 3, 1), LocalTime.NOON, ZoneOffset.UTC),
                Dmn16Functions.dateAndTime(List.of(LocalDate.of(2024, 3, 1), LocalTime.NOON, "Z")));
        assertThrows(IllegalArgumentException.class, () -> Dmn16Functions.dateAndTime(List.of(LocalDate.of(2024, 3, 1), "Z")));
        assertThrows(IllegalArgumentException.class,
                () -> Dmn16Functions.dateAndTime(List.of(LocalDate.of(2024, 3, 1), LocalTime.NOON, "z")));
    }
}
