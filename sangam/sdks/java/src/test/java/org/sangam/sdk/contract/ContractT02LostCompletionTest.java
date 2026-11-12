// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.sangam.sdk.testing.SangamTestEngine;
import org.sangam.sdk.worker.OutcomeUnknownException;
import org.sangam.sdk.worker.SangamWorker;
import org.sangam.sdk.worker.TopicSubscription;

/**
 * T02 (SANGAM.md II §16, VI.3 rules 4-5): a crash or lost answer after the downstream effect gives a safe recovery
 * (one completion) or an explicit unknown outcome, never a duplicate effect.
 */
class ContractT02LostCompletionTest {

    @Test
    void t02_lostCompleteResponseIsRetriedWithTheSameKeyAndCompletesOnce() throws Exception {
        try (SangamTestEngine engine = SangamTestEngine.start()) {
            String id = engine.addWork("pay", Map.of());
            engine.loseNextResponse("complete");
            AtomicInteger effects = new AtomicInteger();
            List<ContractSupport.Log> logs = ContractSupport.logs();
            SangamWorker w = ContractSupport.worker(engine, "w1", logs).subscribe(TopicSubscription.topic("pay", ctx -> {
                effects.incrementAndGet();
                return Map.of("paymentRef", "PAY-1");
            }).lockDuration(Duration.ofMinutes(1)).build()).build().start();
            try {
                ContractSupport.until(() -> engine.callsOf("complete").size() >= 2, "complete retried");
                ContractSupport.until(() -> ContractSupport.logged(logs, "work.complete"), "completion reported");
            } finally {
                w.close();
            }
            var completes = engine.callsOf("complete");
            assertEquals(completes.get(0).idempotencyKey(), completes.get(1).idempotencyKey(),
                    "the retry reuses the Idempotency-Key (R41)");
            assertEquals(completes.get(0).body(), completes.get(1).body(), "the retry is the identical request");
            assertEquals(1, effects.get(), "the handler (the downstream effect) ran once");
            assertEquals(1, engine.completions(id), "exactly one completion");
            assertEquals("COMPLETED", engine.state(id));
            assertEquals("SUCCEEDED", engine.operationState(id));
        }
    }

    @Test
    void t02_unknownDownstreamOutcomeOpensAReconciliationItemAndIsNeverReoffered() throws Exception {
        try (SangamTestEngine engine = SangamTestEngine.start()) {
            String id = engine.addWork("pay", Map.of());
            AtomicInteger runs = new AtomicInteger();
            List<ContractSupport.Log> logs = ContractSupport.logs();
            SangamWorker w = ContractSupport.worker(engine, "w1", logs).subscribe(TopicSubscription.topic("pay", ctx -> {
                runs.incrementAndGet();
                throw new OutcomeUnknownException("bank timed out after the debit request");
            }).lockDuration(Duration.ofMinutes(1)).build()).build().start();
            try {
                engine.awaitState(id, "UNKNOWN_OUTCOME", Duration.ofSeconds(10));
                engine.advance(Duration.ofHours(1));
                Thread.sleep(300); // the worker keeps polling; nothing may be handed out
            } finally {
                w.close();
            }
            assertEquals(1, runs.get(), "never re-offered (VI.3 rule 5)");
            assertEquals(1, engine.grants(id));
            assertEquals("UNKNOWN_OUTCOME", engine.state(id));
            assertTrue(engine.reconciliationItems().stream()
                    .anyMatch(r -> r.workId().equals(id) && r.kind().equals("UNKNOWN_OUTCOME") && r.state().equals("OPEN")));
        }
    }

    @Test
    void t02_reconciliationRetryReusesTheOperationId() throws Exception {
        try (SangamTestEngine engine = SangamTestEngine.start()) {
            String id = engine.addWork("pay", Map.of());
            List<String> ops = new java.util.concurrent.CopyOnWriteArrayList<>();
            AtomicInteger runs = new AtomicInteger();
            SangamWorker w = ContractSupport.worker(engine, "w1", ContractSupport.logs()).subscribe(TopicSubscription.topic("pay", ctx -> {
                ops.add(ctx.operationId());
                if (runs.incrementAndGet() == 1) throw new OutcomeUnknownException("unknown");
                return Map.of();
            }).lockDuration(Duration.ofMinutes(1)).build()).build().start();
            try {
                engine.awaitState(id, "UNKNOWN_OUTCOME", Duration.ofSeconds(10));
                String item = engine.reconciliationItems().get(0).id();
                engine.resolveReconciliation(item, "RETRY");
                engine.awaitState(id, "COMPLETED", Duration.ofSeconds(10));
            } finally {
                w.close();
            }
            assertEquals(2, ops.size());
            assertEquals(ops.get(0), ops.get(1), "the retry uses the same operationId (R36)");
        }
    }
}
