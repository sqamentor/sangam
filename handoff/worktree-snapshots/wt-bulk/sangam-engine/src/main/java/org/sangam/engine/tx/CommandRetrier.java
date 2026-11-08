// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.tx;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.sangam.core.engine.ConflictRetryExhaustedException;
import org.sangam.core.engine.EngineMetrics;
import org.sangam.engine.db.Conflicts;

/**
 * VI.1 rule 7 / A-11: retries a whole command on retryable conflicts (lock timeout, deadlock victim, optimistic lock,
 * serialization failure) with exponential back-off and <b>full jitter</b> (default 5 attempts, base 20 ms, cap 1 s).
 * After the last attempt it throws {@link ConflictRetryExhaustedException} ({@code 409 CONFLICT_RETRY_EXHAUSTED}).
 * Retrying is safe because a rolled-back command changed nothing outside the database.
 */
public final class CommandRetrier {

    private final int maxAttempts;
    private final long baseMillis;
    private final long capMillis;
    private final EngineMetrics metrics;

    public CommandRetrier(int maxAttempts, Duration base, Duration cap, EngineMetrics metrics) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.baseMillis = Math.max(1, base.toMillis());
        this.capMillis = Math.max(baseMillis, cap.toMillis());
        this.metrics = metrics == null ? EngineMetrics.NONE : metrics;
    }

    /** Runs {@code attempt} until it commits, a non-retryable error is thrown, or the attempts are used up. */
    public <T> T run(String commandName, boolean firstUseDuplicatesRetryable, Supplier<T> attempt) {
        RuntimeException last = null;
        for (int n = 1; n <= maxAttempts; n++) {
            try {
                return attempt.get();
            } catch (RuntimeException e) {
                if (!Conflicts.isRetryable(e, firstUseDuplicatesRetryable)) {
                    throw e;
                }
                last = e;
                metrics.commandRetry(commandName);
                if (n < maxAttempts) {
                    sleep(n);
                }
            }
        }
        metrics.commandRetryExhausted(commandName);
        throw new ConflictRetryExhaustedException(commandName, maxAttempts, last);
    }

    /** Full jitter: uniform in [0, min(cap, base * 2^(n-1))]. */
    private void sleep(int attemptNo) {
        long ceiling = Math.min(capMillis, baseMillis << Math.min(attemptNo - 1, 20));
        long pause = ThreadLocalRandom.current().nextLong(ceiling + 1);
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off a retryable conflict", e);
        }
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
