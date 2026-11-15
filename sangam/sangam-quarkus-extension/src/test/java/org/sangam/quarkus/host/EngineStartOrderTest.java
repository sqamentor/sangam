// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.jobexecutor.JobExecutor;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.quarkus.engine.extension.QuarkusProcessEngineConfiguration;
import org.sangam.assembly.engine.EngineStartOrder;
import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.CapabilityMissingException;
import org.sangam.assembly.host.CapabilitySet;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.assembly.host.SangamRuntime;
import org.sangam.engine.SangamEngineConfigurer;

/**
 * Owner decision 376 (G7 phase 3 blocker 5) on a real engine on Narayana and Agroal: the engine is built with the job
 * executor off although its configuration asks for it, a due job does not run while the host's start is refused, and
 * the job executor starts as the runtime's last component only after the capability check passed.
 */
class EngineStartOrderTest {

    @TempDir
    Path objectStore;

    private JtaFixture jta;
    private ProcessEngine engine;

    @BeforeEach
    void start() throws SQLException {
        jta = JtaFixture.start(objectStore);
    }

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.close();
        }
        jta.close();
    }

    private QuarkusHost host() {
        return new QuarkusHost(SangamProperties.of(Map.of()), jta.dataSource, jta.transactionManager,
                new BoundPrincipal());
    }

    private ProcessEngine engine(String name, EngineStartOrder startOrder) {
        QuarkusProcessEngineConfiguration config = new QuarkusProcessEngineConfiguration();
        config.setProcessEngineName(name);
        config.setDataSource(jta.dataSource);
        config.setTransactionManager(jta.transactionManager);
        config.setDatabaseSchemaUpdate(ProcessEngineConfigurationImpl.DB_SCHEMA_UPDATE_CREATE_DROP);
        config.setJobExecutorActivate(true); // what an operator's configuration asks for
        List<ProcessEnginePlugin> plugins = new ArrayList<>(config.getProcessEnginePlugins());
        plugins.add(startOrder);
        config.setProcessEnginePlugins(plugins);
        engine = config.buildProcessEngine();
        return engine;
    }

    private static JobExecutor jobExecutor(ProcessEngine engine) {
        return ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getJobExecutor();
    }

    /** One process instance waiting on an async job, due now. */
    private static void dueJob(ProcessEngine engine) {
        engine.getRepositoryService().createDeployment().name("async")
                .addModelInstance("async.bpmn", Bpmn.createExecutableProcess("async").startEvent()
                        .operatonAsyncAfter().endEvent().done())
                .deploy();
        engine.getRuntimeService().startProcessInstanceByKey("async");
    }

    private static long jobs(ProcessEngine engine) {
        return engine.getManagementService().createJobQuery().count();
    }

    @Test
    void noJobRunsBeforeTheCapabilityCheckAndTheJobExecutorStartsLastOnlyAfterItPassed() throws InterruptedException {
        QuarkusHost host = host();
        ProcessEngine e = engine("start-order", host.startOrder());
        assertFalse(jobExecutor(e).isActive(), "the engine is built with the job executor off");
        assertEquals(Boolean.TRUE, host.startOrder().wanted(), "the configuration's wish is kept for the start");
        dueJob(e);
        assertEquals(1, jobs(e));

        // the host's start event: this host lacks required capabilities, so the start is refused by name
        CapabilityMissingException refused = assertThrows(CapabilityMissingException.class, host.runtime()::start);
        assertTrue(refused.missing().contains(Capabilities.AUDIT_CHAIN), refused.getMessage());
        assertEquals(EngineStartOrder.NAME, host.runtime().startOrder().get(host.runtime().startOrder().size() - 1),
                "the job executor is the runtime's last component");
        Thread.sleep(1500); // longer than the job executor's first acquisition would take
        assertFalse(jobExecutor(e).isActive(), "a refused start never starts the job executor");
        assertEquals(1, jobs(e), "no job ran before (or without) a passing capability check");

        // a start whose capability check passes starts the job executor, last
        SangamRuntime passing = SangamRuntime.of(CapabilitySet.resolve(List.of(), List.of()),
                List.of(host.startOrder().lifecycle()), List.of());
        passing.start();
        assertTrue(jobExecutor(e).isActive());
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (jobs(e) > 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(0, jobs(e), "the due job ran once the job executor started");
        passing.stop(Duration.ZERO);
        assertFalse(jobExecutor(e).isActive(), "stopped with the runtime");
    }

    @Test
    void theEngineBuildReportsItselfToTheAfterBuildClaims() {
        QuarkusHost host = host();
        assertFalse(host.engineClaims().built());
        engine("claims", host.startOrder());
        assertTrue(host.engineClaims().built(), "the after-build services are assembled once the engine is built");
    }

    @Test
    void aJobExecutorOffByConfigurationIsNotStartedAtAll() {
        QuarkusHost host = host();
        QuarkusProcessEngineConfiguration config = new QuarkusProcessEngineConfiguration();
        config.setProcessEngineName("off");
        config.setDataSource(jta.dataSource);
        config.setTransactionManager(jta.transactionManager);
        config.setDatabaseSchemaUpdate(ProcessEngineConfigurationImpl.DB_SCHEMA_UPDATE_CREATE_DROP);
        config.setJobExecutorActivate(false);
        List<ProcessEnginePlugin> plugins = new ArrayList<>(config.getProcessEnginePlugins());
        plugins.add(host.startOrder());
        config.setProcessEnginePlugins(plugins);
        engine = config.buildProcessEngine();
        assertEquals(Boolean.FALSE, host.startOrder().wanted());
        SangamRuntime passing = SangamRuntime.of(CapabilitySet.resolve(List.of(), List.of()),
                List.of(host.startOrder().lifecycle()), List.of());
        passing.start();
        assertFalse(jobExecutor(engine).isActive());
        passing.stop(Duration.ZERO);
    }

    @Test
    void theHostsEngineConfigurationCarriesTheStartOrderBeforeTheSettingsReapply() {
        QuarkusHost host = host();
        QuarkusProcessEngineConfiguration cfg = new QuarkusProcessEngineConfiguration();
        SangamEngineConfigurer configurer = SangamEngineConfiguration.apply(cfg, host, new EngineCapabilityGuard(host),
                new SangamEngineConfiguration.AfterBuild(jta.dataSource));
        try {
            List<ProcessEnginePlugin> plugins = cfg.getProcessEnginePlugins();
            int at = plugins.indexOf(host.startOrder());
            assertTrue(at >= 0, "the start order is registered: " + plugins);
            assertSame(host.startOrder(), plugins.get(plugins.size() - 2), "right before the settings re-apply");
            assertTrue(plugins.stream().filter(p -> p instanceof SangamEngineConfiguration.AfterBuild)
                    .allMatch(p -> plugins.indexOf(p) < at), "after the engine adapter step");
        } finally {
            configurer.releaseEngineSlot();
        }
    }
}
