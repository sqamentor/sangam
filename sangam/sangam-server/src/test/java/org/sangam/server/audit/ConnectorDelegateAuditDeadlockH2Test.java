// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.bridge.operaton.IntegrationBridge;
import org.sangam.integration.connector.ConnectorRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * SL-133 (M-003) regression on the real engine (h2 profile, H2 LOCK_TIMEOUT 10 s): an {@code asyncBefore} service task
 * with {@code SangamConnectorDelegate}. The job's transaction holds the root's {@code ROOT_SEQ} lock (job guard); the
 * delegate's invoker writes the ledger on another connection. Before the fix its audit append waited for that lock
 * until the 10 s timeout and the job failed into an incident. Now the job completes in well under 10 s, and the
 * dispatch and outcome events reach the root's chain through the pending-audit drain, which verifies.
 *
 * <p>Needs the W2-A wiring of SL-133 (the bridge's delegate invoker is {@code invoker.deferringAudit(drainer)}).
 */
@SpringBootTest(properties = {"sangam.engine.allow-multiple-engines-per-jvm=true",
        // the test runs the job itself, on a thread it can time out
        "operaton.bpm.job-execution.enabled=false"})
@ActiveProfiles("h2")
@DirtiesContext
class ConnectorDelegateAuditDeadlockH2Test {

    static final String TENANT = "dev";
    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="sl133">
              <process id="sl133delegate" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                <serviceTask id="call" operaton:asyncBefore="true"
                             operaton:class="org.sangam.integration.bridge.operaton.SangamConnectorDelegate">
                  <extensionElements>
                    <operaton:field name="connector" stringValue="sl133-echo"/>
                    <operaton:field name="operation" stringValue="send"/>
                    <operaton:field name="resultVariable" stringValue="echoResult"/>
                  </extensionElements>
                </serviceTask>
                <sequenceFlow id="f2" sourceRef="call" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    static final class Echo implements SangamConnector {
        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("sl133-echo", "test", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, List.of("send"),
                    ConnectorProfile.LIVE);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            return ConnectorResult.Success.of(Map.of("sent", true), "echo-" + request.operationId());
        }
    }

    @Autowired
    ProcessEngine engine;

    @Autowired
    DataSource dataSource;

    @Autowired
    ConnectorRegistry registry;

    @Test
    void anAsyncDelegateConnectorCallCompletesWithoutTheSelfDeadlockAndItsAuditVerifies() throws Exception {
        assertTrue(IntegrationBridge.get().delegateInvoker().defersAudit(),
                "the bridge's delegate invoker defers audit (SL-133 wiring)");
        registry.register(TENANT, new Echo());
        engine.getRepositoryService().createDeployment().tenantId(TENANT).name("sl133")
                .addString("sl133.bpmn", BPMN).deploy();
        ProcessInstance pi = engine.getRuntimeService().createProcessInstanceByKey("sl133delegate")
                .processDefinitionTenantId(TENANT).execute();
        Job job = engine.getManagementService().createJobQuery().processInstanceId(pi.getId()).singleResult();

        long t0 = System.nanoTime();
        CompletableFuture.runAsync(() -> engine.getManagementService().executeJob(job.getId()))
                .get(9, TimeUnit.SECONDS);   // before the fix: the 10 s lock timeout, then a failed job
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(ms < 9_000, "job took " + ms + " ms");
        assertEquals(0, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pi.getId()).count(),
                "the process ran to its end");
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().processInstanceId(pi.getId()).count());

        Transactions tx = Transactions.of(dataSource);
        String root = pi.getId();   // a top-level instance is its own root
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9);
        while (pending(tx, root) > 0 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertEquals(0, pending(tx, root), "the drain appended every pending event");
        List<String> types = tx.inTransaction(c -> Jdbc.query(c,
                "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? ORDER BY seq", rs -> rs.getString(1),
                TENANT, root));
        assertTrue(types.contains("OPERATION_DISPATCHED") && types.contains("OPERATION_OUTCOME"), "chain: " + types);
        assertTrue(types.indexOf("OPERATION_DISPATCHED") < types.indexOf("OPERATION_OUTCOME"), "chain: " + types);
        AuditChain chain = new AuditChain(Dialect.POSTGRESQL);
        AuditChain.VerifyResult v = tx.inTransaction(c -> chain.verify(c, TENANT, root));
        assertTrue(v.ok(), "chain verifies: " + v);
    }

    private static long pending(Transactions tx, String root) {
        return tx.inTransaction((Connection c) -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_PENDING WHERE tenant_id=? AND chain_id=?",
                rs -> rs.getLong(1), TENANT, root).orElse(0L));
    }
}
