// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.rights.DataSubjectRightsService;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * Owner decision 336: the compliance processes come only from the installed compliance pack ({@code sangam.compliance},
 * deployed for a tenant by the pack installation, {@link PackDeployer}). Until it is installed for the tenant (or
 * shared), a start is refused with {@code COMPLIANCE_PACK_NOT_INSTALLED} before the engine is asked, never with the
 * engine's own "no processes deployed" error; once installed, the tenant's definition is started. A pack installed
 * for one tenant does not serve another. In-memory engine, job executor off (the first step is asynchronous, so a
 * start only creates the instance).
 */
class ComplianceWorkflowStarterTest {

    static final String ACCESS = "sangam-dsr-access";
    static ProcessEngine engine;

    @BeforeAll
    static void engine() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:d336" + System.nanoTime() + ";DB_CLOSE_DELAY=1000");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        engine = cfg.buildProcessEngine();
    }

    @AfterAll
    static void close() {
        if (engine != null) {
            engine.close();
        }
        // R9-statics: a test class stays loaded for the whole run; its statics must not pin what they held
        engine = null;
    }

    static long instances(String tenant) {
        return engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(ACCESS).tenantIdIn(tenant).count();
    }

    @Test
    void notInstalledIsRefusedByNameThenTheInstalledPackIsStartedForItsTenantOnly() {
        ComplianceWorkflowStarter starter = new ComplianceWorkflowStarter(engine);
        Map<String, Object> vars = Map.of("dpoSeat", "SEAT-DPO", "sangamTenantId", "t1");

        ComplianceException refused = assertThrows(ComplianceException.class,
                () -> starter.start(null, "t1", ACCESS, "rq-1", vars));
        assertEquals(ComplianceException.Code.COMPLIANCE_PACK_NOT_INSTALLED, refused.code());
        assertEquals(409, refused.httpStatus());
        assertTrue(refused.getMessage().contains(ComplianceWorkflowStarter.PACK_ID), refused.getMessage());
        assertEquals(0, engine.getRuntimeService().createProcessInstanceQuery().count(), "nothing started");

        // the existing pack installation: the compliance pack, for tenant t1
        new PackDeployer(engine, PackRegistry.discover()).deploy(List.of(ComplianceWorkflowStarter.PACK_ID), "t1");

        String id = starter.start(null, "t1", ACCESS, "rq-2", vars);
        assertNotNull(id);
        assertEquals(1, instances("t1"), "the tenant's definition is started");
        assertEquals("rq-2", engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(id).singleResult()
                .getBusinessKey());

        ComplianceException other = assertThrows(ComplianceException.class,
                () -> starter.start(null, "t2", ACCESS, "rq-3", Map.of("dpoSeat", "SEAT-DPO")));
        assertEquals(ComplianceException.Code.COMPLIANCE_PACK_NOT_INSTALLED, other.code(), "t1's pack does not serve t2");
        assertEquals(0, instances("t2"));
    }

    @Test
    void theCompliancePackCarriesEveryRightsProcessTheModuleStarts() {
        var pack = PackRegistry.discover().find(ComplianceWorkflowStarter.PACK_ID)
                .orElseThrow(() -> new AssertionError("compliance pack not on the class path")).descriptor();
        String resources = pack.resources().toString();
        for (String key : DataSubjectRightsService.SEAT_ROLES.keySet()) {
            assertTrue(resources.contains(key), "pack " + ComplianceWorkflowStarter.PACK_ID + " has no " + key + ": " + resources);
        }
    }
}
