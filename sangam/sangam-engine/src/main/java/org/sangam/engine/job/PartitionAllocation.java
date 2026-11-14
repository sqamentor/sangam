// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Decision 146 (M-056, SANGAM.md X.2.1.7 step 1): the deterministic, versioned minimal-movement slot allocation for a
 * partition-count change. Pure; no database access.
 *
 * <p>Algorithm {@value #ALGORITHM_VERSION}, from a committed assignment {@code slot -> partition} (partition 0 is the
 * virtual SHARED partition) to {@code P'} partitions over {@code S} slots:
 * <ol>
 *   <li>{@code P' = 0}: every slot goes to partition 0 (partitioning off).</li>
 *   <li>Quotas are balanced: every partition {@code 1..P'} gets {@code floor(S/P')} slots, and {@code S mod P'} of them
 *       get one more. The extra slots go to the partitions that already hold the most slots (ties: lower partition id),
 *       which minimises the number of moves.</li>
 *   <li>Each partition {@code 1..P'} keeps its own slots, lowest slot ids first, up to its quota. Its surplus, and every
 *       slot of a partition outside {@code 1..P'} (including 0), goes to a pool sorted by slot id.</li>
 *   <li>Partitions with a deficit take slots from the pool in ascending partition id, lowest pooled slot first.</li>
 * </ol>
 * The number of moved slots is the pool size, which no allocation with balanced quotas can undercut: every slot above a
 * partition's quota and every slot of a removed partition must move. Logical slot ids never change; a partition may own
 * non-contiguous ranges afterwards. Every slot has exactly one partition in the result (invariant I-1).
 */
public final class PartitionAllocation {

    /** Version written to each resize record; a change of the rules above needs a new version. */
    public static final String ALGORITHM_VERSION = "MM-1";

    /**
     * @param target  the new assignment, index = slot
     * @param moved   the slots whose partition changes, ascending
     */
    public record Plan(int fromCount, int toCount, int[] target, int[] moved) {

        /** {@code from} of a moved slot is {@code current[slot]}; ranges of consecutive moved slots with equal from/to. */
        public List<Move> ranges(int[] current) {
            List<Move> out = new ArrayList<>();
            Move open = null;
            for (int s : moved) {
                int from = current[s];
                int to = target[s];
                if (open != null && open.last() == s - 1 && open.fromPartition() == from && open.toPartition() == to) {
                    open = new Move(open.first(), s, from, to);
                    out.set(out.size() - 1, open);
                } else {
                    open = new Move(s, s, from, to);
                    out.add(open);
                }
            }
            return out;
        }
    }

    /** An inclusive slot range moving from one partition to another. */
    public record Move(int first, int last, int fromPartition, int toPartition) {
    }

    private PartitionAllocation() {
    }

    /** The number of partitions an assignment uses (0 when every slot is in the SHARED partition 0). */
    public static int partitionCount(int[] current) {
        int max = 0;
        for (int p : current) {
            max = Math.max(max, p);
        }
        return max;
    }

    /**
     * @param current  the committed assignment, index = slot, values 0..S
     * @param toCount  0 (SHARED) or 1..S
     */
    public static Plan plan(int[] current, int toCount) {
        int slots = current.length;
        if (slots < 1) {
            throw new IllegalArgumentException("decision 146: an allocation needs at least one slot");
        }
        if (toCount < 0 || toCount > slots) {
            throw new IllegalArgumentException("decision 146: partition count must be 0.." + slots + ": " + toCount);
        }
        for (int p : current) {
            if (p < 0 || p > slots) {
                throw new IllegalArgumentException("decision 146: slot owned by invalid partition " + p);
            }
        }
        int[] target = new int[slots];
        if (toCount == 0) {
            return finish(current, 0, target);
        }
        int[] held = new int[toCount + 1];
        for (int p : current) {
            if (p >= 1 && p <= toCount) {
                held[p]++;
            }
        }
        Integer[] order = new Integer[toCount];
        for (int i = 0; i < toCount; i++) {
            order[i] = i + 1;
        }
        Arrays.sort(order, Comparator.<Integer>comparingInt(p -> -held[p]).thenComparingInt(p -> p));
        int base = slots / toCount;
        int extra = slots % toCount;
        int[] quota = new int[toCount + 1];
        for (int i = 0; i < toCount; i++) {
            quota[order[i]] = base + (i < extra ? 1 : 0);
        }
        int[] kept = new int[toCount + 1];
        List<Integer> pool = new ArrayList<>();
        Arrays.fill(target, -1);
        for (int s = 0; s < slots; s++) { // ascending slot id: the lowest ids are kept
            int p = current[s];
            if (p >= 1 && p <= toCount && kept[p] < quota[p]) {
                target[s] = p;
                kept[p]++;
            } else {
                pool.add(s);
            }
        }
        Collections.sort(pool);
        int next = 0;
        for (int p = 1; p <= toCount; p++) {
            while (kept[p] < quota[p]) {
                target[pool.get(next++)] = p;
                kept[p]++;
            }
        }
        if (next != pool.size()) {
            throw new IllegalStateException("decision 146: allocation left " + (pool.size() - next) + " slots unmapped");
        }
        return finish(current, toCount, target);
    }

    private static Plan finish(int[] current, int toCount, int[] target) {
        int n = 0;
        for (int s = 0; s < current.length; s++) {
            if (current[s] != target[s]) {
                n++;
            }
        }
        int[] moved = new int[n];
        int i = 0;
        for (int s = 0; s < current.length; s++) {
            if (current[s] != target[s]) {
                moved[i++] = s;
            }
        }
        return new Plan(partitionCount(current), toCount, target, moved);
    }

    /** Encodes moves as {@code first-last:from>to;...} (the {@code EXEC_RESIZE.moved_slots} text). */
    public static String encode(List<Move> moves) {
        StringBuilder b = new StringBuilder();
        for (Move m : moves) {
            b.append(m.first()).append('-').append(m.last()).append(':').append(m.fromPartition()).append('>')
                    .append(m.toPartition()).append(';');
        }
        return b.toString();
    }

    /** The inverse of {@link #encode}; refuses malformed text. */
    public static List<Move> decode(String text) {
        List<Move> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String part : text.split(";")) {
            if (part.isEmpty()) {
                continue;
            }
            int dash = part.indexOf('-');
            int colon = part.indexOf(':');
            int arrow = part.indexOf('>');
            if (dash <= 0 || colon <= dash || arrow <= colon) {
                throw new IllegalArgumentException("decision 146: malformed moved_slots entry '" + part + "'");
            }
            Move m = new Move(Integer.parseInt(part.substring(0, dash)), Integer.parseInt(part.substring(dash + 1, colon)),
                    Integer.parseInt(part.substring(colon + 1, arrow)), Integer.parseInt(part.substring(arrow + 1)));
            if (m.first() < 0 || m.last() < m.first()) {
                throw new IllegalArgumentException("decision 146: malformed moved_slots range '" + part + "'");
            }
            out.add(m);
        }
        return out;
    }
}
