// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Decision 146: the MM-1 minimal-movement allocation (pure). Written, NOT_RUN. */
class PartitionAllocationTest {

    private static int[] contiguous(int slots, int partitions) {
        int[] a = new int[slots];
        for (int s = 0; s < slots; s++) {
            a[s] = PartitionLeaseManager.partitionOfSlot(s, slots, partitions);
        }
        return a;
    }

    private static int[] counts(int[] a, int partitions) {
        int[] c = new int[partitions + 1];
        for (int p : a) {
            c[p]++;
        }
        return c;
    }

    /** The lower bound: every slot above a balanced quota, and every slot of a removed partition, must move. */
    private static int lowerBound(int[] current, int to) {
        int slots = current.length;
        int[] held = counts(current, Math.max(PartitionAllocation.partitionCount(current), to));
        Integer[] order = new Integer[to];
        for (int i = 0; i < to; i++) {
            order[i] = i + 1;
        }
        java.util.Arrays.sort(order, (x, y) -> held[y] != held[x] ? held[y] - held[x] : x - y);
        int keep = 0;
        for (int i = 0; i < to; i++) {
            int quota = slots / to + (i < slots % to ? 1 : 0);
            keep += Math.min(held[order[i]], quota);
        }
        return slots - keep;
    }

    private static void assertBalanced(int[] target, int to) {
        int[] c = counts(target, to);
        int slots = target.length;
        for (int p = 1; p <= to; p++) {
            assertTrue(c[p] == slots / to || c[p] == slots / to + 1, "partition " + p + " holds " + c[p]);
        }
        assertEquals(0, c[0], "no slot left in the SHARED partition");
    }

    @Test
    void scaleOutEightToTwelveMovesOnlyTheMinimum() {
        int[] current = contiguous(4096, 8);
        PartitionAllocation.Plan plan = PartitionAllocation.plan(current, 12);
        assertBalanced(plan.target(), 12);
        assertEquals(1364, plan.moved().length, "4096 - (4 x 342 + 4 x 341) kept");
        assertEquals(lowerBound(current, 12), plan.moved().length);
        for (int s = 0; s < 4096; s++) {
            if (plan.target()[s] != current[s]) {
                assertTrue(java.util.Arrays.binarySearch(plan.moved(), s) >= 0);
            }
        }
        assertEquals(8, plan.fromCount());
        assertEquals(12, plan.toCount());
    }

    @Test
    void scaleInAndRoundTripsStayMinimalAndDeterministic() {
        int[] a = contiguous(4096, 8);
        int[] b = PartitionAllocation.plan(a, 12).target();
        PartitionAllocation.Plan in = PartitionAllocation.plan(b, 4);
        assertBalanced(in.target(), 4);
        assertEquals(lowerBound(b, 4), in.moved().length);
        assertArrayEquals(in.target(), PartitionAllocation.plan(b, 4).target(), "deterministic");
        int[] off = PartitionAllocation.plan(in.target(), 0).target();
        for (int p : off) {
            assertEquals(0, p, "off: every slot in the SHARED partition 0");
        }
        PartitionAllocation.Plan on = PartitionAllocation.plan(off, 8);
        assertBalanced(on.target(), 8);
        assertEquals(4096, on.moved().length, "from SHARED every slot moves");
        assertArrayEquals(contiguous(4096, 8), on.target(), "from SHARED the allocation is the contiguous balanced one");
    }

    @Test
    void anUnchangedBalancedCountMovesNothingAndSurvivorsKeepTheirLowestSlots() {
        int[] a = contiguous(64, 4);
        assertEquals(0, PartitionAllocation.plan(a, 4).moved().length);
        PartitionAllocation.Plan p = PartitionAllocation.plan(a, 3);
        for (int s = 0; s < 64; s++) {
            if (a[s] <= 3 && p.target()[s] != a[s]) {
                // a survivor only loses slots above its quota, and those are its highest ones
                int owner = a[s];
                for (int t = s + 1; t < 64; t++) {
                    if (a[t] == owner) {
                        assertTrue(p.target()[t] != owner, "slot " + t + " of " + owner + " kept above a moved lower slot");
                    }
                }
            }
        }
        assertEquals(lowerBound(a, 3), p.moved().length);
    }

    @Test
    void movesEncodeAsRangesAndRoundTrip() {
        int[] a = contiguous(16, 2);
        PartitionAllocation.Plan p = PartitionAllocation.plan(a, 4);
        List<PartitionAllocation.Move> ranges = p.ranges(a);
        int covered = ranges.stream().mapToInt(m -> m.last() - m.first() + 1).sum();
        assertEquals(p.moved().length, covered);
        String text = PartitionAllocation.encode(ranges);
        assertEquals(ranges, PartitionAllocation.decode(text));
        assertEquals(List.of(), PartitionAllocation.decode(""));
        assertThrows(IllegalArgumentException.class, () -> PartitionAllocation.decode("3-1:1>2;"));
        assertThrows(IllegalArgumentException.class, () -> PartitionAllocation.decode("x"));
    }

    @Test
    void invalidCountsAreRefused() {
        int[] a = contiguous(8, 2);
        assertThrows(IllegalArgumentException.class, () -> PartitionAllocation.plan(a, 9));
        assertThrows(IllegalArgumentException.class, () -> PartitionAllocation.plan(a, -1));
        assertThrows(IllegalArgumentException.class, () -> PartitionAllocation.plan(new int[0], 1));
        assertThrows(IllegalArgumentException.class, () -> PartitionAllocation.plan(new int[]{0, 9}, 1));
    }
}
