// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;

import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.VerifiedPayeeLookup;
import org.sangam.packs.port.VerifiedPayees;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * SANGAM-PAYMENT-LOOP (IFMS E-31 §6) and SANGAM-PAYEE-VERIFIED v2 (JOINT-LEDGER-SANGAM-IFMS). Before the fix a refused
 * bill payment was reissued automatically for ever (bl_payFailed, bl_reissue, bl_pay), a bill whose payment outcome
 * was unknown could be withdrawn (the encumbrance released while the money may have left), the NOT_FOUND lookup of
 * core execution resubmitted without a cap, and the payee's bank details were never taken from a verified record.
 */
class PaymentLoopTest {

    static final String BILL = "sangam-ifms-bill";
    static ProcessEngine engine;
    static PackDriver driver;
    SimulatorConnectorGateway gateway;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-payment-loop;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    @AfterEach
    void reset() {
        VerifiedPayees.install(null);
        driver.resetClock();
    }

    private void install(SimulatorConnectorGateway g) {
        gateway = g;
        driver.resetClock();
        PackRuntime.install(PackRuntime.ports().withGateway(g).withAudit(new LoggingAuditSink())
                .withClock(PackDriver.clock()));
    }

    private static final List<String> STOP = List.of("bl_payCorrect", "bl_payeeHold");

    private String bill(String bk, String directive) {
        install(new SimulatorConnectorGateway());
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.put("amount", 1_500_000L);
        if (directive != null) {
            v.put("sim_pfms_payment", directive);
        }
        ProcessInstance pi = engine.getRuntimeService().createProcessInstanceByKey(BILL).businessKey(bk)
                .setVariables(v).processDefinitionTenantId(PackTestTenant.ID).execute();
        FormAnswers answers = new FormAnswers(engine);
        try {
            driver.run(bk, t -> STOP.contains(t.getTaskDefinitionKey()) ? null : answers.answer(t), 150);
        } catch (RuntimeException e) {
            // before the fix the reissue loop runs until the step limit
        }
        return pi.getId();
    }

    private long payments() {
        return gateway.journal().stream().filter(r -> "pfms.payment".equals(r.connector())).count();
    }

    private List<String> open(String bk) {
        return engine.getTaskService().createTaskQuery().processInstanceBusinessKey(bk).list().stream()
                .map(Task::getTaskDefinitionKey).toList();
    }

    @Test
    void aRefusedPaymentStopsAtAPersonAndIsNeverReissuedAutomatically() {
        bill("PL/1", "FAILED:ACCOUNT_CLOSED");
        assertEquals(1, payments(), "one payment request, no automatic reissue");
        assertEquals(List.of("bl_payCorrect"), open("PL/1"), "open tasks");
        assertEquals("ACCOUNT_CLOSED", driver.var("PL/1", "opReasonCode"));
    }

    @Test
    void anUnknownOutcomeBlocksEveryActionUntilTheEvidenceArrives() {
        String pi = bill("PL/2", "UNKNOWN");
        assertEquals(1, payments());
        assertEquals(List.of(), open("PL/2"), "no task while the outcome is unknown");
        assertEquals(1, engine.getRuntimeService().createExecutionQuery().processInstanceBusinessKey("PL/2")
                .activityId("ex_resolved").count(), "waits for the reconciliation evidence");
        assertThrows(RuntimeException.class, () -> engine.getRuntimeService()
                .createMessageCorrelation("sangam.withdrawCase").processInstanceId(pi).correlate(),
                "withdrawal refused while the outcome is unknown");
        assertFalse(driver.ended("PL/2"));
        assertEquals(1, payments(), "nothing resubmitted");

        engine.getRuntimeService().createMessageCorrelation("sangam.reconciliationResolved")
                .processInstanceBusinessKey("PL/2").setVariable("resolution", "CONFIRMED_FAILURE")
                .setVariable("resolutionReasonCode", "PAYMENT_RETURNED").correlate();
        driver.run("PL/2", t -> null, 40);
        assertEquals(List.of("bl_payCorrect"), open("PL/2"), "confirmed failure goes to a person");
        assertEquals(1, payments(), "still one payment request");
    }

    /** Submit answers PENDING, every lookup NOT_FOUND: before the fix the resubmission never ended. */
    static class NotFoundGateway extends SimulatorConnectorGateway {
        final java.util.concurrent.atomic.AtomicInteger submits = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public ConnectorResult submit(ConnectorRequest r) {
            submits.incrementAndGet();
            return new ConnectorResult(OperationStatus.PENDING, null, "accepted", null, Map.of());
        }

        @Override
        public ConnectorResult lookup(String tenantId, String connector, String operationId) {
            return ConnectorResult.failed("NOT_FOUND", "never reached the external system");
        }
    }

    @Test
    void technicalRetriesAreBoundedByCount() {
        NotFoundGateway g = new NotFoundGateway();
        install(g);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.putAll(Map.of("executionConnector", "docs.render", "executionOperation", "execute", "executionStep", "doc",
                "verifyApproval", false, "reconcileRequired", false, "ackWindow", "PT1S", "maxLookups", 3,
                "executionPayloadFields", "applicantRef"));
        engine.getRuntimeService().createProcessInstanceByKey("sangam-core-execution").businessKey("PL/3")
                .setVariables(v).processDefinitionTenantId(PackTestTenant.ID).execute();
        try {
            driver.run("PL/3", t -> null, 60);
        } catch (RuntimeException e) {
            // before the fix: the loop runs until the step limit
        }
        int submits = g.submits.get();
        assertTrue(submits <= 3, "submits bounded by maxLookups: " + submits);
        assertEquals(1, engine.getRuntimeService().createExecutionQuery().processInstanceBusinessKey("PL/3")
                .activityId("ex_resolved").count(), "the reconciliation item is open for a person");
    }

    @Test
    void anUnverifiedPayeeStopsAtAPersonAndNothingIsPaid() {
        VerifiedPayees.install((t, e, p, d) -> new VerifiedPayeeLookup.NotVerified(
                VerifiedPayeeLookup.Reason.PAYEE_NOT_VERIFIED, "test"));
        bill("PL/4", null);
        assertEquals(0, payments());
        assertEquals(List.of("bl_payeeHold"), open("PL/4"));
        assertEquals("PAYEE_NOT_VERIFIED", driver.var("PL/4", "payeeNotVerifiedReason"));
    }

    @Test
    void theVerifiedPayeeVersionIsRecordedAndPaid() {
        VerifiedPayees.install((t, e, p, d) -> new VerifiedPayeeLookup.Verified("PV-7", "A Payee", "123456789012",
                "SBIN0000001"));
        bill("PL/5", null);
        assertTrue(driver.ended("PL/5"), "paid and closed: " + open("PL/5"));
        assertEquals("PV-7", driver.var("PL/5", "payeeVersionId"));
        ConnectorRequest pay = gateway.journal().stream().filter(r -> "pfms.payment".equals(r.connector()))
                .findFirst().orElseThrow();
        assertEquals("123456789012", pay.payload().get("accountNo"));
        assertEquals("SBIN0000001", pay.payload().get("ifsc"));
        assertEquals("15000", pay.payload().get("amount"));
        assertEquals("PV-7", pay.payload().get("payeeVersionId"));
    }
}
