// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.variables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.variable.serializer.TypedValueSerializer;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.packs.engine.DecimalText;
import org.sangam.packs.engine.DecimalValueSerializer;
import org.sangam.packs.engine.DecimalValueType;

/**
 * Decision 138: BigDecimal process variables are stored as {@code sangam-decimal-v1} text and come back with the same
 * value and scale, through runtime, history and a new engine on the same database; Double variables keep Operaton's
 * {@code double} type; invalid stored text and range queries are refused.
 */
class DecimalValueSerializerH2Test {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="money" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <userTask id="work"/><sequenceFlow id="f2" sourceRef="work" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    private JdbcDataSource ds;

    private OperatonEngineAdapter engine(boolean deploy) {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        if (ds == null) {
            ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:d138" + UUID.randomUUID().toString().replace("-", "")
                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        }
        OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2");
        if (deploy) {
            a.deploy("t1", "d138-" + UUID.randomUUID(), Map.of("money.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
        }
        return a;
    }

    private String start(OperatonEngineAdapter a, Map<String, Object> vars) {
        return a.startProcess("t1", "money", null, vars);
    }

    private List<String[]> rows(String name) throws Exception {
        List<String[]> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT TYPE_, TEXT_, TEXT2_, LONG_, DOUBLE_, BYTEARRAY_ID_"
                     + " FROM ACT_RU_VARIABLE WHERE NAME_ = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6)});
                }
            }
        }
        return out;
    }

    private static boolean causedBy(Throwable t, String code) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof DecimalText.DecimalFormatException d && d.code().equals(code)) {
                return true;
            }
            if (x.getMessage() != null && x.getMessage().contains(code)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void amountsRatesAndAdjustmentsRoundTripWithValueAndScale() throws Exception {
        Map<String, BigDecimal> values = new HashMap<>();
        values.put("rate", new BigDecimal("0.0725"));
        values.put("large", new BigDecimal("98765432109876543210987654321.987654321"));
        values.put("negative", new BigDecimal("-1250.50"));
        values.put("zeroScaled", new BigDecimal("0.000"));
        values.put("exponent", new BigDecimal("1E+3"));
        values.put("widest", new BigDecimal("9".repeat(DecimalText.MAX_CHARS)));
        try (OperatonEngineAdapter a = engine(true)) {
            String pid = start(a, new HashMap<>(values));
            RuntimeService rt = a.processEngine().getRuntimeService();
            for (Map.Entry<String, BigDecimal> e : values.entrySet()) {
                Object back = rt.getVariable(pid, e.getKey());
                assertInstanceOf(BigDecimal.class, back, e.getKey());
                assertEquals(e.getValue().unscaledValue(), ((BigDecimal) back).unscaledValue(), e.getKey());
                assertEquals(e.getValue().scale(), ((BigDecimal) back).scale(), e.getKey());
                TypedValue typed = rt.getVariableTyped(pid, e.getKey());
                assertEquals(DecimalValueType.NAME, typed.getType().getName());
                String[] row = rows(e.getKey()).getFirst();
                assertEquals(DecimalValueSerializer.NAME, row[0], "persisted type id");
                assertEquals(e.getValue().toString(), row[1], "TEXT_ holds format v1");
                assertNull(row[2]);
                assertNull(row[3]);
                assertNull(row[4], "no binary double copy");
                assertNull(row[5]);
            }
        }
    }

    @Test
    void repeatedCalculationsThroughTheEngineStayExact() {
        try (OperatonEngineAdapter a = engine(true)) {
            String pid = start(a, Map.of("total", BigDecimal.ZERO));
            RuntimeService rt = a.processEngine().getRuntimeService();
            for (int i = 0; i < 10; i++) {
                BigDecimal t = (BigDecimal) rt.getVariable(pid, "total");
                rt.setVariable(pid, "total", t.add(new BigDecimal("0.10")));
            }
            assertEquals(new BigDecimal("1.00"), rt.getVariable(pid, "total"));
        }
    }

    @Test
    void historyAndANewEngineOnTheSameDatabaseReadTheSameDecimal() {
        String pid;
        try (OperatonEngineAdapter a = engine(true)) {
            pid = start(a, Map.of("amount", new BigDecimal("1234.5600")));
        }
        try (OperatonEngineAdapter b = engine(false)) {
            assertEquals(new BigDecimal("1234.5600"), b.processEngine().getRuntimeService().getVariable(pid, "amount"));
            Object hist = b.processEngine().getHistoryService().createHistoricVariableInstanceQuery()
                    .processInstanceId(pid).variableName("amount").singleResult().getValue();
            assertEquals(new BigDecimal("1234.5600"), hist);
            assertEquals(4, ((BigDecimal) hist).scale());
        }
    }

    @Test
    void aDoubleIsNeverConvertedAndKeepsOperatonsDoubleType() throws Exception {
        try (OperatonEngineAdapter a = engine(true)) {
            String pid = start(a, Map.of("legacyRate", 0.1d));
            assertInstanceOf(Double.class, a.processEngine().getRuntimeService().getVariable(pid, "legacyRate"));
            assertEquals(DoubleVariableInventory.DOUBLE_TYPE, rows("legacyRate").getFirst()[0]);
        }
    }

    @Test
    void untypedNullStaysNullTypeAndTypedNullKeepsTheDecimalType() throws Exception {
        try (OperatonEngineAdapter a = engine(true)) {
            Map<String, Object> vars = new HashMap<>();
            vars.put("nothing", null);
            vars.put("noAmount", DecimalValueType.decimal(null));
            String pid = start(a, vars);
            assertEquals("null", rows("nothing").getFirst()[0]);
            assertEquals(DecimalValueSerializer.NAME, rows("noAmount").getFirst()[0]);
            TypedValue typed = a.processEngine().getRuntimeService().getVariableTyped(pid, "noAmount");
            assertEquals(DecimalValueType.NAME, typed.getType().getName());
            assertNull(typed.getValue());
        }
    }

    @Test
    void storedTextThatIsNotFormatV1IsRefusedOnRead() throws Exception {
        try (OperatonEngineAdapter a = engine(true)) {
            String pid = start(a, Map.of("amount", new BigDecimal("15")));
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement("UPDATE ACT_RU_VARIABLE SET TEXT_ = ? WHERE NAME_ = ?")) {
                ps.setString(1, "1.5e1");
                ps.setString(2, "amount");
                assertEquals(1, ps.executeUpdate());
            }
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> a.processEngine().getRuntimeService().getVariable(pid, "amount"));
            assertTrue(causedBy(e, "DECIMAL_NOT_CANONICAL"), e.toString());
        }
    }

    @Test
    void anOversizedDecimalIsRefusedAtWriteNotTruncated() {
        try (OperatonEngineAdapter a = engine(true)) {
            BigDecimal tooWide = new BigDecimal("9".repeat(DecimalText.MAX_CHARS + 1));
            RuntimeException e = assertThrows(RuntimeException.class, () -> start(a, Map.of("amount", tooWide)));
            assertTrue(causedBy(e, "DECIMAL_TOO_LARGE"), e.toString());
        }
    }

    @Test
    void equalityQueriesMatchValueAndScaleAndRangeQueriesAreRefused() {
        try (OperatonEngineAdapter a = engine(true)) {
            String p1 = start(a, Map.of("amount", new BigDecimal("1.50")));
            start(a, Map.of("amount", new BigDecimal("1.5")));
            RuntimeService rt = a.processEngine().getRuntimeService();
            assertEquals(p1, rt.createProcessInstanceQuery().variableValueEquals("amount", new BigDecimal("1.50"))
                    .singleResult().getId());
            assertEquals(1, rt.createProcessInstanceQuery().variableValueNotEquals("amount", new BigDecimal("1.50"))
                    .count());
            assertTrue(DecimalValueSerializer.operatorReadable(), "the query operator field is still readable");
            RuntimeException e = assertThrows(RuntimeException.class, () -> rt.createProcessInstanceQuery()
                    .variableValueGreaterThan("amount", new BigDecimal("1")).count());
            assertTrue(causedBy(e, "DECIMAL_RANGE_QUERY"), e.toString());
            RuntimeException e2 = assertThrows(RuntimeException.class, () -> rt.createProcessInstanceQuery()
                    .variableValueLessThanOrEqual("amount", new BigDecimal("1")).count());
            assertTrue(causedBy(e2, "DECIMAL_RANGE_QUERY"), e2.toString());
        }
    }

    @Test
    void registrationPutsTheSerializerFirstOnceForHostsWithoutThePlugin() {
        ProcessEngineConfigurationImpl cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setCustomPreVariableSerializers(new ArrayList<>(List.of(new LargeStringValueSerializer(4000))));
        DecimalValueSerializer.register(cfg);
        DecimalValueSerializer.register(cfg);
        List<TypedValueSerializer<?>> pre = cfg.getCustomPreVariableSerializers();
        assertEquals(2, pre.size());
        assertEquals(DecimalValueSerializer.NAME, pre.getFirst().getName());
    }

    @Test
    void thePluginRegistersTheSerializerOnTheEngine() {
        try (OperatonEngineAdapter a = engine(false)) {
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) a.processEngine()
                    .getProcessEngineConfiguration();
            assertTrue(cfg.getVariableSerializers().getSerializerByName(DecimalValueSerializer.NAME) != null);
            assertEquals(1, cfg.getCustomPreVariableSerializers().stream()
                    .filter(s -> DecimalValueSerializer.NAME.equals(s.getName())).count());
        }
    }
}
