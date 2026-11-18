// SPDX-License-Identifier: Apache-2.0
package org.sangam.feel;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnEngine;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Owner ruling G1 (DAT-1, 2026-11-18): a zoned date-time handed to a Sangam DMN engine is normalised to a UTC instant
 * at the Sangam boundary ({@link SangamFeelEngine}: caller variables only), so two variables holding the same instant in different zones
 * are equal, and a decision sees the instant in UTC. Literal date-time equality is refused at deploy
 * ({@code SANGAM-DMN-DATETIME-EQ}).
 */
class DateTimeUtcBoundaryTest {

    private static Object eval(String expression, Map<String, Object> vars) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\""
                + " id=\"g\" name=\"g\" namespace=\"https://sangam.org/g1\"><decision id=\"d\" name=\"d\"><variable name=\"d\"/>"
                + "<literalExpression id=\"l\"><text>" + expression + "</text></literalExpression></decision></definitions>";
        DmnEngine e = SangamFeel.dmnEngineConfiguration().buildEngine();
        DmnDecision d = e.parseDecisions(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).get(0);
        return e.evaluateDecision(d, new HashMap<>(vars)).getSingleResult().getSingleEntry();
    }

    private static final ZonedDateTime KOLKATA = ZonedDateTime.of(2026, 11, 8, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"));
    private static final ZonedDateTime UTC = KOLKATA.withZoneSameInstant(ZoneOffset.UTC);

    @Test
    void theSameInstantInTwoZonesIsEqual() {
        assertEquals(true, eval("a = b", Map.of("a", KOLKATA, "b", UTC)));
        assertEquals(false, eval("a != b", Map.of("a", KOLKATA, "b", UTC)));
    }

    @Test
    void offsetDateTimeAndInstantAreTheSameInstantToo() {
        OffsetDateTime plus2 = UTC.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.ofHours(2));
        assertEquals(true, eval("a = b", Map.of("a", plus2, "b", KOLKATA)));
        assertEquals(true, eval("a = b", Map.of("a", Instant.from(UTC), "b", KOLKATA)));
    }

    @Test
    void aZonedInputReachesTheDecisionAsAUtcInstant() {
        Object v = eval("a", Map.of("a", KOLKATA));
        ZonedDateTime z = (ZonedDateTime) v;
        assertEquals(ZoneOffset.UTC, z.getZone().normalized());
        assertEquals(KOLKATA.toInstant(), z.toInstant());
    }

    @Test
    void orderingIsUnchanged() {
        assertEquals(true, eval("a &lt; b", Map.of("a", KOLKATA, "b", UTC.plusSeconds(1))));
    }
}
