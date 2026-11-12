// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.consumer.ifms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.runtime.Incident;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * <b>SG-35-SANGAM</b>: the IFMS works-bill band gateway {@code gwBand} (IFMS Trial 2, kit X-13; evidence oneifms-sangam
 * 7c34baa, E-54) on the Sangam engine (H2, no container). The model is derived from the frozen, SHA-256-verified E-46
 * WF-4 fixture (the same bill: JEN to AEN / XEN / SEN / ACEN by amount), exactly as {@code E46ConformanceIT} derives
 * its overlap variant:
 * <ul>
 *   <li><b>X-13 shape</b>: WF-4 without its configured default flow, and an amount above every band (6,000,000 as a
 *       {@code Long}, as IFMS sends it): the instance must stop at gwBand with an open {@code NO_MATCHING_ROUTE}
 *       incident <i>and</i> one {@code ROUTING_INCIDENT} event in the audit hash chain, never a silent park;</li>
 *   <li>overlapping bands: {@code AMBIGUOUS_ROUTE}, audited the same way.</li>
 * </ul>
 * Red without commit 0d04fea (SG-35): before it the {@code sangamRouting} incident handler opened the incident but wrote
 * no audit event, so both audit assertions failed; the rest pins that no band, task or posting is taken.
 */
class Sg35BillBandRegressionH2Test {

    private static final String WF4 = "acceptance/consumer/ifms/e46/e46-wf4.bpmn.model";
    private static final String WF4_SHA256 = "30abead9e70dd73845b51a6d017a789235390405a2c51c8620807413560b8ab7";
    private static final List<String> BANDS = List.of("AEN", "XEN", "SEN", "ACEN");

    private static String frozenWf4() {
        try (InputStream in = Sg35BillBandRegressionH2Test.class.getClassLoader().getResourceAsStream(WF4)) {
            assertTrue(in != null, "fixture " + WF4 + " on the test class path");
            byte[] b = in.readAllBytes();
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
            assertEquals(WF4_SHA256, sha, "fixture must be byte-identical to the frozen kit copy");
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** X-13: the band gateway with no default flow (no band above 50,00,000). */
    private static String withoutDefault() {
        String m = frozenWf4().replace("id=\"E46-WF4\"", "id=\"SG35-BILL\"");
        String noDefault = m.replace(" default=\"fDefault\"", "")
                .replace("<sequenceFlow id=\"fDefault\" sourceRef=\"gwBand\" targetRef=\"ACEN\"/>", "");
        assertFalse(noDefault.contains("fDefault"), "default flow removed");
        return noDefault;
    }

    /** Overlap: the lower bound of the XEN band made inclusive, so at 1,00,000 two flows are true. */
    private static String overlapping() {
        String m = frozenWf4().replace("id=\"E46-WF4\"", "id=\"SG35-BILL-OVERLAP\"");
        String o = m.replace("${amount &gt; 100000 &amp;&amp; amount &lt;= 1000000}",
                "${amount &gt;= 100000 &amp;&amp; amount &lt;= 1000000}");
        assertTrue(o.contains("amount &gt;= 100000"), "overlap planted");
        return o;
    }

    private static void withEngine(Consumer<OperatonEngineAdapter> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sg35" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true"); // empty in-memory database owned by this test
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            test.accept(a);
        }
    }

    private static String startAtJenAndSubmit(OperatonEngineAdapter a, String key, String bk, long amount) {
        ProcessEngine e = a.processEngine();
        String pi = a.startProcess("t1", key, bk, Map.of("amount", amount));
        Task jen = e.getTaskService().createTaskQuery().processInstanceId(pi).taskDefinitionKey("JEN").singleResult();
        e.getTaskService().complete(jen.getId());
        return pi;
    }

    private static List<String> routingAudit(OperatonEngineAdapter a, String root) {
        return a.inEngineTransaction(c -> Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT ev JOIN AUDIT_PAYLOAD p"
                + " ON p.event_id = ev.event_id WHERE ev.event_type = 'ROUTING_INCIDENT' AND ev.chain_id = ?",
                rs -> rs.getString(1), root));
    }

    private static void assertStoppedAtGwBandAudited(OperatonEngineAdapter a, String pi, String code) {
        ProcessEngine e = a.processEngine();
        List<Incident> inc = e.getRuntimeService().createIncidentQuery().processInstanceId(pi)
                .incidentType("sangamRouting").list();
        assertEquals(1, inc.size(), "one routing incident");
        assertTrue(inc.get(0).getIncidentMessage().startsWith(code), inc.get(0).getIncidentMessage());
        assertEquals("gwBand", inc.get(0).getActivityId());
        List<String> audit = routingAudit(a, pi);
        assertEquals(1, audit.size(), "exactly one audited routing incident (SG-35): " + audit);
        assertTrue(audit.get(0).contains(code) && audit.get(0).contains("gwBand")
                && audit.get(0).contains(inc.get(0).getId()), audit.get(0));
        assertEquals(List.of(), e.getTaskService().createTaskQuery().processInstanceId(pi).list().stream()
                .map(Task::getTaskDefinitionKey).toList(), "no band task opened");
        assertTrue(e.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(pi).list().stream()
                .noneMatch(h -> BANDS.contains(h.getActivityId())), "no band taken");
        assertFalse(a.getInstance(pi).ended(), "parked at gwBand with an open incident, not ended");
    }

    @Test
    void x13AmountAboveEveryBandIsAnAuditedNoMatchingRouteIncident() {
        withEngine(a -> {
            a.deploy("t1", "sg35-bill", Map.of("sg35-bill.bpmn", withoutDefault().getBytes(StandardCharsets.UTF_8)));
            // in-band amounts still route to exactly one band, with no routing audit
            for (long amount : new long[] {100_000L, 100_001L, 5_000_000L}) {
                String pi = startAtJenAndSubmit(a, "SG35-BILL", "SG35-OK-" + amount, amount);
                assertEquals(1, a.processEngine().getTaskService().createTaskQuery().processInstanceId(pi).count());
                assertEquals(List.of(), routingAudit(a, pi), "no routing incident for an in-band amount");
            }
            String pi = startAtJenAndSubmit(a, "SG35-BILL", "SG35-X13", 6_000_000L);
            assertStoppedAtGwBandAudited(a, pi, "NO_MATCHING_ROUTE");
        });
    }

    @Test
    void overlappingBandsAreAnAuditedAmbiguousRouteIncident() {
        withEngine(a -> {
            a.deploy("t1", "sg35-bill-o", Map.of("sg35-bill-o.bpmn", overlapping().getBytes(StandardCharsets.UTF_8)));
            String pi = startAtJenAndSubmit(a, "SG35-BILL-OVERLAP", "SG35-AMB", 100_000L);
            assertStoppedAtGwBandAudited(a, pi, "AMBIGUOUS_ROUTE");
        });
    }
}
