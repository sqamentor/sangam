// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential back-off with jitter: {@code min(max, initial * factor^(n-1))}, then +/- {@code jitter} fraction.
 * Used for fetch errors and for the {@code retryTimeoutMs} sent with {@code fail} (R28 default R3/PT1M with jitter).
 */
public record Backoff(Duration initial, Duration max, double factor, double jitter) {

    public static Backoff fetchDefaults() {
        return new Backoff(Duration.ofMillis(500), Duration.ofSeconds(60), 2.0, 0.2);
    }

    /** R28 default: first retry after about one minute, growing, capped at 10 minutes. */
    public static Backoff retryDefaults() {
        return new Backoff(Duration.ofMinutes(1), Duration.ofMinutes(10), 2.0, 0.2);
    }

    /** Delay for the {@code n}-th consecutive failure (1-based). */
    public Duration delay(int n) {
        double base = initial.toMillis() * Math.pow(factor, Math.max(0, n - 1));
        double capped = Math.min(base, max.toMillis());
        double j = capped * jitter;
        double v = capped + (j == 0 ? 0 : ThreadLocalRandom.current().nextDouble(-j, j));
        return Duration.ofMillis(Math.max(0, (long) v));
    }
}
