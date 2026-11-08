// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-destination circuit breaker for the relay. When a destination fails {@code threshold} times in a row it is
 * opened for {@code openFor}; while open the relay <b>defers</b> its deliveries (back to PENDING with a later
 * {@code next_attempt_at}, attempts unchanged) instead of spending their retry budget, so an outage of one webhook
 * does not park thousands of events. After {@code openFor} one probe delivery is let through (half-open).
 */
public final class CircuitBreaker {

    private record State(int failures, long openUntil, boolean probing) {
    }

    private final int threshold;
    private final Duration openFor;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public CircuitBreaker(int threshold, Duration openFor) {
        this.threshold = threshold;
        this.openFor = openFor;
    }

    public static CircuitBreaker defaults() {
        return new CircuitBreaker(5, Duration.ofSeconds(30));
    }

    /** True when a delivery to {@code destination} may be attempted now. */
    public boolean allow(String destination) {
        long now = System.currentTimeMillis();
        boolean[] allowed = {true};
        states.compute(destination, (k, s) -> {
            if (s == null || s.openUntil() == 0) {
                return s;
            }
            if (now < s.openUntil()) {
                allowed[0] = false;
                return s;
            }
            if (s.probing()) {
                allowed[0] = false;   // one probe at a time
                return s;
            }
            return new State(s.failures(), s.openUntil(), true);
        });
        return allowed[0];
    }

    public void success(String destination) {
        states.remove(destination);
    }

    public void failure(String destination) {
        long now = System.currentTimeMillis();
        states.compute(destination, (k, s) -> {
            int f = s == null ? 1 : s.failures() + 1;
            boolean open = f >= threshold || (s != null && s.probing());
            return new State(f, open ? now + openFor.toMillis() : 0, false);
        });
    }

    /** Milliseconds until {@code destination} may be retried (0 when closed). */
    public long remainingOpenMillis(String destination) {
        State s = states.get(destination);
        return s == null ? 0 : Math.max(0, s.openUntil() - System.currentTimeMillis());
    }

    public Map<String, Integer> openDestinations() {
        Map<String, Integer> out = new java.util.TreeMap<>();
        long now = System.currentTimeMillis();
        states.forEach((k, s) -> {
            if (s.openUntil() > now) {
                out.put(k, s.failures());
            }
        });
        return out;
    }
}
