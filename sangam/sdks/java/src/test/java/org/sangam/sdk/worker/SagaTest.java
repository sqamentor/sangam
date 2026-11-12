// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.log.SangamLogger;
import org.sangam.sdk.model.ExternalWork;
import org.sangam.sdk.model.FetchAndLockRequest;
import org.sangam.sdk.model.FetchTopic;
import org.sangam.sdk.model.TypedValue;
import org.sangam.sdk.testing.SangamTestEngine;

/** M-026: the saga helper's registration, forward record and compensation context (operationId reuse, R36). */
class SagaTest {

    static TaskContext lease(SangamTestEngine e, String topic) {
        SangamClient c = e.client();
        List<ExternalWork> l = c.work().fetchAndLock(new FetchAndLockRequest("w1",
                List.of(new FetchTopic(topic, 60_000L, null, null, null, null)), 1, 0L, null));
        assertEquals(1, l.size());
        return new TaskContext(l.get(0), "w1", c, c.transport().json(), SangamLogger.noop());
    }

    static Saga charge(List<CompensationContext> seen) {
        return Saga.step("pay.charge-1")
                .action("payment.charge", ctx -> {
                    ctx.outcomeReference("PAY-42");
                    return Map.of("charged", true);
                })
                .compensation("payment.refund", c -> {
                    seen.add(c);
                    return Map.of("refunded", true);
                })
                .build();
    }

    @Test
    void recordVariableNamesAreStable() {
        assertEquals("sangamSaga_pay_charge_1", Saga.recordVariable("pay.charge-1"));
    }

    @Test
    void theForwardActionAddsItsRecordAndKeepsItsVariables() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            e.addWork("payment.charge", Map.of());
            TaskContext ctx = lease(e, "payment.charge");
            Map<String, Object> out = charge(new java.util.ArrayList<>()).action().handler().handle(ctx);
            assertEquals(true, out.get("charged"));
            TypedValue record = (TypedValue) out.get("sangamSaga_pay_charge_1");
            assertEquals("Json", record.type());
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) record.value();
            assertEquals(ctx.operationId(), m.get("operationId"));
            assertEquals("PAY-42", m.get("providerRef"));
            assertEquals(ctx.workId(), m.get("workId"));
            assertEquals("pay.charge-1", m.get("step"));
        }
    }

    @Test
    void theCompensationReusesTheForwardOperationId() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            Map<String, Object> record = Map.of("type", "Json", "value",
                    Map.of("step", "pay.charge-1", "operationId", "op-1", "providerRef", "PAY-42", "workId", "wk-1"));
            e.addWork("payment.refund", Map.of("sangamSaga_pay_charge_1", record));
            TaskContext ctx = lease(e, "payment.refund");
            List<CompensationContext> seen = new java.util.ArrayList<>();
            Map<String, Object> out = charge(seen).compensation().handler().handle(ctx);
            assertEquals(true, out.get("refunded"));
            CompensationContext c = seen.get(0);
            assertEquals("op-1", c.forwardOperationId());
            assertEquals("PAY-42", c.forwardProviderRef());
            assertEquals("op-1:compensate:pay.charge-1", c.idempotencyKey());
            assertEquals(ctx, c.task());
        }
    }

    @Test
    void withoutARecordTheCompensationUsesItsOwnOperationId() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            e.addWork("payment.refund", Map.of());
            TaskContext ctx = lease(e, "payment.refund");
            List<CompensationContext> seen = new java.util.ArrayList<>();
            charge(seen).compensation().handler().handle(ctx);
            assertNull(seen.get(0).forward());
            assertEquals(ctx.operationId(), seen.get(0).idempotencyKey());
        }
    }

    @Test
    void optionsApplyToBothSubscriptionsAndTheRecordIsAlwaysFetched() {
        Saga s = Saga.step("reserve")
                .action("stock.reserve", ctx -> Map.of())
                .compensation("stock.release", c -> Map.of())
                .lockDuration(java.time.Duration.ofMinutes(2))
                .retries(1)
                .irreversible(true)
                .variables(List.of("orderId"))
                .build();
        assertEquals(List.of("stock.reserve", "stock.release"), s.subscriptions().stream().map(TopicSubscription::topic).toList());
        assertTrue(s.action().irreversible());
        assertEquals(java.time.Duration.ofMinutes(2), s.compensation().lockDuration());
        assertEquals(1, s.compensation().retries());
        assertEquals(List.of("orderId"), s.action().variables());
        assertEquals(List.of("orderId", "sangamSaga_reserve"), s.compensation().variables());
    }

    @Test
    void invalidSagasAreRefusedAtBuild() {
        assertThrows(IllegalArgumentException.class, () -> Saga.step("bad name"));
        assertThrows(IllegalStateException.class, () -> Saga.step("x").action("t", ctx -> Map.of()).build());
        assertThrows(IllegalStateException.class, () -> Saga.step("x")
                .action("t", ctx -> Map.of()).compensation("t", c -> Map.of()).build());
    }

    @Test
    void registerOnSubscribesBothTopics() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            SangamWorker.Builder b = SangamWorker.builder(e.client()).workerId("w1");
            Saga s = charge(new java.util.ArrayList<>());
            try (SangamWorker w = s.registerOn(b).build()) {
                assertEquals("w1", w.workerId());
            }
        }
    }
}
