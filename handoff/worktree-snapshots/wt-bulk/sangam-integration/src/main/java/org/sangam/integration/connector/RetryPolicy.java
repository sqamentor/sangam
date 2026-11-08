// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-call retry policy of a transport (R28: {@code R3/PT1M} with jitter by default, per-operation override).
 *
 * <p>A transport retries only failures that are proven to have caused no effect, or any failure when the downstream
 * is idempotent by operationId (capability class a). It never retries past the request deadline.
 *
 * @param maxAttempts total attempts in one call, including the first (1 = no retry)
 * @param initial     first back-off
 * @param max         back-off cap
 * @param jitter      0..1, fraction of the back-off that is randomised (full jitter at 1)
 */
public record RetryPolicy(int maxAttempts, Duration initial, Duration max, double jitter) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts >= 1");
        }
        jitter = Math.max(0, Math.min(1, jitter));
    }

    /** R28 default: three attempts, up to one minute apart, with jitter. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(1), 0.5);
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 0);
    }

    /** Back-off before attempt {@code nextAttempt} (2 = first retry). */
    public Duration backoff(int nextAttempt) {
        long base = initial.toMillis() * (1L << Math.min(Math.max(0, nextAttempt - 2), 20));
        long capped = Math.min(max.toMillis(), base);
        long jitterPart = (long) (capped * jitter);
        long fixed = capped - jitterPart;
        long rnd = jitterPart <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterPart + 1);
        return Duration.ofMillis(fixed + rnd);
    }

    /** Sleeps the back-off; false when interrupted or when it would pass {@code deadlineMillis} (epoch ms, 0 = none). */
    public boolean sleepBefore(int nextAttempt, long deadlineMillis) {
        long ms = backoff(nextAttempt).toMillis();
        if (deadlineMillis > 0 && System.currentTimeMillis() + ms >= deadlineMillis) {
            return false;
        }
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
