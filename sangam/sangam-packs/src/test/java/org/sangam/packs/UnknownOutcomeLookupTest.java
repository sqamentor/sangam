// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * SL-118: in core execution a lost response (UNKNOWN) of a connector whose outcome can be looked up (class a / b)
 * takes the PENDING path: it waits for the result message or the acknowledgement window, then looks the outcome up
 * (capped by maxLookups); an outcome still unknown opens the reconciliation item. Class (c) is never looked up: the
 * reconciliation item opens at once, as before (AC-03, CORE-3).
 */
class UnknownOutcomeLookupTest {

    static ProcessEngine engine;
    static PackDriver driver;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-unknown-lookup;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(null);
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    @BeforeEach
    void ports() {
        driver.resetClock();
        PackRuntime.install(PackRuntime.ports().withGateway(new SimulatorConnectorGateway())
                .withAudit(new LoggingAuditSink()).withClock(PackDriver.clock()));
    }

    @AfterEach
    void clock() {
        driver.resetClock();
    }

    private static void execute(String bk, String connector, String directive) {
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.putAll(Map.of("executionConnector", connector, "executionOperation", "execute", "executionStep", "fee",
                "verifyApproval", false, "reconcileRequired", false, "ackWindow", "PT30M",
                "executionPayloadFields", "fee,applicantRef", "fee", 1000L));
        v.put(org.sangam.packs.core.Vars.simVar(connector), directive);
        engine.getRuntimeService().createProcessInstanceByKey("sangam-core-execution").businessKey(bk).setVariables(v)
                .processDefinitionWithoutTenantId().execute();
        driver.run(bk, t -> null, 20);
    }

    private static List<String> audit(String bk) {
        return ((LoggingAuditSink) PackRuntime.audit()).timeline(bk).stream().map(e -> e.type()).toList();
    }

    private static boolean waitingAt(String bk, String activity) {
        return engine.getRuntimeService().createExecutionQuery().processInstanceBusinessKey(bk).activityId(activity)
                .count() > 0;
    }

    @Test
    void aLostResponseOfALookupConnectorIsConfirmedByTheLookupAfterTheWindow() {
        execute("SL118/1", "paygov.fee", "UNKNOWN_THEN_COMPLETED");
        assertTrue(waitingAt("SL118/1", "ex_wait"), "waits for the result or the window, like PENDING");
        assertFalse(audit("SL118/1").contains("RECON_ITEM_OPENED"), "no reconciliation item yet");
        driver.advance(Duration.ofMinutes(31));
        driver.run("SL118/1", t -> null, 20);
        assertTrue(driver.ended("SL118/1"));
        assertEquals("DONE", driver.var("SL118/1", "executionOutcome"));
        assertEquals(Boolean.TRUE, driver.var("SL118/1", "op_lookedUp"), "confirmed by the status lookup");
        assertFalse(audit("SL118/1").contains("RECON_ITEM_OPENED"));
    }

    @Test
    void anOutcomeStillUnknownAfterTheLookupOpensTheReconciliationItem() {
        execute("SL118/2", "paygov.fee", "UNKNOWN");
        driver.advance(Duration.ofMinutes(31));
        driver.run("SL118/2", t -> null, 20);
        assertTrue(audit("SL118/2").contains("RECON_ITEM_OPENED"));
        assertTrue(waitingAt("SL118/2", "ex_resolved"), "the operator resolves it, as before");
        assertEquals(1, ((Number) driver.var("SL118/2", "opLookups")).intValue(), "one lookup, no loop");
    }

    @Test
    void classCIsNeverLookedUpAndOpensTheItemAtOnce() {
        execute("SL118/3", "pfms.payment", "UNKNOWN_THEN_COMPLETED");
        assertTrue(audit("SL118/3").contains("RECON_ITEM_OPENED"), "unchanged for class (c) (VI.3)");
        assertTrue(waitingAt("SL118/3", "ex_resolved"));
        assertFalse(waitingAt("SL118/3", "ex_wait"));
    }
}
