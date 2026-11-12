// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.testing;

import static org.sangam.sdk.testing.EngineModel.endLease;
import static org.sangam.sdk.testing.EngineModel.gone;
import static org.sangam.sdk.testing.EngineModel.lockDuration;
import static org.sangam.sdk.testing.EngineModel.notFound;
import static org.sangam.sdk.testing.EngineModel.removeLease;
import static org.sangam.sdk.testing.EngineModel.staleLease;
import static org.sangam.sdk.testing.EngineModel.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.sangam.sdk.testing.EngineModel.Problem;
import org.sangam.sdk.testing.EngineModel.Work;

/**
 * The fenced worker calls of {@link SangamTestEngine} (VI.3, VI.5): extend-lock, complete, fail, bpmn-error, unlock,
 * outcome-unknown and cancel-ack, each checked like {@code OperatonWorkAdapter} (fence order NO_LEASE, STALE_VERSION,
 * NOT_OWNER, EXPIRED, then the attempt id). The engine calls them while it holds its lock. Package-private.
 */
final class EngineWorkCalls {

    private final Map<String, Work> works;
    private final Set<String> irreversible;
    private final Clock clock;
    private final ObjectMapper json;
    private final BiFunction<String, Work, String> openRecon;

    EngineWorkCalls(Map<String, Work> works, Set<String> irreversible, Clock clock, ObjectMapper json,
                    BiFunction<String, Work, String> openRecon) {
        this.works = works;
        this.irreversible = irreversible;
        this.clock = clock;
        this.json = json;
        this.openRecon = openRecon;
    }

    private String openRecon(String kind, Work w) {
        return openRecon.apply(kind, w);
    }

    JsonNode extendLock(String workId, JsonNode r) {
        Work w = works.get(workId);
        boolean cancelRequested = w != null && "CANCEL_REQUESTED".equals(w.tombstone);
        long lv = r.path("lockVersion").asLong();
        if (w == null || !w.exists()) {
            if (cancelRequested) return lockState(workId, lv, null, true);
            throw notFound("external work", workId);
        }
        fence(w, text(r, "workerId"), lv, text(r, "attemptId"));
        Duration d = lockDuration(r.hasNonNull("newDurationMs") ? r.get("newDurationMs").asLong() : null);
        w.expiresAt = clock.instant().plus(d);
        return lockState(workId, lv, w.expiresAt, cancelRequested);
    }

    ObjectNode lockState(String id, long lv, Instant expires, boolean cancel) {
        ObjectNode n = json.createObjectNode();
        n.put("id", id);
        n.put("lockVersion", lv);
        if (expires != null) n.put("lockExpiresAt", expires.toString());
        n.put("cancelRequested", cancel);
        return n;
    }

    void complete(String workId, JsonNode r) {
        long lv = r.path("lockVersion").asLong();
        Work w = works.get(workId);
        rejectIfTombstoned(w, workId, true);
        JsonNode vars = r.hasNonNull("variables") ? r.get("variables") : json.createObjectNode();
        ObjectNode outcome = json.createObjectNode();
        outcome.set("output", vars);
        JsonNode o = r.get("outcome");
        if (o != null && o.hasNonNull("reference")) outcome.set("providerRef", o.get("reference")); else outcome.putNull("providerRef");
        outcome.put("duplicate", false);
        if (o != null && o.hasNonNull("details")) outcome.set("details", o.get("details"));
        if (w != null && !w.exists() && "SUCCEEDED".equals(w.opState) && w.completedLockVersion != null
                && w.completedLockVersion == lv && outcome.equals(w.completedOutcome)) {
            return; // the same completion again (a retried request): answered as the first one
        }
        if (w == null || !w.exists()) throw notFound("external work", workId);
        fence(w, text(r, "workerId"), lv, text(r, "attemptId"));
        w.state = "COMPLETED";
        w.opState = "SUCCEEDED";
        w.storedOutcome = outcome;
        w.completedOutcome = outcome;
        w.completedLockVersion = lv;
        w.completedVariables = vars.deepCopy();
        w.completions++;
        removeLease(w);
    }

    void fail(String workId, JsonNode r) {
        Work w = works.get(workId);
        Integer retries = r.hasNonNull("retries") ? r.get("retries").asInt() : null;
        if (w != null && w.exists() && irreversible.contains(w.topic) && (retries == null || retries > 0)) {
            // VI.3 rule 5: no blind automatic retry of an irreversible effect
            outcomeUnknown(workId, r, "failure on irreversible topic: " + text(r, "errorMessage"));
            return;
        }
        rejectIfTombstoned(w, workId, false);
        if (w == null || !w.exists()) throw notFound("external work", workId);
        long lv = r.path("lockVersion").asLong();
        fence(w, text(r, "workerId"), lv, text(r, "attemptId"));
        int left = retries == null ? Math.max(0, (w.retries == null ? 1 : w.retries) - 1) : Math.max(0, retries);
        long timeout = r.hasNonNull("retryTimeoutMs") ? Math.max(0, r.get("retryTimeoutMs").asLong()) : 0;
        w.retries = left;
        w.errorMessage = text(r, "errorMessage");
        if (left > 0) {
            w.state = "AVAILABLE";
            w.availableAt = clock.instant().plusMillis(timeout);
        } else {
            w.state = "INCIDENT";
            w.opState = "FAILED";
        }
        endLease(w);
    }

    void bpmnError(String workId, JsonNode r) {
        String code = text(r, "errorCode");
        if (code == null || code.isBlank()) throw new Problem(400, "VALIDATION_FAILED", null, "errorCode is required");
        Work w = works.get(workId);
        rejectIfTombstoned(w, workId, false);
        if (w == null || !w.exists()) throw notFound("external work", workId);
        fence(w, text(r, "workerId"), r.path("lockVersion").asLong(), text(r, "attemptId"));
        w.state = "COMPLETED_WITH_ERROR";
        w.opState = "FAILED";
        w.errorMessage = code;
        removeLease(w);
    }

    void unlock(String workId, JsonNode r) {
        Work w = works.get(workId);
        if (w == null || !w.exists()) throw notFound("external work", workId);
        fence(w, text(r, "workerId"), r.path("lockVersion").asLong(), text(r, "attemptId"));
        w.state = "AVAILABLE";
        w.availableAt = Instant.MIN;
        endLease(w);
    }

    JsonNode outcomeUnknown(String workId, JsonNode r, String detail) {
        Work w = works.get(workId);
        if (w != null && w.tombstone != null) {
            String item = openRecon("LATE_RESULT_AFTER_DELETE", w);
            throw gone(workId, item);
        }
        if (w == null || !w.exists()) throw notFound("external work", workId);
        fence(w, text(r, "workerId"), r.path("lockVersion").asLong(), text(r, "attemptId"));
        String item = openRecon("UNKNOWN_OUTCOME", w);
        w.state = "UNKNOWN_OUTCOME";
        w.opState = "UNKNOWN_OUTCOME";
        w.errorMessage = detail;
        endLease(w);
        ObjectNode n = json.createObjectNode();
        n.put("workId", workId);
        n.put("operationId", w.operationId);
        n.put("reconciliationItemId", item);
        n.put("state", "UNKNOWN_OUTCOME");
        return n;
    }

    void cancelAck(String workId, JsonNode r) {
        Work w = works.get(workId);
        if (w == null || w.tombstone == null) throw notFound("cancel request for work", workId);
        long lv = r.path("lockVersion").asLong();
        if (!"CANCEL_REQUESTED".equals(w.tombstone) || w.tombstoneVersion == null || w.tombstoneVersion != lv) {
            throw staleLease(workId, "STALE_VERSION", "no CANCEL_REQUESTED tombstone with lockVersion " + lv);
        }
        w.tombstone = "CANCELLED";
        w.state = "CANCELLED";
        removeLease(w);
    }

    /** Tombstones.onLateResult: 410, and a reconciliation item when the result says an effect happened. */
    void rejectIfTombstoned(Work w, String workId, boolean effectHappened) {
        if (w == null || w.tombstone == null) return;
        throw gone(workId, effectHappened ? openRecon("LATE_RESULT_AFTER_DELETE", w) : null);
    }

    /** LeaseTable.check order (NO_LEASE, STALE_VERSION, NOT_OWNER, EXPIRED), then the attempt id. */
    void fence(Work w, String workerId, long lockVersion, String attemptId) {
        if (workerId == null || workerId.isBlank()) throw new Problem(400, "WORKER_ID_REQUIRED", null, "workerId is required");
        if (w.leaseVersion == null) throw staleLease(w.id, "NO_LEASE", "no lease was granted for this work");
        if (w.leaseVersion != lockVersion) {
            throw staleLease(w.id, "STALE_VERSION", "lockVersion " + lockVersion + " is not the current version " + w.leaseVersion);
        }
        if (w.workerId == null || !w.workerId.equals(workerId)) {
            throw staleLease(w.id, "NOT_OWNER", workerId + " does not hold the lease (owner: " + w.workerId + ")");
        }
        if (w.expiresAt == null || !w.expiresAt.isAfter(clock.instant())) {
            throw staleLease(w.id, "EXPIRED", "the lease of " + workerId + " expired at " + w.expiresAt);
        }
        if (attemptId != null && w.attemptId != null && !attemptId.equals(w.attemptId)) {
            throw staleLease(w.id, "ATTEMPT_MISMATCH", "attemptId " + attemptId + " is not the current attempt");
        }
    }
}
