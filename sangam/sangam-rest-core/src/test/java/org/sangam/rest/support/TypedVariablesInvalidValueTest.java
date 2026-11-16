// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;

/**
 * UI-DEMO sweep S8: a typed variable whose value is not of its declared type is the caller's error. Before the fix a
 * {@code Date} in the engine's own text form ({@code 2026-11-23T03:36:12.000+0000}) reached {@code Instant.parse}
 * unguarded and POST /process-instances answered 500 (DateTimeParseException), and a {@code Boolean} of any other text
 * was silently read as {@code false}. Each is now 400 VALIDATION_FAILED naming the type.
 */
class TypedVariablesInvalidValueTest {

    private static Map<String, Object> typed(String type, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("value", value);
        return m;
    }

    private static void refused(String type, Object value) {
        ApiProblem p = assertThrows(ApiProblem.class, () -> TypedVariables.fromJson(Map.of("v", typed(type, value))),
                type + " " + value);
        assertEquals(400, p.status(), type + " " + value);
        assertEquals("VALIDATION_FAILED", p.code(), type + " " + value);
        assertTrue(p.detail().contains(type), p.detail());
    }

    @Test
    void aDateThatIsNotAnIsoInstantIsAValidationProblem() {
        for (String bad : List.of("2026-11-23T03:36:12.000+0000", "2026-11-23", "23/11/2026", "", "now")) {
            refused("Date", bad);
            refused("date-time", bad);
        }
    }

    @Test
    void aNumberThatIsNotOfItsTypeIsAValidationProblem() {
        for (String bad : List.of("1.5", "12a", "", "9223372036854775808")) {
            refused("Long", bad);
        }
        for (String bad : List.of("1.5", "2147483648", "x")) {
            refused("Integer", bad);
        }
        for (String bad : List.of("1.0", "1e3", "")) {
            refused("BigInteger", bad);
        }
    }

    @Test
    void aBooleanIsTrueOrFalseNeverAnythingElseReadAsFalse() {
        for (Object bad : List.of("yes", "no", "TRUE", "False", "1", "0", "")) {
            refused("Boolean", bad);
        }
        assertEquals(Boolean.TRUE, TypedVariables.fromJsonValue(typed("Boolean", true)));
        assertEquals(Boolean.FALSE, TypedVariables.fromJsonValue(typed("boolean", "false")));
    }

    @Test
    void anUnknownTypeIsAValidationProblem() {
        refused("Money", "12");
    }

    @Test
    void validValuesStillConvert() {
        assertEquals(java.util.Date.from(java.time.Instant.parse("2026-11-23T03:36:12Z")),
                TypedVariables.fromJsonValue(typed("Date", "2026-11-23T03:36:12Z")));
        assertEquals(java.util.Date.from(java.time.Instant.parse("2026-11-23T09:06:12+05:30")),
                TypedVariables.fromJsonValue(typed("Date", "2026-11-23T09:06:12+05:30")));
        assertEquals(42L, TypedVariables.fromJsonValue(typed("Long", "42")));
        assertEquals(7, TypedVariables.fromJsonValue(typed("Integer", 7)));
    }
}
