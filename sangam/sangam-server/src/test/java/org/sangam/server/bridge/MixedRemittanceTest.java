// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.Task;
import org.sangam.connectors.health.x12.X12;
import org.sangam.connectors.health.x12.X12Builders;
import org.sangam.connectors.health.x12.X12Connector;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.layer.Dialect;
import org.sangam.integration.inbox.InboxHandler;
import org.sangam.integration.inbox.UniqueStartGuard;
import org.sangam.ledger.LedgerConnector;
import org.sangam.ledger.LedgerRequest;
import org.sangam.ledger.LedgerService;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * A mixed 835 (one trace, claims A, B and C) reaches several processes. Each posts only its own claim, keyed on trace +
 * patient control number, so every leg posts exactly once whichever process handles it; C, with no receivable, is a
 * work-queue exception only where C is handled.
 */
class MixedRemittanceTest {

    static String mixed835() {
        List<X12.Segment> b = new ArrayList<>();
        b.add(X12.Segment.of("BPR", "I", "150", "C", "ACH", "CCP", "01", "999999999", "DA", "123456", "1999999999", "", "01",
                "888888888", "DA", "654321", "20261101"));
        b.add(X12.Segment.of("TRN", "1", "T1", "1999999999"));
        b.add(X12.Segment.of("N1", "PR", "SIM PAYER"));
        b.add(X12.Segment.of("N1", "PE", "SIM PROVIDER", "XX", "1234567893"));
        b.add(X12.Segment.of("LX", "1"));
        b.add(X12.Segment.of("CLP", "A", "1", "100", "70", "10", "12", "PA"));
        b.add(X12.Segment.of("CAS", "CO", "45", "20"));
        b.add(X12.Segment.of("CAS", "PR", "1", "10"));
        b.add(X12.Segment.of("CLP", "B", "1", "50", "50", "", "12", "PB"));
        b.add(X12.Segment.of("CLP", "C", "1", "30", "30", "", "12", "PC"));
        return X12.write(new X12.Envelope("ZZ", "SIMPAYER", "ZZ", "SANGAM", null, null, 835, false, null, false),
                "HP", "835", X12Builders.V835, List.of(b), null);
    }

    static ConnectorRequest post(String x12, String pcn) throws Exception {
        Map<String, Object> era = X12Connector.parseFocused(x12, "T1", pcn);
        Map<String, Object> in = new LinkedHashMap<>(Map.of("ledgerId", "RECEIVABLES", "line", "CLAIM", "period", "OPEN",
                "eraTraceNumber", "T1", "eraClaims", new ObjectMapper().writeValueAsString(era.get("claims"))));
        if (pcn != null) {
            in.put("patientControlNumber", pcn);
        }
        return new ConnectorRequest("T", UUID.randomUUID(), "a1", 1, "root", "BK-" + pcn, "post", in, null);
    }

    @Test
    void eachClaimPostsOnceWhicheverProcessHandlesIt() throws Exception {
        LedgerService s = RemittanceAndSeedsTest.ledger("mixed1");
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", RemittanceAndSeedsTest.claim("A"), new BigDecimal("100"), "a")).applied());
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", RemittanceAndSeedsTest.claim("B"), new BigDecimal("50"), "b")).applied());
        var posting = new PackGapConnectors.RemittancePosting(new LedgerConnector(s, (t, d) -> "OPEN", Clock.systemUTC()));
        String x12 = mixed835();
        assertEquals("PAID_PRIMARY", X12Connector.parseFocused(x12, "T1", "B").get("claimStatus"));
        assertEquals("NOT_IN_FILE", X12Connector.parseFocused(x12, "T1", "Z").get("claimStatus"));

        var a = (ConnectorResult.Success) posting.invoke(post(x12, "A"));   // claim case of A
        var b = (ConnectorResult.Success) posting.invoke(post(x12, "B"));   // later-remittance case of B
        var c = (ConnectorResult.Success) posting.invoke(post(x12, "C"));   // later-remittance case of C: no receivable
        assertEquals(0, a.output().get("exceptionCount"), String.valueOf(a.output()));
        assertEquals(0, b.output().get("exceptionCount"));
        assertEquals(1, c.output().get("exceptionCount"), "only C is an exception, only in C's process");
        var whole = (ConnectorResult.Success) posting.invoke(post(x12, null));   // the whole file again, unscoped
        assertEquals(1, whole.output().get("exceptionCount"), String.valueOf(whole.output()));
        var missing = (ConnectorResult.Success) posting.invoke(post(x12, "Z"));
        assertEquals("CLAIM_NOT_IN_FILE", ((List<Map<String, Object>>) missing.output().get("exceptions")).get(0).get("code"));

        assertEquals(0, s.available(RemittanceAndSeedsTest.claim("A")).signum(), "A posted once");
        assertEquals(0, new BigDecimal("10").compareTo(s.available(RemittanceAndSeedsTest.claim("patient:A"))));
        assertEquals(0, s.available(RemittanceAndSeedsTest.claim("B")).signum(), "B posted once");
    }

    @Test
    void routerSendsEachClaimToItsOwnProcess() throws Exception {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:mixed-engine;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        ProcessEngine engine = cfg.buildProcessEngine();
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:mixed-guard;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")) {
            c.createStatement().execute("CREATE TABLE UNIQUE_START (tenant_id VARCHAR(64) NOT NULL, definition_key VARCHAR(255)"
                    + " NOT NULL, business_key VARCHAR(255) NOT NULL, instance_id VARCHAR(64), source VARCHAR(64), message_id"
                    + " VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (tenant_id, definition_key, business_key))");
            new PackDeployer(engine, PackRegistry.discover()).deploy(List.of("sec.ushealth.claim"), "T");
            engine.getRuntimeService().createProcessInstanceByKey("sangam-sec-ushealth-claim").businessKey("CASE-A")
                    .processDefinitionTenantId("T").setVariables(Map.of("customerId", "PA", "patientControlNumber", "A",
                            "serviceDate", "2026-11-01", "statusFollowUp", "P30D")).execute();
            for (int i = 0; i < 20; i++) {
                engine.getManagementService().createJobQuery().executable().list().stream()
                        .filter(j -> j.getDuedate() == null).forEach(j -> engine.getManagementService().executeJob(j.getId()));
                for (Task t : engine.getTaskService().createTaskQuery().taskDefinitionKey("cl_scrub").list()) {
                    engine.getTaskService().complete(t.getId(), Map.of("codingNotes", "ok"));
                }
            }
            InboxHandler h = Remittance835Router.handler(engine, new UniqueStartGuard(Dialect.POSTGRESQL));
            String x12 = mixed835();
            for (String pcn : List.of("A", "B", "C", "B")) {    // B delivered twice
                String data = new ObjectMapper().writeValueAsString(Map.of("x12", x12, "eraTraceNumber", "T1",
                        "patientControlNumber", pcn));
                h.handle(c, new InboxHandler.Envelope("T", "x12.835", "T1:" + pcn, Remittance835Router.EVENT_TYPE, pcn, null, data));
            }
            Set<String> started = engine.getHistoryService().createHistoricProcessInstanceQuery()
                    .processDefinitionKey(Remittance835Router.REMITTANCE_PROCESS).list().stream()
                    .map(p -> p.getBusinessKey()).collect(Collectors.toSet());
            assertEquals(Set.of("T1:B", "T1:C"), started, "one later-remittance case per unmatched claim");
            assertEquals("T1", engine.getRuntimeService().createVariableInstanceQuery().variableName("eraTraceNumber").list()
                    .stream().filter(v -> v.getProcessInstanceId().equals(engine.getRuntimeService().createProcessInstanceQuery()
                            .processInstanceBusinessKey("CASE-A").singleResult().getId())).findFirst().orElseThrow().getValue(),
                    "the waiting claim case received its claim");
        } finally {
            engine.close();
        }
    }
}
