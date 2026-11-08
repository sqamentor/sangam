// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.function.Function;

/**
 * The business calendar day of a tenant. Every business date ("today" for receipt numbers, effective dates, as-of
 * dates, assignment validity, period defaults) is the date <em>in the tenant's zone</em>, never the JVM default zone
 * (production JVMs run with {@code -Duser.timezone=UTC}). Technical timestamps (audit, leases, events) stay UTC
 * {@link Instant}s and do not use this.
 * <p>
 * Backed by an injectable {@link Clock} (only its instant is used, never its zone) so tests can fix the moment.
 */
public interface BusinessClock {

    /** The instant source; only {@link Clock#instant()} is meaningful. */
    Clock clock();

    /** The business zone of a tenant (never null). A null or unknown tenant gets the implementation's fallback. */
    ZoneId zone(String tenantId);

    /** Today's business date of the tenant. */
    default LocalDate today(String tenantId) {
        return clock().instant().atZone(zone(tenantId)).toLocalDate();
    }

    /** The current local date-time of the tenant. */
    default LocalDateTime now(String tenantId) {
        return LocalDateTime.ofInstant(clock().instant(), zone(tenantId));
    }

    /**
     * The process-wide business clock, for value types that are built in many places and cannot take it by injection
     * (e.g. a context record whose zone was not given). The server installs the tenant-profile clock at start-up
     * ({@link #install}); until then it is UTC on the system clock. Prefer injection everywhere else.
     */
    static BusinessClock system() {
        return Holder.current;
    }

    /** Installs the process-wide business clock (server start-up, tests); returns the previous one. */
    static BusinessClock install(BusinessClock clock) {
        BusinessClock prev = Holder.current;
        Holder.current = Objects.requireNonNull(clock, "clock");
        return prev;
    }

    /** Holder of {@link #system()}. */
    final class Holder {
        private static volatile BusinessClock current = utc(Clock.systemUTC());

        private Holder() {
        }
    }

    /** A business clock whose every tenant is in one zone (tests, single-zone tools). */
    static BusinessClock fixedZone(Clock clock, ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        return of(clock, t -> zone);
    }

    /** A business clock in UTC for every tenant (the documented fallback). */
    static BusinessClock utc(Clock clock) {
        return fixedZone(clock, ZoneOffset.UTC);
    }

    /** A business clock from a zone lookup; a null result falls back to UTC. */
    static BusinessClock of(Clock clock, Function<String, ZoneId> zones) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(zones, "zones");
        return new BusinessClock() {
            @Override
            public Clock clock() {
                return clock;
            }

            @Override
            public ZoneId zone(String tenantId) {
                ZoneId z = zones.apply(tenantId);
                return z == null ? ZoneOffset.UTC : z;
            }
        };
    }
}
