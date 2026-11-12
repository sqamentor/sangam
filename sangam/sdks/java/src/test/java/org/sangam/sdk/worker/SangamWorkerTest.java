// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Worker runtime: SL-16 stored-outcome completion, irreversibility from the subscription only, report retries. */
class SangamWorkerTest {

    private static final String SUCCEEDED = "\"operation\":{\"state\":\"SUCCEEDED\",\"attempts\":2,"
            + "\"capabilityClass\":\"A_IDEMPOTENT_WITH_LOOKUP\",\"mayDispatch\":false,\"storedOutcome\":{\"output\":"
            + "{\"paymentRef\":\"PAY-77\",\"amount\":{\"type\":\"Decimal\",\"value\":\"12.50\"}},\"providerRef\":\"PAY-77\","
            + "\"duplicate\":false}}";

    /** Runs one leased item through a started worker and returns its report call. */
    private static FakeWorkServer.Call runOne(FakeWorkServer server, TopicSubscription sub, String item) throws Exception {
        server.enqueue(item);
        SangamWorker worker = server.worker().subscribe(sub).build().start();
        try {
            return server.waitFor(c -> FakeWorkServer.REPORTS.contains(c.action()));
        } finally {
            worker.close();
        }
    }

    private static TopicSubscription.Builder sub(TaskHandler h) {
        return TopicSubscription.topic("pay", h).lockDuration(Duration.ofMinutes(1));
    }

    @Test
    void completesFromTheStoredOutcomeWithoutCallingTheHandler() throws Exception {
        try (FakeWorkServer server = new FakeWorkServer()) {
            AtomicInteger calls = new AtomicInteger();
            FakeWorkServer.Call r = runOne(server, sub(ctx -> {
                calls.incrementAndGet();
                return Map.of("paymentRef", "NEW");
            }).build(), FakeWorkServer.work(SUCCEEDED));
            assertEquals(0, calls.get());
            assertEquals("complete", r.action());
            assertEquals("op-1:complete:3", r.idempotencyKey());
            assertEquals(Map.of("paymentRef", "PAY-77", "amount", Map.of("type", "Decimal", "value", "12.50")),
                    r.body().get("variables"));
            assertEquals(Map.of("reference", "PAY-77"), r.body().get("outcome"));
            assertTrue(server.logged("work.replayed"));
        }
    }

    @Test
    void runsTheHandlerWhileTheOperationMayBeDispatched() throws Exception {
        try (FakeWorkServer server = new FakeWorkServer()) {
            FakeWorkServer.Call r = runOne(server, sub(ctx -> Map.of("attempts", ctx.operation().attempts())).build(),
                    FakeWorkServer.work("\"operation\":{\"state\":\"INTENT\",\"attempts\":2,\"mayDispatch\":true}"));
            assertEquals(Map.of("attempts", 2), r.body().get("variables"));
            assertFalse(r.body().containsKey("outcome"));
        }
    }

    @Test
    void anUnexpectedErrorFailsWithRetriesUnlessTheSubscriptionIsIrreversible() throws Exception {
        try (FakeWorkServer server = new FakeWorkServer()) {
            FakeWorkServer.Call r = runOne(server, sub(ctx -> {
                throw new IllegalStateException("boom");
            }).retryBackoff(new Backoff(Duration.ofMillis(10), Duration.ofMillis(10), 1.0, 0.0)).build(),
                    FakeWorkServer.work(null));
            assertEquals("fail", r.action());
            assertEquals("boom", r.body().get("errorMessage"));
            assertEquals(2, r.body().get("retries"));
            assertEquals(10, r.body().get("retryTimeoutMs"));
        }
        try (FakeWorkServer server = new FakeWorkServer()) {
            FakeWorkServer.Call r = runOne(server, sub(ctx -> {
                throw new IllegalStateException("connection reset");
            }).irreversible(true).build(), FakeWorkServer.work(null));
            assertEquals("outcome-unknown", r.action());
            assertTrue(String.valueOf(r.body().get("detail")).startsWith("unexpected error on irreversible topic"));
        }
    }

    @Test
    void aStrayEffectFieldOnTheWorkItemIsIgnored() throws Exception {
        // the server sends no effect (WorkPort.ExternalWork); irreversibility comes from the subscription only
        try (FakeWorkServer server = new FakeWorkServer()) {
            FakeWorkServer.Call r = runOne(server, sub(ctx -> {
                throw new IllegalStateException("boom");
            }).build(), FakeWorkServer.work("\"effect\":\"irreversible\""));
            assertEquals("fail", r.action());
        }
    }

    @Test
    void retryableAnswersAreRetriedWithTheSameKeyAndStaleLeaseIsNot() throws Exception {
        try (FakeWorkServer server = new FakeWorkServer()) {
            server.reply("complete", 503, "{\"title\":\"busy\"}").reply("complete", 503, null).reply("complete", 204, null);
            runOne(server, sub(ctx -> Map.of()).build(), FakeWorkServer.work(null));
            FakeWorkServer.until(() -> server.callsOf("complete").size() == 3);
            assertEquals(List.of("op-1:complete:3", "op-1:complete:3", "op-1:complete:3"),
                    server.callsOf("complete").stream().map(FakeWorkServer.Call::idempotencyKey).toList());
        }
        try (FakeWorkServer server = new FakeWorkServer()) {
            server.reply("complete", 409, "{\"code\":\"STALE_LEASE\"}");
            runOne(server, sub(ctx -> Map.of()).build(), FakeWorkServer.work(null));
            FakeWorkServer.until(() -> server.logged("work.abandoned"));
            assertEquals(1, server.callsOf("complete").size());
        }
    }

    @Test
    void anItemWithoutLockVersionNeverStarts() throws Exception {
        try (FakeWorkServer server = new FakeWorkServer()) {
            server.enqueue("{\"id\":\"wk-1\",\"topic\":\"pay\",\"attemptId\":\"att-1\",\"operationId\":\"op-1\"}");
            AtomicInteger calls = new AtomicInteger();
            SangamWorker worker = server.worker().subscribe(sub(ctx -> {
                calls.incrementAndGet();
                return Map.of();
            }).build()).build().start();
            try {
                FakeWorkServer.until(() -> server.logged("work.protocol.violation"));
            } finally {
                worker.close();
            }
            assertEquals(0, calls.get());
            assertTrue(server.calls.stream().noneMatch(c -> FakeWorkServer.REPORTS.contains(c.action())));
        }
    }

    @Test
    void failRequestOmitsAbsentOptionals() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                .writeValueAsString(new org.sangam.sdk.model.FailWorkRequest("w", 1, null, null, null, null, null));
        assertEquals("{\"workerId\":\"w\",\"lockVersion\":1}", json);
        assertNull(new org.sangam.sdk.model.WorkOperation("SUCCEEDED", null, null, null, null).mayDispatch());
    }
}
