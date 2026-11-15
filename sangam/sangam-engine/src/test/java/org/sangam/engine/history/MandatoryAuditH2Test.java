// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.task.Task;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * M-012 guard (X.2.3 "cannot be switched off by history level", T62). One scenario (a parent with a user task, a
 * called child, an async step whose job gets an incident that is then resolved) runs per history configuration twice:
 * <ul>
 *   <li><b>legacy</b>: {@code auditedHistoryEvents} empty. The mandatory-audit wrapper then answers exactly as the
 *       configured level, records nothing, and the handler and producer pass everything through, so this is the
 *       engine as it was before M-012;</li>
 *   <li><b>current</b>: the default audited set.</li>
 * </ul>
 * The {@code ACT_HI_*} tables (ids and times normalised) and the typed engine events must be identical in both runs:
 * the configured level still decides operational history and typed events. The audit chain must hold the class-B
 * events whatever the level, which the legacy run proves it did not before for per-definition {@code none} and for
 * incidents at {@code audit} / {@code activity}.
 */
class MandatoryAuditH2Test {

    private static final String MODELS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="m012P" isExecutable="true" operaton:historyTimeToLive="P1D">
                <startEvent id="ps"/><sequenceFlow id="p1" sourceRef="ps" targetRef="approve"/>
                <userTask id="approve"/>
                <sequenceFlow id="p2" sourceRef="approve" targetRef="call"/>
                <callActivity id="call" calledElement="m012C" operaton:calledElementBinding="latest"
                              operaton:calledElementTenantId="t1"/>
                <sequenceFlow id="p3" sourceRef="call" targetRef="post"/>
                <serviceTask id="post" operaton:asyncBefore="true" operaton:expression="${true}"/>
                <sequenceFlow id="p4" sourceRef="post" targetRef="pe"/><endEvent id="pe"/>
              </process>
              <process id="m012C" isExecutable="true" operaton:historyTimeToLive="P1D">
                <startEvent id="cs"/><sequenceFlow id="c1" sourceRef="cs" targetRef="review"/>
                <userTask id="review"/>
                <sequenceFlow id="c2" sourceRef="review" targetRef="ce"/><endEvent id="ce"/>
              </process>
            </definitions>
            """;

    private static final Pattern UUID_LIKE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** What one run left behind. */
    record Run(Map<String, List<String>> history, List<String> typedEvents, List<String> audit) {
    }

    private static Run run(Consumer<SangamEngineSettings> config, boolean legacy) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m012" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        List<String> typed = Collections.synchronizedList(new ArrayList<>());
        List<String> audit = Collections.synchronizedList(new ArrayList<>());
        s.setEventSink((c, tenant, root, type, data) -> typed.add(type + "|" + data.get("processDefinitionKey")));
        s.setAuditSink((c, tenant, root, type, data) -> audit.add(type + "|" + data.get("processDefinitionKey")));
        config.accept(s);
        if (legacy) {
            s.setAuditedHistoryEvents(new java.util.LinkedHashSet<>());
        }
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            scenario(a);
            return new Run(dumpHistory(ds), new ArrayList<>(typed), new ArrayList<>(audit));
        }
    }

    private static void scenario(OperatonEngineAdapter a) {
        a.deploy("t1", "m012", Map.of("m012.bpmn", MODELS.getBytes(StandardCharsets.UTF_8)));
        String pi = a.startProcess("t1", "m012P", "M012-1", Map.of());
        TaskService tasks = a.processEngine().getTaskService();
        Task approve = tasks.createTaskQuery().processInstanceId(pi).singleResult();
        tasks.setAssignee(approve.getId(), "officer-1"); // TASK_INSTANCE_UPDATE
        tasks.complete(approve.getId());
        Task review = tasks.createTaskQuery().taskDefinitionKey("review").singleResult();
        tasks.complete(review.getId());
        ManagementService mgmt = a.processEngine().getManagementService();
        Job post = mgmt.createJobQuery().processInstanceId(pi).singleResult();
        mgmt.setJobRetries(post.getId(), 0); // INCIDENT_CREATE
        mgmt.setJobRetries(post.getId(), 1); // INCIDENT_RESOLVE
        a.executeDueJobs();
        assertEquals(0, a.processEngine().getRuntimeService().createProcessInstanceQuery().processInstanceId(pi).count(),
                "the scenario ran to the end");
    }

    /** Every ACT_HI_* table: rows with ids and times normalised, sorted. */
    private static Map<String, List<String>> dumpHistory(JdbcDataSource ds) {
        Map<String, List<String>> out = new TreeMap<>();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES"
                    + " WHERE UPPER(TABLE_NAME) LIKE 'ACT\\_HI\\_%' ESCAPE '\\'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String t : tables) {
                List<String> rows = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT * FROM " + t)) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        StringBuilder b = new StringBuilder();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            b.append(md.getColumnName(i)).append('=').append(normalise(md, i, rs)).append(';');
                        }
                        rows.add(b.toString());
                    }
                }
                Collections.sort(rows);
                out.put(t.toUpperCase(java.util.Locale.ROOT), rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static String normalise(ResultSetMetaData md, int i, ResultSet rs) throws SQLException {
        Object v = rs.getObject(i);
        if (v == null) {
            return "null";
        }
        int type = md.getColumnType(i);
        if (type == Types.TIMESTAMP || type == Types.TIMESTAMP_WITH_TIMEZONE || type == Types.DATE
                || md.getColumnName(i).toUpperCase(java.util.Locale.ROOT).contains("DURATION")) {
            return "<set>"; // presence is compared (e.g. REMOVAL_TIME_), not the clock value
        }
        if (v instanceof byte[] bytes) {
            return "<bytes:" + bytes.length + ">";
        }
        return UUID_LIKE.matcher(String.valueOf(v)).replaceAll("#");
    }

    private static void assertSameHistoryAndTypedEvents(Consumer<SangamEngineSettings> config, String label) {
        Run legacy = run(config, true);
        Run current = run(config, false);
        assertEquals(legacy.history(), current.history(), label + ": ACT_HI_* rows as the configured level wrote them");
        List<String> lt = new ArrayList<>(legacy.typedEvents());
        List<String> ct = new ArrayList<>(current.typedEvents());
        Collections.sort(lt);
        Collections.sort(ct);
        assertEquals(lt, ct, label + ": typed engine events still follow the configured level");
    }

    private static Consumer<SangamEngineSettings> global(String level) {
        return s -> s.setHistory(level);
    }

    private static Consumer<SangamEngineSettings> parentNone() {
        return s -> {
            s.setHistory("full");
            s.setHistoryLevelByProcessKey(Map.of("m012P", "none"));
        };
    }

    // ------------------------------------------------------------------ ACT_HI_* and typed events unchanged

    @Test
    void perDefinitionNoneKeepsHistoryAndTypedEvents() {
        assertSameHistoryAndTypedEvents(parentNone(), "per-definition none (child full)");
    }

    @Test
    void globalActivityKeepsHistoryAndTypedEvents() {
        assertSameHistoryAndTypedEvents(global("activity"), "activity");
    }

    @Test
    void globalAuditKeepsHistoryAndTypedEvents() {
        assertSameHistoryAndTypedEvents(global("audit"), "audit");
    }

    @Test
    void globalFullKeepsHistoryAndTypedEvents() {
        assertSameHistoryAndTypedEvents(global("full"), "full");
    }

    // global "none" is refused at startup (owner decision 2026-11-12): GlobalHistoryNoneRefusedTest

    @Test
    void globalVariableKeepsHistoryAndTypedEvents() {
        assertSameHistoryAndTypedEvents(global("variable"), "variable");
    }

    // ------------------------------------------------------------------ deprecated alias history=variable

    @Test
    void variableResolvesToTheActivityWrapper() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m012v" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setHistory("variable");
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            org.operaton.bpm.engine.impl.history.HistoryLevel l =
                    ((org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl) a.processEngine()
                            .getProcessEngineConfiguration()).getHistoryLevel();
            assertTrue(l instanceof MandatoryAuditHistoryLevel w
                    && w.configured() == org.operaton.bpm.engine.impl.history.HistoryLevel.HISTORY_LEVEL_ACTIVITY,
                    "variable = Operaton's activity level, wrapped: " + l);
            assertEquals(org.operaton.bpm.engine.impl.history.HistoryLevel.HISTORY_LEVEL_ACTIVITY.getId(), l.getId());
        }
    }

    @Test
    void variableKeepsTheIncidentAudit() {
        Run current = run(global("variable"), false);
        assertTrue(current.audit().contains("INCIDENT_CREATE|m012P"), current.audit().toString());
        assertTrue(current.audit().contains("INCIDENT_RESOLVE|m012P"), current.audit().toString());
        assertTrue(current.history().get("ACT_HI_INCIDENT").isEmpty(), "variable = activity: no ACT_HI_INCIDENT rows");
        assertTrue(current.typedEvents().stream().noneMatch(t -> t.startsWith("INCIDENT_")), "and no typed incident events");
    }

    @Test
    void variableWritesExactlyWhatActivityWrites() {
        assertSameRun(run(global("variable"), false), run(global("activity"), false), "global variable vs activity");
    }

    /** Integrator decision: the per-definition base "variable" is Operaton's activity level, not full. */
    @Test
    void perDefinitionBaseVariableWritesExactlyWhatGlobalActivityWrites() {
        Run perDefinition = run(s -> {
            s.setHistory("variable");
            s.setHistoryLevelByProcessKey(Map.of("m012NotInTheScenario", "full")); // m012P and m012C use the base
        }, false);
        assertSameRun(perDefinition, run(global("activity"), false), "per-definition base variable vs global activity");
    }

    private static void assertSameRun(Run variable, Run activity, String label) {
        assertEquals(activity.history(), variable.history(), label + ": ACT_HI_* rows");
        List<String> vt = new ArrayList<>(variable.typedEvents());
        List<String> at = new ArrayList<>(activity.typedEvents());
        Collections.sort(vt);
        Collections.sort(at);
        assertEquals(at, vt, label + ": typed engine events");
        List<String> va = new ArrayList<>(variable.audit());
        List<String> aa = new ArrayList<>(activity.audit());
        Collections.sort(va);
        Collections.sort(aa);
        assertEquals(aa, va, label + ": audit chain");
    }

    // ------------------------------------------------------------------ class-B audit kept

    private static final List<String> PARENT_CLASS_B = List.of(
            "PROCESS_INSTANCE_START|m012P", "TASK_INSTANCE_CREATE|m012P", "TASK_INSTANCE_COMPLETE|m012P", "INCIDENT_CREATE|m012P", "INCIDENT_RESOLVE|m012P",
            "PROCESS_INSTANCE_END|m012P");

    @Test
    void perDefinitionNoneStillAuditsTheParent() {
        Run legacy = run(parentNone(), true);
        // INCIDENT_OPENED is not a history event: the incident handler writes it whatever the audited set is (class B,
        // decisions 299 to 303)
        assertTrue(legacy.audit().stream().allMatch(e -> e.startsWith("INCIDENT_OPENED|")),
                "precondition: an empty audited set audits no history event: " + legacy.audit());
        Run current = run(parentNone(), false);
        assertTrue(current.audit().containsAll(PARENT_CLASS_B), "class-B events of the none definition: " + current.audit());
        assertEquals(0, current.history().get("ACT_HI_PROCINST").stream().filter(r -> r.contains("m012P")).count(),
                "and still no operational history for it");
        // incidents are decided by the base level (PerDefinitionHistoryLevel has no definition key for an incident)
        assertTrue(current.typedEvents().stream().filter(t -> t.endsWith("|m012P")).allMatch(t -> t.startsWith("INCIDENT_")),
                "and no process or task typed events for it: " + current.typedEvents());
    }

    @Test
    void incidentsAreAuditedAtAuditAndActivity() {
        for (String level : List.of("audit", "activity")) {
            Run current = run(global(level), false);
            assertTrue(current.audit().contains("INCIDENT_CREATE|m012P"), level + ": " + current.audit());
            assertTrue(current.audit().contains("INCIDENT_RESOLVE|m012P"), level + ": " + current.audit());
            assertTrue(current.history().get("ACT_HI_INCIDENT").isEmpty(), level + ": still no ACT_HI_INCIDENT rows");
            assertTrue(current.typedEvents().stream().noneMatch(t -> t.startsWith("INCIDENT_")),
                    level + ": and no typed incident events");
        }
    }

    @Test
    void fullAuditsWhatItAuditedBefore() {
        Run current = run(global("full"), false);
        assertTrue(current.audit().containsAll(PARENT_CLASS_B), current.audit().toString());
        assertTrue(Set.copyOf(current.audit()).containsAll(Set.of("PROCESS_INSTANCE_START|m012C", "PROCESS_INSTANCE_END|m012C")));
    }
}
