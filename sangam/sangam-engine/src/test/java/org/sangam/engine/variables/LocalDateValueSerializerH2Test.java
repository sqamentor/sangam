// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.variables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.impl.QueryOperator;
import org.operaton.bpm.engine.impl.QueryVariableValue;
import org.operaton.bpm.engine.impl.SingleQueryVariableValueCondition;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.variable.serializer.TypedValueSerializer;
import org.operaton.bpm.engine.runtime.ProcessInstanceQuery;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.packs.engine.DecimalValueSerializer;
import org.sangam.packs.engine.LocalDateText;
import org.sangam.packs.engine.LocalDateValueSerializer;
import org.sangam.packs.engine.LocalDateValueType;

/**
 * Decision 333: LocalDate process variables are a type of their own ({@code sangam-local-date-v1}, ISO text in
 * {@code TEXT_}), built as decision 138 built decimals: they come back typed through runtime, history and a new engine
 * on the same database, inside JSON maps and lists, from a DMN {@code date} output; range queries are chronological;
 * invalid text is refused; a date stored as a string stays a string. Also pins the engine internals the type relies on
 * (PATCHES.md B-47, I-24).
 */
class LocalDateValueSerializerH2Test {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="days" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <userTask id="work"/><sequenceFlow id="f2" sourceRef="work" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    /** A decision with a {@code typeRef="date"} input, a FEEL range, a FEEL comparison and FEEL date arithmetic. */
    private static final String DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d333" name="d333" namespace="t">
              <decision id="quarter" name="quarter"><decisionTable id="qt" hitPolicy="UNIQUE">
                <input id="qi"><inputExpression id="qie" typeRef="date"><text>day</text></inputExpression></input>
                <output id="qo1" name="band" typeRef="string"/>
                <output id="qo2" name="next" typeRef="date"/>
                <output id="qo3" name="beforeCutOff" typeRef="boolean"/>
                <rule id="q1"><inputEntry id="q1i"><text>[date("2026-01-01")..date("2026-03-31")]</text></inputEntry>
                  <outputEntry id="q1a"><text>"Q4"</text></outputEntry>
                  <outputEntry id="q1b"><text>day + duration("P1D")</text></outputEntry>
                  <outputEntry id="q1c"><text>day &lt; date("2026-03-15")</text></outputEntry></rule>
                <rule id="q2"><inputEntry id="q2i"><text>&gt; date("2026-03-31")</text></inputEntry>
                  <outputEntry id="q2a"><text>"later"</text></outputEntry>
                  <outputEntry id="q2b"><text>day - duration("P1D")</text></outputEntry>
                  <outputEntry id="q2c"><text>false</text></outputEntry></rule>
              </decisionTable></decision>
            </definitions>
            """;

    private static final String RULE_MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="dayRule" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="brt"/>
                <businessRuleTask id="brt" operaton:decisionRef="quarter" operaton:decisionRefBinding="deployment"
                                  operaton:mapDecisionResult="singleResult" operaton:resultVariable="result"/>
                <sequenceFlow id="f2" sourceRef="brt" targetRef="ut"/><userTask id="ut"/>
                <sequenceFlow id="f3" sourceRef="ut" targetRef="e"/><endEvent id="e"/>
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
            ds.setURL("jdbc:h2:mem:d333" + UUID.randomUUID().toString().replace("-", "")
                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        }
        OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2");
        if (deploy) {
            a.deploy("t1", "d333-" + UUID.randomUUID(), Map.of("days.bpmn", MODEL.getBytes(StandardCharsets.UTF_8),
                    "dayRule.bpmn", RULE_MODEL.getBytes(StandardCharsets.UTF_8),
                    "quarter.dmn", DMN.getBytes(StandardCharsets.UTF_8)));
        }
        return a;
    }

    private String start(OperatonEngineAdapter a, Map<String, Object> vars) {
        return a.startProcess("t1", "days", null, vars);
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

    private static boolean causedBy(Throwable t, String code) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof LocalDateText.LocalDateFormatException d && d.code().equals(code)) {
                return true;
            }
            if (x.getMessage() != null && x.getMessage().contains(code)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void typedAndUntypedDatesRoundTripWithTheirTypeAsIsoText() throws Exception {
        try (OperatonEngineAdapter a = engine(true)) {
            Map<String, Object> vars = new HashMap<>();
            vars.put("day", LocalDate.of(2026, 3, 31));
            vars.put("typedDay", LocalDateValueType.localDate(LocalDate.of(1999, 1, 9)));
            vars.put("firstDay", LocalDate.of(0, 1, 1));
            vars.put("lastDay", LocalDate.of(9999, 12, 31));
            String pid = start(a, vars);
            RuntimeService rt = a.processEngine().getRuntimeService();
            for (String n : List.of("day", "typedDay", "firstDay", "lastDay")) {
                Object want = vars.get(n) instanceof TypedValue tv ? tv.getValue() : vars.get(n);
                assertEquals(want, rt.getVariable(pid, n), n);
                assertEquals(LocalDateValueType.NAME, rt.getVariableTyped(pid, n).getType().getName(), n);
                String[] row = rows(n).getFirst();
                assertEquals(LocalDateValueSerializer.NAME, row[0], "persisted type id");
                assertEquals(want.toString(), row[1], "TEXT_ holds format v1");
                assertNull(row[2]);
                assertNull(row[3]);
                assertNull(row[4]);
                assertNull(row[5], "no Java serialization, no byte array");
            }
        }
    }

    @Test
    void historyAndANewEngineOnTheSameDatabaseReadTheSameTypedDate() {
        String pid;
        try (OperatonEngineAdapter a = engine(true)) {
            pid = start(a, Map.of("due", LocalDate.of(2026, 4, 1)));
        }
        try (OperatonEngineAdapter b = engine(false)) {
            assertEquals(LocalDate.of(2026, 4, 1), b.processEngine().getRuntimeService().getVariable(pid, "due"));
            var hist = b.processEngine().getHistoryService().createHistoricVariableInstanceQuery()
                    .processInstanceId(pid).variableName("due").singleResult();
            assertEquals(LocalDate.of(2026, 4, 1), hist.getValue());
            assertEquals(LocalDateValueType.NAME, hist.getTypedValue().getType().getName());
            assertEquals(LocalDateValueType.NAME, hist.getTypeName(), "history keeps the type");
        }
    }

    @Test
    void aDateStoredAsAStringStaysAString() throws Exception {
        try (OperatonEngineAdapter a = engine(true)) {
            String pid = start(a, Map.of("oldDay", "2026-03-31"));
            Object back = a.processEngine().getRuntimeService().getVariable(pid, "oldDay");
            assertInstanceOf(String.class, back, "no silent conversion");
            assertEquals("string", rows("oldDay").getFirst()[0]);
            assertEquals(0, a.processEngine().getRuntimeService().createProcessInstanceQuery()
                    .variableValueEquals("oldDay", LocalDate.of(2026, 3, 31)).count(),
                    "a typed date query does not match a string");
        }
    }

    @Test
    void untypedNullStaysNullTypeAndTypedNullKeepsTheDateType() throws Exception {
        try (OperatonEngineAdapter a = engine(true)) {
            Map<String, Object> vars = new HashMap<>();
            vars.put("nothing", null);
            vars.put("noDay", LocalDateValueType.localDate(null));
            String pid = start(a, vars);
            assertEquals("null", rows("nothing").getFirst()[0]);
            assertEquals(LocalDateValueSerializer.NAME, rows("noDay").getFirst()[0]);
            TypedValue typed = a.processEngine().getRuntimeService().getVariableTyped(pid, "noDay");
            assertEquals(LocalDateValueType.NAME, typed.getType().getName());
            assertNull(typed.getValue());
        }
    }

    @Test
    void storedTextThatIsNotFormatV1IsRefusedOnRead() throws Exception {
        try (OperatonEngineAdapter a = engine(true)) {
            String pid = start(a, Map.of("day", LocalDate.of(2026, 3, 31)));
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement("UPDATE ACT_RU_VARIABLE SET TEXT_ = ? WHERE NAME_ = ?")) {
                ps.setString(1, "2026-03-31T00:00:00Z");
                ps.setString(2, "day");
                assertEquals(1, ps.executeUpdate());
            }
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> a.processEngine().getRuntimeService().getVariable(pid, "day"));
            assertTrue(causedBy(e, "LOCAL_DATE_INVALID"), e.toString());
        }
    }

    @Test
    void aYearOutsideTheSortableRangeIsRefusedAtWrite() {
        try (OperatonEngineAdapter a = engine(true)) {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> start(a, Map.of("day", LocalDate.of(10000, 1, 1))));
            assertTrue(causedBy(e, "LOCAL_DATE_OUT_OF_RANGE"), e.toString());
        }
    }

    @Test
    void rangeQueriesAreChronologicalAndLikeIsRefused() {
        try (OperatonEngineAdapter a = engine(true)) {
            String early = start(a, Map.of("day", LocalDate.of(999, 12, 31)));
            String mid = start(a, Map.of("day", LocalDate.of(2026, 3, 9)));
            String late = start(a, Map.of("day", LocalDate.of(2026, 10, 1)));
            RuntimeService rt = a.processEngine().getRuntimeService();
            LocalDate pivot = LocalDate.of(2026, 3, 9);
            assertEquals(List.of(late), ids(rt.createProcessInstanceQuery().variableValueGreaterThan("day", pivot)));
            assertEquals(List.of(mid, late).stream().sorted().toList(),
                    ids(rt.createProcessInstanceQuery().variableValueGreaterThanOrEqual("day", pivot)));
            assertEquals(List.of(early), ids(rt.createProcessInstanceQuery().variableValueLessThan("day", pivot)));
            assertEquals(List.of(early, mid).stream().sorted().toList(),
                    ids(rt.createProcessInstanceQuery().variableValueLessThanOrEqual("day", pivot)));
            assertEquals(List.of(mid), ids(rt.createProcessInstanceQuery().variableValueEquals("day", pivot)));
            assertEquals(List.of(early, late).stream().sorted().toList(),
                    ids(rt.createProcessInstanceQuery().variableValueNotEquals("day", pivot)));
            // "2026-10-01" > "2026-3-9" would be false as unpadded text; format v1 pads, so the order holds
            assertEquals(1, rt.createProcessInstanceQuery().variableValueGreaterThan("day", LocalDate.of(2026, 9, 30))
                    .count());

            // LIKE takes a String through the public API; a LIKE condition carrying a date is refused (fail closed)
            assertTrue(DecimalValueSerializer.operatorReadable(), "pin: the query operator field is still readable");
            for (QueryOperator op : List.of(QueryOperator.LIKE, QueryOperator.NOT_LIKE)) {
                SingleQueryVariableValueCondition like = new SingleQueryVariableValueCondition(
                        new QueryVariableValue("day", pivot, op, true));
                RuntimeException e = assertThrows(RuntimeException.class,
                        () -> new LocalDateValueSerializer().writeValue(LocalDateValueType.localDate(pivot), like));
                assertTrue(causedBy(e, "LOCAL_DATE_QUERY_OPERATOR"), e.toString());
            }
        }
    }

    private static List<String> ids(ProcessInstanceQuery q) {
        return q.list().stream().map(p -> p.getId()).sorted().toList();
    }

    @Test
    void datesInsideMapsAndListsAreTypedJsonObjectsAndComeBackAsDates() throws Exception {
        try (OperatonEngineAdapter a = engine(true)) {
            Map<String, Object> period = new LinkedHashMap<>();
            period.put("from", LocalDate.of(2026, 4, 1));
            period.put("label", "2026-27");
            period.put("holidays", new ArrayList<>(List.of(LocalDate.of(2026, 8, 15), "2026-10-02")));
            List<Object> days = new ArrayList<>(List.of(LocalDate.of(2026, 3, 31), Map.of("on", LocalDate.of(2027, 1, 26))));
            String pid = start(a, Map.of("period", period, "days", days));
            RuntimeService rt = a.processEngine().getRuntimeService();

            Map<?, ?> p = (Map<?, ?>) rt.getVariable(pid, "period");
            assertEquals(LocalDate.of(2026, 4, 1), p.get("from"));
            assertEquals("2026-27", p.get("label"));
            assertEquals(List.of(LocalDate.of(2026, 8, 15), "2026-10-02"), p.get("holidays"),
                    "a nested date stays a date, a nested string stays a string");
            List<?> d = (List<?>) rt.getVariable(pid, "days");
            assertEquals(LocalDate.of(2026, 3, 31), d.get(0));
            assertEquals(LocalDate.of(2027, 1, 26), ((Map<?, ?>) d.get(1)).get("on"));

            String json = storedJson("period");
            assertTrue(json.contains("\"from\":{\"type\":\"LocalDate\",\"value\":\"2026-04-01\"}"), json);
            assertTrue(json.contains("\"2026-10-02\""), json);
            assertTrue(storedJson("days").startsWith("[{\"type\":\"LocalDate\",\"value\":\"2026-03-31\"}"));
        }
    }

    @Test
    void aNestedDateOutOfRangeOrAnInvalidTypedShapeIsRefusedAtWrite() {
        try (OperatonEngineAdapter a = engine(true)) {
            Map<String, Object> wide = new LinkedHashMap<>();
            wide.put("from", LocalDate.of(-1, 1, 1));
            RuntimeException e = assertThrows(RuntimeException.class, () -> start(a, Map.of("period", wide)));
            assertTrue(causedBy(e, "LOCAL_DATE_OUT_OF_RANGE"), e.toString());

            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("type", "LocalDate");
            shape.put("value", "31-03-2026");
            RuntimeException e2 = assertThrows(RuntimeException.class,
                    () -> start(a, Map.of("period", Map.of("from", shape))));
            assertTrue(causedBy(e2, "LOCAL_DATE_INVALID"), e2.toString());
        }
    }

    @Test
    void dmnDateInputsFeelComparisonsAndDateArithmeticWorkOnATypedDate() throws Exception {
        try (OperatonEngineAdapter a = engine(true)) {
            String pid = a.startProcess("t1", "dayRule", null, Map.of("day", LocalDate.of(2026, 3, 9)));
            Map<?, ?> r = (Map<?, ?>) a.processEngine().getRuntimeService().getVariable(pid, "result");
            assertEquals("Q4", r.get("band"));
            assertEquals(LocalDate.of(2026, 3, 10), r.get("next"), "FEEL day + P1D is a date, stored typed");
            assertEquals(Boolean.TRUE, r.get("beforeCutOff"));

            String pid2 = a.startProcess("t1", "dayRule", null,
                    Map.of("day", LocalDateValueType.localDate(LocalDate.of(2026, 4, 1))));
            Map<?, ?> r2 = (Map<?, ?>) a.processEngine().getRuntimeService().getVariable(pid2, "result");
            assertEquals("later", r2.get("band"));
            assertEquals(LocalDate.of(2026, 3, 31), r2.get("next"));

            var direct = a.processEngine().getDecisionService().evaluateDecisionByKey("quarter")
                    .decisionDefinitionTenantId("t1").variables(Map.of("day", LocalDate.of(2026, 1, 1))).evaluate();
            assertEquals(LocalDate.of(2026, 1, 2), direct.getSingleResult().getEntry("next"));
        }
    }

    @Test
    void pinRegistrationOrderAndOnePerEngine() {
        ProcessEngineConfigurationImpl cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setCustomPreVariableSerializers(new ArrayList<>(List.of(new LargeStringValueSerializer(4000))));
        LocalDateValueSerializer.register(cfg);
        LocalDateValueSerializer.register(cfg);
        List<TypedValueSerializer<?>> pre = cfg.getCustomPreVariableSerializers();
        assertEquals(2, pre.size());
        assertEquals(LocalDateValueSerializer.NAME, pre.getFirst().getName());

        try (OperatonEngineAdapter a = engine(false)) {
            ProcessEngineConfigurationImpl c = (ProcessEngineConfigurationImpl) a.processEngine()
                    .getProcessEngineConfiguration();
            assertTrue(c.getVariableSerializers().getSerializerByName(LocalDateValueSerializer.NAME) != null);
            List<String> names = c.getCustomPreVariableSerializers().stream().map(TypedValueSerializer::getName).toList();
            assertEquals(DecimalValueSerializer.NAME, names.get(0), "B-37: the decimal serializer stays first");
            assertEquals(LocalDateValueSerializer.NAME, names.get(1), "B-47: before the string and JSON serializers");
            assertEquals(1, names.stream().filter(LocalDateValueSerializer.NAME::equals).count());
        }
    }
}
