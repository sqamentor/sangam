// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.runtime.Incident;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.engine.deploy.StrictExclusiveGatewayBehavior;
import org.sangam.engine.tx.CommandState;

/**
 * ADR-D-25 strict exclusive (XOR) routing on the Sangam engine (H2). Replaces the former pin test
 * {@code exclusiveGatewayTakesFirstTrueFlowInDocumentOrder}, which documented Operaton's native first-match rule that
 * the owner decision replaced. Every test here fails on native first-match semantics.
 */
class StrictExclusiveRoutingH2Test {

    private static final String NS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                         xmlns:sangam="http://sangam.org/schema/1.0/bpmn" targetNamespace="t">
            """;

    /** A downstream financial effect: one ledger posting row per execution that reaches it. */
    public static class Posting implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            Jdbc.update(CommandState.currentConnection(), "INSERT INTO TEST_POSTING(instance_id, route) VALUES (?, ?)",
                    execution.getProcessInstanceId(), execution.getCurrentActivityId());
        }
    }

    /** Test authorization port: MODIFY on PROCESS_INSTANCE only for role ROUTE_FIXER. */
    static final class RoleAuthorization implements SecurityPorts.Authorization {
        @Override
        public org.sangam.core.layer.SqlPredicate visible(Connection c, SecurityIdentity i, String type, String p, String alias) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean allow(Connection c, SecurityIdentity i, String type, String p, String id) {
            return "PROCESS_INSTANCE".equals(type) && "MODIFY".equals(p) && i.hasRole("ROUTE_FIXER");
        }

        @Override
        public void require(Connection c, SecurityIdentity i, String type, String p, String id) {
            if (!allow(c, i, type, p, id)) {
                throw new SangamSecurityException.AccessDenied(i.personId() + " may not " + p + " " + type, 1);
            }
        }

        @Override
        public long policyVersion(Connection c, String tenantId) {
            return 1;
        }
    }

    private static SecurityIdentity person(String id, String... roles) {
        return new SecurityIdentity("t1", id, "user:" + id, Set.of(), Set.of(roles), Set.of(), Set.of(), "PASSWORD",
                null, null, false, Map.of());
    }

    private static byte[] bpmn(String body) {
        return (NS + body + "\n</definitions>").getBytes(StandardCharsets.UTF_8);
    }

    private static void withEngine(Consumer<OperatonEngineAdapter> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:r" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setRouteCorrectionAuthorization(new RoleAuthorization());
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            a.inEngineTransaction(c -> Jdbc.update(c,
                    "CREATE TABLE TEST_POSTING(instance_id VARCHAR(64), route VARCHAR(64))"));
            test.accept(a);
        }
    }

    /** Gateway {@code gw} with the given flows (each targets a posting service task, then a user task). */
    private static String model(String gatewayAttrs, String... flows) {
        StringBuilder b = new StringBuilder("""
                  <process id="route" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f0" sourceRef="s" targetRef="gw"/>
                """);
        b.append("<exclusiveGateway id=\"gw\" ").append(gatewayAttrs).append("/>\n");
        for (String f : flows) {
            String[] p = f.split("\\|", -1); // id | condition | extra attrs
            String id = p[0];
            b.append("<sequenceFlow id=\"").append(id).append("\" sourceRef=\"gw\" targetRef=\"post_").append(id)
                    .append("\" ").append(p.length > 2 ? p[2] : "").append(">");
            if (!p[1].isEmpty()) {
                b.append("<conditionExpression xsi:type=\"tFormalExpression\">").append(p[1]).append("</conditionExpression>");
            }
            b.append("</sequenceFlow>\n");
            b.append("<serviceTask id=\"post_").append(id).append("\" operaton:class=\"")
                    .append(Posting.class.getName()).append("\"/>\n");
            b.append("<sequenceFlow id=\"g_").append(id).append("\" sourceRef=\"post_").append(id)
                    .append("\" targetRef=\"done_").append(id).append("\"/>\n");
            b.append("<userTask id=\"done_").append(id).append("\"/>\n");
        }
        return b.append("</process>").toString();
    }

    private static final String[] BANDS = {
            "low|${amount &lt; 100000}", "exact|${amount == 100000}", "high|${amount &gt; 100000}"};

    private static final String[] OVERLAP = {
            "atLeast|${amount &gt;= 100000}", "above|${amount &gt; 99999}", "fallback||"};

    private static long postings(OperatonEngineAdapter a, String pi) {
        return a.inEngineTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM TEST_POSTING WHERE instance_id = ?",
                rs -> rs.getLong(1), pi).orElse(0L));
    }

    private static List<String> taskKeys(ProcessEngine e, String pi) {
        return e.getTaskService().createTaskQuery().processInstanceId(pi).list().stream()
                .map(Task::getTaskDefinitionKey).sorted().toList();
    }

    private static List<Incident> routingIncidents(ProcessEngine e, String pi) {
        return e.getRuntimeService().createIncidentQuery().processInstanceId(pi)
                .incidentType(StrictExclusiveGatewayBehavior.INCIDENT_TYPE).list();
    }

    private static long downstreamActivities(ProcessEngine e, String pi) {
        return e.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(pi).list().stream()
                .filter(h -> h.getActivityId().startsWith("post_") || h.getActivityId().startsWith("done_")).count();
    }

    /** Non-overlapping bands at the exact boundaries 99,999 / 1,00,000 / 1,00,001: exactly one route each. */
    @Test
    void exactBoundariesTakeExactlyOneRoute() {
        withEngine(a -> {
            a.deploy("t1", "r", Map.of("r.bpmn", bpmn(model("", BANDS))));
            Map<Integer, String> expected = Map.of(99_999, "done_low", 100_000, "done_exact", 100_001, "done_high");
            for (var e : expected.entrySet()) {
                String pi = a.startProcess("t1", "route", "B-" + e.getKey(), Map.of("amount", e.getKey()));
                assertEquals(List.of(e.getValue()), taskKeys(a.processEngine(), pi), "amount " + e.getKey());
                assertEquals(1, postings(a, pi), "exactly one posting");
            }
        });
    }

    /**
     * Overlapping bands (amount >= 100000 and amount > 99999) at 100000: AMBIGUOUS_ROUTE incident at the gateway, no
     * downstream activity and no posting. Native first-match would post on the first flow; the default is not taken.
     */
    @Test
    void overlappingBandsRaiseAmbiguousRouteWithoutDownstreamEffect() {
        withEngine(a -> {
            a.deploy("t1", "r", Map.of("r.bpmn", bpmn(model("default=\"fallback\"", OVERLAP))));
            String pi = a.startProcess("t1", "route", "O-1", Map.of("amount", 100_000));
            ProcessEngine e = a.processEngine();
            List<Incident> inc = routingIncidents(e, pi);
            assertEquals(1, inc.size(), "one routing incident");
            assertTrue(inc.get(0).getIncidentMessage().startsWith("AMBIGUOUS_ROUTE"), inc.get(0).getIncidentMessage());
            assertTrue(inc.get(0).getIncidentMessage().contains("atLeast") && inc.get(0).getIncidentMessage().contains("above"));
            assertEquals("gw", inc.get(0).getActivityId(), "the incident is at the gateway");
            assertEquals(0, postings(a, pi), "no ledger posting");
            assertEquals(0, downstreamActivities(e, pi), "no downstream activity ran");
            assertEquals(List.of(), taskKeys(e, pi));
            assertFalse(a.getInstance(pi).ended(), "the instance waits at the gateway");
            // only one of the two true at 99,999.5? (integer data): 99,999 makes neither true -> explicit default
            String low = a.startProcess("t1", "route", "O-2", Map.of("amount", 50));
            assertEquals(List.of("done_fallback"), taskKeys(e, low), "none true -> explicit default flow");
            String one = a.startProcess("t1", "route", "O-3", Map.of("amount", 99_999.5));
            assertEquals(List.of("done_above"), taskKeys(e, one), "exactly one true -> that flow");
        });
    }

    /** Several true conditions (three flows all true): AMBIGUOUS_ROUTE listing every match. */
    @Test
    void multipleMatchesListEveryTrueFlow() {
        withEngine(a -> {
            a.deploy("t1", "r", Map.of("r.bpmn", bpmn(model("", "a|${x &gt; 0}", "b|${x &gt; 1}", "c|${x &gt; 2}"))));
            String pi = a.startProcess("t1", "route", "M-1", Map.of("x", 5));
            String msg = routingIncidents(a.processEngine(), pi).get(0).getIncidentMessage();
            assertTrue(msg.startsWith("AMBIGUOUS_ROUTE") && msg.contains("[a, b, c]"), msg);
            assertEquals(0, postings(a, pi));
        });
    }

    /** None true and no default: NO_MATCHING_ROUTE incident, nothing downstream. */
    @Test
    void noMatchWithoutDefaultIsNoMatchingRouteIncident() {
        withEngine(a -> {
            a.deploy("t1", "r", Map.of("r.bpmn", bpmn(model("", BANDS))));
            String pi = a.startProcess("t1", "route", "N-1", Map.of("amount", Double.NaN)); // every band false
            List<Incident> inc = routingIncidents(a.processEngine(), pi);
            assertEquals(1, inc.size());
            assertTrue(inc.get(0).getIncidentMessage().startsWith("NO_MATCHING_ROUTE"), inc.get(0).getIncidentMessage());
            assertEquals(0, postings(a, pi));
            assertEquals(0, downstreamActivities(a.processEngine(), pi));
        });
    }

    /** A missing variable is an error, never "false": the command fails, the default is not taken, nothing posts. */
    @Test
    void missingVariableIsAnErrorNotTheDefault() {
        withEngine(a -> {
            a.deploy("t1", "r", Map.of("r.bpmn", bpmn(model("default=\"fallback\"", OVERLAP))));
            assertThrows(RuntimeException.class, () -> a.startProcess("t1", "route", "E-1", Map.of()));
            long all = a.inEngineTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM TEST_POSTING",
                    rs -> rs.getLong(1)).orElse(0L));
            assertEquals(0L, all, "no posting, the default flow was not taken");
            assertEquals(0, a.processEngine().getRuntimeService().createProcessInstanceQuery().count());
        });
    }

    /** Behind an async boundary the same errors (missing variable, type error) become a failed-job incident. */
    @Test
    void expressionErrorsBecomeIncidentsNotDefault() {
        withEngine(a -> {
            a.deploy("t1", "r", Map.of("r.bpmn", bpmn(model("operaton:asyncBefore=\"true\" default=\"fallback\"",
                    "atLeast|${amount &gt;= 100000}", "typed|${amount.substring(1) == 'x'}", "fallback||"))));
            String missing = a.startProcess("t1", "route", "E-2", Map.of());
            String typeError = a.startProcess("t1", "route", "E-3", Map.of("amount", 5));
            for (int i = 0; i < 6; i++) {
                a.executeDueJobs();
            }
            ProcessEngine e = a.processEngine();
            for (String pi : List.of(missing, typeError)) {
                assertEquals(1, e.getRuntimeService().createIncidentQuery().processInstanceId(pi)
                        .incidentType("failedJob").count(), "failed-job incident for " + pi);
                assertEquals(0, postings(a, pi), "no posting, no default for " + pi);
                assertEquals(List.of(), taskKeys(e, pi));
            }
        });
    }

    /**
     * Recovery: an unauthorized user cannot correct; a correction that is still ambiguous rolls back; an authorized
     * correction resumes on exactly one route, resolves the incident and is audited with the corrector.
     */
    @Test
    void authorizedAuditedCorrectionResumesOnExactlyOneRoute() {
        withEngine(a -> {
            a.deploy("t1", "r", Map.of("r.bpmn", bpmn(model("default=\"fallback\"", OVERLAP))));
            String pi = a.startProcess("t1", "route", "R-1", Map.of("amount", 100_000));
            ProcessEngine e = a.processEngine();
            assertEquals(1, routingIncidents(e, pi).size());

            assertThrows(SangamSecurityException.AccessDenied.class,
                    () -> a.correctRoute(person("clerk-1"), pi, Map.of("amount", 10), "try"));
            assertEquals((Object) 100_000, e.getRuntimeService().getVariable(pi, "amount"), "denied: data unchanged");

            StrictExclusiveGatewayBehavior.RoutingException still = assertThrows(
                    StrictExclusiveGatewayBehavior.RoutingException.class,
                    () -> a.correctRoute(person("officer-7", "ROUTE_FIXER"), pi, Map.of("amount", 200_000), "still both"));
            assertEquals("AMBIGUOUS_ROUTE", still.code());
            assertEquals((Object) 100_000, e.getRuntimeService().getVariable(pi, "amount"), "unresolved correction rolled back");
            assertEquals(1, routingIncidents(e, pi).size());

            assertEquals(List.of("gw"), a.correctRoute(person("officer-7", "ROUTE_FIXER"), pi,
                    Map.of("amount", 99_999.5), "data entry fix: amount was mistyped"));
            assertEquals(List.of("done_above"), taskKeys(e, pi), "exactly one route");
            assertEquals(1, postings(a, pi), "exactly one posting");
            assertEquals(0, routingIncidents(e, pi).size(), "incident resolved");
            String audit = a.inEngineTransaction(c -> Jdbc.one(c, "SELECT p.payload FROM AUDIT_EVENT ev JOIN AUDIT_PAYLOAD p"
                    + " ON p.event_id = ev.event_id WHERE ev.event_type = 'ROUTE_CORRECTED'", rs -> rs.getString(1))
                    .orElse(""));
            assertTrue(audit.contains("officer-7") && audit.contains("amount") && audit.contains("mistyped"), audit);
            assertFalse(audit.contains("clerk-1"));
        });
    }

    /** Priority policy: explicit, validated priorities give deterministic first-match by priority (not model order). */
    @Test
    void priorityPolicyHonoursExplicitPriorityNotModelOrder() {
        withEngine(a -> {
            a.deploy("t1", "r", Map.of("r.bpmn", bpmn(model("sangam:routingPolicy=\"priority\" default=\"fallback\"",
                    "atLeast|${amount &gt;= 100000}|sangam:priority=\"2\"",
                    "above|${amount &gt; 99999}|sangam:priority=\"1\"", "fallback||"))));
            String pi = a.startProcess("t1", "route", "P-1", Map.of("amount", 100_000));
            assertEquals(List.of("done_above"), taskKeys(a.processEngine(), pi), "priority 1 wins although listed second");
            assertEquals(1, postings(a, pi));
        });
    }

    /** R44: incomplete, duplicate, non-integer or misplaced priorities and unknown policies are rejected at deploy. */
    @Test
    void invalidRoutingPoliciesAreRejectedAtDeploy() {
        withEngine(a -> {
            Map<String, String> bad = Map.of(
                    "missing", model("sangam:routingPolicy=\"priority\"", "x|${amount &gt; 1}|sangam:priority=\"1\"",
                            "y|${amount &gt; 2}"),
                    "duplicate", model("sangam:routingPolicy=\"priority\"", "x|${amount &gt; 1}|sangam:priority=\"1\"",
                            "y|${amount &gt; 2}|sangam:priority=\"1\""),
                    "nonInteger", model("sangam:routingPolicy=\"priority\"", "x|${amount &gt; 1}|sangam:priority=\"first\"",
                            "y|${amount &gt; 2}|sangam:priority=\"2\""),
                    "strictWithPriority", model("", "x|${amount &gt; 1}|sangam:priority=\"1\"", "y|${amount &gt; 2}"),
                    "unknownPolicy", model("sangam:routingPolicy=\"first\"", "x|${amount &gt; 1}", "y|${amount &gt; 2}"),
                    "unconditional", model("", "x|${amount &gt; 1}", "y||"),
                    "badVersion", model("sangam:routingPolicyVersion=\"9\"", "x|${amount &gt; 1}", "y|${amount &lt;= 1}"));
            for (var b : bad.entrySet()) {
                RuntimeException ex = assertThrows(RuntimeException.class,
                        () -> a.deploy("t1", b.getKey(), Map.of("r.bpmn", bpmn(b.getValue()))), b.getKey());
                StringBuilder m = new StringBuilder();
                for (Throwable t = ex; t != null; t = t.getCause()) {
                    m.append(t.getMessage());
                }
                assertTrue(m.toString().contains("ADR-D-25"), b.getKey() + ": " + m);
            }
        });
    }

    /** Inclusive and parallel gateways keep BPMN semantics: every true flow / every flow is taken. */
    @Test
    void inclusiveAndParallelGatewaysAreUnchanged() {
        withEngine(a -> {
            String m = """
                      <process id="incl" isExecutable="true">
                        <startEvent id="s"/>
                        <sequenceFlow id="f0" sourceRef="s" targetRef="ig"/>
                        <inclusiveGateway id="ig"/>
                        <sequenceFlow id="i1" sourceRef="ig" targetRef="ta"><conditionExpression xsi:type="tFormalExpression">${amount &gt;= 100000}</conditionExpression></sequenceFlow>
                        <sequenceFlow id="i2" sourceRef="ig" targetRef="tb"><conditionExpression xsi:type="tFormalExpression">${amount &gt; 99999}</conditionExpression></sequenceFlow>
                        <userTask id="ta"/><userTask id="tb"/>
                      </process>
                      <process id="par" isExecutable="true">
                        <startEvent id="ps"/>
                        <sequenceFlow id="p0" sourceRef="ps" targetRef="pg"/>
                        <parallelGateway id="pg"/>
                        <sequenceFlow id="p1" sourceRef="pg" targetRef="pa"/>
                        <sequenceFlow id="p2" sourceRef="pg" targetRef="pb"/>
                        <userTask id="pa"/><userTask id="pb"/>
                      </process>
                    """;
            a.deploy("t1", "ip", Map.of("ip.bpmn", bpmn(m)));
            String i = a.startProcess("t1", "incl", "I-1", Map.of("amount", 100_000));
            assertEquals(List.of("ta", "tb"), taskKeys(a.processEngine(), i));
            String p = a.startProcess("t1", "par", "P-1", Map.of());
            assertEquals(List.of("pa", "pb"), taskKeys(a.processEngine(), p));
        });
    }
}
