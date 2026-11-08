// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.calendar;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A half-open UTC interval {@code [from, to)}. */
public record Interval(Instant from, Instant to) {

    public Interval {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("interval needs from < to");
        }
    }

    public boolean overlaps(Interval o) {
        return from.isBefore(o.to) && o.from.isBefore(to);
    }

    public boolean contains(Interval o) {
        return !o.from.isBefore(from) && !o.to.isAfter(to);
    }

    public boolean contains(Instant t) {
        return !t.isBefore(from) && t.isBefore(to);
    }

    public Duration duration() {
        return Duration.between(from, to);
    }

    /** Sorts and merges overlapping or touching intervals. */
    public static List<Interval> union(List<Interval> in) {
        List<Interval> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparing(Interval::from));
        List<Interval> out = new ArrayList<>();
        for (Interval i : sorted) {
            if (!out.isEmpty() && !out.get(out.size() - 1).to.isBefore(i.from)) {
                Interval last = out.remove(out.size() - 1);
                out.add(new Interval(last.from, last.to.isAfter(i.to) ? last.to : i.to));
            } else {
                out.add(i);
            }
        }
        return out;
    }

    /** {@code base} minus every interval of {@code cut}. */
    public static List<Interval> subtract(List<Interval> base, List<Interval> cut) {
        List<Interval> result = union(base);
        for (Interval c : union(cut)) {
            List<Interval> next = new ArrayList<>();
            for (Interval b : result) {
                if (!b.overlaps(c)) {
                    next.add(b);
                    continue;
                }
                if (b.from.isBefore(c.from)) {
                    next.add(new Interval(b.from, c.from));
                }
                if (c.to.isBefore(b.to)) {
                    next.add(new Interval(c.to, b.to));
                }
            }
            result = next;
        }
        return result;
    }

    /** Whether {@code target} lies wholly inside the union of {@code cover}. */
    public static boolean covered(List<Interval> cover, Interval target) {
        for (Interval i : union(cover)) {
            if (i.contains(target)) {
                return true;
            }
        }
        return false;
    }
}
