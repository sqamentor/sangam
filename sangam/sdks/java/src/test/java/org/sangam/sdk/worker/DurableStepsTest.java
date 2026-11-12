// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.error.SangamApiException;
import org.sangam.sdk.generated.model.WorkStep;
import org.sangam.sdk.log.SangamLogger;
import org.sangam.sdk.model.ExternalWork;
import org.sangam.sdk.model.FetchAndLockRequest;
import org.sangam.sdk.model.FetchTopic;
import org.sangam.sdk.model.TypedValue;
import org.sangam.sdk.model.UnlockRequest;
import org.sangam.sdk.testing.SangamTestEngine;

/** M-025 (R37): {@link TaskContext#step} against the test engine's step journal. */
class DurableStepsTest {

    static TaskContext lease(SangamTestEngine e, String worker, String topic) {
        SangamClient c = e.client();
        List<ExternalWork> l = c.work().fetchAndLock(new FetchAndLockRequest(worker,
                List.of(new FetchTopic(topic, 60_000L, null, null, null, null)), 1, 0L, null));
        assertEquals(1, l.size());
        return new TaskContext(l.get(0), worker, c, c.transport().json(), SangamLogger.noop());
    }

    /** Ends the lease without completing, so the next fetch is a new attempt of the same operation. */
    static void release(SangamTestEngine e, TaskContext ctx) {
        e.client().work().unlock(ctx.workId(), new UnlockRequest(ctx.workerId(), ctx.lockVersion(), ctx.attemptId()), null);
    }

    static List<WorkStep> steps(SangamTestEngine e, String workId) {
        return e.client().api().work().listWorkSteps(workId, null);
    }

    @Test
    void firstRunRecordsIntentThenSucceededAndAnotherAttemptReplaysWithoutCalling() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            String id = e.addWork("pay", Map.of());
            AtomicInteger calls = new AtomicInteger();
            TaskContext first = lease(e, "w1", "pay");
            String keySeen = first.step("charge", String.class, s -> {
                calls.incrementAndGet();
                assertNull(s.previousState());
                s.providerRef("PAY-1");
                return s.idempotencyKey();
            });
            assertEquals(first.operationId() + ":charge", keySeen);
            WorkStep rec = steps(e, id).get(0);
            assertEquals("SUCCEEDED", rec.state());
            assertEquals("PAY-1", rec.providerRef());
            assertEquals(first.attemptId(), rec.attemptId());

            release(e, first);
            TaskContext second = lease(e, "w2", "pay");
            String replayed = second.step("charge", String.class, s -> {
                calls.incrementAndGet();
                return "again";
            });
            assertEquals(keySeen, replayed, "the recorded result is replayed");
            assertEquals(1, calls.get(), "a SUCCEEDED step is never called again");
        }
    }

    @Test
    void replayUnwrapsTypedValues() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            e.addWork("pay", Map.of());
            TaskContext first = lease(e, "w1", "pay");
            first.step("amount", Object.class, s -> TypedValue.decimal("12.50"));
            release(e, first);
            TaskContext second = lease(e, "w1", "pay");
            assertEquals(new BigDecimal("12.50"), second.step("amount", BigDecimal.class, s -> BigDecimal.ONE));
        }
    }

    @Test
    void anUncertainFailureLeavesIntentAndTheDefaultReportsOutcomeUnknown() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            String id = e.addWork("pay", Map.of());
            TaskContext first = lease(e, "w1", "pay");
            IllegalStateException boom = new IllegalStateException("timeout after send");
            assertSame(boom, assertThrows(IllegalStateException.class,
                    () -> first.step("charge", String.class, s -> { throw boom; })));
            assertEquals("INTENT", steps(e, id).get(0).state(), "uncertain: the step stays INTENT");

            release(e, first);
            TaskContext second = lease(e, "w1", "pay");
            AtomicInteger calls = new AtomicInteger();
            OutcomeUnknownException u = assertThrows(OutcomeUnknownException.class,
                    () -> second.step("charge", String.class, s -> { calls.incrementAndGet(); return "x"; }));
            assertEquals(0, calls.get(), "class (c): never a blind re-issue (R37)");
            assertEquals("charge", u.variables().get("step"));
            assertEquals(second.operationId() + ":charge", u.variables().get("idempotencyKey"));
        }
    }

    @Test
    void anIdempotentCallRunsAgainWithTheSameKey() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            String id = e.addWork("pay", Map.of());
            TaskContext first = lease(e, "w1", "pay");
            List<String> keys = new ArrayList<>();
            assertThrows(IllegalStateException.class, () -> first.step("charge", String.class,
                    StepOptions.idempotentCall(), s -> { keys.add(s.idempotencyKey()); throw new IllegalStateException("x"); }));
            release(e, first);
            TaskContext second = lease(e, "w1", "pay");
            String out = second.step("charge", String.class, StepOptions.idempotentCall(), s -> {
                assertEquals("INTENT", s.previousState());
                keys.add(s.idempotencyKey());
                return "ok";
            });
            assertEquals("ok", out);
            assertEquals(keys.get(0), keys.get(1), "same idempotency key on the re-run");
            assertEquals("SUCCEEDED", steps(e, id).get(0).state());
        }
    }

    @Test
    void aLookupResolvesTheCrashWindowWithoutCallingAgain() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            String id = e.addWork("pay", Map.of());
            TaskContext first = lease(e, "w1", "pay");
            assertThrows(IllegalStateException.class,
                    () -> first.step("charge", String.class, s -> { throw new IllegalStateException("crash"); }));
            release(e, first);
            TaskContext second = lease(e, "w1", "pay");
            AtomicInteger calls = new AtomicInteger();
            String out = second.step("charge", String.class, StepOptions.withLookup(s -> {
                s.providerRef("PAY-7");
                return Optional.of("found");
            }), s -> { calls.incrementAndGet(); return "called"; });
            assertEquals("found", out);
            assertEquals(0, calls.get());
            WorkStep rec = steps(e, id).get(0);
            assertEquals("SUCCEEDED", rec.state());
            assertEquals("PAY-7", rec.providerRef());
        }
    }

    @Test
    void anEmptyLookupMeansNoEffectAndTheStepRunsAgain() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            e.addWork("pay", Map.of());
            TaskContext first = lease(e, "w1", "pay");
            assertThrows(IllegalStateException.class,
                    () -> first.step("charge", String.class, s -> { throw new IllegalStateException("crash"); }));
            release(e, first);
            TaskContext second = lease(e, "w1", "pay");
            assertEquals("called", second.step("charge", String.class,
                    StepOptions.withLookup(s -> Optional.empty()), s -> "called"));
        }
    }

    @Test
    void aLookupErrorPropagatesAndTheStepStaysIntent() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            String id = e.addWork("pay", Map.of());
            TaskContext first = lease(e, "w1", "pay");
            assertThrows(IllegalStateException.class,
                    () -> first.step("charge", String.class, s -> { throw new IllegalStateException("crash"); }));
            release(e, first);
            TaskContext second = lease(e, "w1", "pay");
            assertThrows(java.io.IOException.class, () -> second.step("charge", String.class,
                    StepOptions.<String>withLookup(s -> { throw new java.io.IOException("lookup down"); }), s -> "x"));
            assertEquals("INTENT", steps(e, id).get(0).state());
        }
    }

    @Test
    void noEffectRecordsFailedAndALaterAttemptRunsAgain() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            String id = e.addWork("pay", Map.of());
            TaskContext first = lease(e, "w1", "pay");
            assertThrows(StepNoEffectException.class, () -> first.step("charge", String.class,
                    s -> { throw new StepNoEffectException("card declined"); }));
            WorkStep rec = steps(e, id).get(0);
            assertEquals("FAILED", rec.state());
            assertEquals("card declined", rec.error());
            release(e, first);
            TaskContext second = lease(e, "w1", "pay");
            assertEquals("ok", second.step("charge", String.class, s -> {
                assertEquals("FAILED", s.previousState());
                return "ok";
            }));
        }
    }

    @Test
    void aStaleLeaseMarksTheLeaseLostAndCancelsTheHandler() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            e.addWork("pay", Map.of());
            TaskContext first = lease(e, "w1", "pay");
            release(e, first);   // lockVersion + 1: the context's lease is stale now
            AtomicInteger calls = new AtomicInteger();
            assertThrows(TaskCancelledException.class,
                    () -> first.step("charge", String.class, s -> { calls.incrementAndGet(); return "x"; }));
            assertTrue(first.isLeaseLost());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void stepsAreListedInRecordingOrder() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            String id = e.addWork("pay", Map.of());
            TaskContext ctx = lease(e, "w1", "pay");
            ctx.step("reserve", String.class, s -> "r");
            ctx.step("charge", String.class, s -> "c");
            ctx.step("notify", Void.class, s -> null);
            assertEquals(List.of("reserve", "charge", "notify"), steps(e, id).stream().map(WorkStep::name).toList());
        }
    }

    @Test
    void stepNamesAreValidatedBeforeAnyCall() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            e.addWork("pay", Map.of());
            TaskContext ctx = lease(e, "w1", "pay");
            assertThrows(IllegalArgumentException.class, () -> ctx.step("", String.class, s -> "x"));
            assertThrows(IllegalArgumentException.class, () -> ctx.step("x".repeat(129), String.class, s -> "x"));
            assertThrows(IllegalArgumentException.class, () -> ctx.step("tab\there", String.class, s -> "x"));
            assertTrue(e.calls().stream().noneMatch(c -> c.path().contains("/steps/")), "no journal call was made");
        }
    }

    @Test
    void theJournalRefusesAnOutcomeWithoutIntentAndAChangedSuccess() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            String id = e.addWork("pay", Map.of());
            TaskContext ctx = lease(e, "w1", "pay");
            var api = e.client().api().work();
            SangamApiException noIntent = assertThrows(SangamApiException.class, () -> api.recordWorkStep(id, "s",
                    new org.sangam.sdk.generated.model.WorkStepRequest("w1", ctx.lockVersion(), ctx.attemptId(),
                            "SUCCEEDED", "v", null, null), null));
            assertEquals(409, noIntent.status());
            assertEquals("STEP_INTENT_REQUIRED", noIntent.problem().code);
            ctx.step("s", String.class, s -> "v");
            // an identical repeat is a no-op; a different outcome is refused
            api.recordWorkStep(id, "s", new org.sangam.sdk.generated.model.WorkStepRequest("w1", ctx.lockVersion(),
                    ctx.attemptId(), "SUCCEEDED", "v", null, null), null);
            SangamApiException changed = assertThrows(SangamApiException.class, () -> api.recordWorkStep(id, "s",
                    new org.sangam.sdk.generated.model.WorkStepRequest("w1", ctx.lockVersion(), ctx.attemptId(),
                            "SUCCEEDED", "other", null, null), null));
            assertEquals("STEP_ALREADY_RECORDED", changed.problem().code);
        }
    }
}
