// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.sangam.ops.flowable.InstancePlan.TaskState;
import org.sangam.ops.flowable.OperatonStateWriter.ActivityKind;
import org.sangam.ops.flowable.OperatonStateWriter.TargetState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M-135 target side on a real in-memory Operaton engine (H2): the deployed model gives the same activity facts as the
 * corpus model, every SUPPORTED corpus plan is recreated exactly (reconciliation finds no difference), nothing runs
 * (no finished task, no executed job), provenance adoption works, a failing plan leaves nothing behind, and delete
 * removes the instance. Written, NOT_RUN.
 */
class OperatonEngineStateWriterH2Test {

    ProcessEngine engine;
    OperatonEngineStateWriter writer;
    String definitionId;

    @BeforeEach
    void setUp() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:fmig" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        cfg.setJobExecutorActivate(false);
        cfg.setEnforceHistoryTimeToLive(false);
        cfg.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().tenantId(FlowableCorpus.TENANT)
                .addString("order.bpmn", FlowableCorpus.ORDER_BPMN).deploy();
        writer = new OperatonEngineStateWriter(engine);
        definitionId = writer.definitionId("order", 1, FlowableCorpus.TENANT).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private List<InstancePlan> supportedPlans() {
        FlowableSnapshot snap = new FlowableInventory(FlowableCorpus.supported(), FlowableCorpus.ZONE).read(FlowableCorpus.TENANT);
        StateClassifier c = new StateClassifier(FlowableCorpus.MAPPINGS, writer, 3);
        List<InstancePlan> plans = new ArrayList<>();
        snap.instances().forEach(i -> plans.add(c.classify(i, FlowableCorpus.NOW)));
        return plans;
    }

    @Test
    void definitionLookupIsTenantAndVersionExact() {
        assertTrue(writer.definitionId("order", 2, FlowableCorpus.TENANT).isEmpty());
        assertTrue(writer.definitionId("order", 1, "beta").isEmpty());
        assertTrue(writer.definitionId("order", 1, null).isEmpty(), "a tenant deployment is not a no-tenant deployment");
    }

    @Test
    void deployedModelGivesTheCorpusActivityFacts() {
        FlowableCorpus.ORDER_ACTIVITIES.forEach((activity, facts) -> {
            if (!"callSub".equals(activity)) {
                assertEquals(facts, writer.activity(definitionId, activity), activity);
            }
        });
        assertEquals(ActivityKind.MISSING, writer.activity(definitionId, "callSub").kind());
        assertEquals(ActivityKind.MISSING, writer.activity(definitionId, "nope").kind());
    }

    @Test
    void everySupportedPlanIsRecreatedExactlyWithoutRunningAnything() {
        List<InstancePlan> plans = supportedPlans();
        assertEquals(FlowableCorpus.SUPPORTED_IDS.size(), plans.size());
        for (InstancePlan p : plans) {
            assertEquals(InstancePlan.Status.SUPPORTED, p.status(), p.sourceInstanceId() + " " + p.findings());
            String target = writer.apply(p, "flowable-prod:" + p.sourceInstanceId());
            TargetState s = writer.state(target);
            assertEquals(List.of(), FlowableMigrationPipeline.compare(p, s), p.sourceInstanceId());
            assertEquals(0, s.finishedTasks(), p.sourceInstanceId());
            assertEquals(0, s.executedJobs(), p.sourceInstanceId());
            assertEquals(Optional.of(target), writer.findMigrated("flowable-prod:" + p.sourceInstanceId()));
        }
    }

    @Test
    void delegatedTaskKeepsOwnerAssigneeAndDelegationState() {
        InstancePlan s1 = supportedPlans().stream().filter(p -> p.sourceInstanceId().equals("s1")).findFirst().orElseThrow();
        TaskState t = writer.state(writer.apply(s1, "flowable-prod:s1")).tasks().get(0);
        assertEquals("bob", t.assignee());
        assertEquals("carol", t.owner());
        assertEquals("PENDING", t.delegation());
        assertEquals(List.of("alice"), t.candidateUsers());
        assertEquals(List.of("clerks"), t.candidateGroups());
    }

    @Test
    void suspendedPlanIsSuspendedOnTheTarget() {
        InstancePlan s8 = supportedPlans().stream().filter(p -> p.sourceInstanceId().equals("s8")).findFirst().orElseThrow();
        assertTrue(writer.state(writer.apply(s8, "flowable-prod:s8")).suspended());
    }

    @Test
    void aFailingPlanLeavesNothingBehind() {
        InstancePlan s2 = supportedPlans().stream().filter(p -> p.sourceInstanceId().equals("s2")).findFirst().orElseThrow();
        // a task the started activities do not create: the engine transaction must roll back the started instance
        InstancePlan broken = new InstancePlan(s2.sourceInstanceId(), s2.sourceDefinitionId(), s2.definitionKey(), s2.sourceVersion(),
                s2.tenantId(), s2.targetDefinitionId(), s2.businessKey(), false, s2.startActivities(),
                List.of(new TaskState("approve", "x", null, null, null, 50, null, List.of(), List.of(), List.of())), List.of(),
                s2.messageNames(), s2.variables(), InstancePlan.Status.SUPPORTED, List.of(), s2.fingerprint());
        assertThrows(IllegalStateException.class, () -> writer.apply(broken, "flowable-prod:s2"));
        assertTrue(writer.findMigrated("flowable-prod:s2").isEmpty());
        assertEquals(0, engine.getRuntimeService().createProcessInstanceQuery().count());
    }

    @Test
    void rejectedPlanIsNeverApplied() {
        InstancePlan s2 = supportedPlans().stream().filter(p -> p.sourceInstanceId().equals("s2")).findFirst().orElseThrow();
        InstancePlan rejected = new InstancePlan(s2.sourceInstanceId(), s2.sourceDefinitionId(), s2.definitionKey(), s2.sourceVersion(),
                s2.tenantId(), s2.targetDefinitionId(), s2.businessKey(), false, s2.startActivities(), s2.tasks(), s2.timers(),
                s2.messageNames(), s2.variables(), InstancePlan.Status.REJECTED, List.of(), s2.fingerprint());
        assertThrows(IllegalArgumentException.class, () -> writer.apply(rejected, "flowable-prod:s2"));
        assertEquals(0, engine.getRuntimeService().createProcessInstanceQuery().count());
    }

    @Test
    void deleteRemovesTheInstance() {
        InstancePlan s3 = supportedPlans().stream().filter(p -> p.sourceInstanceId().equals("s3")).findFirst().orElseThrow();
        String target = writer.apply(s3, "flowable-prod:s3");
        writer.delete(target, "rollback test");
        assertFalse(writer.state(target).exists());
        assertTrue(writer.findMigrated("flowable-prod:s3").isEmpty());
        assertEquals(0, engine.getManagementService().createJobQuery().count(), "the timer job went with it");
    }

    @Test
    void startingAtAWaitStateDoesNotPassIt() {
        InstancePlan s5 = supportedPlans().stream().filter(p -> p.sourceInstanceId().equals("s5")).findFirst().orElseThrow();
        String target = writer.apply(s5, "flowable-prod:s5");
        assertEquals(1, engine.getManagementService().createJobQuery().processInstanceId(target).count(),
                "the async continuation is a pending job, not executed");
        assertEquals(List.of("dispatch"), writer.state(target).activeActivities());
        assertEquals(0, engine.getTaskService().createTaskQuery().processInstanceId(target).count(), "nothing ran past dispatch");
    }
}
