// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.field.FieldJson;
import org.sangam.field.template.FieldTemplate;

/** Decision 212: the NUMBER item score uses the same exact, canonical check as ChecklistValidator. */
class ScoringServiceNumberTest {

    private static FieldTemplate.Item item(String bounds) throws Exception {
        return FieldJson.MAPPER.readValue("""
                {"key": "s212", "version": 1, "sections": [ {"id": "s", "items": [
                  {"id": "n", "type": "NUMBER", %s } ] } ] }""".formatted(bounds), FieldTemplate.class).item("n");
    }

    @Test
    void canonicalTextInRangeScoresOne() throws Exception {
        FieldTemplate.Item i = item("\"min\": 0.1, \"max\": 12.5");
        assertEquals(1, ScoringService.itemScore(i, "12.5"));
        assertEquals(1, ScoringService.itemScore(i, "0.1"));
    }

    @Test
    void nonCanonicalTextScoresZero() throws Exception {
        FieldTemplate.Item i = item("\"min\": 0, \"max\": 1000");
        assertEquals(0, ScoringService.itemScore(i, "12.50"));
        assertEquals(0, ScoringService.itemScore(i, "+3"));
        assertEquals(0, ScoringService.itemScore(i, "1E+2"));
        assertEquals(0, ScoringService.itemScore(i, "abc"));
        assertEquals(1, ScoringService.itemScore(i, "100"));
    }

    @Test
    void rangeIsExact() throws Exception {
        FieldTemplate.Item i = item("\"max\": 0.3");
        assertEquals(0, ScoringService.itemScore(i, "0.30000000000000001"), "above 0.3 although the nearest double is 0.3");
        assertEquals(1, ScoringService.itemScore(i, "0.3"));
    }

    @Test
    void jsonNumbersFromOlderClientsScore() throws Exception {
        FieldTemplate.Item i = item("\"min\": 0.1, \"max\": 12.5");
        Map<?, ?> in = FieldJson.MAPPER.readValue("{\"n\": 12.50}", Map.class);
        assertEquals(1, ScoringService.itemScore(i, in.get("n")));
        Map<?, ?> out = FieldJson.MAPPER.readValue("{\"n\": 13}", Map.class);
        assertEquals(0, ScoringService.itemScore(i, out.get("n")));
        assertEquals(1, ScoringService.itemScore(i, new BigDecimal("12.500")));
    }
}
