// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.clock;

import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.sangam.core.engine.SangamClock;

/**
 * The single wrapper of Operaton's static, JVM-wide {@link ClockUtil} (FLOWABLE-INTO-OPERATON D-10, W-7, XI.10.4). Nothing else in Sangam
 * may call {@code ClockUtil}; everything reads time through {@link SangamClock}.
 *
 * <p>Because the clock is JVM-wide, Sangam runs <b>one live engine per JVM</b>: {@link #register(boolean)} refuses a
 * second live engine unless explicitly allowed (tests that really run engines side by side). Each registration is a
 * {@link Registration} handle owned by the engine that took it; {@link Registration#release()} is idempotent and
 * thread-safe, so a closed engine (or a closed Spring context) frees its slot exactly once and the next engine may
 * start. A per-engine clock SPI is the upstream patch planned for Stage D (FLOWABLE-INTO-OPERATON D-10).
 */
public final class OperatonClock implements SangamClock {

    private static final Set<Registration> LIVE = ConcurrentHashMap.newKeySet();
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

    /**
     * M-021 test mode: the engine clock runs on, shifted by {@code offsetMillis} from system time (0 = system time).
     * JVM-wide, like {@link #set}.
     */
    public void offset(long offsetMillis) {
        ClockUtil.reset();
        if (offsetMillis != 0) {
            ClockUtil.offset(offsetMillis);
        }
    }

    /**
     * Takes the engine slot of this JVM; refuses when another engine still holds one, unless {@code allowMany}.
     * The caller must {@link Registration#release() release} the handle when its engine closes (or fails to build).
     */
    public static synchronized Registration register(boolean allowMany) {
        LIVE.removeIf(Registration::ownerClosed); // R8-pnp PNP-1: a drop-in engine closed by its host frees its slot
        if (!LIVE.isEmpty() && !allowMany) {
            throw new IllegalStateException("FLOWABLE-INTO-OPERATON D-10: a second Sangam engine in one JVM shares Operaton's static ClockUtil; "
                    + "run one engine per JVM (set allowMultipleEnginesPerJvm only for tests); live engines: " + LIVE.size());
        }
        Registration r = new Registration();
        LIVE.add(r);
        return r;
    }

    /** Engines registered and not yet released in this JVM. */
    public static int liveEngines() {
        return LIVE.size();
    }

    /** One engine's slot. {@link #release()} may be called any number of times, from any thread. */
    public static final class Registration implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();

        private volatile org.operaton.bpm.engine.ProcessEngine owner;

        private Registration() {
        }

        /**
         * R8-pnp PNP-1: binds this slot to the engine that holds it, for an engine whose host closes it with
         * {@code ProcessEngine.close()} only (the drop-in {@code SangamEnginePlugin}). Operaton unregisters a closed
         * engine from {@code ProcessEngines}; the next {@link #register} then treats this slot as released.
         */
        public void bindTo(org.operaton.bpm.engine.ProcessEngine engine) {
            owner = engine;
        }

        /** {@code true} (and released) when the bound engine is no longer the one Operaton registers under its name. */
        boolean ownerClosed() {
            org.operaton.bpm.engine.ProcessEngine e = owner;
            if (e == null || org.operaton.bpm.engine.ProcessEngines.getProcessEngines().get(e.getName()) == e) {
                return false;
            }
            released.set(true);
            return true;
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                LIVE.remove(this);
            }
        }

        public boolean released() {
            return released.get();
        }

        @Override
        public void close() {
            release();
        }
    }
}
