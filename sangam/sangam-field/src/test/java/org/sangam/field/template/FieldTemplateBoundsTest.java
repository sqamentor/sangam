// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Hashes;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;

/**
 * Owner decision 230: NUMBER bounds are exact decimals, written as canonical decimal text, read from a JSON number or
 * from canonical text only, and the template content hash depends on that text, never on a binary double.
 */
class FieldTemplateBoundsTest {

    private static String json(String bounds) {
        return """
                {"key": "t230", "version": 1, "sections": [ {"id": "s", "items": [
                  {"id": "n", "type": "NUMBER", %s } ] } ] }""".formatted(bounds);
    }

    private static FieldTemplate.Item item(String bounds) {
        return FieldJson.read(json(bounds), FieldTemplate.class).item("n");
    }

    @Test
    void boundsAreReadExactlyFromJsonNumbers() {
        FieldTemplate.Item i = item("\"min\": 0.30000000000000001, \"max\": 12345678901234567.89");
        assertEquals(new BigDecimal("0.30000000000000001"), i.min(), "no binary double on the way in");
        assertEquals(new BigDecimal("12345678901234567.89"), i.max());
    }

    @Test
    void boundsAreWrittenAsCanonicalDecimalText() {
        FieldTemplate t = FieldJson.read(json("\"min\": 0.0, \"max\": 12.50"), FieldTemplate.class);
        String written = FieldJson.write(t);
        assertTrue(written.contains("\"min\":\"0\""), written);
        assertTrue(written.contains("\"max\":\"12.5\""), written);
        FieldTemplate again = FieldJson.read(written, FieldTemplate.class);
        assertEquals(written, FieldJson.write(again), "written text reads back to the same text");
        assertEquals(t.item("n"), again.item("n"));
    }

    @Test
    void canonicalBoundTextIsAccepted() {
        FieldTemplate.Item i = item("\"min\": \"-0.05\", \"max\": \"100\"");
        assertEquals(new BigDecimal("-0.05"), i.min());
        assertEquals(new BigDecimal("1E+2"), i.max());
    }

    @Test
    void nonCanonicalBoundTextIsRefused() {
        for (String text : List.of("12.50", "+3", "1E+2", "1e2", "0.0", "-0", "03", " 3", "3 ", "100.", "abc", "", "NaN")) {
            FieldException e = assertThrows(FieldException.class, () -> item("\"max\": \"" + text + "\""), text);
            assertEquals("INVALID_JSON", e.code(), text);
        }
        assertThrows(FieldException.class, () -> item("\"min\": true"));
        assertThrows(FieldException.class, () -> item("\"min\": [1]"));
    }

    @Test
    void equalValuesInAnyNumberFormHashTheSame() {
        String a = FieldJson.write(FieldJson.read(json("\"min\": 1.50, \"max\": 100"), FieldTemplate.class));
        String b = FieldJson.write(FieldJson.read(json("\"min\": \"1.5\", \"max\": 1E+2"), FieldTemplate.class));
        assertEquals(a, b);
        assertEquals(Hashes.hex(Hashes.sha256(a)), Hashes.hex(Hashes.sha256(b)));
        String c = FieldJson.write(FieldJson.read(json("\"min\": 1.51, \"max\": 100"), FieldTemplate.class));
        assertTrue(!a.equals(c), "a different bound is a different template");
    }

    @Test
    void absentBoundsStayAbsent() {
        FieldTemplate.Item i = item("\"required\": true");
        assertNull(i.min());
        assertNull(i.max());
        assertTrue(FieldJson.write(FieldJson.read(json("\"required\": true"), FieldTemplate.class)).contains("\"min\":null"));
    }

    @Test
    void aBodyParsedIntoAMapKeepsTextBoundsExact() {
        Map<String, Object> body = FieldJson.map(json("\"min\": \"0.30000000000000001\""));
        FieldTemplate t = FieldJson.convert(body, FieldTemplate.class);
        assertEquals(new BigDecimal("0.30000000000000001"), t.item("n").min());
    }

    @Test
    void theRangeCheckUsesTheExactBounds() {
        FieldTemplate.Item i = item("\"min\": \"0.30000000000000001\"");
        assertTrue(!ChecklistValidator.inRange(i, new BigDecimal("0.3")), "0.3 is below 0.30000000000000001");
        assertTrue(ChecklistValidator.inRange(i, new BigDecimal("0.30000000000000001")));
    }

    @Test
    void boundParseAndTextAgree() {
        assertEquals("12.5", FieldTemplate.Bound.text(new BigDecimal("12.500")));
        assertEquals(new BigDecimal("12.5"), FieldTemplate.Bound.parse("12.5"));
        assertThrows(IllegalArgumentException.class, () -> FieldTemplate.Bound.parse("12.50"));
        assertThrows(IllegalArgumentException.class, () -> FieldTemplate.Bound.parse(null));
    }
}
