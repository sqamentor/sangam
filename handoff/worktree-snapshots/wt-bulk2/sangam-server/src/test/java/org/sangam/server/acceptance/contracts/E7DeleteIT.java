// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.api.EngineAdapter.InstanceView;
import org.sangam.core.api.StaleLeaseException;
import org.sangam.core.engine.SangamEngineAdapter;
import org.sangam.core.engine.SangamEngineAdapter.LeasedWork;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Tombstones;
import org.sangam.integration.sim.ifms.IfmsJitSimulator;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * E7 (SANGAM.md XI.4, VI.5, T11, T39) on Sangam: technical delete of a payment instance through the REST API
 * ({@code DELETE /api/v1/process-instances/{id}}) while its payment worker holds the lease; the worker's late result goes
 * through the real worker API ({@code /api/v1/work/{id}/complete|fail|outcome-unknown}), which checks the tombstone
 * first; the officer acts through the reconciliation console API.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E7DeleteIT extends ContractSupport {

    static final Duration LEASE = Duration.ofSeconds(60);
    final String T = REST_TENANT;

    @Autowired
    OperationLedger ledger;

    @Autowired
    ReconciliationQueue reconciliation;

    SangamEngineAdapter adapter;
    Tombstones tombstones;
    IfmsJitSimulator sim;
    Ifms ifms;
    Instant clock;

    @BeforeAll
    void setUp() {
        adapter = (SangamEngineAdapter) engine;
        tombstones = new Tombstones(dialect(), reconciliation, new AuditChain(dialect()));
        sim = new IfmsJitSimulator().start();
        ifms = new Ifms(sim);
        clock = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        engine.setClock(clock);
    }

    @AfterAll
    void tearDown() {
        engine.setClock(null);
        if (sim != null) {
            sim.close();
        }
    }

    private String toPayment(String scenario, String topic) {
        String key = key("e7-" + scenario);
        deploy(T, key + ".bpmn", Models.payment(key, topic));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("billId", key("bill-" + scenario));
        v.put("amount", 75_000L);
        v.put("beneficiaryAccount", "ACC-E7-" + scenario);
        return engine.startProcess(T, key, key("bk-" + scenario), v);
    }

    private PaymentWorker worker(String id, String topic) {
        return new PaymentWorker(adapter, ledger, tombstones, id, T, topic, ifms, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, LEASE);
    }

    private Rest restDelete(String pid, String reason) {
        return delete("/api/v1/process-instances/" + pid + "?reason=" + URLEncoder.encode(reason, StandardCharsets.UTF_8));
    }

    private long audit(String pid, String type) {
        return count("SELECT count(*) FROM AUDIT_EVENT WHERE chain_id = ? AND event_type = ?", pid, type);
    }

    private boolean deleted(String pid) {
        InstanceView v = engine.getInstance(pid);
        return (v == null || v.ended()) && count("SELECT count(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_ = ?", pid) == 0;
    }

    private static Map<String, Object> fenced(String workerId, ExternalWork w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workerId", workerId);
        m.put("lockVersion", w.lockVersion());
        m.put("attemptId", w.attemptId());
        return m;
    }

    @Test
    @Order(1)
    void e7_1_deleteDuringPaymentThenLateCompletion() {
        String topic = key("pay-e7-1");
        String pid = toPayment("1", topic);
        PaymentWorker w = worker("worker-1", topic);
        LeasedWork lw = w.fetch();
        assertNotNull(lw);
        ExternalWork work = lw.work();
        OperationLedger.Intent intent = w.intent(lw);
        Ifms.Result pay = w.dispatch(lw, intent);           // the payment happens at IFMS ...
        assertTrue(pay.accepted());

        Rest del = restDelete(pid, "technical delete: bill entered twice");   // ... meanwhile
        assertEquals(200, del.status(), del.body());
        assertTrue(deleted(pid));
        Tombstones.Tombstone ts = inEngineTx(c -> tombstones.find(c, T, work.id()).orElseThrow());
        assertEquals(Tombstones.State.CANCEL_REQUESTED, ts.state());
        assertEquals(work.lockVersion(), ts.lockVersion());
        assertEquals(intent.operationId(), ts.operationId());
        assertEquals(1, audit(pid, "INSTANCE_DELETED"));
        assertEquals(1, count("SELECT count(*) FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                        + " WHERE e.chain_id = ? AND e.event_type = 'INSTANCE_DELETED' AND p.payload LIKE ?",
                pid, "%" + intent.operationId() + "%"), "INSTANCE_DELETED lists the open operationId");

        // the worker reports success after the delete, through the worker API
        Map<String, Object> complete = fenced("worker-1", work);
        complete.put("variables", Map.of("paymentRef", pay.paymentRef()));
        Rest late = post("/api/v1/work/" + work.id() + "/complete", complete);
        assertEquals(410, late.status(), late.body());
        assertEquals("GONE_INSTANCE_DELETED", late.code());
        String itemId = late.json().path("reconciliationItemId").asText(null);
        assertNotNull(itemId, late.body());
        ReconciliationQueue.Item item = inEngineTx(c -> reconciliation.get(c, Long.parseLong(itemId)).orElseThrow());
        assertEquals(ReconciliationQueue.Kind.LATE_RESULT_AFTER_DELETE, item.kind());
        assertEquals(ReconciliationQueue.State.OPEN, item.state());
        assertEquals(intent.operationId(), item.operationId());
        assertEquals(1, audit(pid, "LATE_RESULT_AFTER_DELETE"));

        // the instance is never revived: no runtime rows, no work offered, retry refused on the item
        engine.executeDueJobs();
        assertTrue(deleted(pid));
        assertNull(worker("worker-2", topic).fetch());
        Rest retry = post("/api/v1/ops/reconciliation/" + itemId + "/resolve", Map.of("action", "RETRY_SAME_OPERATION",
                "evidence", "operator retry", "reason", "retry"));
        assertEquals(422, retry.status(), "RETRY is impossible for a deleted instance: " + retry.body());
        Rest confirm = post("/api/v1/ops/reconciliation/" + itemId + "/resolve", Map.of("action", "CONFIRM_SUCCESS",
                "evidence", "IFMS shows payment " + pay.paymentRef(), "result", Map.of("paymentRef", pay.paymentRef(),
                        "recovery", "refund requested"), "reason", "paid after delete"));
        assertEquals(200, confirm.status(), confirm.body());
        assertEquals("RESOLVED", confirm.json().path("state").asText());
        assertTrue(deleted(pid));
        assertEquals(1, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT, intent.operationId().toString()));

        // the raw adapter, without the tombstone check: the lease row went with the delete (F-7), so NO_LEASE
        StaleLeaseException raw = assertThrows(StaleLeaseException.class, () -> engine.completeExternal(work.id(),
                "worker-1", work.lockVersion(), Map.of("paymentRef", pay.paymentRef())));
        assertEquals(StaleLeaseException.Reason.NO_LEASE, raw.reason());
        assertEquals(0, count("SELECT count(*) FROM SANGAM_LEASE WHERE work_id = ?", work.id()));
        assertTrue(deleted(pid));
    }

    @Test
    @Order(2)
    void e7_2_tombstonedLeaseExpiresToUnknownOutcome() throws Exception {
        String topic = key("pay-e7-2");
        String pid = toPayment("2", topic);
        PaymentWorker w = worker("worker-1", topic);
        LeasedWork lw = w.fetch();
        OperationLedger.Intent intent = w.intent(lw);
        w.dispatch(lw, intent);
        clock = clock.plus(LEASE.minusMillis(400));          // 400 ms of the lease remain at the delete
        engine.setClock(clock);
        Rest del = restDelete(pid, "technical delete");
        assertEquals(200, del.status(), del.body());
        Thread.sleep(800);                                    // the tombstone lease end is on the DB clock
        int swept = inEngineTx(tombstones::sweepExpiredLeases);
        assertEquals(1, swept);
        Tombstones.Tombstone ts = inEngineTx(c -> tombstones.find(c, T, lw.work().id()).orElseThrow());
        assertEquals(Tombstones.State.UNKNOWN_OUTCOME, ts.state());
        List<ReconciliationQueue.Item> open = inEngineTx(c -> reconciliation.listOpen(c, T)).stream()
                .filter(i -> intent.operationId().equals(i.operationId())).toList();
        assertEquals(1, open.size());
        assertEquals(ReconciliationQueue.Kind.UNKNOWN_OUTCOME, open.get(0).kind());
        assertEquals(OperationLedger.State.UNKNOWN_OUTCOME, inEngineTx(c -> ledger.find(c, intent.operationId()).orElseThrow()).state());
        assertTrue(deleted(pid));
    }

    @Test
    @Order(3)
    void e7_3_lateFailAndUnknownAfterDelete() {
        String topic = key("pay-e7-3");
        String pid = toPayment("3", topic);
        PaymentWorker w = worker("worker-1", topic);
        LeasedWork lw = w.fetch();
        ExternalWork work = lw.work();
        UUID op = w.intent(lw).operationId();
        Rest del = restDelete(pid, "technical delete");
        assertEquals(200, del.status(), del.body());

        Map<String, Object> fail = fenced("worker-1", work);
        fail.put("errorMessage", "IFMS 503");
        fail.put("retries", 0);
        Rest g1 = post("/api/v1/work/" + work.id() + "/fail", fail);
        Map<String, Object> unknown = fenced("worker-1", work);
        unknown.put("detail", "timeout");
        Rest g2 = post("/api/v1/work/" + work.id() + "/outcome-unknown", unknown);
        assertEquals(410, g1.status(), g1.body());
        assertNull(g1.json().path("reconciliationItemId").asText(null), "no effect reported: audited, no item");
        assertEquals(410, g2.status(), g2.body());
        assertNotNull(g2.json().path("reconciliationItemId").asText(null), "outcome unknown: item opened");
        assertEquals(2, audit(pid, "LATE_RESULT_AFTER_DELETE"));
        assertTrue(deleted(pid));
        assertFalse(inEngineTx(c -> reconciliation.listOpen(c, T)).stream()
                .filter(i -> op.equals(i.operationId())).toList().isEmpty());
    }
}
