// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.error.GoneException;
import org.sangam.sdk.error.SangamApiException;
import org.sangam.sdk.error.StaleLeaseException;
import org.sangam.sdk.model.BpmnErrorRequest;
import org.sangam.sdk.model.CompleteWorkRequest;
import org.sangam.sdk.model.ExtendLockRequest;
import org.sangam.sdk.model.ExtendLockResult;
import org.sangam.sdk.model.ExternalWork;
import org.sangam.sdk.model.FailWorkRequest;
import org.sangam.sdk.model.FetchAndLockRequest;
import org.sangam.sdk.model.FetchTopic;
import org.sangam.sdk.model.OutcomeUnknownRequest;
import org.sangam.sdk.model.OutcomeUnknownResult;
import org.sangam.sdk.model.UnlockRequest;

/** M-021: the test engine's VI.3 state machine, driven through the SDK's WorkApi. */
class SangamTestEngineTest {

    static List<ExternalWork> fetch(SangamClient c, String worker, String topic, long lockMs) {
        return c.work().fetchAndLock(new FetchAndLockRequest(worker, List.of(new FetchTopic(topic, lockMs, null, null, null, null)),
                1, 0L, null));
    }

    static ExternalWork one(SangamClient c, String worker, String topic) {
        List<ExternalWork> l = fetch(c, worker, topic, 60_000);
        assertEquals(1, l.size(), "one item expected");
        return l.get(0);
    }

    @Test
    void grantBumpsLockVersionAndAttemptAndRecordsTheIntent() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            SangamClient c = e.client();
            String id = e.addWork("pay", Map.of("a", 1));
            ExternalWork w = one(c, "w1", "pay");
            assertEquals(id, w.id());
            assertEquals(1L, w.lockVersion());
            assertEquals(e.operationId(id), w.operationId());
            assertEquals("INTENT", w.operation().state());
            assertEquals(Boolean.TRUE, w.operation().mayDispatch());
            assertEquals("LOCKED", e.state(id));
            assertTrue(fetch(c, "w2", "pay", 60_000).isEmpty(), "LOCKED work is not handed out again");
            c.work().unlock(id, new UnlockRequest("w1", 1, w.attemptId()), "k-unlock");
            assertEquals(2L, e.lockVersion(id), "release ends the lease with lock_version + 1");
            ExternalWork again = one(c, "w2", "pay");
            assertEquals(3L, again.lockVersion());
            assertNotEquals(w.attemptId(), again.attemptId());
            assertEquals(w.operationId(), again.operationId(), "operationId is stable across leases (R36)");
            assertEquals(2, again.operation().attempts());
        }
    }

    @Test
    void validatesFetchRequests() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            SangamApiException ex = assertThrows(SangamApiException.class, () -> e.client().work().fetchAndLock(
                    new FetchAndLockRequest("", List.of(new FetchTopic("pay", null, null, null, null, null)), 1, 0L, null)));
            assertEquals(400, ex.status());
            assertEquals("VALIDATION_FAILED", ex.problem().code);
        }
    }

    @Test
    void fencingFollowsTheServerOrder() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            SangamClient c = e.client();
            String id = e.addWork("pay", Map.of());
            ExternalWork w = one(c, "w1", "pay");
            StaleLeaseException stale = assertThrows(StaleLeaseException.class, () -> c.work().complete(id,
                    new CompleteWorkRequest("w1", 99, w.attemptId(), Map.of(), null, null), "k1"));
            assertEquals("STALE_VERSION", stale.problem().extensions().get("reason"));
            assertEquals("https://sangam.dev/problems/stale-lease", stale.problem().type);
            assertEquals(id, stale.problem().extensions().get("workId"));
            StaleLeaseException owner = assertThrows(StaleLeaseException.class, () -> c.work().complete(id,
                    new CompleteWorkRequest("other", 1, w.attemptId(), Map.of(), null, null), "k2"));
            assertEquals("NOT_OWNER", owner.problem().extensions().get("reason"));
            StaleLeaseException attempt = assertThrows(StaleLeaseException.class, () -> c.work().complete(id,
                    new CompleteWorkRequest("w1", 1, "not-the-attempt", Map.of(), null, null), "k3"));
            assertEquals("ATTEMPT_MISMATCH", attempt.problem().extensions().get("reason"));
            assertEquals("LOCKED", e.state(id), "a stale call changes nothing");
            SangamApiException nf = assertThrows(SangamApiException.class, () -> c.work().complete("nope",
                    new CompleteWorkRequest("w1", 1, null, Map.of(), null, null), "k4"));
            assertEquals(404, nf.status());
        }
    }

    @Test
    void identicalRepeatedCompletionIsAnsweredAsTheFirst() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            SangamClient c = e.client();
            String id = e.addWork("pay", Map.of());
            ExternalWork w = one(c, "w1", "pay");
            CompleteWorkRequest done = new CompleteWorkRequest("w1", 1, w.attemptId(), Map.of("ok", true), null, null);
            c.work().complete(id, done, "k");
            c.work().complete(id, done, "k");
            assertEquals(1, e.completions(id));
            assertEquals("SUCCEEDED", e.operationState(id));
            SangamApiException other = assertThrows(SangamApiException.class, () -> c.work().complete(id,
                    new CompleteWorkRequest("w1", 1, w.attemptId(), Map.of("ok", false), null, null), "k2"));
            assertEquals(404, other.status(), "a different completion of finished work: the task is gone");
        }
    }

    @Test
    void expiryReoffersWithANewVersionAndIrreversibleExpiryGoesToUnknownOutcome() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            SangamClient c = e.client();
            String id = e.addWork("pay", Map.of());
            one(c, "w1", "pay");
            e.advance(Duration.ofMinutes(2));
            assertEquals("AVAILABLE", e.state(id));
            assertEquals(2L, e.lockVersion(id));

            e.irreversibleTopic("transfer");
            String t = e.addWork("transfer", Map.of());
            one(c, "w1", "transfer");
            e.advance(Duration.ofMinutes(2));
            assertEquals("UNKNOWN_OUTCOME", e.state(t));
            assertTrue(fetch(c, "w2", "transfer", 60_000).isEmpty(), "UNKNOWN_OUTCOME is never re-offered");
            assertTrue(e.reconciliationItems().stream().anyMatch(r -> r.workId().equals(t) && r.kind().equals("UNKNOWN_OUTCOME")));
        }
    }

    @Test
    void failWithRetriesReoffersAfterTheTimeoutAndWithoutRetriesRaisesAnIncident() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            SangamClient c = e.client();
            String id = e.addWork("pay", Map.of(), 2, 0);
            ExternalWork w = one(c, "w1", "pay");
            c.work().fail(id, new FailWorkRequest("w1", 1, w.attemptId(), "boom", null, 1, 60_000L), "f1");
            assertEquals("AVAILABLE", e.state(id));
            assertTrue(fetch(c, "w1", "pay", 60_000).isEmpty(), "back-off not over");
            e.advance(Duration.ofMinutes(2));
            ExternalWork w2 = one(c, "w1", "pay");
            c.work().fail(id, new FailWorkRequest("w1", w2.lockVersion(), w2.attemptId(), "boom", null, 0, 0L), "f2");
            assertEquals("INCIDENT", e.state(id));
            assertEquals("FAILED", e.operationState(id));
        }
    }

    @Test
    void failOnAnIrreversibleTopicWithRetriesLeftIsAnUnknownOutcome() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            e.irreversibleTopic("transfer");
            SangamClient c = e.client();
            String id = e.addWork("transfer", Map.of());
            ExternalWork w = one(c, "w1", "transfer");
            c.work().fail(id, new FailWorkRequest("w1", 1, w.attemptId(), "timeout", null, 3, 0L), "f");
            assertEquals("UNKNOWN_OUTCOME", e.state(id));
        }
    }

    @Test
    void bpmnErrorAndOutcomeUnknown() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            SangamClient c = e.client();
            String a = e.addWork("pay", Map.of());
            ExternalWork wa = one(c, "w1", "pay");
            c.work().bpmnError(a, new BpmnErrorRequest("w1", 1, wa.attemptId(), "NO_FUNDS", "no funds", null), "b");
            assertEquals("COMPLETED_WITH_ERROR", e.state(a));

            String b = e.addWork("pay", Map.of());
            ExternalWork wb = one(c, "w1", "pay");
            OutcomeUnknownResult r = c.work().outcomeUnknown(b, new OutcomeUnknownRequest("w1", 1, wb.attemptId(), "timeout", null), "u");
            assertEquals("UNKNOWN_OUTCOME", r.state());
            assertEquals(e.operationId(b), r.operationId());
            e.resolveReconciliation(r.reconciliationItemId(), "RETRY");
            ExternalWork again = one(c, "w2", "pay");
            assertEquals(b, again.id());
            assertEquals(wb.operationId(), again.operationId(), "RETRY keeps the operationId (VI.3.1)");
        }
    }

    @Test
    void deletedInstanceRequestsCancelAndRejectsResults() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            SangamClient c = e.client();
            String id = e.addWork("pay", Map.of());
            ExternalWork w = one(c, "w1", "pay");
            e.deleteInstance(id);
            ExtendLockResult hb = c.work().extendLock(id, new ExtendLockRequest("w1", 1, w.attemptId(), 60_000), "h");
            assertEquals(Boolean.TRUE, hb.cancelRequested());
            GoneException gone = assertThrows(GoneException.class, () -> c.work().complete(id,
                    new CompleteWorkRequest("w1", 1, w.attemptId(), Map.of(), null, null), "c"));
            assertEquals("GONE_INSTANCE_DELETED", gone.problem().code);
            c.work().cancelAck(id, new UnlockRequest("w1", 1, w.attemptId()), "a");
            assertEquals("CANCELLED", e.state(id));
        }
    }

    @Test
    void deletedInstanceWithoutAckBecomesUnknownOutcomeAtExpiry() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            SangamClient c = e.client();
            String id = e.addWork("pay", Map.of());
            one(c, "w1", "pay");
            e.deleteInstance(id);
            e.advance(Duration.ofMinutes(2));
            assertTrue(e.reconciliationItems().stream().anyMatch(r -> r.workId().equals(id) && r.kind().equals("UNKNOWN_OUTCOME")));
        }
    }
}
