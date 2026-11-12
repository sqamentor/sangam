// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.testing;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * State and wire helpers of {@link SangamTestEngine} (M-021): the work item with its lease row, operation ledger entry
 * and tombstone, reconciliation items, the R41 store entry, and RFC 9457 problems built like the server's
 * {@code ApiProblem} ({@code staleLease}, {@code gone}, {@code notFound}). Package-private.
 */
final class EngineModel {

    private EngineModel() {
    }

    static final class Work {
        String id;
        String topic;
        String operationId;
        String processInstanceId;
        String activityInstanceId;
        JsonNode variables;
        Integer retries;
        long priority;
        String errorMessage;
        String state;
        Instant availableAt;
        // lease row (LeaseTable): null leaseVersion = no row
        Long leaseVersion;
        String attemptId;
        String workerId;
        Instant expiresAt;
        // operation ledger (R37)
        String opState = "INTENT";
        int attempts;
        JsonNode storedOutcome;
        Long completedLockVersion;
        JsonNode completedOutcome;
        JsonNode completedVariables;
        int completions;
        int grants;
        // tombstone (VI.5)
        String tombstone;
        Long tombstoneVersion;

        boolean exists() {
            // the engine task exists until it completed, was cancelled, failed by reconciliation or was deleted
            return switch (state) {
                case "AVAILABLE", "LOCKED", "INCIDENT", "UNKNOWN_OUTCOME" -> true;
                default -> false;
            };
        }
    }

    static final class Recon {
        String id;
        String kind;
        String workId;
        String operationId;
        String state = "OPEN";
    }

    record Stored(JsonNode fingerprint, int status, byte[] body) { }

    /** An RFC 9457 answer, built like the server's {@code Problems.body}. */
    static final class Problem extends RuntimeException {
        final int status;
        final String code;
        final String title;
        final Map<String, Object> ext = new LinkedHashMap<>();

        Problem(int status, String code, String title, String detail) {
            super(detail);
            this.status = status;
            this.code = code;
            this.title = title;
        }

        Problem with(String k, Object v) {
            if (v != null) ext.put(k, v);
            return this;
        }
    }

    record Answer(int status, byte[] body, String contentType, Map<String, String> headers) { }

    /** LeaseTable.end: lock_version + 1, no owner. */
    static void endLease(Work w) {
        w.leaseVersion = w.leaseVersion == null ? 1L : w.leaseVersion + 1;
        w.attemptId = null;
        w.workerId = null;
        w.expiresAt = null;
    }

    /** LeaseTable.remove: the lease row is gone (completion). */
    static void removeLease(Work w) {
        w.leaseVersion = null;
        w.attemptId = null;
        w.workerId = null;
        w.expiresAt = null;
    }

    static Problem staleLease(String workId, String reason, String detail) {
        return new Problem(409, "STALE_LEASE", "Stale lease", detail).with("workId", workId).with("reason", reason);
    }

    static Problem gone(String workId, String item) {
        return new Problem(410, "GONE_INSTANCE_DELETED", null, "The instance of this work was deleted; the result was recorded")
                .with("workId", workId).with("reconciliationItemId", item);
    }

    static Problem notFound(String what, String id) {
        return new Problem(404, "NOT_FOUND", null, what + " " + id + " was not found or is not visible to you")
                .with("resource", what);
    }

    static String defaultTitle(int status) {
        return switch (status) {
            case 400 -> "Bad request";
            case 404 -> "Not found";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 422 -> "Unprocessable content";
            case 501 -> "Not implemented";
            default -> status >= 500 ? "Internal error" : "Error";
        };
    }

    static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    static Duration lockDuration(Long ms) {
        Duration d = ms == null || ms <= 0 ? SangamTestEngine.DEFAULT_LOCK : Duration.ofMillis(ms);
        return d.compareTo(SangamTestEngine.MAX_LOCK) > 0 ? SangamTestEngine.MAX_LOCK : d;
    }
}
