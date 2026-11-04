// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.booking;

import org.sangam.core.layer.Jdbc;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.catalogue.ResourceType;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The no-double-booking primitive (N01 concurrency): slot-granular claim rows with a database primary key
 * (tenant, resource, slot_key, unit_no). See {@link org.sangam.resources.dialect.ResourcesDialect} for the statement
 * used per dialect.
 *
 * <h2>The claim grid</h2>
 * A booking claims every grid cell its interval touches ({@code floor(start)} up to {@code end}). Because every booking
 * of a resource uses the same grid, two overlapping intervals always share at least one cell, so the primary key makes an
 * overlap impossible, whatever the isolation level and whatever the interleaving. The grid is
 * {@code gcd(slotMinutes, alignMinutes, 60, |zone offsets|)} minutes on the epoch-minute axis: aligning on the zone
 * offsets means adjacent bookings that meet on a local boundary (check-out 12:00 / check-in 12:00 in any zone) do not
 * share a cell, so back-to-back bookings never conflict falsely.
 *
 * <h2>Capacity</h2>
 * A resource with capacity N has units 0..N-1 per cell; a booking of quantity q takes q free units in every cell
 * (pooled capacity: seats of a hall, beds of a dormitory). A resource that must be booked as one whole thing has
 * capacity 1.
 */
public final class ClaimLedger {

    private final ResourcesContext ctx;

    public ClaimLedger(ResourcesContext ctx) {
        this.ctx = ctx;
    }

    /** Grid size in minutes for a type in a zone (stable for the zone: uses winter and summer offsets). */
    public static int gridMinutes(ResourceType type, ZoneId zone) {
        int g = gcd(type.slotMinutes(), type.alignMinutes());
        g = gcd(g, 60);
        int y = 2025;
        int winter = Math.abs(zone.getRules().getOffset(LocalDateTime.of(y, 1, 15, 12, 0)).getTotalSeconds() / 60);
        int summer = Math.abs(zone.getRules().getOffset(LocalDateTime.of(y, 7, 15, 12, 0)).getTotalSeconds() / 60);
        if (winter != 0) {
            g = gcd(g, winter);
        }
        if (summer != 0) {
            g = gcd(g, summer);
        }
        return Math.max(g, 1);
    }

    /** Slot keys (epoch minutes) of every cell that {@code [from, to)} touches. */
    public static List<Long> cells(Instant from, Instant to, int gridMinutes) {
        long start = Sql.slotKey(from);
        long first = Math.floorDiv(start, gridMinutes) * gridMinutes;
        long endExclusive = Sql.slotKey(to.minusNanos(1)) + 1;
        List<Long> out = new ArrayList<>();
        for (long k = first; k < endExclusive; k += gridMinutes) {
            out.add(k);
        }
        return out;
    }

    /**
     * Claims {@code quantity} units in every cell for {@code bookingId}. On failure every claim made by this call is
     * removed and {@code SLOT_TAKEN} is thrown; the caller's transaction stays usable on every dialect.
     *
     * @param staleReleaser called once with the cells that could not be satisfied; returns true when it released
     *                      claims (e.g. lapsed holds), in which case the claim is retried once
     */
    public void claim(Connection c, String tenantId, String resourceId, String bookingId, List<Long> cells, int quantity,
                      int capacity, StaleReleaser staleReleaser) {
        if (quantity <= 0) {
            throw ResourcesException.invalid("QUANTITY", "quantity must be positive");
        }
        if (quantity > capacity) {
            throw ResourcesException.unavailable("CAPACITY", "requested quantity exceeds the capacity of the resource");
        }
        int maxRows = ctx.setting("maxClaimRows", 20000);
        if ((long) cells.size() * quantity > maxRows) {
            throw ResourcesException.invalid("TOO_LONG", "the booking is too long for slot claims; use an allotment");
        }
        List<Long> missing = tryClaim(c, tenantId, resourceId, bookingId, cells, quantity, capacity);
        if (!missing.isEmpty() && staleReleaser != null && staleReleaser.release(c, missing)) {
            missing = tryClaim(c, tenantId, resourceId, bookingId, missing, quantity, capacity);
        }
        if (!missing.isEmpty()) {
            release(c, tenantId, bookingId);
            throw ResourcesException.unavailable("SLOT_TAKEN", "the resource is not available for the whole requested period");
        }
    }

    @FunctionalInterface
    public interface StaleReleaser {
        boolean release(Connection c, List<Long> cells);
    }

    /** Returns the cells where fewer than {@code quantity} units could be claimed. */
    private List<Long> tryClaim(Connection c, String t, String resourceId, String bookingId, List<Long> cells, int quantity,
                                int capacity) {
        Map<Long, Set<Integer>> taken = takenUnits(c, t, resourceId, cells.get(0), cells.get(cells.size() - 1));
        Map<Long, Integer> mine = myUnits(c, t, resourceId, bookingId, cells.get(0), cells.get(cells.size() - 1));
        List<Long> missing = new ArrayList<>();
        String sql = "INSERT INTO RES_CLAIM(tenant_id, resource_id, slot_key, unit_no, booking_id) VALUES (?,?,?,?,?)";
        for (Long cell : cells) {
            int have = mine.getOrDefault(cell, 0);
            Set<Integer> busy = taken.getOrDefault(cell, Set.of());
            for (int unit = 0; unit < capacity && have < quantity; unit++) {
                if (busy.contains(unit)) {
                    continue;
                }
                if (ctx.dialect().insertIfAbsent(c, sql, t, resourceId, cell, unit, bookingId)) {
                    have++;
                }
            }
            if (have < quantity) {
                missing.add(cell);
            }
        }
        return missing;
    }

    private Map<Long, Set<Integer>> takenUnits(Connection c, String t, String resourceId, long first, long last) {
        Map<Long, Set<Integer>> m = new HashMap<>();
        Jdbc.query(c, "SELECT slot_key, unit_no FROM RES_CLAIM WHERE tenant_id=? AND resource_id=? AND slot_key BETWEEN ? AND ?",
                rs -> m.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getInt(2)), t, resourceId, first, last);
        return m;
    }

    private Map<Long, Integer> myUnits(Connection c, String t, String resourceId, String bookingId, long first, long last) {
        Map<Long, Integer> m = new HashMap<>();
        Jdbc.query(c, "SELECT slot_key FROM RES_CLAIM WHERE tenant_id=? AND resource_id=? AND booking_id=? AND slot_key BETWEEN ? AND ?",
                rs -> m.merge(rs.getLong(1), 1, Integer::sum), t, resourceId, bookingId, first, last);
        return m;
    }

    /** Releases every claim of a booking. */
    public int release(Connection c, String tenantId, String bookingId) {
        return Jdbc.update(c, "DELETE FROM RES_CLAIM WHERE tenant_id=? AND booking_id=?", tenantId, bookingId);
    }

    /** Releases the claims of a booking from {@code fromKey} on (early check-out, partial cancellation). */
    public int releaseFrom(Connection c, String tenantId, String bookingId, long fromKey) {
        return Jdbc.update(c, "DELETE FROM RES_CLAIM WHERE tenant_id=? AND booking_id=? AND slot_key >= ?", tenantId, bookingId, fromKey);
    }

    /** Distinct bookings that own a claim in the cells. */
    public Set<String> owners(Connection c, String tenantId, String resourceId, List<Long> cells) {
        Set<String> out = new LinkedHashSet<>();
        if (cells.isEmpty()) {
            return out;
        }
        Set<Long> wanted = new HashSet<>(cells);
        Jdbc.query(c, "SELECT slot_key, booking_id FROM RES_CLAIM WHERE tenant_id=? AND resource_id=? AND slot_key BETWEEN ? AND ?",
                rs -> {
                    if (wanted.contains(rs.getLong(1))) {
                        out.add(rs.getString(2));
                    }
                    return null;
                }, tenantId, resourceId, cells.get(0), cells.get(cells.size() - 1));
        return out;
    }

    /** Units claimed per cell (availability views). */
    public Map<Long, Integer> usage(Connection c, String tenantId, String resourceId, long first, long last) {
        Map<Long, Integer> m = new HashMap<>();
        Jdbc.query(c, "SELECT slot_key, COUNT(*) FROM RES_CLAIM WHERE tenant_id=? AND resource_id=? AND slot_key BETWEEN ? AND ?"
                + " GROUP BY slot_key", rs -> m.put(rs.getLong(1), rs.getInt(2)), tenantId, resourceId, first, last);
        return m;
    }

    /** Whether any claim exists in a range (allotments of BOTH-mode resources must not overlap slot bookings). */
    public boolean anyClaim(Connection c, String tenantId, String resourceId, long first, long last) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM RES_CLAIM WHERE tenant_id=? AND resource_id=? AND slot_key BETWEEN ? AND ?",
                rs -> rs.getLong(1), tenantId, resourceId, first, last).orElse(0L) > 0;
    }

    static int gcd(int a, int b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            int x = a % b;
            a = b;
            b = x;
        }
        return a == 0 ? 1 : a;
    }
}
