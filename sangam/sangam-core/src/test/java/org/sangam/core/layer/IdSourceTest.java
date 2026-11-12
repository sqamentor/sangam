// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M-097 / D11: the injectable id and randomness provider of sangam-core, and the pure UUIDv7 function behind it. */
class IdSourceTest {

    private static final long MS = 0x0192_3456_789AL;   // a 2024 epoch-millisecond value

    @Test
    void aSourceOnAFixedTimeAndSeedIsRepeatable() {
        assertEquals(draw(IdSource.of(() -> MS, new SplittableRandom(7))), draw(IdSource.of(() -> MS, new SplittableRandom(7))));
        assertNotEquals(draw(IdSource.of(() -> MS, new SplittableRandom(7))), draw(IdSource.of(() -> MS, new SplittableRandom(8))));
    }

    @Test
    void timeOrderedIdsAreVersion7AndCarryTheSourceTime() {
        UUID u = IdSource.of(() -> MS, new SplittableRandom(1)).timeOrderedUuid();
        assertEquals(7, u.version());
        assertEquals(2, u.variant());
        assertEquals(MS, u.getMostSignificantBits() >>> 16, "the 48-bit time field is the source's epoch milliseconds");
        UUID later = IdSource.of(() -> MS + 1, new SplittableRandom(1)).timeOrderedUuid();
        assertTrue(later.toString().compareTo(u.toString()) > 0, "a later millisecond sorts later");
    }

    @Test
    void randomIdsAreVersion4AndUnitRandomStaysInRange() {
        IdSource s = IdSource.of(() -> MS, new SplittableRandom(3));
        for (int i = 0; i < 1000; i++) {
            UUID u = s.randomUuid();
            assertEquals(4, u.version());
            assertEquals(2, u.variant());
            double d = s.unitRandom();
            assertTrue(d >= 0 && d < 1, "unitRandom in [0, 1): " + d);
        }
    }

    @Test
    void uuidV7IsAPureFunctionWithTheRfc9562Layout() {
        SplittableRandom expected = new SplittableRandom(11);
        long msb = (MS << 16) | 0x7000L | (expected.nextInt() & 0x0fffL);
        long lsb = (expected.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        assertEquals(new UUID(msb, lsb), Hashes.uuidV7(MS, new SplittableRandom(11)));
        assertEquals(Hashes.uuidV7(MS, new SplittableRandom(11)), Hashes.uuidV7(MS, new SplittableRandom(11)));
    }

    @Test
    void theSystemSourceUsesTheWallClockAndFreshEntropy() {
        long before = System.currentTimeMillis();
        UUID v7 = IdSource.SYSTEM.timeOrderedUuid();
        UUID legacy = Hashes.uuidV7();
        long after = System.currentTimeMillis();
        for (UUID u : List.of(v7, legacy)) {
            assertEquals(7, u.version());
            long t = u.getMostSignificantBits() >>> 16;
            assertTrue(t >= before && t <= after, "UUIDv7 time " + t + " within [" + before + ", " + after + "]");
        }
        assertNotEquals(IdSource.SYSTEM.randomUuid(), IdSource.SYSTEM.randomUuid());
        assertEquals(4, IdSource.SYSTEM.randomUuid().version());
        double d = IdSource.SYSTEM.unitRandom();
        assertTrue(d >= 0 && d < 1);
    }

    @Test
    void ofRefusesMissingParts() {
        assertThrows(NullPointerException.class, () -> IdSource.of(null, new SplittableRandom(1)));
        assertThrows(NullPointerException.class, () -> IdSource.of(() -> MS, null));
    }

    private static List<Object> draw(IdSource s) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            out.add(s.randomUuid());
            out.add(s.timeOrderedUuid());
            out.add(s.unitRandom());
        }
        return out;
    }
}
