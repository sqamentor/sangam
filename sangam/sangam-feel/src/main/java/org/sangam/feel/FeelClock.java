// SPDX-License-Identifier: Apache-2.0
package org.sangam.feel;

import java.time.ZonedDateTime;

/**
 * Decision 151 (integrator ruling, option A): the time FEEL's built-ins {@code now()} and {@code today()} read. The
 * pinned feel-scala engine reads its {@code FeelEngineClock} for both (feel-engine 1.22.1,
 * {@code TemporalBuiltinFunctions}); Operaton 2.2.0 never sets one, so FEEL reads the JVM clock, not the engine clock.
 * A Sangam FEEL engine built with a {@code FeelClock} reads this instead, which lets a replay capture record each read
 * ({@code CLOCK_READ}) and a replay supply the recorded value. Without one the engine keeps feel-scala's default clock.
 */
@FunctionalInterface
public interface FeelClock {

    /** The current time as FEEL sees it; never null. */
    ZonedDateTime now();
}
