// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import java.time.Duration;

/**
 * Thrown by a publisher when a delivery must wait without consuming an attempt: the destination asked for it
 * ({@code Retry-After} on 429/503) or is administratively disabled and still inside its hold period. The relay puts the
 * delivery back to PENDING with {@code next_attempt_at = now + delay} (claim-epoch fenced); ordering is preserved,
 * because an ordered line's head stays the head.
 */
public final class DeferPublishException extends RuntimeException {

    private final Duration delay;

    public DeferPublishException(String message, Duration delay) {
        super(message);
        this.delay = delay == null || delay.isNegative() ? Duration.ZERO : delay;
    }

    public Duration delay() {
        return delay;
    }
}
