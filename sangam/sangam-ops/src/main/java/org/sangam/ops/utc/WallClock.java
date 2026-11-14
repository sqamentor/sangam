// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.utc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Decision 150: a stored value written before the cutover holds the source zone's wall clock labelled as UTC. Its
 * instant is that wall clock resolved in the named source zone with the zone's historical rules. A wall clock in a DST
 * gap (no valid offset) or overlap (two) is never resolved by choice.
 */
public final class WallClock {

    /** Why a value was not converted. */
    public enum Unresolved {
        DST_GAP, DST_OVERLAP, NO_PROVENANCE, CUTOVER_AMBIGUOUS, CUTOVER_INCONSISTENT,
        /** Decision 240: the wall clock read under the declared session zone lies in its DST overlap (not restorable). */
        SESSION_ZONE_OVERLAP,
        /** Decision 240: the wall clock read under the declared session zone lies in its DST gap (rules disagree). */
        SESSION_ZONE_GAP
    }

    /** Either the instant or the reason it cannot be resolved. */
    public record Resolution(Instant instant, Unresolved unresolved) {
        public boolean resolved() {
            return instant != null;
        }
    }

    private WallClock() {
    }

    /** A named zone with historical rules; a fixed offset (also {@code UTC+05:30} style ids) is refused. */
    public static ZoneId requireNamedZone(ZoneId zone) {
        if (zone == null || zone instanceof ZoneOffset || (zone.normalized() instanceof ZoneOffset
                && !zone.getId().equals("UTC") && !zone.getId().equals("Etc/UTC"))) {
            throw new IllegalArgumentException("a named source zone with historical rules is required, never a fixed"
                    + " offset (decision 150): " + zone);
        }
        return zone;
    }

    /** The instant whose source-zone wall clock is {@code stored} read at UTC. */
    public static Resolution resolve(Instant stored, ZoneId zone) {
        LocalDateTime local = LocalDateTime.ofInstant(stored, ZoneOffset.UTC);
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) {
            return new Resolution(null, Unresolved.DST_GAP);
        }
        if (offsets.size() > 1) {
            return new Resolution(null, Unresolved.DST_OVERLAP);
        }
        return new Resolution(local.toInstant(offsets.get(0)), null);
    }

    /**
     * Whether a row is before the cutover (its values are wall clocks to convert) or after it (already UTC), from its
     * provenance value. The cutover is the instant the writers started to write UTC. A value that reads as before the
     * cutover under one interpretation and after it under the other is CUTOVER_AMBIGUOUS; one that fits neither is
     * CUTOVER_INCONSISTENT; both are recorded unresolved.
     *
     * @return a resolution whose instant is non-null for a pre-cutover row (the resolved provenance), {@code null}
     *         instant with {@code null} reason for an already-UTC row, or the reason
     */
    public static Resolution side(Instant provenance, ZoneId zone, Instant cutover) {
        return side(provenance, provenance, zone, cutover);
    }

    /**
     * As {@link #side(Instant, ZoneId, Instant)} for a column whose wall clock and stored instant are read apart
     * (decision 240, MySQL session-zoned columns): {@code provenance} is the wall clock as written, {@code stored} the
     * instant the database holds, which is what an already-UTC row wrote.
     */
    public static Resolution side(Instant provenance, Instant stored, ZoneId zone, Instant cutover) {
        if (provenance == null) {
            return new Resolution(null, Unresolved.NO_PROVENANCE);
        }
        Resolution local = resolve(provenance, zone);
        if (!local.resolved()) {
            return local;
        }
        boolean before = local.instant().isBefore(cutover);
        boolean after = !stored.isBefore(cutover);
        if (before && !after) {
            return local;
        }
        if (after && !before) {
            return new Resolution(null, null);
        }
        return new Resolution(null, before ? Unresolved.CUTOVER_AMBIGUOUS : Unresolved.CUTOVER_INCONSISTENT);
    }
}
