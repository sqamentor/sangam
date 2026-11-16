// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.sangam.rest.adapter.jdbc.JdbcEventAdapter;
import org.sangam.rest.adapter.jdbc.JdbcPowerTableAdapter;
import org.sangam.rest.adapter.operaton.OperatonOpsAdapter;
import org.sangam.rest.adapter.operaton.OperatonPackageToolsAdapter;
import org.sangam.rest.port.ApiProblem;

/**
 * Owner decision 381(4): a Double or Float handed to {@link TypedVariables} is refused by name
 * ({@code DECIMAL_FROM_FLOAT}), never rounded into a decimal; and every in-process source that reads stored JSON back
 * into TypedVariables (batch parameters, package DMN test inputs, power-table test cases, inbound event data) reads a
 * fraction as the exact BigDecimal of its text, so it reaches the engine instead of the refusal.
 */
class NoBinaryFloatVariablesTest {

    private static final String WIDE = "12345678901234567890.123456789";

    @Test
    void aBinaryFloatIsRefusedByNameAtEveryDepth() {
        for (Object bad : new Object[] {0.1d, 2.5f, Map.of("net", 0.1d), List.of(1, 0.1d),
                Map.of("lines", List.of(Map.of("net", 0.1d)))}) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> TypedVariables.fromJson(Map.of("amount", bad)),
                    String.valueOf(bad));
            assertEquals(400, p.status());
            assertEquals("VALIDATION_FAILED", p.code());
            assertEquals(TypedVariables.DECIMAL_FROM_FLOAT, p.extensions().get("reason"), String.valueOf(bad));
            assertTrue(p.detail().startsWith("DECIMAL_FROM_FLOAT"), p.detail());
        }
        assertEquals(new BigDecimal("0.1"), TypedVariables.fromJsonValue(new BigDecimal("0.1")), "a BigDecimal passes");
        assertEquals(7L, TypedVariables.fromJsonValue(7L), "an integer passes");
    }

    private static ObjectMapper mapper(Class<?> owner, String field) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return (ObjectMapper) f.get(null);
    }

    @Test
    void everyStoredJsonReaderFeedingTypedVariablesReadsFractionsExactly() throws Exception {
        Map<Class<?>, String> readers = new LinkedHashMap<>();
        readers.put(OperatonOpsAdapter.class, "CONFIG_JSON");
        readers.put(OperatonPackageToolsAdapter.class, "json");
        readers.put(JdbcPowerTableAdapter.class, "JSON");
        readers.put(JdbcEventAdapter.class, "JSON");
        for (Map.Entry<Class<?>, String> r : readers.entrySet()) {
            ObjectMapper m = mapper(r.getKey(), r.getValue());
            String who = r.getKey().getSimpleName();
            @SuppressWarnings("unchecked")
            Map<String, Object> read = m.readValue("{\"amount\":" + WIDE + ",\"rate\":0.1}", Map.class);
            assertEquals(new BigDecimal(WIDE), read.get("amount"), who);
            assertEquals(new BigDecimal("0.1"), TypedVariables.fromJson(read).get("rate"), who);
            @SuppressWarnings("unchecked")
            Map<String, Object> fromTree = m.convertValue(m.readTree("{\"rate\":0.1}"), Map.class);
            assertEquals(new BigDecimal("0.1"), TypedVariables.fromJson(fromTree).get("rate"), who + " via a tree");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStoredSetVariablesBatchKeepsEveryDigit() throws Exception {
        // the SET_VARIABLES batch of OperatonOpsAdapter stores its raw parameters as JSON and hands them back to
        // TypedVariables when an item runs
        Method write = Arrays.stream(OperatonOpsAdapter.class.getDeclaredMethods())
                .filter(x -> x.getName().equals("configJson")).findFirst().orElseThrow();
        Method parse = OperatonOpsAdapter.class.getDeclaredMethod("parse", String.class);
        write.setAccessible(true);
        parse.setAccessible(true);
        Map<String, Object> params = Map.of("variables", Map.of("amount", new BigDecimal(WIDE)));
        String stored = (String) write.invoke(null, null, params, "fix amounts", null, false, "k", null);
        Map<String, Object> config = (Map<String, Object>) parse.invoke(null, stored);
        Object variables = ((Map<String, Object>) config.get("params")).get("variables");
        Object amount = TypedVariables.fromJson((Map<String, Object>) variables).get("amount");
        assertInstanceOf(BigDecimal.class, amount);
        assertEquals(new BigDecimal(WIDE), amount);
    }
}
