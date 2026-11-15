// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.packs.engine.DecimalText;
import org.sangam.rest.port.ApiProblem;

/**
 * G25 (decision 138) at the REST variable boundary: a typed {@code Decimal} is read with the engine's strict
 * {@link DecimalText} rules (invalid text is {@code VALIDATION_FAILED}, never a raw {@code NumberFormatException} and
 * never accepted loosely) and written back with its value and scale ({@code 1E+3} was {@code 1000}, scale 0).
 */
class TypedVariablesDecimalH2Test {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="g25" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="ut"/><userTask id="ut"/>
                <sequenceFlow id="f2" sourceRef="ut" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    private static Map<String, Object> typed(String type, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("value", value);
        return m;
    }

    @Test
    void decimalTextIsReadStrictlyWithItsScale() {
        assertEquals(new BigDecimal("1.50"), TypedVariables.fromJsonValue(typed("Decimal", "1.50")));
        BigDecimal thousand = (BigDecimal) TypedVariables.fromJsonValue(typed("decimal", "1E+3"));
        assertEquals(-3, thousand.scale());
        assertEquals(new BigDecimal("1E+3"), TypedVariables.fromJsonValue(typed("Decimal", "1e3")));
        assertEquals(new BigDecimal("-0.0000001"), TypedVariables.fromJsonValue(typed("Decimal", "-0.0000001")));
        assertEquals(new BigDecimal("10"), TypedVariables.fromJsonValue(typed("Decimal", 10)), "an exact JSON integer");
        assertEquals(new BigDecimal("12345678901234567890"),
                TypedVariables.fromJsonValue(typed("Decimal", new java.math.BigInteger("12345678901234567890"))));
    }

    @Test
    void invalidDecimalTextIsAValidationProblem() {
        for (Object bad : new Object[] {"+1", " 1", "1 ", "1,000.50", "NaN", "Infinity", "0x10", "1.", ".5", "",
                "1E+1234567890", "1".repeat(DecimalText.MAX_CHARS + 1), 0.1d, 1.5f, true, List.of("1")}) {
            ApiProblem p = assertThrows(ApiProblem.class,
                    () -> TypedVariables.fromJson(Map.of("amount", typed("Decimal", bad))), String.valueOf(bad));
            assertEquals(400, p.status(), String.valueOf(bad));
            assertEquals("VALIDATION_FAILED", p.code(), String.valueOf(bad));
            assertTrue(p.detail().startsWith("typed variable Decimal: DECIMAL_"), p.detail());
        }
    }

    @Test
    void decimalsAreWrittenBackWithValueAndScale() {
        for (String text : List.of("1234.50", "125000", "-0.01", "0.0000001", "0E-7", "12345678901234567890.123456789",
                "1E+3", "-2.5E+10", "1E-2000")) {
            BigDecimal d = new BigDecimal(text);
            Map<?, ?> out = (Map<?, ?>) TypedVariables.toJsonValue(d);
            assertEquals("Decimal", out.get("type"));
            BigDecimal back = (BigDecimal) TypedVariables.fromJsonValue(typed("Decimal", out.get("value")));
            assertEquals(d, back, text + " -> " + out.get("value") + ": value and scale");
        }
        assertEquals("1.50", ((Map<?, ?>) TypedVariables.toJsonValue(new BigDecimal("1.50"))).get("value"));
        assertEquals("0.0000001", ((Map<?, ?>) TypedVariables.toJsonValue(new BigDecimal("1E-7"))).get("value"),
                "plain DecimalString text whenever it keeps the scale");
        assertEquals("1E+3", ((Map<?, ?>) TypedVariables.toJsonValue(new BigDecimal("1E+3"))).get("value"),
                "a negative scale has no plain text of the same scale");
        assertEquals("1E-2000", ((Map<?, ?>) TypedVariables.toJsonValue(new BigDecimal("1E-2000"))).get("value"),
                "plain text longer than the decimal limit is written canonically");
    }

    @Test
    void typedDecimalsRoundTripThroughTheEngineAlsoInsideMaps() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:g25" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            a.deploy("t1", "g25-" + UUID.randomUUID(), Map.of("g25.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("thousand", typed("Decimal", "1E+3"));
            in.put("fee", Map.of("whole", typed("Decimal", "15"), "rate", typed("Decimal", "0.075")));
            String pid = a.startProcess("t1", "g25", null, TypedVariables.fromJson(in));
            Map<String, Object> out = TypedVariables.toJson(a.processEngine().getRuntimeService().getVariables(pid));
            assertEquals(typed("Decimal", "1E+3"), out.get("thousand"));
            Map<?, ?> fee = assertInstanceOf(Map.class, out.get("fee"));
            assertEquals(typed("Decimal", "15"), fee.get("whole"), "a nested whole decimal is not an Integer (G24)");
            assertEquals(typed("Decimal", "0.075"), fee.get("rate"));
        }
    }
}
