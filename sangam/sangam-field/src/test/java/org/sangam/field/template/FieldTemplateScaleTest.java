// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.template;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;

/**
 * Owner decision 242: a NUMBER item may carry a scale (0..10). Bounds and answers with more decimal places than the scale
 * are refused, counted on the canonical form; without a scale there is no extra limit, and the written template of an
 * item without a scale is unchanged.
 */
class FieldTemplateScaleTest {

    private static FieldTemplate template(String type, String props) {
        return FieldJson.read("""
                {"key": "t242", "version": 1, "sections": [ {"id": "s", "items": [
                  {"id": "n", "type": "%s" %s } ] } ] }""".formatted(type, props), FieldTemplate.class);
    }

    private static List<ChecklistValidator.Violation> answer(FieldTemplate t, Object value) {
        return ChecklistValidator.validate(t, Map.of("n", value), Map.of());
    }

    @Test
    void decimalPlacesAreCountedOnTheCanonicalForm() {
        assertEquals(1, FieldTemplate.Item.decimalPlaces(new BigDecimal("12.50")));
        assertEquals(2, FieldTemplate.Item.decimalPlaces(new BigDecimal("-0.05")));
        assertEquals(0, FieldTemplate.Item.decimalPlaces(new BigDecimal("100")));
        assertEquals(0, FieldTemplate.Item.decimalPlaces(new BigDecimal("1E+2")));
        assertEquals(0, FieldTemplate.Item.decimalPlaces(new BigDecimal("0.000")));
    }

    @Test
    void boundsWithinTheScalePublish() {
        FieldTemplate t = template("NUMBER", ", \"scale\": 2, \"min\": \"0.05\", \"max\": 1000");
        assertDoesNotThrow(t::validateDefinition);
        assertEquals(2, t.item("n").scale());
        assertDoesNotThrow(template("NUMBER", ", \"scale\": 0, \"min\": 1.0, \"max\": 100")::validateDefinition,
                "1.0 is the integer 1 in canonical form");
    }

    @Test
    void boundsWithMoreDecimalPlacesThanTheScaleAreRefused() {
        FieldException min = assertThrows(FieldException.class,
                () -> template("NUMBER", ", \"scale\": 1, \"min\": \"0.05\"").validateDefinition());
        assertEquals("INVALID_TEMPLATE", min.code());
        assertTrue(min.getMessage().contains("min 0.05"), min.getMessage());
        FieldException max = assertThrows(FieldException.class,
                () -> template("NUMBER", ", \"scale\": 0, \"max\": 12.5").validateDefinition());
        assertEquals("INVALID_TEMPLATE", max.code());
        assertTrue(max.getMessage().contains("max 12.5"), max.getMessage());
    }

    @Test
    void aScaleOutsideZeroToTenOrOnAnotherTypeIsRefused() {
        for (int s : new int[] {-1, 11, 100}) {
            FieldException e = assertThrows(FieldException.class,
                    () -> template("NUMBER", ", \"scale\": " + s).validateDefinition(), String.valueOf(s));
            assertEquals("INVALID_TEMPLATE", e.code());
        }
        assertDoesNotThrow(template("NUMBER", ", \"scale\": 10")::validateDefinition);
        assertDoesNotThrow(template("NUMBER", ", \"scale\": 0")::validateDefinition);
        FieldException text = assertThrows(FieldException.class,
                () -> template("TEXT", ", \"scale\": 2").validateDefinition());
        assertEquals("INVALID_TEMPLATE", text.code());
        assertTrue(text.getMessage().contains("only for NUMBER"), text.getMessage());
    }

    @Test
    void answersWithMoreDecimalPlacesThanTheScaleAreRefused() {
        FieldTemplate t = template("NUMBER", ", \"scale\": 2, \"min\": 0, \"max\": 1000");
        assertTrue(answer(t, "12.34").isEmpty());
        assertTrue(answer(t, "12.3").isEmpty());
        assertTrue(answer(t, "12").isEmpty());
        assertTrue(answer(t, new BigDecimal("12.300")).isEmpty(), "a JSON number is canonicalised first");
        List<ChecklistValidator.Violation> v = answer(t, "12.345");
        assertEquals(1, v.size());
        assertEquals("INVALID_ANSWER", v.get(0).code());
        assertEquals("too many decimal places: at most 2", v.get(0).message());
        assertFalse(answer(t, new BigDecimal("0.001")).isEmpty());
    }

    @Test
    void withoutAScaleThereIsNoExtraLimit() {
        FieldTemplate t = template("NUMBER", ", \"min\": 0, \"max\": 1");
        assertNull(t.item("n").scale());
        assertTrue(answer(t, "0.123456789012345678901234567891").isEmpty());
        assertDoesNotThrow(template("NUMBER", ", \"min\": \"0.123456789012345678901\"")::validateDefinition);
    }

    @Test
    void scaleIsWrittenOnlyWhenSet() {
        String without = FieldJson.write(template("NUMBER", ", \"max\": 5"));
        assertFalse(without.contains("scale"), "a template without scale keeps its written form and hash: " + without);
        String with = FieldJson.write(template("NUMBER", ", \"scale\": 3, \"max\": 5"));
        assertTrue(with.contains("\"scale\":3"), with);
        assertEquals(with, FieldJson.write(FieldJson.read(with, FieldTemplate.class)));
    }
}
