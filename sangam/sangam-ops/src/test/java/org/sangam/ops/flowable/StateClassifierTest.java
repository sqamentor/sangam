// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import org.junit.jupiter.api.Test;
import org.sangam.ops.flowable.FlowableSnapshot.Instance;
import org.sangam.ops.flowable.InstancePlan.Finding;
import org.sangam.ops.flowable.InstancePlan.Severity;
import org.sangam.ops.flowable.InstancePlan.TaskState;
import org.sangam.ops.flowable.InstancePlan.TimerState;
import org.sangam.ops.flowable.InstancePlan.VariableValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M-135 inventory and classification on the synthetic corpus (decision 169): every supported state gets the exact plan,
 * every unsupported state is rejected with its code, nothing is guessed. Written, NOT_RUN.
 */
class StateClassifierTest {

    private static Map<String, InstancePlan> classify(FlowableRows rows) {
        FlowableSnapshot snap = new FlowableInventory(rows, FlowableCorpus.ZONE).read(FlowableCorpus.TENANT);
        StateClassifier c = new StateClassifier(FlowableCorpus.MAPPINGS, FlowableCorpus.model(), 3);
        return snap.instances().stream().map(i -> c.classify(i, FlowableCorpus.NOW))
                .collect(Collectors.toMap(InstancePlan::sourceInstanceId, Function.identity()));
    }

    private static Set<String> codes(InstancePlan p, Severity s) {
        return p.findings().stream().filter(f -> f.severity() == s).map(Finding::code).collect(Collectors.toSet());
    }

    private static long millis(String utc) {
        return Instant.parse(utc).toEpochMilli();
    }

    @Test
    void inventoryReadsOnlyTheSourceTenantAndListsWhatIsOutOfScope() {
        FlowableSnapshot snap = new FlowableInventory(FlowableCorpus.supported(), FlowableCorpus.ZONE).read(FlowableCorpus.TENANT);
        assertEquals(FlowableCorpus.SUPPORTED_IDS, snap.instances().stream().map(Instance::id).toList());
        assertEquals(19L, snap.tableCounts().get(FlowableTables.EXECUTION));
        assertEquals(0L, snap.tableCounts().get(FlowableTables.DEADLETTER_JOB), "a table absent from the corpus counts 0");
        assertTrue(snap.outOfScope().stream().anyMatch(s -> s.startsWith("standalone task t-alone")), snap.outOfScope().toString());
        assertTrue(snap.outOfScope().stream().anyMatch(s -> s.startsWith("definition-level job j-def")), snap.outOfScope().toString());
    }

    @Test
    void fingerprintIsStableAndChangesWithTheSource() {
        FlowableInventory inv = new FlowableInventory(FlowableCorpus.supported(), FlowableCorpus.ZONE);
        Instance a = inv.read(FlowableCorpus.TENANT).instances().get(0);
        Instance b = inv.reread(List.of("s1")).get("s1");
        assertEquals(a.fingerprint(), b.fingerprint());
        assertEquals(64, a.fingerprint().length());
        Instance other = inv.reread(List.of("s2")).get("s2");
        assertNotEquals(a.fingerprint(), other.fingerprint());
    }

    @Test
    void everySupportedInstanceIsSupported() {
        Map<String, InstancePlan> plans = classify(FlowableCorpus.supported());
        assertEquals(Set.copyOf(FlowableCorpus.SUPPORTED_IDS), plans.keySet());
        plans.values().forEach(p -> assertEquals(InstancePlan.Status.SUPPORTED, p.status(), p.sourceInstanceId() + " " + p.findings()));
        plans.values().forEach(p -> assertEquals(FlowableCorpus.TARGET_DEFINITION, p.targetDefinitionId()));
    }

    @Test
    void userTaskKeepsIdentityDelegationCandidatesDueDateAndLocals() {
        InstancePlan p = classify(FlowableCorpus.supported()).get("s1");
        assertEquals(List.of("review"), p.startActivities());
        assertEquals("ORD-1", p.businessKey());
        // 2026-12-15T09:00 in Europe/Berlin is CET (+01:00)
        assertEquals(List.of(new TaskState("review", "Review order", "bob", "carol", "PENDING", 70, millis("2026-12-15T08:00:00Z"),
                List.of("alice"), List.of("clerks"), List.of(new VariableValue("note", "string", "check stock")))), p.tasks());
        assertEquals(List.of(
                new VariableValue("amount", "long", "1250"),
                new VariableValue("approved", "boolean", "false"),
                new VariableValue("count", "integer", "3"),
                new VariableValue("customer", "string", "Rao & Sons"),
                new VariableValue("empty", "null", null),
                new VariableValue("orderedAt", "date", "1767225600000"),
                new VariableValue("rate", "double", "12.5"),
                new VariableValue("ref", "string", "1b4e28ba-2fa1-11d2-883f-0016d3cca427")), p.variables());
        assertEquals(Set.of(StateClassifier.UUID_AS_STRING, StateClassifier.DOUBLE_VARIABLE), codes(p, Severity.WARN));
    }

    @Test
    void messageCatchCarriesItsSubscription() {
        InstancePlan p = classify(FlowableCorpus.supported()).get("s2");
        assertEquals(List.of("waitPayment"), p.startActivities());
        assertEquals(List.of("PaymentReceived"), p.messageNames());
        assertEquals(List.of(new VariableValue("paymentRef", "string", "PAY-2")), p.variables());
    }

    @Test
    void intermediateTimerKeepsItsOriginalDueDate() {
        InstancePlan p = classify(FlowableCorpus.supported()).get("s3");
        assertEquals(List.of("cooling"), p.startActivities());
        assertEquals(List.of(new TimerState("cooling", millis("2026-12-01T09:00:00Z"), null)), p.timers());
        assertTrue(codes(p, Severity.WARN).isEmpty());
    }

    @Test
    void boundaryTimerIsRecreatedWithItsActivityAndStaysOverdue() {
        InstancePlan p = classify(FlowableCorpus.supported()).get("s4");
        assertEquals(List.of("approve"), p.startActivities(), "the boundary execution is not a start activity");
        // 2026-05-01T10:00 in Europe/Berlin is CEST (+02:00)
        assertEquals(List.of(new TimerState("approveTimeout", millis("2026-05-01T08:00:00Z"), null)), p.timers());
        assertEquals(Set.of(StateClassifier.OVERDUE_TIMER), codes(p, Severity.WARN));
    }

    @Test
    void asyncExternalAndGatewayWaitStates() {
        Map<String, InstancePlan> plans = classify(FlowableCorpus.supported());
        assertEquals(List.of("dispatch"), plans.get("s5").startActivities());
        assertEquals(List.of("ship"), plans.get("s6").startActivities());
        InstancePlan g = plans.get("s7");
        assertEquals(List.of("choose"), g.startActivities());
        assertEquals(List.of("CancelOrder"), g.messageNames());
        assertEquals(List.of(new TimerState("chooseTimer", millis("2026-12-02T09:00:00Z"), null)), g.timers());
    }

    @Test
    void suspendedInstanceStaysSuspended() {
        InstancePlan p = classify(FlowableCorpus.supported()).get("s8");
        assertTrue(p.suspended());
        assertEquals(List.of("clerks"), p.tasks().get(0).candidateGroups());
        assertNull(p.tasks().get(0).assignee());
    }

    @Test
    void everyRejectedInstanceCarriesItsCode() {
        Map<String, InstancePlan> plans = classify(FlowableCorpus.rejected());
        assertEquals(FlowableCorpus.REJECTED.keySet(), plans.keySet());
        FlowableCorpus.REJECTED.forEach((id, code) -> {
            InstancePlan p = plans.get(id);
            assertEquals(InstancePlan.Status.REJECTED, p.status(), id);
            assertTrue(codes(p, Severity.REJECT).contains(code), id + " expected " + code + " in " + p.findings());
        });
    }

    @Test
    void ambiguousAndMissingLocalTimesAreNotGuessed() {
        Map<String, InstancePlan> plans = classify(FlowableCorpus.rejected());
        assertTrue(plans.get("r11").findings().stream().anyMatch(f -> f.message().contains("ambiguous (DST fold)")));
        assertTrue(plans.get("r12").findings().stream().anyMatch(f -> f.message().contains("DST gap")));
        assertTrue(plans.get("r12").timers().isEmpty(), "no timer is planned from an unresolved local time");
    }

    @Test
    void lowerRetriesThanTheDefaultMeansTheJobFailedBefore() {
        FlowableSnapshot snap = new FlowableInventory(FlowableCorpus.supported(), FlowableCorpus.ZONE).read(FlowableCorpus.TENANT);
        Instance s5 = snap.instances().stream().filter(i -> i.id().equals("s5")).findFirst().orElseThrow();
        InstancePlan strict = new StateClassifier(FlowableCorpus.MAPPINGS, FlowableCorpus.model(), 5).classify(s5, FlowableCorpus.NOW);
        assertTrue(codes(strict, Severity.REJECT).contains(StateClassifier.FAILED_JOB), strict.findings().toString());
    }

    @Test
    void targetMismatchAndTaskListenersAreRejected() {
        FlowableSnapshot snap = new FlowableInventory(FlowableCorpus.supported(), FlowableCorpus.ZONE).read(FlowableCorpus.TENANT);
        Instance s1 = snap.instances().get(0);
        OperatonStateWriter.TargetModel listeners = new OperatonStateWriter.TargetModel() {
            @Override
            public java.util.Optional<String> definitionId(String key, int version, String tenantId) {
                return FlowableCorpus.model().definitionId(key, version, tenantId);
            }

            @Override
            public OperatonStateWriter.ActivityFacts activity(String definitionId, String activityId) {
                return "review".equals(activityId)
                        ? new OperatonStateWriter.ActivityFacts(OperatonStateWriter.ActivityKind.USER_TASK, false, true)
                        : FlowableCorpus.model().activity(definitionId, activityId);
            }
        };
        InstancePlan p = new StateClassifier(FlowableCorpus.MAPPINGS, listeners, 3).classify(s1, FlowableCorpus.NOW);
        assertTrue(codes(p, Severity.REJECT).contains(StateClassifier.TASK_LISTENERS), p.findings().toString());

        OperatonStateWriter.TargetModel asyncTask = new OperatonStateWriter.TargetModel() {
            @Override
            public java.util.Optional<String> definitionId(String key, int version, String tenantId) {
                return FlowableCorpus.model().definitionId(key, version, tenantId);
            }

            @Override
            public OperatonStateWriter.ActivityFacts activity(String definitionId, String activityId) {
                return "review".equals(activityId)
                        ? new OperatonStateWriter.ActivityFacts(OperatonStateWriter.ActivityKind.USER_TASK, true, false)
                        : FlowableCorpus.model().activity(definitionId, activityId);
            }
        };
        InstancePlan q = new StateClassifier(FlowableCorpus.MAPPINGS, asyncTask, 3).classify(s1, FlowableCorpus.NOW);
        assertTrue(codes(q, Severity.REJECT).contains(StateClassifier.TARGET_ACTIVITY_MISMATCH), q.findings().toString());
    }

    @Test
    void missingTargetDeploymentRejectsEverything() {
        FlowableSnapshot snap = new FlowableInventory(FlowableCorpus.supported(), FlowableCorpus.ZONE).read(FlowableCorpus.TENANT);
        StateClassifier c = new StateClassifier(List.of(new DefinitionMapping("order", 1, "order", 2, Map.of())),
                FlowableCorpus.model(), 3);
        for (Instance i : snap.instances()) {
            InstancePlan p = c.classify(i, FlowableCorpus.NOW);
            assertTrue(codes(p, Severity.REJECT).contains(StateClassifier.TARGET_DEFINITION_MISSING), i.id());
            assertTrue(p.startActivities().isEmpty());
        }
    }

    @Test
    void planSurvivesItsJsonRoundTrip() {
        InstancePlan p = classify(FlowableCorpus.supported()).get("s1");
        String json = FlowableMigrationStore.write(p);
        assertEquals(p, FlowableMigrationStore.read(json, new com.fasterxml.jackson.core.type.TypeReference<InstancePlan>() { }));
    }
}
