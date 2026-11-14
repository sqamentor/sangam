// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.field.FieldJson;

/** Decision 212: NUMBER answers are checked as exact decimals and must arrive in canonical text form. */
class ChecklistValidatorNumberTest {

    /** Template authored as JSON, the way templates are published (bounds 0.1 and 12.5). */
    private static FieldTemplate template(String bounds) throws Exception {
        return FieldJson.MAPPER.readValue("""
                {"key": "t212", "version": 1, "sections": [ {"id": "s", "items": [
                  {"id": "n", "type": "NUMBER", "required": true, %s } ] } ] }""".formatted(bounds), FieldTemplate.class);
    }

    private static List<ChecklistValidator.Violation> check(FieldTemplate t, Object answer) {
        return ChecklistValidator.validate(t, Map.of("n", answer), Map.of());
    }

    @Test
    void canonicalTextInsideTheBoundsIsAccepted() throws Exception {
        FieldTemplate t = template("\"min\": 0.1, \"max\": 12.5");
        assertEquals(List.of(), check(t, "12.5"), "upper bound is inclusive and exact");
        assertEquals(List.of(), check(t, "0.1"), "lower bound is inclusive and exact");
        assertEquals(List.of(), check(t, "3"));
    }

    @Test
    void nonCanonicalTextIsRefused() throws Exception {
        FieldTemplate t = template("\"min\": 0, \"max\": 1000");
        for (String text : List.of("12.50", "+3", "1E+2", "1e2", "0.0", "-0", "03", " 3", "3 ", "100.")) {
            List<ChecklistValidator.Violation> v = check(t, text);
            assertEquals(1, v.size(), text);
            assertEquals("INVALID_ANSWER", v.get(0).code(), text);
            assertTrue(v.get(0).message().startsWith("number not canonical"), text + ": " + v.get(0).message());
        }
    }

    @Test
    void textThatIsNotANumberIsRefused() throws Exception {
        FieldTemplate t = template("\"min\": 0");
        for (String text : List.of("abc", "NaN", "Infinity", "1,5", "true")) {
            List<ChecklistValidator.Violation> v = check(t, text);
            assertEquals(1, v.size(), text);
            assertEquals("number expected", v.get(0).message(), text);
        }
        assertEquals("number expected", check(t, Boolean.TRUE).get(0).message());
    }

    @Test
    void rangeIsExactNotBinaryFloatingPoint() throws Exception {
        FieldTemplate t = template("\"max\": 0.3");
        // 0.30000000000000001 rounds to the same binary double as 0.3, so a Double check accepted it
        List<ChecklistValidator.Violation> v = check(t, "0.30000000000000001");
        assertEquals(1, v.size());
        assertEquals("out of range", v.get(0).message());
        assertEquals(List.of(), check(t, "0.3"));
        assertEquals("out of range", check(template("\"min\": 12.5"), "12.49999999999999999").get(0).message());
    }

    @Test
    void jsonNumbersFromOlderClientsAreStillAccepted() throws Exception {
        FieldTemplate t = template("\"min\": 0.1, \"max\": 12.5");
        Map<String, Object> answers = FieldJson.MAPPER.readValue("{\"n\": 12.50}", Map.class);
        assertEquals(List.of(), ChecklistValidator.validate(t, answers, Map.of()), "JSON 12.50 is a number, not text");
        Map<String, Object> tooBig = FieldJson.MAPPER.readValue("{\"n\": 13}", Map.class);
        assertEquals("out of range", ChecklistValidator.validate(t, tooBig, Map.of()).get(0).message());
        assertEquals(List.of(), check(t, new BigDecimal("12.500")), "a BigDecimal is a number and is canonicalised");
    }

    @Test
    void canonicalFormMatchesPacketHasherNormalise() {
        assertEquals("12.5", ChecklistValidator.canonical(new BigDecimal("12.50")));
        assertEquals("100", ChecklistValidator.canonical(new BigDecimal("1E+2")));
        assertEquals("3", ChecklistValidator.canonical(new BigDecimal("+3")));
        assertEquals("0", ChecklistValidator.canonical(new BigDecimal("0.000")));
        assertEquals("0", ChecklistValidator.canonical(new BigDecimal("-0")));
        assertEquals("-0.05", ChecklistValidator.canonical(new BigDecimal("-0.050")));
        assertEquals("0.0000001", ChecklistValidator.canonical(new BigDecimal("1E-7")), "plain, never exponent form");
    }

    @Test
    void canonicalNumberAcceptsJsonNumbersAndOnlyCanonicalText() {
        assertEquals(new BigDecimal("12.5"), ChecklistValidator.canonicalNumber("12.5"));
        assertNull(ChecklistValidator.canonicalNumber("12.50"));
        assertNull(ChecklistValidator.canonicalNumber("+3"));
        assertNull(ChecklistValidator.canonicalNumber("1E+2"));
        assertEquals(0, new BigDecimal("12.5").compareTo(ChecklistValidator.canonicalNumber(new BigDecimal("12.50"))));
        assertEquals(0, BigDecimal.valueOf(100).compareTo(ChecklistValidator.canonicalNumber(Long.valueOf(100))));
        assertNull(ChecklistValidator.canonicalNumber(null));
        assertNull(ChecklistValidator.canonicalNumber(Boolean.FALSE));
    }
}
