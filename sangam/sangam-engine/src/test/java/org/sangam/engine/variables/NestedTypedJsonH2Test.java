// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.variables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RuntimeService;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.variables.TypedJsonShapeInventory.Entry;
import org.sangam.engine.variables.TypedJsonShapeInventory.Finding;
import org.sangam.engine.variables.TypedJsonShapeInventory.Source;

/**
 * G24 (decision 138): a {@code BigDecimal} inside a JSON map or list keeps its type, value and scale, carried as the R33
 * typed object as decision 333 carries a nested {@code LocalDate}; rows stored before keep reading as they did.
 * Decision 353: a stored object of exactly the typed local-date shape reads back as a {@code LocalDate} (bad text
 * refused), and {@link TypedJsonShapeInventory} lists the stored rows that hold a typed shape.
 */
class NestedTypedJsonH2Test {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="fees" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <userTask id="work"/><sequenceFlow id="f2" sourceRef="work" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    private JdbcDataSource ds;

    private OperatonEngineAdapter engine() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:g24" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2");
        a.deploy("t1", "g24-" + UUID.randomUUID(), Map.of("fees.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
        return a;
    }

    private String start(OperatonEngineAdapter a, Map<String, Object> vars) {
        return a.startProcess("t1", "fees", null, vars);
    }

    private String storedJson(String name) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT b.BYTES_ FROM ACT_RU_VARIABLE v JOIN ACT_GE_BYTEARRAY b"
                     + " ON b.ID_ = v.BYTEARRAY_ID_ WHERE v.NAME_ = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "a JSON variable " + name);
                return new String(rs.getBytes(1), StandardCharsets.UTF_8);
            }
        }
    }

    /** Replaces the stored runtime document of {@code name}, as a row written before G24 / decision 333 holds it. */
    private void storeLegacy(String name, String json) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE ACT_GE_BYTEARRAY SET BYTES_ = ? WHERE ID_ ="
                     + " (SELECT BYTEARRAY_ID_ FROM ACT_RU_VARIABLE WHERE NAME_ = ?)")) {
            ps.setBytes(1, json.getBytes(StandardCharsets.UTF_8));
            ps.setString(2, name);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static boolean causedBy(Throwable t, String code) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x.getMessage() != null && x.getMessage().contains(code)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void nestedDecimalsKeepTypeValueAndScale() throws Exception {
        try (OperatonEngineAdapter a = engine()) {
            Map<String, Object> fee = map("whole", new BigDecimal("15"), "amount", new BigDecimal("1250.50"),
                    "thousand", new BigDecimal("1E+3"), "rates", new ArrayList<>(List.of(new BigDecimal("-0.075"),
                            new BigDecimal("0"))), "label", "stamp duty", "count", 3);
            String pid = start(a, Map.of("fee", fee));
            Map<?, ?> back = (Map<?, ?>) a.processEngine().getRuntimeService().getVariable(pid, "fee");
            for (String k : List.of("whole", "amount", "thousand")) {
                assertInstanceOf(BigDecimal.class, back.get(k), k + " stays a BigDecimal (a whole one was an Integer)");
                assertEquals(fee.get(k), back.get(k), k + ": value and scale");
            }
            assertEquals(-3, ((BigDecimal) back.get("thousand")).scale());
            assertEquals(List.of(new BigDecimal("-0.075"), new BigDecimal("0")), back.get("rates"));
            assertEquals("stamp duty", back.get("label"));
            assertEquals(3, back.get("count"), "a nested Integer stays an Integer");

            String json = storedJson("fee");
            assertTrue(json.contains("\"whole\":{\"type\":\"Decimal\",\"value\":\"15\"}"), json);
            assertTrue(json.contains("\"thousand\":{\"type\":\"Decimal\",\"value\":\"1E+3\"}"), json);
            assertTrue(json.contains("\"count\":3"), json);

            var hist = a.processEngine().getHistoryService().createHistoricVariableInstanceQuery()
                    .processInstanceId(pid).variableName("fee").singleResult();
            assertEquals(new BigDecimal("15"), ((Map<?, ?>) hist.getValue()).get("whole"), "history keeps the type");
        }
    }

    @Test
    void aPlainMapOfTheDecimalShapeIsThatDecimalAndBadTextIsRefusedAtWrite() {
        try (OperatonEngineAdapter a = engine()) {
            String pid = start(a, Map.of("fee", map("amount", map("type", "Decimal", "value", "10.00"))));
            assertEquals(new BigDecimal("10.00"),
                    ((Map<?, ?>) a.processEngine().getRuntimeService().getVariable(pid, "fee")).get("amount"));

            for (Object bad : new Object[] {"1,000", " 1", "+1", "NaN", 0.5d, 10}) {
                RuntimeException e = assertThrows(RuntimeException.class,
                        () -> start(a, Map.of("fee", map("amount", map("type", "Decimal", "value", bad)))));
                assertTrue(causedBy(e, "DECIMAL_INVALID"), bad + ": " + e);
            }
        }
    }

    @Test
    void rowsStoredBeforeKeepTheirPlainNumbers() throws Exception {
        try (OperatonEngineAdapter a = engine()) {
            String pid = start(a, Map.of("fee", map("amount", "placeholder")));
            storeLegacy("fee", "{\"amount\":15,\"rate\":0.075,\"big\":123456789012,\"label\":\"x\"}");
            Map<?, ?> back = (Map<?, ?>) a.processEngine().getRuntimeService().getVariable(pid, "fee");
            assertEquals(15, back.get("amount"), "a stored whole number is not converted silently");
            assertEquals(new BigDecimal("0.075"), back.get("rate"));
            assertEquals(123456789012L, back.get("big"));
        }
    }

    @Test
    void aStoredTypedShapeReadsAsItsTypeAndBadStoredTextIsRefused() throws Exception {
        try (OperatonEngineAdapter a = engine()) {
            RuntimeService rt = a.processEngine().getRuntimeService();
            String pid = start(a, Map.of("period", map("from", "placeholder"), "fee", map("amount", "placeholder")));
            // decision 353: a row stored with exactly the LocalDate shape (before decision 333 it was a plain map)
            storeLegacy("period", "{\"from\":{\"type\":\"LocalDate\",\"value\":\"2026-03-31\"},\"note\":"
                    + "{\"type\":\"LocalDate\",\"value\":\"x\",\"extra\":1}}");
            Map<?, ?> period = (Map<?, ?>) rt.getVariable(pid, "period");
            assertEquals(LocalDate.of(2026, 3, 31), period.get("from"));
            assertInstanceOf(Map.class, period.get("note"), "only the exact two-member shape is a date");
            storeLegacy("fee", "{\"amount\":{\"type\":\"Decimal\",\"value\":\"99.90\"}}");
            assertEquals(new BigDecimal("99.90"), ((Map<?, ?>) rt.getVariable(pid, "fee")).get("amount"));

            storeLegacy("period", "{\"from\":{\"type\":\"LocalDate\",\"value\":\"31-03-2026\"}}");
            RuntimeException e = assertThrows(RuntimeException.class, () -> rt.getVariable(pid, "period"));
            assertTrue(causedBy(e, "LOCAL_DATE_INVALID"), e.toString());
            storeLegacy("fee", "{\"amount\":{\"type\":\"Decimal\",\"value\":\"9,90\"}}");
            RuntimeException e2 = assertThrows(RuntimeException.class, () -> rt.getVariable(pid, "fee"));
            assertTrue(causedBy(e2, "DECIMAL_INVALID"), e2.toString());
        }
    }

    @Test
    void theInventoryListsRowsHoldingATypedShapeAndChangesNothing() throws Exception {
        try (OperatonEngineAdapter a = engine()) {
            start(a, Map.of("period", map("from", LocalDate.of(2026, 4, 1)), "plain", map("from", "2026-04-01")));
            start(a, Map.of("period", map("from", LocalDate.of(2026, 5, 1)), "fee", map("amount", new BigDecimal("5"))));
            String pid = start(a, Map.of("bad", map("x", "placeholder"), "broken", map("x", "placeholder")));
            storeLegacy("bad", "[{\"type\":\"LocalDate\",\"value\":\"2026-02-30\"},{\"type\":\"Decimal\",\"value\":1.5}]");
            storeLegacy("broken", "{not json");

            List<Entry> entries;
            try (Connection c = ds.getConnection()) {
                entries = TypedJsonShapeInventory.scan(c, "");
            }
            List<Entry> runtime = entries.stream().filter(e -> e.source() == Source.RUNTIME).toList();
            assertTrue(runtime.contains(new Entry(Source.RUNTIME, "t1", "fees", "period", Finding.LOCAL_DATE, 2)), runtime.toString());
            assertTrue(runtime.contains(new Entry(Source.RUNTIME, "t1", "fees", "fee", Finding.DECIMAL, 1)), runtime.toString());
            assertTrue(runtime.contains(new Entry(Source.RUNTIME, "t1", "fees", "bad", Finding.LOCAL_DATE_INVALID, 1)));
            assertTrue(runtime.contains(new Entry(Source.RUNTIME, "t1", "fees", "bad", Finding.DECIMAL_INVALID, 1)));
            assertTrue(runtime.contains(new Entry(Source.RUNTIME, "t1", "fees", "broken", Finding.UNREADABLE, 1)));
            assertTrue(runtime.stream().noneMatch(e -> e.variableName().equals("plain")), "a plain string is no shape");
            assertTrue(entries.contains(new Entry(Source.HISTORY, "t1", "fees", "period", Finding.LOCAL_DATE, 2)),
                    entries.toString());
            assertTrue(entries.contains(new Entry(Source.HISTORY, "t1", "fees", "fee", Finding.DECIMAL, 1)));

            assertEquals("[{\"type\":\"LocalDate\",\"value\":\"2026-02-30\"},{\"type\":\"Decimal\",\"value\":1.5}]",
                    storedJson("bad"), "read-only");
            try (Connection c = ds.getConnection()) {
                assertThrows(IllegalArgumentException.class, () -> TypedJsonShapeInventory.scan(c, "x;--"));
            }
            assertEquals(Map.of("x", "placeholder"),
                    a.processEngine().getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(pid)
                            .variableName("bad").singleResult().getValue(), "history row untouched");
        }
    }
}
