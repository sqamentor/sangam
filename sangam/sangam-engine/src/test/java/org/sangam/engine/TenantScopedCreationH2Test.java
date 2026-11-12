// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.engine.deploy.TenantIsolationException;

/**
 * SL-45 (PostgreSQL finding of {@code CrossTenantIsolationIT}, rerun 4): with the engine authentication that
 * {@code /engine-rest} sets (exactly the token's tenant), tenant A still changed tenant B through the two things Operaton
 * creates without a tenant check. A deployment naming tenant B was created in B ({@code TenantCommandChecker
 * #checkCreateDeployment} is empty), and a batch of B's instance ids (async delete, async suspend) was created without
 * a tenant, so its jobs ran without any authentication and deleted or suspended B's instance. Every "refused" or
 * "untouched" test here fails without {@link org.sangam.engine.deploy.TenantScopedCreation}; the others pin that the
 * same calls still work in the caller's own tenant, that platform code (no engine authentication) is unchanged, and
 * that the selector calls the IT saw answered 2xx (suspension by a shared key, correlation and signals) never reached
 * tenant B.
 */
class TenantScopedCreationH2Test {

    static final String A = "tenant-a";
    static final String B = "tenant-b";

    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <message id="m" name="isoMsg"/><signal id="s" name="isoSig"/>
              <process id="iso" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="start"/><sequenceFlow id="f0" sourceRef="start" targetRef="fork"/>
                <parallelGateway id="fork"/>
                <sequenceFlow id="f1" sourceRef="fork" targetRef="review"/>
                <sequenceFlow id="f2" sourceRef="fork" targetRef="waitMsg"/>
                <sequenceFlow id="f3" sourceRef="fork" targetRef="waitSig"/>
                <userTask id="review"/>
                <intermediateCatchEvent id="waitMsg"><messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                <intermediateCatchEvent id="waitSig"><signalEventDefinition signalRef="s"/></intermediateCatchEvent>
                <sequenceFlow id="f4" sourceRef="review" targetRef="e1"/><endEvent id="e1"/>
                <sequenceFlow id="f5" sourceRef="waitMsg" targetRef="e2"/><endEvent id="e2"/>
                <sequenceFlow id="f6" sourceRef="waitSig" targetRef="e3"/><endEvent id="e3"/>
              </process>
            </definitions>
            """;

    private static Map<String, byte[]> model() {
        return Map.of("iso.bpmn", BPMN.getBytes(StandardCharsets.UTF_8));
    }

    /** An engine with tenants A and B, each with the model deployed and one running instance (platform code, no auth). */
    private static void withTenants(boolean jobExecutor, Consumer<Tenants> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:x" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setJobExecutorActivate(jobExecutor);
        s.setJobWaitTimeInMillis(50);
        s.setJobMaxWait(250);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            a.deploy(A, "iso", model());
            a.deploy(B, "iso", model());
            String piA = a.startProcess(A, "iso", "BK-A", Map.of());
            String piB = a.startProcess(B, "iso", "BK-B", Map.of());
            test.accept(new Tenants(a.processEngine(), piA, piB));
        }
    }

    record Tenants(ProcessEngine engine, String piA, String piB) {
        RepositoryService repo() {
            return engine.getRepositoryService();
        }

        RuntimeService runtime() {
            return engine.getRuntimeService();
        }

        ManagementService mgmt() {
            return engine.getManagementService();
        }

        org.operaton.bpm.engine.TaskService tasks() {
            return engine.getTaskService();
        }

        /** Runs {@code work} with the engine authentication {@code /engine-rest} sets for a token of {@code tenant}. */
        <T> T as(String tenant, Supplier<T> work) {
            IdentityService ids = engine.getIdentityService();
            ids.setAuthentication("user-" + tenant, List.of("sangam-engine-admin"), List.of(tenant));
            try {
                return work.get();
            } finally {
                ids.clearAuthentication();
            }
        }

        long deployments(String tenant) {
            return tenant == null ? repo().createDeploymentQuery().withoutTenantId().count()
                    : repo().createDeploymentQuery().tenantIdIn(tenant).count();
        }

        boolean active(String pi) {
            return runtime().createProcessInstanceQuery().processInstanceId(pi).active().count() == 1;
        }
    }

    private static void assertIsolation(Throwable thrown, String target) {
        TenantIsolationException t = TenantIsolationException.find(thrown);
        assertNotNull(t, "a tenant isolation refusal: " + messages(thrown));
        assertTrue(t.getMessage().startsWith("SL-13 tenant isolation"), t.getMessage());
        assertEquals(target, t.targetTenant(), t.getMessage());
        assertEquals(A, t.callerTenant(), t.getMessage());
    }

    private static String messages(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
            b.append(x.getClass().getSimpleName()).append(": ").append(x.getMessage()).append(" | ");
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ deployments

    @Test
    void deploymentIntoAnotherTenantIsRefused() {
        withTenants(false, t -> {
            RuntimeException e = assertThrows(RuntimeException.class, () -> t.as(A, () -> t.repo().createDeployment()
                    .name("sl45").tenantId(B).addString("iso.bpmn", BPMN).deploy()));
            assertIsolation(e, B);
            assertEquals(1, t.deployments(B), "nothing was deployed into tenant B");
            assertEquals(1, t.repo().createProcessDefinitionQuery().tenantIdIn(B).count());
        });
    }

    @Test
    void sharedDeploymentByAnIdentityOfOneTenantIsRefused() {
        withTenants(false, t -> {
            long shared = t.deployments(null);
            RuntimeException e = assertThrows(RuntimeException.class, () -> t.as(A, () -> t.repo().createDeployment()
                    .name("sl45-shared").addString("iso.bpmn", BPMN).deploy()));
            assertIsolation(e, null);
            assertEquals(shared, t.deployments(null), "no shared definition every tenant resolves was created");
        });
    }

    @Test
    void duplicateFilteringNeverReturnsAnotherTenantsDeployment() {
        withTenants(false, t -> {
            // tenant B's pack deployment; pack content is the same for every tenant, so A can replay it byte for byte
            Deployment ofB = t.repo().createDeployment().name("pack:iso@1").tenantId(B).addString("iso.bpmn", BPMN).deploy();
            RuntimeException e = assertThrows(RuntimeException.class, () -> t.as(A, () -> t.repo().createDeployment()
                    .name("pack:iso@1").tenantId(B).enableDuplicateFiltering(false).addString("iso.bpmn", BPMN).deploy()),
                    "the duplicate lookup in tenant B returned B's deployment " + ofB.getId());
            assertIsolation(e, B);
            assertEquals(2, t.deployments(B));
        });
    }

    @Test
    void deploymentIntoTheCallersOwnTenantStillWorks() {
        withTenants(false, t -> {
            Deployment d = t.as(A, () -> t.repo().createDeployment().name("sl45-own").tenantId(A)
                    .addString("iso.bpmn", BPMN).deploy());
            assertEquals(A, d.getTenantId());
            assertEquals(2, t.repo().createProcessDefinitionQuery().processDefinitionKey("iso").tenantIdIn(A).count());
            // and with duplicate filtering: an unchanged redeploy is filtered to the caller's own deployment
            Deployment again = t.as(A, () -> t.repo().createDeployment().name("sl45-own").tenantId(A)
                    .enableDuplicateFiltering(false).addString("iso.bpmn", BPMN).deploy());
            assertEquals(d.getId(), again.getId());
        });
    }

    @Test
    void platformDeploymentsWithoutAnEngineAuthenticationAreUnchanged() {
        withTenants(false, t -> {
            t.repo().createDeployment().name("platform-b").tenantId(B).addString("iso.bpmn", BPMN).deploy();
            t.repo().createDeployment().name("platform-shared").addString("iso.bpmn", BPMN).deploy();
            assertEquals(2, t.deployments(B));
            assertEquals(1, t.deployments(null));
        });
    }

    // ------------------------------------------------------------------ batches

    @Test
    void aBatchOfAnIdentityOfOneTenantBelongsToThatTenant() {
        withTenants(false, t -> {
            Batch b = t.as(A, () -> t.runtime().deleteProcessInstancesAsync(List.of(t.piA()), "SL-45"));
            assertEquals(A, b.getTenantId(), "the batch belongs to the caller's tenant");
            Job seed = t.mgmt().createJobQuery().jobDefinitionId(b.getSeedJobDefinitionId()).singleResult();
            assertEquals(A, seed.getTenantId(), "its jobs inherit the tenant, so they run with its authentication");
            assertEquals(0, t.as(B, () -> t.mgmt().createBatchQuery().batchId(b.getId()).count()),
                    "another tenant neither sees nor controls the batch");
            assertEquals(1, t.as(A, () -> t.mgmt().createBatchQuery().batchId(b.getId()).count()));
        });
    }

    @Test
    void aBatchOfAnIdentityOfSeveralTenantsIsRefused() {
        withTenants(false, t -> {
            IdentityService ids = t.engine().getIdentityService();
            ids.setAuthentication("user-ab", List.of(), List.of(A, B));
            try {
                // fail closed: the batch could belong to neither, and an unowned batch runs its elements unchecked
                RuntimeException e = assertThrows(RuntimeException.class,
                        () -> t.runtime().deleteProcessInstancesAsync(List.of(t.piA()), "SL-45"));
                TenantIsolationException refused = TenantIsolationException.find(e);
                assertNotNull(refused, messages(e));
                assertTrue(refused.getMessage().startsWith("SL-13 tenant isolation"), refused.getMessage());
            } finally {
                ids.clearAuthentication();
            }
            assertEquals(0, t.mgmt().createBatchQuery().count(), "no batch was created");
        });
    }

    @Test
    void aBatchNamingAnotherTenantsInstanceLeavesItUntouched() {
        withTenants(true, t -> {
            Batch delete = t.as(A, () -> t.runtime().deleteProcessInstancesAsync(List.of(t.piB()), "SL-45 probe"));
            Batch suspend = t.as(A, () -> t.runtime().updateProcessInstanceSuspensionState()
                    .byProcessInstanceIds(t.piB()).suspendAsync());
            settle(t, delete, suspend);
            assertTrue(t.active(t.piB()), "tenant B's instance still exists and is active");
            assertEquals(1, t.runtime().createProcessInstanceQuery().processInstanceId(t.piB()).count());
            for (Batch b : List.of(delete, suspend)) {
                // the batch did run: its element for B was refused by Operaton's tenant check, as a synchronous call is
                List<Job> failed = t.mgmt().createJobQuery().jobDefinitionId(b.getBatchJobDefinitionId()).noRetriesLeft()
                        .list();
                assertEquals(1, failed.size(), "the element job of batch " + b.getType() + " failed");
                assertTrue(String.valueOf(failed.get(0).getExceptionMessage()).contains("no authenticated tenant"),
                        failed.get(0).getExceptionMessage());
            }
        });
    }

    @Test
    void aBatchOnTheCallersOwnInstanceStillRuns() {
        withTenants(true, t -> {
            Batch delete = t.as(A, () -> t.runtime().deleteProcessInstancesAsync(List.of(t.piA()), "SL-45 own"));
            settle(t, delete);
            assertEquals(0, t.runtime().createProcessInstanceQuery().processInstanceId(t.piA()).count(),
                    "the caller's own instance was deleted by its batch");
            assertEquals(0, t.mgmt().createJobQuery().jobDefinitionId(delete.getBatchJobDefinitionId()).count(),
                    "no element job failed");
            assertTrue(t.active(t.piB()));
        });
    }

    /** Waits until each batch is seeded and none of its element jobs has a retry left (done or failed for good). */
    private static void settle(Tenants t, Batch... batches) {
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end) {
            boolean settled = true;
            for (Batch b : batches) {
                settled &= t.mgmt().createJobQuery().jobDefinitionId(b.getSeedJobDefinitionId()).count() == 0
                        && t.mgmt().createJobQuery().jobDefinitionId(b.getBatchJobDefinitionId()).withRetriesLeft()
                        .count() == 0;
            }
            if (settled) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("the batches did not settle in 60 s");
    }

    // ------------------------------------------------------------------ selector calls the IT saw answered 2xx

    @Test
    void suspensionByAKeyBothTenantsUseStaysInTheCallersTenant() {
        withTenants(false, t -> {
            t.as(A, () -> {
                t.runtime().updateProcessInstanceSuspensionState().byProcessDefinitionKey("iso").suspend();
                return null;
            });
            assertTrue(!t.active(t.piA()), "the caller's own instance of the key is suspended");
            assertTrue(t.active(t.piB()), "tenant B's instance of the same key is not");
        });
    }

    @Test
    void correlationAndSignalsNamingAnotherTenantDoNotReachIt() {
        withTenants(false, t -> {
            t.as(A, () -> t.runtime().createMessageCorrelation("isoMsg").tenantId(B).correlateAllWithResult());
            t.as(A, () -> {
                t.runtime().createSignalEvent("isoSig").send();
                return null;
            });
            assertEquals(1, t.runtime().createEventSubscriptionQuery().processInstanceId(t.piB()).eventType("message")
                    .count(), "tenant B's message subscription is still waiting");
            assertEquals(1, t.runtime().createEventSubscriptionQuery().processInstanceId(t.piB()).eventType("signal")
                    .count(), "tenant B's signal subscription is still waiting");
            assertEquals(0, t.runtime().createEventSubscriptionQuery().processInstanceId(t.piA()).eventType("signal")
                    .count(), "the broadcast reached the caller's own instance");
        });
    }

    // ------------------------------------------------------------------ standalone tasks (SL-73)

    /** Creates a standalone task as {@code tenant} (the engine authentication {@code /engine-rest} sets). */
    private static String standaloneTask(Tenants t, String tenant, String named) {
        return t.as(tenant, () -> {
            org.operaton.bpm.engine.task.Task task = t.tasks().newTask();
            task.setName("sl73-standalone");
            if (named != null) {
                task.setTenantId(named);
            }
            t.tasks().saveTask(task);
            return task.getId();
        });
    }

    @Test
    void aStandaloneTaskOfAnIdentityOfOneTenantBelongsToThatTenant() {
        withTenants(false, t -> {
            String id = standaloneTask(t, A, null);
            assertEquals(A, t.tasks().createTaskQuery().taskId(id).singleResult().getTenantId(),
                    "before SL-73 the task had no tenant, so every tenant saw and used it");
            assertEquals(0, t.as(B, () -> t.tasks().createTaskQuery().taskId(id).count()), "tenant B does not see it");
            assertEquals(1, t.as(A, () -> t.tasks().createTaskQuery().taskId(id).count()), "tenant A does");
        });
    }

    /** Pinned, not changed by SL-73: Operaton's {@code TenantCommandChecker#checkCreateTask} already refuses this. */
    @Test
    void aStandaloneTaskNamingAnotherTenantIsRefused() {
        withTenants(false, t -> {
            assertThrows(RuntimeException.class, () -> standaloneTask(t, A, B));
            assertEquals(0, t.tasks().createTaskQuery().taskName("sl73-standalone").count(), "nothing was created");
        });
    }

    @Test
    void aStandaloneTaskOfAnIdentityOfSeveralTenantsIsRefused() {
        withTenants(false, t -> {
            IdentityService ids = t.engine().getIdentityService();
            ids.setAuthentication("user-ab", List.of("sangam-engine-admin"), List.of(A, B));
            try {
                org.operaton.bpm.engine.task.Task task = t.tasks().newTask();
                RuntimeException e = assertThrows(RuntimeException.class, () -> t.tasks().saveTask(task));
                assertNotNull(TenantIsolationException.find(e), messages(e));
            } finally {
                ids.clearAuthentication();
            }
            assertEquals(0, t.tasks().createTaskQuery().withoutTenantId().count(), "no task without a tenant");
        });
    }

    @Test
    void platformStandaloneTasksAndProcessTasksAreUnchanged() {
        withTenants(false, t -> {
            org.operaton.bpm.engine.task.Task task = t.tasks().newTask();
            t.tasks().saveTask(task);
            assertEquals(null, t.tasks().createTaskQuery().taskId(task.getId()).singleResult().getTenantId(),
                    "platform code (no engine authentication) creates as before");
            assertEquals(B, t.tasks().createTaskQuery().processInstanceId(t.piB()).singleResult().getTenantId(),
                    "a process task keeps its instance's tenant");
        });
    }
}
