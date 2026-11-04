// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.sangam.core.engine.SangamClockHolder;

/** A {@link Clock} over the engine clock (one clock per JVM, D-10), for modules that take a {@code java.time.Clock}. */
public final class GeoEngineClock extends Clock {

    private final ZoneId zone;

    public GeoEngineClock(ZoneId zone) {
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
        return new GeoEngineClock(z);
    }

    @Override
    public Instant instant() {
        return SangamClockHolder.now();
    }
}
