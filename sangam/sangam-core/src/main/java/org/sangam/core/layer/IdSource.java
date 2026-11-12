// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * The id and randomness provider of sangam-core (SANGAM.md D11, M-097: "time and IDs come from injectable providers").
 * Core code that needs a fresh id or a random value takes an {@code IdSource} (constructor or parameter) instead of
 * calling {@code UUID.randomUUID()}, {@code System.currentTimeMillis()} or a random generator itself; a test or a
 * steered host passes {@link #of} with a fixed time and a seeded generator and gets repeatable ids.
 *
 * <p>{@link #SYSTEM} is the production default and, with {@code SangamClock.SYSTEM} (time) and the default of
 * {@code BusinessClock.system()} (business dates), one of the only places in sangam-core main code that reads the
 * system clock or system entropy ({@code ArchitectureGuardTest.coreIsDeterministic} holds the exact list).
 */
public interface IdSource {

    /** A random (RFC 9562 version 4) UUID: lease attempt ids and other opaque handles. */
    UUID randomUuid();

    /** An RFC 9562 version 7 UUID at the source's current time (roughly time-ordered event ids, {@link Hashes#uuidV7}). */
    UUID timeOrderedUuid();

    /** A uniform value in [0, 1) (for example the full jitter of {@link Relay#backoffMillis}). */
    double unitRandom();

    /**
     * Production default: version 4 from {@link UUID#randomUUID()} (a CSPRNG), version 7 from the JVM wall clock and
     * a {@link SecureRandom} tail, jitter from {@link ThreadLocalRandom} (the same sources the core used before M-097).
     */
    IdSource SYSTEM = new IdSource() {
        private final SecureRandom tail = new SecureRandom();

        @Override
        public UUID randomUuid() {
            return UUID.randomUUID();
        }

        @Override
        public UUID timeOrderedUuid() {
            return Hashes.uuidV7(System.currentTimeMillis(), tail);
        }

        @Override
        public double unitRandom() {
            return ThreadLocalRandom.current().nextDouble();
        }
    };

    /**
     * A source on the given time (epoch milliseconds) and generator: deterministic when both are (tests, replays, a
     * steered test clock). {@code random} must be safe for the threads that use this source.
     */
    static IdSource of(LongSupplier epochMillis, RandomGenerator random) {
        Objects.requireNonNull(epochMillis, "epochMillis");
        Objects.requireNonNull(random, "random");
        return new IdSource() {
            @Override
            public UUID randomUuid() {
                long msb = (random.nextLong() & 0xffffffffffff0fffL) | 0x0000000000004000L;   // version 4
                long lsb = (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;   // IETF variant
                return new UUID(msb, lsb);
            }

            @Override
            public UUID timeOrderedUuid() {
                return Hashes.uuidV7(epochMillis.getAsLong(), random);
            }

            @Override
            public double unitRandom() {
                return random.nextDouble();
            }
        };
    }
}
