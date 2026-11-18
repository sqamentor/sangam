// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.Task;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * Integrator ruling under M1-M5 / DEC-5 (no invented amounts), not an owner decision: there is no premium rating
 * source, so a proposal reaching the premium collection without an entered premiumAmount above zero (the
 * straight-through "Standard terms" path) is never captured with a pack default; a person enters the premium first.
 * Before the fix the STP path collected premiumAmount=0 (the pack default).
 */
class ProposalPremiumTest {

    static ProcessEngine engine;
    static PackDriver driver;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-proposal-premium;DB_CLOSE_DELAY=-1");
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
        engine = null;
        driver = null;
        PackRuntime.reset();
    }

    @Test
    void aProposalWithoutAnEnteredPremiumIsNotCapturedUntilAPersonEntersIt() {
        SimulatorConnectorGateway gateway = new SimulatorConnectorGateway();
        driver.resetClock();
        PackRuntime.install(PackRuntime.ports().withGateway(gateway).withAudit(new LoggingAuditSink())
                .withClock(PackDriver.clock()));
        String bk = "PREM/1";
        Map<String, Object> v = AllPacksSmokeTest.bag();
        v.remove("premiumAmount");
        engine.getRuntimeService().createProcessInstanceByKey("sangam-sec-ins-proposal").businessKey(bk)
                .setVariables(v).processDefinitionTenantId(PackTestTenant.ID).execute();
        try {
            driver.run(bk, t -> null, 30);
        } catch (RuntimeException e) {
            System.err.println(bk + ": driver stopped: " + e);
        }
        assertTrue(gateway.journal().stream().noneMatch(r -> "pg.collect".equals(r.connector())),
                "no capture without an entered premium");
        List<Task> open = engine.getTaskService().createTaskQuery().processInstanceBusinessKey(bk).list();
        assertEquals(List.of("ip_premiumEnter"), open.stream().map(Task::getTaskDefinitionKey).toList(),
                "a person enters the premium");

        driver.run(bk, t -> "ip_premiumEnter".equals(t.getTaskDefinitionKey())
                ? PackDriver.Answer.of("uw.officer", "UNDERWRITER", "premiumAmount", 1_250_000L) : null, 30);
        assertEquals(1, gateway.journal().stream().filter(r -> "pg.collect".equals(r.connector())).count(),
                "collected once, with the entered premium");
        assertEquals(1_250_000L, ((Number) driver.var(bk, "premiumAmount")).longValue());
    }
}
