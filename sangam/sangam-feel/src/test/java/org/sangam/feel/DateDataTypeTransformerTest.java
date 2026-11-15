// SPDX-License-Identifier: Apache-2.0
package org.sangam.feel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Date;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnEngine;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.impl.spi.type.DmnDataTypeTransformer;

/**
 * ADR-D-26: an input typed {@code date} takes the FEEL date its expression evaluates to, so a table can select its row
 * by a date range (the rounding policies and wage rules in force on a business date). Without
 * {@link DateDataTypeTransformer} the evaluation fails with "'java.time.LocalDate' cannot be converted to
 * 'java.util.Date'".
 */
class DateDataTypeTransformerTest {

    private static final String TABLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="https://example.org/date">
              <decision id="inForce" name="inForce">
                <decisionTable id="t" hitPolicy="UNIQUE">
                  <input id="i"><inputExpression id="ie" typeRef="date"><text>date(asOfDate)</text></inputExpression></input>
                  <output id="o" name="version" typeRef="string"/>
                  <rule id="r1"><inputEntry id="r1i"><text>[date("2025-04-01")..date("2026-03-31")]</text></inputEntry><outputEntry id="r1o"><text>"v1"</text></outputEntry></rule>
                  <rule id="r2"><inputEntry id="r2i"><text>[date("2026-04-01")..date("2027-03-31")]</text></inputEntry><outputEntry id="r2o"><text>"v2"</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """;

    private static Object version(Object asOfDate) {
        DmnEngine e = SangamFeel.dmnEngineConfiguration().buildEngine();
        DmnDecision d = e.parseDecisions(new ByteArrayInputStream(TABLE.strip().getBytes(StandardCharsets.UTF_8)))
                .get(0);
        var result = e.evaluateDecision(d, Map.of("asOfDate", asOfDate));
        return result.isEmpty() ? null : result.getSingleResult().getSingleEntry();
    }

    @Test
    void aDateTypedInputSelectsTheRowInForceOnTheDay() {
        assertEquals("v1", version("2025-04-01"), "first day of the first range");
        assertEquals("v1", version("2026-03-31"), "last day of the first range");
        assertEquals("v2", version("2026-04-01"), "first day of the second range");
        assertEquals("v2", version("2027-03-31"), "last day of the second range");
        assertEquals(null, version("2027-04-01"), "no row in force: no result, never a neighbouring row");
    }

    @Test
    void aFeelDateStaysADateAndOtherValuesGoToTheReplacedTransformer() {
        DefaultDmnEngineConfiguration cfg = SangamFeel.dmnEngineConfiguration();
        DmnDataTypeTransformer t = cfg.getTransformer().getDataTypeTransformerRegistry()
                .getTransformer(DateDataTypeTransformer.TYPE);
        assertInstanceOf(DateDataTypeTransformer.class, t);
        LocalDate day = LocalDate.of(2026, 4, 1);
        assertSame(day, t.transform(day).getValue(), "no time and no zone is invented");
        Date instant = new Date(0);
        assertEquals(instant, t.transform(instant).getValue(), "a date-time value as before");
        assertTrue(t.transform("2026-04-01T10:15:00").getValue() instanceof Date, "an ISO date-time string as before");
        assertThrows(RuntimeException.class, () -> t.transform(42), "a value that is no date is still refused");
    }

    @Test
    void aRepeatedInstallKeepsOneWrapper() {
        DefaultDmnEngineConfiguration cfg = SangamFeel.dmnEngineConfiguration();
        DmnDataTypeTransformer first = cfg.getTransformer().getDataTypeTransformerRegistry()
                .getTransformer(DateDataTypeTransformer.TYPE);
        SangamFeel.install(cfg);
        assertSame(first, cfg.getTransformer().getDataTypeTransformerRegistry()
                .getTransformer(DateDataTypeTransformer.TYPE));
    }
}