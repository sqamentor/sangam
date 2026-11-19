// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.ResourcesSchema;
import org.sangam.resources.SangamResources;
import org.sangam.resources.catalogue.Resource;
import org.sangam.resources.dialect.ResourcesDialect;
import org.sangam.resources.port.ResourcesPorts;

/**
 * On Oracle the skip-locked selects of {@link BookingService#markNoShows} and {@link BookingService#expireHolds} lock
 * rows chosen from the statement's snapshot, so they can return a booking a peer marked NO_SHOW (and refunded), checked
 * in, or whose hold was renewed, after that snapshot. The peer's committed change is injected right after the select:
 * the sweeper must leave the booking as the peer set it (no second refund, no NO_SHOW event, no expiry).
 */
class BookingServiceSkipLockedFenceH2Test {

    private static final SecurityIdentity SYSTEM = SecurityIdentity.system("t1", null);

    private final List<String> refunds = new CopyOnWriteArrayList<>();
    private Connection c;
    private BookingService bookings;

    @BeforeEach
    void setUp() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:bkfence" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE", "sa", "");
        ResourcesDialect d = ResourcesDialect.detect(c);
        ResourcesSchema.create(c, d);
        ResourcesPorts.BillingPort billing = new ResourcesPorts.BillingPort() {
            @Override
            public ResourcesPorts.Quote quote(Connection cc, String tenantId, String tariffCode, Map<String, Object> inputs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String charge(Connection cc, String tenantId, String key, String payerId, String subjectRef,
                                 ResourcesPorts.Quote quote) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String refund(Connection cc, String tenantId, String key, String chargeRef, BigDecimal amount,
                                 String currency, String reason) {
                refunds.add(key + " " + amount.toPlainString());
                return "refund-" + refunds.size();
            }
        };
        SangamResources res = new SangamResources(ResourcesContext.builder(d).deliverDirectly(true).billing(billing)
                .withoutAuthorization(SkipLockedRaceH2.DEVELOPMENT).build());
        bookings = res.bookings();
        res.catalogue().loadTypes(c, SYSTEM, List.of(Map.of("typeId", "ROOM", "name", "Room", "noShowGraceMinutes", 15,
                "extra", Map.of("noShowRefundPercent", 50))));
        res.catalogue().create(c, SYSTEM, new Resource.Draft("r1", "ROOM", "R-1", "Hall", null, null, null, null, null, 1,
                null, null, null));
    }

    @AfterEach
    void tearDown() throws Exception {
        c.close();
    }

    @Test
    void aBookingAPeerMarkedNoShowAfterTheSnapshotIsNotRefundedAgain() {
        Instant start = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        booking("b1", "CONFIRMED", start, null);
        Connection[] px = new Connection[1];
        SkipLockedRaceH2 race = SkipLockedRaceH2.after("FROM RES_BOOKING b", real -> Jdbc.update(real,
                "UPDATE RES_BOOKING SET state='NO_SHOW', refund_amount=50, row_version=row_version+1 WHERE booking_id='b1'"), c, px);

        RuntimeException failed = null;
        int n = -1;
        try {
            n = bookings.markNoShows(px[0], SYSTEM, 10);
        } catch (RuntimeException e) {
            failed = e;
        }

        assertTrue(race.raced(), "the race was injected");
        assertEquals(List.of(), refunds, "the peer's refund is not repeated");
        assertNull(failed, "the peer's NO_SHOW is not processed again");
        assertEquals(0, n);
        assertEquals("NO_SHOW|2", stateAndVersion("b1"), "left as the peer set it");
        assertEquals(0, events("b1", "NO_SHOW"), "no second NO_SHOW event");
    }

    @Test
    void aBookingAPeerCheckedInAfterTheSnapshotIsNotMarkedNoShow() {
        Instant start = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        booking("b1", "CONFIRMED", start, null);
        Connection[] px = new Connection[1];
        SkipLockedRaceH2 race = SkipLockedRaceH2.after("FROM RES_BOOKING b", real -> Jdbc.update(real,
                "UPDATE RES_BOOKING SET state='CHECKED_IN', row_version=row_version+1 WHERE booking_id='b1'"), c, px);

        RuntimeException failed = null;
        int n = -1;
        try {
            n = bookings.markNoShows(px[0], SYSTEM, 10);
        } catch (RuntimeException e) {
            failed = e;
        }

        assertTrue(race.raced(), "the race was injected");
        assertEquals(List.of(), refunds, "a checked-in booking is not refunded");
        assertNull(failed, "a checked-in booking is not processed as a no-show");
        assertEquals(0, n);
        assertEquals("CHECKED_IN|2", stateAndVersion("b1"));
        assertEquals(0, events("b1", "NO_SHOW"));
    }

    @Test
    void aHoldAPeerRenewedAfterTheSnapshotIsNotExpired() {
        Instant start = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
        booking("b1", "HELD", start, Instant.now().minus(Duration.ofMinutes(5)));
        Instant renewed = Instant.now().plus(Duration.ofMinutes(30));
        Connection[] px = new Connection[1];
        SkipLockedRaceH2 race = SkipLockedRaceH2.after("FROM RES_BOOKING b", real -> Jdbc.update(real,
                "UPDATE RES_BOOKING SET hold_expires_at=?, row_version=row_version+1 WHERE booking_id='b1'", renewed), c, px);

        int n = bookings.expireHolds(px[0], SYSTEM, 10);

        assertTrue(race.raced(), "the race was injected");
        assertEquals(0, n, "a renewed hold is not expired");
        assertEquals("HELD|2", stateAndVersion("b1"));
        assertEquals(0, events("b1", "EXPIRED"));
    }

    /** A charged booking of r1, written as the booking flow leaves it. */
    private void booking(String id, String state, Instant start, Instant holdExpires) {
        Instant now = Instant.now();
        Jdbc.update(c, "INSERT INTO RES_BOOKING(" + BookingService.COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "t1", id, "REF-" + id, "r1", "ROOM", state, "p1", null, null, null, null, 0, 1, start, start.plus(Duration.ofHours(1)),
                holdExpires, null, new BigDecimal("100.00"), null, "INR", "charge-" + id, null, null, null, null, null, null,
                "{}", 1L, now, now);
    }

    private String stateAndVersion(String id) {
        return Jdbc.one(c, "SELECT state, row_version FROM RES_BOOKING WHERE booking_id=?",
                rs -> rs.getString(1) + "|" + rs.getLong(2), id).orElseThrow();
    }

    private int events(String id, String type) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM RES_EVENT WHERE entity_type='BOOKING' AND entity_id=? AND event_type=?",
                rs -> rs.getInt(1), id, type).orElse(0);
    }
}
