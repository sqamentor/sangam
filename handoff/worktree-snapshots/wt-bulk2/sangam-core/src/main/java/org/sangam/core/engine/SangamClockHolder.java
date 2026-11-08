// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.time.Instant;
import java.util.Objects;

/**
 * Static access to the engine clock for code that cannot be handed a {@link SangamClock} (Operaton job handlers,
 * listeners, the WS-PORTS jobs and tenant guard): {@code SangamClockHolder.now()} instead of {@code ClockUtil}
 * (FLOWABLE-INTO-OPERATON D-10). sangam-engine registers its clock when it configures the engine; until then it is the JVM wall clock.
 * Code that can take a constructor argument should inject {@link SangamClock} instead (the server exposes it as a bean).
 */
public final class SangamClockHolder {

    private static volatile SangamClock clock = SangamClock.SYSTEM;

    private SangamClockHolder() {
    }

    /** The registered engine clock. */
    public static SangamClock get() {
        return clock;
    }

    /** Current engine time. */
    public static Instant now() {
        return clock.now();
    }

    /** Engine side: registers the engine clock (one engine per JVM, FLOWABLE-INTO-OPERATON D-10). */
    public static void set(SangamClock c) {
        clock = Objects.requireNonNull(c, "clock");
    }

    /** Back to the JVM wall clock (engine close, tests). */
    public static void reset() {
        clock = SangamClock.SYSTEM;
    }
}
