// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.util.Locale;

/**
 * R31 / FLOWABLE-INTO-OPERATON D-30: what a cycle timer does with firings missed while the engine was down. Declared per timer
 * ({@code sangam:catchUp} extension property), per process definition key, or engine-wide.
 */
public enum TimerCatchUpPolicy {
    /** Missed firings collapse into one firing now, then the schedule continues (the engine's native behaviour). */
    FIRE_ONCE,
    /** Every missed slot fires, one after another, bounded by the engine's catch-up limit. */
    FIRE_ALL,
    /** Missed slots are skipped; the next firing is the next future slot. */
    SKIP_MISSED;

    public static TimerCatchUpPolicy parse(String s, TimerCatchUpPolicy dflt) {
        if (s == null || s.isBlank()) {
            return dflt;
        }
        String v = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (v) {
            case "ONCE", "FIRE_ONCE" -> FIRE_ONCE;
            case "ALL", "FIRE_ALL", "REPLAY" -> FIRE_ALL;
            case "SKIP", "SKIP_MISSED", "NONE" -> SKIP_MISSED;
            default -> throw new IllegalArgumentException("unknown timer catch-up policy: " + s);
        };
    }
}
