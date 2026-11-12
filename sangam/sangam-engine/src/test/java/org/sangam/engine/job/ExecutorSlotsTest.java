// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * M-056 (SANGAM.md X.2.1.2): the job slot is MurmurHash3 x86 32-bit (seed 0, UTF-8) of {@code tenant:root}, unsigned,
 * modulo the slot count. The hash is pinned by the published reference vectors, the slot by fixed values, so the slot
 * of a stored job can never change with a JVM, locale or code change.
 */
class ExecutorSlotsTest {

    private static int mm(String s, int seed) {
        return ExecutorSlots.murmur3x86_32(s.getBytes(StandardCharsets.UTF_8), seed);
    }

    @Test
    void murmur3MatchesTheReferenceVectors() {
        assertEquals(0x00000000, mm("", 0));
        assertEquals(0x514e28b7, mm("", 1));
        assertEquals(0x81f16f39, mm("", 0xffffffff));
        assertEquals(0x2362f9de, ExecutorSlots.murmur3x86_32(new byte[4], 0));
        assertEquals(0xb3dd93fa, mm("abc", 0)); // 3-byte tail
        assertEquals(0x248bfa47, mm("hello", 0)); // one block + 1-byte tail
        assertEquals(0x24884cba, mm("Hello, world!", 0x9747b28c));
        assertEquals(0x2fa826cd, mm("The quick brown fox jumps over the lazy dog", 0x9747b28c));
        assertEquals(0x2e4ff723, mm("The quick brown fox jumps over the lazy dog", 0));
    }

    @Test
    void theSlotIsTheUnsignedHashOfTenantColonRootModuloTheSlotCount() {
        assertEquals(0xe7ba1b9d, mm("default:root-1", 0), "a hash with the top bit set (negative as an int)");
        assertEquals(2973, ExecutorSlots.slotOf("default", "root-1", ExecutorSlots.DEFAULT_SLOTS),
                "read as unsigned: 0xe7ba1b9d mod 4096");
        assertEquals(3765, ExecutorSlots.slotOf("t1", "तिथि-1", ExecutorSlots.DEFAULT_SLOTS), "Unicode ids hash as UTF-8");
        assertEquals(0, ExecutorSlots.slotOf("default", "root-1", 1), "one slot: everything in slot 0");
    }

    @Test
    void slotsAreInRangeAndStable() {
        for (int i = 0; i < 2_000; i++) {
            int slot = ExecutorSlots.slotOf("tenant-" + (i % 7), "root-" + i, 4_096);
            assertTrue(slot >= 0 && slot < 4_096, "in range: " + slot);
            assertEquals(slot, ExecutorSlots.slotOf("tenant-" + (i % 7), "root-" + i, 4_096), "deterministic");
            int big = ExecutorSlots.slotOf("tenant-" + (i % 7), "root-" + i, ExecutorSlots.MAX_SLOTS);
            assertTrue(big >= 0 && big < ExecutorSlots.MAX_SLOTS);
        }
    }

    @Test
    void invalidInputsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ExecutorSlots.slotOf(null, "r", 4_096));
        assertThrows(IllegalArgumentException.class, () -> ExecutorSlots.slotOf("t", " ", 4_096));
        assertThrows(IllegalArgumentException.class, () -> ExecutorSlots.slotOf("t", "r", 0));
        assertThrows(IllegalArgumentException.class, () -> ExecutorSlots.slotOf("t", "r", ExecutorSlots.MAX_SLOTS + 1));
    }
}
