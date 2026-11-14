// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.variable.value.TypedValue;

import org.sangam.packs.engine.DecimalText.DecimalFormatException;
import org.sangam.packs.engine.DecimalValueType;
import org.sangam.packs.port.PackRuntime;

/**
 * Decision 138 (RM-36): a fractional amount never passes through a binary floating-point value on the pack path. The
 * pack helpers keep decimal text exact, refuse {@code Double} and {@code Float}, and give the engine exact values only;
 * an engine run shows the amounts arriving, being summed by JUEL and by a delegate, and being stored as the engine
 * decimal type, with results that a {@code double} could not produce.
 */
class VarsExactDecimalTest {

    private static final String TENANT = "t-decimal";
    static ProcessEngine engine;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-vars-exact-decimal;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        org.sangam.packs.PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel), as production
        engine = cfg.buildProcessEngine();
        String ns = "xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"t\"";
        String sum = "<definitions " + ns + "><process id=\"exact-sum\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                + "<serviceTask id=\"set\" operaton:class=\"" + SetVariablesDelegate.class.getName() + "\">"
                + "<extensionElements><operaton:field name=\"values\"><operaton:string>a=0.1;b=0.2;fee=1234567.89"
                + "</operaton:string></operaton:field></extensionElements></serviceTask>"
                + "<serviceTask id=\"add\" operaton:expression=\"${execution.setVariable('total', a + b)}\"/>"
                + "<userTask id=\"hold\"/><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"set\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"set\" targetRef=\"add\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"add\" targetRef=\"hold\"/>"
                + "<sequenceFlow id=\"f4\" sourceRef=\"hold\" targetRef=\"e\"/></process></definitions>";
        String window = "<definitions " + ns + "><process id=\"exact-window\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                + "<serviceTask id=\"eval\" operaton:class=\"org.sangam.packs.universal.EventWindowDelegate\">"
                + "<extensionElements><operaton:field name=\"op\"><operaton:string>EVALUATE</operaton:string>"
                + "</operaton:field></extensionElements></serviceTask>"
                + "<userTask id=\"hold\"/><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"eval\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"eval\" targetRef=\"hold\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"hold\" targetRef=\"e\"/></process></definitions>";
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString("exact-sum.bpmn", sum).addString("exact-window.bpmn", window).deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    private static String code(Runnable r) {
        return assertThrows(DecimalFormatException.class, r::run).code();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    @Test
    void decimalTextStaysExactWithItsScale() {
        assertEquals(new BigDecimal("0.10"), Vars.decimal("0.10"));
        assertEquals(2, Vars.decimal("0.10").scale());
        assertEquals(new BigDecimal("1234567.89"), Vars.decimal("1,234,567.89"));
        assertEquals(new BigDecimal("-0.005"), Vars.decimal(" -0.005 "));
        assertNull(Vars.decimal("  "));
        assertNull(Vars.decimal((Object) null));
    }

    @Test
    void integerTypesConvertExactly() {
        assertEquals(BigDecimal.valueOf(Long.MAX_VALUE), Vars.decimal(Long.MAX_VALUE));
        BigInteger beyondLong = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);
        assertEquals(new BigDecimal(beyondLong), Vars.decimal(beyondLong));
        assertEquals(BigDecimal.valueOf(7), Vars.decimal((short) 7));
    }

    @Test
    void binaryFloatingPointIsRefusedEverywhere() {
        assertEquals("DECIMAL_FROM_FLOAT", code(() -> Vars.decimal(Double.valueOf(0.1))));
        assertEquals("DECIMAL_FROM_FLOAT", code(() -> Vars.decimal(Float.valueOf(0.1f))));
        assertEquals("DECIMAL_FROM_FLOAT", code(() -> Vars.engineValue(0.5d)));
        assertEquals("DECIMAL_FROM_FLOAT", code(() -> Vars.canonical(2.0d)));
        assertEquals(Vars.DECIMAL_FROM_FLOAT, "DECIMAL_FROM_FLOAT");
    }

    @Test
    void invalidTextIsRefusedNotGuessed() {
        assertEquals("DECIMAL_INVALID", code(() -> Vars.decimal("12.3.4")));
        assertEquals("DECIMAL_INVALID", code(() -> Vars.decimal("NaN")));
        assertEquals("DECIMAL_INVALID", code(() -> Vars.decimal("Infinity")));
        assertEquals("DECIMAL_TOO_LARGE", code(() -> Vars.decimal("1".repeat(1001))));
    }

    @Test
    void engineValueNeverReturnsADouble() {
        Object fraction = Vars.engineValue(new BigDecimal("12345.67"));
        assertInstanceOf(BigDecimal.class, fraction);
        assertEquals("12345.67", ((BigDecimal) fraction).toString());
        assertEquals(100L, Vars.engineValue(new BigDecimal("100.00")));
        assertEquals(0L, Vars.engineValue(new BigDecimal("0.000")));
        BigInteger beyondLong = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        assertEquals(new BigDecimal(beyondLong), Vars.engineValue(beyondLong));
        for (String s : new String[] {"0.1", "0.2", "0.3", "1.005", "-2.675", "99999999999999999.99", "1E-30"}) {
            Object v = Vars.engineValue(new BigDecimal(s));
            assertFalse(v instanceof Double || v instanceof Float, s);
            assertEquals(0, new BigDecimal(s).compareTo(Vars.decimal(v)), s);
        }
    }

    // ---- engine run ------------------------------------------------------------------------------------------------

    @Test
    void amountsAreSummedExactlyAndStoredAsTheEngineDecimalType() {
        ProcessInstance pi = engine.getRuntimeService().createProcessInstanceByKey("exact-sum")
                .processDefinitionTenantId(TENANT).execute();
        Object a = engine.getRuntimeService().getVariable(pi.getId(), "a");
        assertInstanceOf(BigDecimal.class, a, "SetVariablesDelegate literal 0.1");
        Object total = engine.getRuntimeService().getVariable(pi.getId(), "total");
        assertInstanceOf(BigDecimal.class, total, "JUEL BigDecimal + BigDecimal stays exact");
        // 0.1 + 0.2 as doubles is 0.30000000000000004; the exact sum is 0.3
        assertEquals(new BigDecimal("0.3"), total);
        TypedValue typed = engine.getRuntimeService().getVariableTyped(pi.getId(), "fee");
        assertEquals(DecimalValueType.DECIMAL.getName(), typed.getType().getName());
        assertEquals(new BigDecimal("1234567.89"), typed.getValue());
    }

    @Test
    void jsonFractionsAreReadAsDecimalsAndComparedExactly() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("windowEvents", "[{\"id\":\"1\",\"value\":0.1},{\"id\":\"2\",\"value\":0.2}]");
        vars.put("threshold", 99);
        // exact 0.1 + 0.2 = 0.3 is below this threshold; the double sum 0.30000000000000004 would reach it
        vars.put("valueThreshold", new BigDecimal("0.30000000000000001"));
        ProcessInstance pi = engine.getRuntimeService().createProcessInstanceByKey("exact-window")
                .processDefinitionTenantId(TENANT).setVariables(vars).execute();
        assertEquals(Boolean.FALSE, engine.getRuntimeService().getVariable(pi.getId(), "thresholdReached"));
        Object sum = engine.getRuntimeService().getVariable(pi.getId(), "windowValueSum");
        assertInstanceOf(BigDecimal.class, sum);
        assertEquals(0, new BigDecimal("0.3").compareTo((BigDecimal) sum));
        assertTrue(((BigDecimal) sum).toString().length() < 10, "no binary-float digits");
    }
}
