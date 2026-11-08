// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.ModelGuard;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * Fast in-memory check (H2, no Docker): every pack on the class path passes the deploy-time guards and deploys to
 * Operaton 2.2.0 with history TTL enforcement on, and every call activity resolves to a deployed process.
 */
class PackDeploymentTest {

    static ProcessEngine engine;

    @BeforeAll
    static void engine() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-packs;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        cfg.setEnforceHistoryTimeToLive(true);
        engine = cfg.buildProcessEngine();
    }

    @AfterAll
    static void close() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void everyPackDeploys() {
        PackRegistry reg = PackRegistry.discover();
        assertFalse(reg.all().isEmpty(), "no packs found");
        List<PackDeployer.Result> results = new PackDeployer(engine, reg).deployAll(null);
        assertEquals(reg.all().size(), results.size());
        List<String> missing = new ArrayList<>();
        for (SangamPack p : reg.all()) {
            PackDescriptor d = p.descriptor();
            assertFalse(d.covers().isEmpty(), d.id() + " covers nothing");
            for (PackDescriptor.PackResource r : d.resources(PackDescriptor.PackResource.Kind.BPMN)) {
                try (var in = p.open(r.path())) {
                    List<String> v = ModelGuard.check(r.path(), in.readAllBytes());
                    assertTrue(v.isEmpty(), v.toString());
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }
        // every calledElement must exist
        engine.getRepositoryService().createProcessDefinitionQuery().list().forEach(pd -> {
            var model = engine.getRepositoryService().getBpmnModelInstance(pd.getId());
            for (var ca : model.getModelElementsByType(org.operaton.bpm.model.bpmn.instance.CallActivity.class)) {
                String key = ca.getCalledElement();
                if (engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key)
                        .count() == 0) {
                    missing.add(pd.getKey() + " -> " + key);
                }
            }
        });
        assertTrue(missing.isEmpty(), "unresolved call activities: " + missing);
        // redeploy is filtered as a duplicate
        List<PackDeployer.Result> again = new PackDeployer(engine, reg).deployAll(null);
        assertTrue(again.stream().noneMatch(PackDeployer.Result::newDeployment), "duplicate filtering failed");
    }
}
