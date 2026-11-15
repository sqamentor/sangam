// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceSchema;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;

/**
 * Owner decision 361: the server's PersonalDataPort over the process-instance store and the retention register. A
 * principal's roots are the tenant's process roots whose {@code sangamApplicantId} or {@code principalRef} is her
 * reference, plus the roots the retention register tracks for her; correlation keys are their business keys; a hold
 * can be placed only on a process root of the tenant. Before the decision the server wired no port (null), so an
 * erasure request could not reach any process data. In-memory engine, job executor off.
 */
class CompliancePersonalDataTest {

    static ProcessEngine engine;
    static final String CITIZEN = "citizen-1";

    Connection c;
    CompliancePersonalData port;

    @BeforeAll
    static void engine() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:d361e" + System.nanoTime() + ";DB_CLOSE_DELAY=1000");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        engine = cfg.buildProcessEngine();
        for (String tenant : List.of("t1", "t2")) {
            engine.getRepositoryService().createDeployment().tenantId(tenant)
                    .addModelInstance("svc.bpmn", Bpmn.createExecutableProcess("svc").startEvent().userTask("u").endEvent().done())
                    .deploy();
        }
    }

    @AfterAll
    static void close() {
        if (engine != null) {
            engine.close();
        }
    }

    @BeforeEach
    void db() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:d361c" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Schema.create(c, Dialect.POSTGRESQL);
        ComplianceSchema.create(c, Dialect.POSTGRESQL);
        port = new CompliancePersonalData(engine);
    }

    @AfterEach
    void closeDb() throws Exception {
        c.close();
    }

    static String start(String tenant, String businessKey, Map<String, Object> vars) {
        return engine.getRuntimeService().createProcessInstanceByKey("svc").processDefinitionTenantId(tenant)
                .businessKey(businessKey).setVariables(vars).execute().getId();
    }

    @Test
    void theRootsOfAPrincipalAreHerProcessesAndTrackedRootsInHerTenantOnly() {
        String applicant = start("t1", "APP-1", Map.of("sangamApplicantId", CITIZEN, "name", "A"));
        String byRef = start("t1", "APP-2", Map.of("principalRef", CITIZEN));
        String someoneElse = start("t1", "APP-3", Map.of("sangamApplicantId", "citizen-2"));
        String otherTenant = start("t2", "APP-4", Map.of("sangamApplicantId", CITIZEN));
        Jdbc.update(c, "INSERT INTO CMP_RETENTION_ITEM(tenant_id, item_id, root_id, principal_ref, purpose_code, state, withdrawn,"
                + " attempts, updated_at) VALUES (?,?,?,?,?,?,0,0,?)", "t1", "i-1", "tracked-root", CITIZEN, "SERVICE", "ACTIVE",
                Timestamp.from(Instant.now()));

        List<String> roots = port.rootsOf(c, "t1", CITIZEN);
        assertEquals(Set.of(applicant, byRef, "tracked-root"), Set.copyOf(roots));
        assertFalse(roots.contains(someoneElse));
        assertFalse(roots.contains(otherTenant), "another tenant's process is not hers here");
        assertEquals(Set.of("APP-1", "APP-2"), Set.copyOf(port.correlationKeysOf(c, "t1", CITIZEN)));
        assertTrue(port.rootsOf(c, "t1", " ").isEmpty());

        Map<String, Object> summary = port.summarise(c, "t1", CITIZEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> one = (Map<String, Object>) summary.get("process " + applicant);
        assertEquals("svc", one.get("process"));
        assertEquals(List.of("name", "sangamApplicantId"), one.get("data"), "names only, never values");
        assertFalse(summary.toString().contains(CITIZEN), "no variable value in the summary: " + summary);
    }

    @Test
    void onlyAProcessRootOfTheTenantIsARoot() {
        String root = start("t1", "APP-9", Map.of());
        assertTrue(port.isProcessRoot(c, "t1", root));
        assertFalse(port.isProcessRoot(c, "t2", root), "another tenant's root");
        assertFalse(port.isProcessRoot(c, "t1", "no-such-instance"));
        assertFalse(port.isProcessRoot(c, "t1", null));
    }

    @Test
    void correctionOfProcessDataIsRefusedByName() {
        ComplianceException e = assertThrows(ComplianceException.class,
                () -> port.correct(c, "t1", CITIZEN, Map.of("name", "B"), "officer-1"));
        assertEquals(ComplianceException.Code.INVALID_STATE, e.code());
    }
}
