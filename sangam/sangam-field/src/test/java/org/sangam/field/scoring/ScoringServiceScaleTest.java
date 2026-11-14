// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.sangam.field.FieldJson;
import org.sangam.field.template.FieldTemplate;

/** Owner decision 242: a NUMBER answer with more decimal places than the item's scale scores 0. */
class ScoringServiceScaleTest {

    private static FieldTemplate.Item item(String props) {
        return FieldJson.read("""
                {"key": "s242", "version": 1, "sections": [ {"id": "s", "items": [
                  {"id": "n", "type": "NUMBER" %s } ] } ] }""".formatted(props), FieldTemplate.class).item("n");
    }

    @Test
    void anAnswerBeyondTheScaleScoresZero() {
        FieldTemplate.Item i = item(", \"scale\": 1, \"min\": 0, \"max\": 100");
        assertEquals(1, ScoringService.itemScore(i, "12.5"));
        assertEquals(1, ScoringService.itemScore(i, "12"));
        assertEquals(0, ScoringService.itemScore(i, "12.55"));
        assertEquals(1, ScoringService.itemScore(i, new java.math.BigDecimal("12.50")), "canonical form has 1 place");
    }

    @Test
    void withoutAScaleOnlyTheRangeCounts() {
        FieldTemplate.Item i = item(", \"min\": 0, \"max\": 100");
        assertEquals(1, ScoringService.itemScore(i, "12.555555"));
    }
}
