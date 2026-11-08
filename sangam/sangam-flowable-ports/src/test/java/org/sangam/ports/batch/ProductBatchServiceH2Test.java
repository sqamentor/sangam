// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.batch.ProductBatchService.BatchStateException;
import org.sangam.ports.batch.ProductBatchService.State;
import org.sangam.ports.support.EngineTx;

/** Product batches: lifecycle, four eyes, failure counting, retry; standard handlers. */
class ProductBatchServiceH2Test {

    private static final String WAIT = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <message id="m" name="go"/>
              <process id="wait" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="w"/>
                <intermediateCatchEvent id="w"><messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                <sequenceFlow id="f2" sourceRef="w" targetRef="t"/><userTask id="t"/>
              </process>
            </definitions>""";

    private PortsH2Engine h2;
    private ProductBatchService batches;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        batches = new ProductBatchService(Dialect.POSTGRESQL, EngineTx.transactions(h2.engine), 3,
                ProductBatchService.FourEyesPolicy.of(Set.of("sensitive"), 100));
        batches.register("flaky", (tenant, ref, cfg) -> {
            calls.incrementAndGet();
            if (ref.startsWith("bad")) {
                throw new IllegalStateException("no " + ref);
            }
            return "ok " + ref;
        });
        batches.register("sensitive", (tenant, ref, cfg) -> "done");
        StandardBatchHandlers.registerAll(batches, h2.engine);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private ProductBatchService.Batch get(String id) {
        return batches.batch(id).orElseThrow();
    }

    @Test
    void runsItemsCountsFailuresAndRetries() {
        String id = batches.create("t1", "flaky", List.of("a", "bad1", "b"), Map.of(), "maker");
        assertEquals(State.RUNNING, get(id).state());
        assertEquals(3, batches.runOnce(10));
        ProductBatchService.Batch b = get(id);
        assertEquals(State.COMPLETED_WITH_FAILURES, b.state());
        assertEquals(2, b.done());
        assertEquals(1, b.failed());
        assertEquals("IllegalStateException: no bad1", batches.items(id, "FAILED").get(0).result());

        assertEquals(1, batches.retryFailed(id));
        assertEquals(State.RUNNING, get(id).state());
        assertEquals(0, get(id).failed());
        batches.runOnce(10);
        assertEquals(1, get(id).failed());
        batches.retryFailed(id);
        batches.runOnce(10);
        assertEquals(0, batches.retryFailed(id), "attempt limit 3 reached");
        b = get(id);
        assertEquals(b.total(), b.done() + b.failed());
    }

    @Test
    void retryOfAPausedBatchKeepsTheCountersConsistent() {
        String id = batches.create("t1", "flaky", List.of("bad1", "x"), Map.of(), "maker");
        batches.runOnce(1);                  // bad1 fails
        batches.pause(id);
        assertEquals(1, batches.retryFailed(id));
        assertEquals(State.PAUSED, get(id).state(), "still paused");
        assertEquals(0, get(id).failed(), "the retried item is no longer counted as failed");
        assertEquals(0, batches.runOnce(10), "paused: nothing runs");
        batches.resume(id);
        batches.runOnce(10);
        ProductBatchService.Batch b = get(id);
        assertEquals(State.COMPLETED_WITH_FAILURES, b.state());
        assertEquals(b.total(), b.done() + b.failed(), "done + failed == total");
    }

    @Test
    void retryOfACancelledBatchIsRefused() {
        String id = batches.create("t1", "flaky", List.of("bad1", "x"), Map.of(), "maker");
        batches.runOnce(1);
        batches.cancel(id);
        assertThrows(BatchStateException.class, () -> batches.retryFailed(id));
        assertEquals(0, batches.items(id, "PENDING").size(), "no orphan PENDING items in a cancelled batch");
    }

    @Test
    void fourEyesBatchWaitsForADifferentApprover() {
        String id = batches.create("t1", "sensitive", List.of("a"), Map.of(), "maker", "year-end fix");
        assertEquals(State.PENDING_APPROVAL, get(id).state());
        assertEquals(0, batches.runOnce(10));
        assertThrows(BatchStateException.class, () -> batches.approve(id, "maker"));
        assertEquals(1, batches.awaitingApproval("t1").size());
        assertEquals(State.RUNNING, batches.approve(id, "checker").state());
        assertThrows(BatchStateException.class, () -> batches.approve(id, "checker"));
        assertEquals(1, batches.runOnce(10));
        assertEquals(State.COMPLETED, get(id).state());

        String r = batches.create("t1", "sensitive", List.of("a"), Map.of(), "maker");
        assertEquals(State.REJECTED, batches.reject(r, "checker", "no").state());
        assertEquals("CANCELLED", batches.items(r, null).get(0).state());
        assertThrows(IllegalArgumentException.class, () -> batches.create("t1", "unknown", List.of("a"), Map.of(), "u"));
        assertThrows(IllegalArgumentException.class, () -> batches.create("t1", "flaky", List.of(), Map.of(), "u"));
    }

    @Test
    void pausedBatchIsSkippedAndCancelEndsPendingItems() {
        String id = batches.create("t1", "flaky", List.of("a", "b"), Map.of(), "maker");
        batches.pause(id);
        assertThrows(BatchStateException.class, () -> batches.pause(id));
        assertEquals(0, batches.runOnce(10));
        assertEquals(0, calls.get());
        assertEquals(State.CANCELLED, batches.cancel(id).state());
        assertEquals(2, batches.items(id, "CANCELLED").size());
        assertThrows(BatchStateException.class, () -> batches.cancel(id));
    }

    @Test
    void defaultTenantMessageCorrelateBatchDoesNotReachOtherTenants() {
        h2.engine.getRepositoryService().createDeployment().addString("w.bpmn", WAIT).deploy();
        h2.engine.getRepositoryService().createDeployment().tenantId("other").addString("w.bpmn", WAIT).deploy();
        h2.engine.getRuntimeService().createProcessInstanceByKey("wait").businessKey("BK-1").processDefinitionWithoutTenantId().execute();
        h2.engine.getRuntimeService().createProcessInstanceByKey("wait").businessKey("BK-1").processDefinitionTenantId("other").execute();

        String id = batches.create("", "message-correlate", List.of("BK-1"), Map.of("messageName", "go"), "maker");
        batches.runOnce(10);
        assertEquals("correlated 1", batches.items(id, "DONE").get(0).result());
        assertEquals(0, h2.engine.getTaskService().createTaskQuery().tenantIdIn("other").count(), "other tenant untouched");
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().withoutTenantId().count());
    }

    private static final String START = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <message id="m" name="go"/>
              <process id="starter" isExecutable="true">
                <startEvent id="s"><messageEventDefinition messageRef="m"/></startEvent>
                <sequenceFlow id="f1" sourceRef="s" targetRef="t"/><userTask id="t"/>
              </process>
            </definitions>""";

    @Test
    void correlateBatchNeverStartsAnInstanceUnlessAskedTo() {
        h2.engine.getRepositoryService().createDeployment().addString("s.bpmn", START).deploy();
        String id = batches.create("", "message-correlate", List.of("BK-9"), Map.of("messageName", "go"), "maker");
        batches.runOnce(10);
        assertEquals("correlated 0", batches.items(id, "DONE").get(0).result());
        assertEquals(0, h2.engine.getRuntimeService().createProcessInstanceQuery().count(),
                "a message start event is not fired by a correlation batch");
    }

    @Test
    void explicitStartGoesThroughTheUniqueStartGuard() {
        h2.engine.getRepositoryService().createDeployment().addString("s.bpmn", START).deploy();
        java.util.Map<String, String> rows = new java.util.HashMap<>();
        var guard = new org.sangam.core.ports.UniqueStartPort() {
            @Override
            public boolean claim(java.sql.Connection c, String t, String k, String bk, String source, String msg) {
                return rows.putIfAbsent(t + "|" + k + "|" + bk, "") == null;
            }

            @Override
            public void bind(java.sql.Connection c, String t, String k, String bk, String instanceId) {
                rows.put(t + "|" + k + "|" + bk, instanceId);
            }

            @Override
            public java.util.Optional<String> existingInstance(java.sql.Connection c, String t, String k, String bk) {
                return java.util.Optional.ofNullable(rows.get(t + "|" + k + "|" + bk));
            }
        };
        StandardBatchHandlers.registerAll(batches, h2.engine, guard);
        String id = batches.create("", "message-correlate", List.of("BK-9", "BK-9", "BK-10"),
                Map.of("messageName", "go", "start", true), "maker");
        batches.runOnce(10);
        List<String> results = batches.items(id, "DONE").stream().map(ProductBatchService.Item::result).toList();
        assertEquals(true, results.get(0).startsWith("started "));
        assertEquals("duplicate start", results.get(1));
        assertEquals(true, results.get(2).startsWith("started "));
        assertEquals(2, h2.engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey("starter").count());
        assertEquals(1, h2.engine.getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKey("BK-9").count());

        ProductBatchService plain = new ProductBatchService(Dialect.POSTGRESQL, EngineTx.transactions(h2.engine), 1);
        StandardBatchHandlers.registerAll(plain, h2.engine);
        String refused = plain.create("", "message-correlate", List.of("BK-11"), Map.of("messageName", "go", "start", true), "m");
        plain.runOnce(10);
        assertEquals(1, plain.batch(refused).orElseThrow().failed(), "start without a unique-start guard is refused");
    }

    @Test
    void taskCompleteGuardsTheAssigneeAndIsIdempotent() {
        h2.engine.getRepositoryService().createDeployment().addString("w.bpmn", WAIT).deploy();
        String pi = h2.engine.getRuntimeService().startProcessInstanceByKey("wait").getId();
        h2.engine.getRuntimeService().correlateMessage("go");
        String task = h2.engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId();
        h2.engine.getTaskService().setAssignee(task, "ravi");

        String wrong = batches.create("", "task-complete", List.of(task), Map.of("assignee", "asha"), "maker");
        batches.runOnce(10);
        assertEquals(1, get(wrong).failed(), "re-assigned task is not completed");
        String ok = batches.create("", "task-complete", List.of(task, task), Map.of("assignee", "ravi"), "maker");
        batches.runOnce(10);
        assertEquals(List.of("completed", "already completed"), batches.items(ok, "DONE").stream().map(ProductBatchService.Item::result).toList());
    }
}
