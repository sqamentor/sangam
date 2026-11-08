// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.idempotency;

import org.sangam.core.layer.IdempotencyStore;

import java.sql.Connection;
import java.util.Optional;

/**
 * The claim of the request being executed (X.2.2.4 step 2). The command code obtains it with {@link #current()} and,
 * <b>as the last write of its engine transaction</b>, calls {@link #complete} with the response it is about to return
 * (lock domain L6, property P-1: engine change and COMPLETED commit together or not at all). A lost claim throws
 * {@link IdempotencyStore.ClaimLostException}, which must roll the command back.
 *
 * <p>Thread-bound for the duration of {@link IdempotencyService#execute}; null when the request carries no key.
 */
public final class IdempotencyContext {

    private static final ThreadLocal<IdempotencyContext> CURRENT = new ThreadLocal<>();

    private final IdempotencyStore store;
    private final IdempotencyStore.Claim claim;
    private volatile boolean completed;
    private volatile int completedStatus;

    IdempotencyContext(IdempotencyStore store, IdempotencyStore.Claim claim) {
        this.store = store;
        this.claim = claim;
    }

    public static Optional<IdempotencyContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static void bind(IdempotencyContext ctx) {
        CURRENT.set(ctx);
    }

    static void unbind() {
        CURRENT.remove();
    }

    /** Last write of the command transaction. */
    public void complete(Connection c, int httpStatus, byte[] responseBody, String resourceRef) {
        store.complete(c, claim, httpStatus, responseBody, resourceRef);
        completed = true;
        completedStatus = httpStatus;
    }

    public boolean completed() {
        return completed;
    }

    public int completedStatus() {
        return completedStatus;
    }

    public IdempotencyStore.Claim claim() {
        return claim;
    }

    /**
     * Called when the command transaction rolled back after {@link #complete}: the COMPLETED row did not commit, so
     * the claim is still IN_PROGRESS and must be classified like any other failure.
     */
    void rolledBack() {
        completed = false;
    }
}
