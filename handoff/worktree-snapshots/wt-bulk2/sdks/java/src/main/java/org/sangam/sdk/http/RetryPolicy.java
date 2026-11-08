// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.http;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential back-off with full jitter for transport retries (R28: retry with jitter).
 * Only safe requests are retried: GET, or a mutation that carries an Idempotency-Key (R41).
 *
 * @param maxAttempts  total attempts including the first one (1 = no retry)
 * @param initialDelay delay cap for the first retry
 * @param maxDelay     upper bound of any single delay
 * @param multiplier   growth factor per attempt
 */
public record RetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay, double multiplier) {

    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >= 1");
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(4, Duration.ofMillis(200), Duration.ofSeconds(5), 2.0);
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 1.0);
    }

    /** Delay before retry number {@code retry} (1-based), full jitter in [0, cap]. */
    public Duration delay(int retry) {
        double cap = initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, retry - 1));
        long capped = (long) Math.min(cap, maxDelay.toMillis());
        if (capped <= 0) return Duration.ZERO;
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(capped / 2, capped + 1));
    }
}
