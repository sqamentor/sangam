// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SL-16: the worker API records the operation ledger of an external (SDK/REST) worker itself, keyed by the engine
 * operationId (SL-08): intent when the work is leased, the outcome with the engine command that ends the work. Without
 * it an SDK worker's operation never reached the ledger (only server-side connectors wrote it).
 */
class WorkApiLedgerIT extends ContractSupport {

    final String T = REST_TENANT;

    @Autowired
    OperationLedger ledger;

    @Autowired
    ReconciliationQueue reconciliation;

    private String start(String scenario, String topic) {
        String key = key("sl16-" + scenario);
        deploy(T, key + ".bpmn", Models.payment(key, topic));
        return engine.startProcess(T, key, key("bk-" + scenario), Map.of("billId", key("bill-" + scenario)));
    }

    private JsonNode fetch(String topic) {
        Rest r = post("/api/v1/work/fetch-and-lock", Map.of("workerId", "sl16-worker", "maxTasks", 1,
                "topics", List.of(Map.of("name", topic, "lockDurationMs", 60_000))));
        assertEquals(200, r.status(), r.body());
        assertEquals(1, r.json().size(), "one work item: " + r.body());
        return r.json().get(0);
    }

    private Map<String, Object> lease(JsonNode w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workerId", "sl16-worker");
        m.put("lockVersion", w.path("lockVersion").asLong());
        m.put("attemptId", w.path("attemptId").asText());
        return m;
    }

    private OperationLedger.Entry entry(JsonNode w) {
        UUID op = UUID.fromString(w.path("operationId").asText());
        return inEngineTx(c -> ledger.find(c, op)).orElseThrow(() -> new AssertionError("no ledger row for " + op));
    }

    @Test
    void fetchRecordsTheIntentAndCompleteTheOutcomeWithTheReference() {
        String topic = key("sl16-pay");
        String pid = start("ok", topic);
        JsonNode w = fetch(topic);

        OperationLedger.Entry intent = entry(w);
        assertEquals(OperationLedger.State.INTENT, intent.state(), "intent recorded at lease, before any dispatch");
        assertEquals(1, intent.attempts());
        assertEquals(pid, intent.rootId());
        assertEquals(w.path("activityInstanceId").asText(), intent.activityInstanceId());
        assertEquals(w.path("lockVersion").asLong(), intent.lockVersion());
        assertEquals(w.path("attemptId").asText(), intent.attemptId());
        assertEquals(List.of(w.path("id").asText()), strings("SELECT engine_work_id FROM OPERATION_LEDGER WHERE operation_id = ?",
                intent.operationId().toString()), "A-14 binding of the engine work");
        assertEquals("INTENT", w.path("operation").path("state").asText(), w.toString());
        assertTrue(w.path("operation").path("mayDispatch").asBoolean());
        assertEquals("C_NEITHER", w.path("operation").path("capabilityClass").asText(), "A-8 default");

        Map<String, Object> done = lease(w);
        done.put("variables", Map.of("paymentRef", "REF-77"));
        done.put("outcome", Map.of("reference", "REF-77"));
        Rest first = post("/api/v1/work/" + w.path("id").asText() + "/complete", done);
        assertEquals(204, first.status(), first.body());
        OperationLedger.Entry settled = entry(w);
        assertEquals(OperationLedger.State.SUCCEEDED, settled.state());
        assertTrue(settled.outcome().contains("\"providerRef\":\"REF-77\""), settled.outcome());
        assertTrue(engine.getInstance(pid).ended());

        // a retried identical completion is answered as the first one and changes nothing
        Rest again = post("/api/v1/work/" + w.path("id").asText() + "/complete", done);
        assertEquals(204, again.status(), again.body());
        assertEquals(settled, entry(w));
        assertEquals(1, count("SELECT count(*) FROM OPERATION_LEDGER WHERE root_id = ?", pid));
        // a different result for the same lease is still stale
        done.put("outcome", Map.of("reference", "REF-OTHER"));
        Rest other = post("/api/v1/work/" + w.path("id").asText() + "/complete", done);
        assertEquals(409, other.status(), other.body());
        assertEquals(settled, entry(w));
    }

    @Test
    void failWithRetriesKeepsTheIntentAndFinalFailureIsFailed() {
        String topic = key("sl16-fail");
        start("fail", topic);
        JsonNode w1 = fetch(topic);
        Map<String, Object> retry = lease(w1);
        retry.put("errorMessage", "downstream refused the connection");
        retry.put("retries", 2);
        retry.put("retryTimeoutMs", 0);
        assertEquals(204, post("/api/v1/work/" + w1.path("id").asText() + "/fail", retry).status());
        assertEquals(OperationLedger.State.INTENT, entry(w1).state(), "retryable: same operation, next attempt");

        JsonNode w2 = fetch(topic);
        assertEquals(w1.path("operationId").asText(), w2.path("operationId").asText());
        assertEquals(2, entry(w2).attempts());
        assertEquals(2, w2.path("operation").path("attempts").asInt());
        Map<String, Object> last = lease(w2);
        last.put("errorMessage", "giving up");
        last.put("retries", 0);
        assertEquals(204, post("/api/v1/work/" + w2.path("id").asText() + "/fail", last).status());
        OperationLedger.Entry failed = entry(w2);
        assertEquals(OperationLedger.State.FAILED, failed.state());
        assertTrue(failed.outcome().contains("TECHNICAL_FAILURE"), failed.outcome());
    }

    @Test
    void outcomeUnknownGoesToReconciliationWithTheOperation() {
        String topic = key("sl16-unknown");
        start("unknown", topic);
        JsonNode w = fetch(topic);
        Map<String, Object> body = lease(w);
        body.put("detail", "timeout after send");
        Rest r = post("/api/v1/work/" + w.path("id").asText() + "/outcome-unknown", body);
        assertEquals(202, r.status(), r.body());
        assertEquals(w.path("operationId").asText(), r.json().path("operationId").asText());
        assertEquals(OperationLedger.State.UNKNOWN_OUTCOME, entry(w).state());
        UUID op = UUID.fromString(w.path("operationId").asText());
        List<ReconciliationQueue.Item> items = inEngineTx(c -> reconciliation.listOpen(c, T)).stream()
                .filter(i -> op.equals(i.operationId())).toList();
        assertEquals(1, items.size());
        assertEquals(w.path("id").asText(), items.get(0).taskId());
        assertNotNull(items.get(0).rootId());
        assertFalse(items.get(0).details().isEmpty());
    }
}
