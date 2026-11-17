// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.model.bpmn.Bpmn;

import org.sangam.packs.port.PackRuntime;

/**
 * GP-47 / GP-28 (integrator decision 2026-11-12): the acceptance driver completes external tasks through the external
 * task service with the pack workers (simulator connectors), standing in for the worker process. A topic with no
 * worker stops the run loudly; a driver without workers leaves external tasks alone (the other tests).
 */
class PackDriverWorkersTest {

    static ProcessEngine engine;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-driver-workers;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().tenantId(PackTestTenant.ID)
                .addModelInstance("ext-known.bpmn", Bpmn.createExecutableProcess("ext-known").startEvent()
                        .serviceTask("card").operatonExternalTask("sangam.docs.bulk").userTask("after").endEvent()
                        .done())
                .addModelInstance("ext-unknown.bpmn", Bpmn.createExecutableProcess("ext-unknown").startEvent()
                        .serviceTask("odd").operatonExternalTask("no.such.topic").endEvent().done())
                .deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        // R8-heap2: a test class stays loaded for the whole run; its statics must not keep a closed engine
        engine = null;
        PackRuntime.reset();
    }

    @Test
    void aKnownTopicIsHandledByItsWorkerAndTheCaseMovesOn() {
        engine.getRuntimeService().startProcessInstanceByKey("ext-known", "EXT/1");
        PackDriver d = new PackDriver(engine).withWorkers();
        assertEquals(List.of("after"), d.run("EXT/1", t -> PackDriver.Answer.of("p", "S"), 10));
        assertTrue(d.ended("EXT/1"));
    }

    @Test
    void anUnknownTopicStopsTheRunLoudly() {
        engine.getRuntimeService().startProcessInstanceByKey("ext-unknown", "EXT/2");
        PackDriver d = new PackDriver(engine).withWorkers();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> d.run("EXT/2", t -> null, 10));
        assertTrue(e.getMessage().contains("no.such.topic"), e.getMessage());
    }

    @Test
    void withoutWorkersExternalTasksWait() {
        engine.getRuntimeService().startProcessInstanceByKey("ext-known", "EXT/3");
        PackDriver d = new PackDriver(engine);
        assertEquals(List.of(), d.run("EXT/3", t -> PackDriver.Answer.of("p", "S"), 10));
        assertFalse(d.ended("EXT/3"));
        assertEquals(1, engine.getExternalTaskService().createExternalTaskQuery().processInstanceId(
                engine.getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKey("EXT/3")
                        .singleResult().getId()).count());
    }
}
