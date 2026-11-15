// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.engine.deploy.TenantIsolationException;

/**
 * Decision 355 (G29): an identity of one tenant cannot suspend or activate tenantless (shared) definitions, job
 * definitions or jobs by key. Operaton's by-key UPDATEs carry the caller's tenant check, but that check also matches
 * rows without a tenant ({@code TENANT_ID_ is null or TENANT_ID_ in (...)}), so tenant B's by-key call changed every
 * tenant's shared definition of that key. The call is now refused by name ({@code TENANT_ISOLATION_VIOLATION}) at
 * Sangam's seam and nothing changes; a key only the caller's own tenant has, a by-key call naming the caller's tenant,
 * and platform code are unchanged.
 */
class SharedDefinitionByKeyTenantH2Test {

    /** An asynchronous first step (one job per instance, one job definition per definition), then a user task. */
    static String bpmn(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="KEY" isExecutable="true" operaton:historyTimeToLive="30">
                    <startEvent id="start"/><sequenceFlow id="f0" sourceRef="start" targetRef="first"/>
                    <serviceTask id="first" operaton:asyncBefore="true" operaton:expression="${true}"/>
                    <sequenceFlow id="f1" sourceRef="first" targetRef="review"/><userTask id="review"/>
                    <sequenceFlow id="f2" sourceRef="review" targetRef="end"/><endEvent id="end"/>
                  </process>
                </definitions>
                """.replace("KEY", key);
    }

    /** Only a shared (tenantless) definition has it. */
    static final String SHARED = "shared";
    /** A shared definition and tenant B's own. */
    static final String MIXED = "mixed";
    /** Only tenant B has it. */
    static final String OWN = "own";

    private void withDefinitions(Consumer<ProcessEngine> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:s355" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setJobExecutorActivate(false);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            ProcessEngine engine = a.processEngine();
            for (String key : List.of(SHARED, MIXED)) {
                // platform code (no tenant identity) deploys and starts the shared definitions
                engine.getRepositoryService().createDeployment().name(key)
                        .addString(key + ".bpmn", bpmn(key)).deploy();
                engine.getRuntimeService().createProcessInstanceByKey(key).processDefinitionWithoutTenantId().execute();
            }
            for (String key : List.of(MIXED, OWN)) {
                a.deploy("B", key, Map.of(key + ".bpmn", bpmn(key).getBytes(StandardCharsets.UTF_8)));
                a.startProcess("B", key, "BK-" + key, Map.of());
            }
            test.accept(engine);
        }
    }

    /** Runs {@code change} as an identity of tenant {@code tenant}. */
    static void as(ProcessEngine engine, String tenant, Executable change) throws Throwable {
        engine.getIdentityService().setAuthentication("user-" + tenant, List.of(), List.of(tenant));
        try {
            change.execute();
        } finally {
            engine.getIdentityService().clearAuthentication();
        }
    }

    static void refusedAs(ProcessEngine engine, String tenant, Executable change, String what) {
        TenantIsolationException e = assertThrows(TenantIsolationException.class, () -> as(engine, tenant, change), what);
        assertTrue(e.getMessage().contains("shared"), e.getMessage());
        assertTrue(e.getMessage().contains("by key"), e.getMessage());
    }

    static boolean definitionSuspended(ProcessEngine engine, String key, String tenant) {
        var q = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key);
        return (tenant == null ? q.withoutTenantId() : q.tenantIdIn(tenant)).singleResult().isSuspended();
    }

    static boolean jobDefinitionSuspended(ProcessEngine engine, String key, String tenant) {
        var q = engine.getManagementService().createJobDefinitionQuery().processDefinitionKey(key);
        return (tenant == null ? q.withoutTenantId() : q.tenantIdIn(tenant)).singleResult().isSuspended();
    }

    static boolean jobSuspended(ProcessEngine engine, String key, String tenant) {
        var q = engine.getManagementService().createJobQuery().processDefinitionKey(key);
        return (tenant == null ? q.withoutTenantId() : q.tenantIdIn(tenant)).singleResult().isSuspended();
    }

    static long timers(ProcessEngine engine) {
        return engine.getManagementService().createJobQuery().timers().count();
    }

    @Test
    void aTenantCannotSuspendOrActivateSharedDefinitionsByKey() {
        withDefinitions(engine -> {
            var repo = engine.getRepositoryService();
            refusedAs(engine, "B", () -> repo.suspendProcessDefinitionByKey(SHARED), "by key");
            refusedAs(engine, "B", () -> repo.suspendProcessDefinitionByKey(SHARED, true, null), "with instances");
            refusedAs(engine, "B", () -> repo.updateProcessDefinitionSuspensionState().byProcessDefinitionKey(SHARED)
                    .processDefinitionWithoutTenantId().suspend(), "the builder, without tenant");
            assertFalse(definitionSuspended(engine, SHARED, null), "the shared definition is unchanged");
            assertFalse(jobDefinitionSuspended(engine, SHARED, null));
            assertFalse(jobSuspended(engine, SHARED, null));

            long timersBefore = timers(engine);
            refusedAs(engine, "B", () -> repo.suspendProcessDefinitionByKey(SHARED, false,
                    new Date(System.currentTimeMillis() + 3_600_000L)), "a delayed change by key");
            assertEquals(timersBefore, timers(engine), "no timer was scheduled");

            repo.suspendProcessDefinitionByKey(SHARED, true, null); // platform code may
            assertTrue(definitionSuspended(engine, SHARED, null));
            refusedAs(engine, "B", () -> repo.activateProcessDefinitionByKey(SHARED), "activate by key");
            refusedAs(engine, "B", () -> repo.updateProcessDefinitionSuspensionState().byProcessDefinitionKey(SHARED)
                    .processDefinitionWithoutTenantId().includeProcessInstances(true).activate(), "activate, builder");
            assertTrue(definitionSuspended(engine, SHARED, null), "still suspended");
        });
    }

    @Test
    void aKeyThatIsSharedAndTheTenantsOwnIsRefusedWholeUnlessTheCallNamesTheTenant() {
        withDefinitions(engine -> {
            var repo = engine.getRepositoryService();
            refusedAs(engine, "B", () -> repo.suspendProcessDefinitionByKey(MIXED, true, null), "shared and own");
            assertFalse(definitionSuspended(engine, MIXED, null), "the shared one is unchanged");
            assertFalse(definitionSuspended(engine, MIXED, "B"), "the call rolled back as a whole");

            assertDoesNotThrow(() -> as(engine, "B", () -> repo.updateProcessDefinitionSuspensionState()
                    .byProcessDefinitionKey(MIXED).processDefinitionTenantId("B").includeProcessInstances(true).suspend()));
            assertTrue(definitionSuspended(engine, MIXED, "B"), "by key and the caller's own tenant");
            assertFalse(definitionSuspended(engine, MIXED, null));

            assertDoesNotThrow(() -> as(engine, "B", () -> repo.suspendProcessDefinitionByKey(OWN, true, null)));
            assertTrue(definitionSuspended(engine, OWN, "B"), "a key only the caller's tenant has");
        });
    }

    @Test
    void aTenantCannotSuspendOrActivateSharedJobDefinitionsOrJobsByKey() {
        withDefinitions(engine -> {
            var mgmt = engine.getManagementService();
            refusedAs(engine, "B", () -> mgmt.suspendJobDefinitionByProcessDefinitionKey(SHARED, true),
                    "job definitions by key");
            refusedAs(engine, "B", () -> mgmt.updateJobDefinitionSuspensionState().byProcessDefinitionKey(SHARED)
                    .processDefinitionWithoutTenantId().suspend(), "job definitions, builder without tenant");
            refusedAs(engine, "B", () -> mgmt.suspendJobByProcessDefinitionKey(SHARED), "jobs by key");
            refusedAs(engine, "B", () -> mgmt.updateJobSuspensionState().byProcessDefinitionKey(SHARED)
                    .processDefinitionWithoutTenantId().suspend(), "jobs, builder without tenant");
            refusedAs(engine, "B", () -> mgmt.suspendJobByProcessDefinitionKey(MIXED), "jobs of a shared and own key");
            assertFalse(jobDefinitionSuspended(engine, SHARED, null));
            assertFalse(jobSuspended(engine, SHARED, null));
            assertFalse(jobSuspended(engine, MIXED, "B"));

            mgmt.suspendJobByProcessDefinitionKey(SHARED); // platform code may
            refusedAs(engine, "B", () -> mgmt.activateJobByProcessDefinitionKey(SHARED), "activate jobs by key");
            assertTrue(jobSuspended(engine, SHARED, null));

            assertDoesNotThrow(() -> as(engine, "B", () -> mgmt.suspendJobByProcessDefinitionKey(OWN)));
            assertTrue(jobSuspended(engine, OWN, "B"));
            assertDoesNotThrow(() -> as(engine, "B", () -> mgmt.updateJobSuspensionState().byProcessDefinitionKey(MIXED)
                    .processDefinitionTenantId("B").suspend()));
            assertTrue(jobSuspended(engine, MIXED, "B"));
            assertFalse(jobSuspended(engine, MIXED, null));
        });
    }
}
