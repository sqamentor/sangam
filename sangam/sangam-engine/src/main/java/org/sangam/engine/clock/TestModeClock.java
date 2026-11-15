// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.clock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.sangam.core.time.BusinessClock;

/**
 * M-021 (C3-13, W3-K REQUEST 12:23:57Z, integrator 12:26Z): clock steering for end-to-end test images, behind
 * {@code sangam.test-mode.enabled}. The engine clock ({@link OperatonClock}, the only {@code ClockUtil} wrapper) and the
 * process-wide {@link BusinessClock} move together by one offset from system time, and keep running (timers, SLAs,
 * business dates all see the shifted time). Each change is handed to the auditor before it returns; a change the
 * auditor rejects is undone.
 *
 * <p><b>Guard</b> ({@link #requireAllowed}): test mode is OFF by default; on, the engine refuses to start unless the
 * database is H2 and no production profile is active. There is no way to enable it on a Tier-1 database.
 */
public final class TestModeClock implements AutoCloseable {

    /** Profile names that always refuse test mode. */
    static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production", "live");

    /** One audited clock change: {@code action} is ADVANCE, SET or RESET. */
    public record Change(String action, long previousOffsetMs, long offsetMs, Instant now, String actor, String reason) {
    }

    private final OperatonClock engineClock;
    private final Consumer<Change> auditor;
    /** The process-wide business clock before this one was put over it, and the one this instance installed. */
    private final BusinessClock replaced;
    private final BusinessClock installed;
    private long offsetMs;

    /**
     * @param auditor records every change (class-B audit through the caller's audit port); required
     */
    public TestModeClock(OperatonClock engineClock, Consumer<Change> auditor) {
        this.engineClock = Objects.requireNonNull(engineClock, "engineClock");
        this.auditor = Objects.requireNonNull(auditor, "auditor: every test-mode clock change is audited");
        BusinessClock previous = BusinessClock.system();
        Clock follows = new EngineClock(engineClock);
        this.replaced = previous;
        this.installed = BusinessClock.of(follows, previous::zone); // tenant zones kept, instant from the engine
        BusinessClock.install(installed);
    }

    /**
     * Gives the process-wide business clock back: the constructor replaces {@link BusinessClock#system()} for the whole
     * JVM, and without this the process kept a clock that follows an engine which may be gone (the next engine or test
     * in the same JVM read its business time through it). The clock that was there before comes back, unless another
     * clock has been installed since (that one is left alone). The engine clock's offset is not touched: it belongs
     * to the engine and is changed only by an audited call. Idempotent.
     */
    @Override
    public synchronized void close() {
        if (BusinessClock.system() == installed) {
            BusinessClock.install(replaced);
        }
    }

    /**
     * The clock route of an engine started in test mode (the engine already refused any other database or a production
     * profile at start-up). The REST controller of {@code /api/v1/test-mode/clock} calls this once.
     *
     * @throws IllegalStateException when the engine is not in test mode
     */
    public static TestModeClock forEngine(org.sangam.engine.SangamEngineSettings settings, Consumer<Change> auditor) {
        if (!settings.isTestMode()) {
            throw new IllegalStateException("M-021: test mode is off (sangam.test-mode.enabled); no clock steering");
        }
        return new TestModeClock(OperatonClock.INSTANCE, auditor);
    }

    /**
     * As {@link #forEngine(org.sangam.engine.SangamEngineSettings, Consumer)}, re-checking the guard with the host's own
     * view (the server passes its datasource kind and Spring's active profiles), so the route never depends on the
     * settings bean alone.
     */
    public static TestModeClock forEngine(org.sangam.engine.SangamEngineSettings settings, boolean databaseIsH2,
                                          Collection<String> hostProfiles, Consumer<Change> auditor) {
        requireAllowed(settings.isTestMode(), databaseIsH2, hostProfiles);
        return forEngine(settings, auditor);
    }

    /**
     * Start-up check: refuses test mode unless the database is H2 and no production profile is active.
     *
     * @param databaseIsH2 whether the engine runs on H2
     * @throws IllegalStateException when test mode is on anywhere else
     */
    public static void requireAllowed(boolean enabled, boolean databaseIsH2, Collection<String> activeProfiles) {
        if (!enabled) {
            return;
        }
        if (!databaseIsH2) {
            throw new IllegalStateException("M-021: sangam.test-mode.enabled is on, but the database is not H2; test mode "
                    + "(clock steering) is refused on every other database");
        }
        for (String p : activeProfiles == null ? Set.<String>of() : activeProfiles) {
            if (p != null && PRODUCTION_PROFILES.contains(p.trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("M-021: sangam.test-mode.enabled is on with production profile '" + p
                        + "'; refused");
            }
        }
    }

    public synchronized Instant now() {
        return engineClock.now();
    }

    public synchronized long offsetMs() {
        return offsetMs;
    }

    /** Moves the clock forward by a positive duration. */
    public Change advanceBy(Duration d, String actor, String reason) {
        return advanceBy(d, actor, reason, null);
    }

    /**
     * As {@link #advanceBy(Duration, String, String)}; {@code callAudit} (the caller's class-B audit, for example the
     * REST route's {@code TEST_CLOCK_CHANGED} with the request context) runs too, before it returns.
     */
    public synchronized Change advanceBy(Duration d, String actor, String reason, Consumer<Change> callAudit) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException("advanceBy must be a positive duration");
        }
        return apply("ADVANCE", Math.addExact(offsetMs, d.toMillis()), actor, reason, callAudit);
    }

    /** Sets the clock to an instant (may be in the past). */
    public Change set(Instant target, String actor, String reason) {
        return set(target, actor, reason, null);
    }

    /** As {@link #set(Instant, String, String)} with the caller's audit. */
    public synchronized Change set(Instant target, String actor, String reason, Consumer<Change> callAudit) {
        Objects.requireNonNull(target, "set needs an instant");
        return apply("SET", target.toEpochMilli() - System.currentTimeMillis(), actor, reason, callAudit);
    }

    /** Back to system time. */
    public Change reset(String actor, String reason) {
        return reset(actor, reason, null);
    }

    /** As {@link #reset(String, String)} with the caller's audit. */
    public synchronized Change reset(String actor, String reason, Consumer<Change> callAudit) {
        return apply("RESET", 0, actor, reason, callAudit);
    }

    private Change apply(String action, long next, String actor, String reason, Consumer<Change> callAudit) {
        long previous = offsetMs;
        engineClock.offset(next);
        offsetMs = next;
        Change c = new Change(action, previous, next, engineClock.now(), actor, reason);
        try {
            if (callAudit != null) {
                callAudit.accept(c);
            }
            auditor.accept(c);
        } catch (RuntimeException e) {
            engineClock.offset(previous); // not audited: not applied
            offsetMs = previous;
            throw e;
        }
        return c;
    }

    /** A {@link Clock} reading the engine clock (so the business clock follows every offset). */
    private static final class EngineClock extends Clock {
        private final OperatonClock engine;

        EngineClock(OperatonClock engine) {
            this.engine = engine;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return engine.now();
        }
    }
}
