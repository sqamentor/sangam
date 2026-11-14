// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;

/**
 * R41 API idempotency lifecycle (X.2.2) as seen by the REST layer. {@link #begin} is the step-1 claim (own short
 * transaction). {@link #execute} runs the command and, for a 2xx result, writes {@code COMPLETED} as the last write of
 * the <b>same</b> transaction (L6, property P-1); deterministic 4xx are stored as {@code FAILED_FINAL}; transient
 * results and 401/403 are not stored (step 3).
 */
public interface IdempotencyPort {

    sealed interface Begin permits Claimed, Replay, Rejected {
    }

    /** The caller owns the claim and must call {@link #execute}. {@code handle} is adapter-private. */
    record Claimed(Object handle) implements Begin {
    }

    /** Stored response (step 4); {@code compacted}: status and resource ref only. */
    record Replay(int status, byte[] body, String resourceRef, boolean compacted, Instant expiresAt) implements Begin {
    }

    /** 409 IDEMPOTENCY_IN_PROGRESS, 422 IDEMPOTENCY_KEY_REUSED / _PRINCIPAL_MISMATCH, 400 IDEMPOTENCY_KEY_INVALID. */
    record Rejected(int status, String code, int retryAfterSeconds) implements Begin {
    }

    record Outcome(int status, byte[] body, String resourceRef) {
    }

    @FunctionalInterface
    interface Command {
        Outcome run() throws Exception;
    }

    Begin begin(RequestContext ctx, String commandType, String key, byte[] fingerprint);

    Outcome execute(Claimed claim, Command command) throws Exception;
}
