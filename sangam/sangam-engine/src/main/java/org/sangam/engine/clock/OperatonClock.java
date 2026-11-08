// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.clock;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.sangam.core.engine.SangamClock;

/**
 * The single wrapper of Operaton's static, JVM-wide {@link ClockUtil} (FLOWABLE-INTO-OPERATON D-10, W-7, XI.10.4). Nothing else in Sangam
 * may call {@code ClockUtil}; everything reads time through {@link SangamClock}.
 *
 * <p>Because the clock is JVM-wide, Sangam runs <b>one engine per JVM</b>: {@link #register(boolean)} refuses a second
 * engine unless explicitly allowed (tests). A per-engine clock SPI is the upstream patch planned for Stage D (FLOWABLE-INTO-OPERATON D-10).
 */
public final class OperatonClock implements SangamClock {

    private static final AtomicInteger ENGINES = new AtomicInteger();
    public static final OperatonClock INSTANCE = new OperatonClock();

    private OperatonClock() {
    }

    @Override
    public Instant now() {
        return ClockUtil.getCurrentTime().toInstant();
    }

    /** Engine clock as a {@link Date} (for engine APIs that take dates). */
    public Date nowDate() {
        return ClockUtil.getCurrentTime();
    }

    /** Moves the engine clock (tests, DST experiments); {@code null} returns to system time. JVM-wide. */
    public void set(Instant instant) {
        if (instant == null) {
            ClockUtil.reset();
        } else {
            ClockUtil.setCurrentTime(Date.from(instant));
        }
    }

    public void reset() {
        ClockUtil.reset();
    }

    /** Counts a new engine in this JVM; refuses the second one unless {@code allowMany}. */
    public static void register(boolean allowMany) {
        int n = ENGINES.incrementAndGet();
        if (n > 1 && !allowMany) {
            ENGINES.decrementAndGet();
            throw new IllegalStateException("FLOWABLE-INTO-OPERATON D-10: a second Sangam engine in one JVM shares Operaton's static ClockUtil; "
                    + "run one engine per JVM (set allowMultipleEnginesPerJvm only for tests)");
        }
    }

    public static void unregister() {
        ENGINES.updateAndGet(v -> Math.max(0, v - 1));
    }
}
