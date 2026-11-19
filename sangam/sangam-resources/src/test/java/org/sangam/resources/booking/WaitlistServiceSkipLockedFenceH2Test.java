// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

/**
 * On Oracle the skip-locked selects of {@link WaitlistService#promote} and {@link WaitlistService#allotNext} lock rows
 * chosen from the statement's snapshot, so they can return an entry another node OFFERED or PROMOTED after that
 * snapshot. The peer's committed change is injected right after the select: the entrant must not get a second booking
 * hold, a second allotment or a second OFFERED / ALLOTTED event. Each race has a control run without the race, so the
 * offer / allotment would otherwise be made.
 */
class WaitlistServiceSkipLockedFenceH2Test {

    private static final SecurityIdentity SYSTEM = SecurityIdentity.system("t1", null);

    private Connection c;
    private WaitlistService waitlist;
    private Instant from;
    private Instant to;

    @BeforeEach
    void setUp() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:wlfence" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE", "sa", "");
        ResourcesDialect d = ResourcesDialect.detect(c);
        ResourcesSchema.create(c, d);
        SangamResources res = new SangamResources(ResourcesContext.builder(d).deliverDirectly(true)
                .withoutAuthorization(SkipLockedRaceH2.DEVELOPMENT).build());
        waitlist = res.waitlist();
        res.catalogue().loadTypes(c, SYSTEM, List.of(Map.of("typeId", "ROOM", "name", "Room"),
                Map.of("typeId", "FLAT", "name", "Flat", "bookingMode", "ALLOTMENT")));
        res.catalogue().create(c, SYSTEM, new Resource.Draft("r1", "ROOM", "R-1", "Hall", null, null, null, null, null, 1,
                null, null, null));
        res.catalogue().create(c, SYSTEM, new Resource.Draft("f1", "FLAT", "F-1", "Flat 1", null, null, null, null, null, 1,
                null, null, null));
        from = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.DAYS).plus(Duration.ofHours(10));
        to = from.plus(Duration.ofHours(1));
    }

    @AfterEach
    void tearDown() throws Exception {
        c.close();
    }

    @Test
    void withoutARacePromoteOffersTheEntrant() {
        String entry = roomEntry();
        assertEquals(1, waitlist.promote(c, SYSTEM, "r1", "ROOM", from, to).size(), "control: the entrant is offered");
        assertEquals("OFFERED", state(entry));
        assertEquals(1, count("SELECT COUNT(*) FROM RES_BOOKING"));
    }

    @Test
    void anEntryAPeerOfferedAfterTheSnapshotIsNotOfferedAgain() {
        String entry = roomEntry();
        Connection[] px = new Connection[1];
        SkipLockedRaceH2 race = SkipLockedRaceH2.after("FROM RES_WAITLIST w", real -> Jdbc.update(real,
                "UPDATE RES_WAITLIST SET state='OFFERED', booking_id='peer-booking', row_version=row_version+1 WHERE entry_id=?",
                entry), c, px);

        List<BookingModel.WaitlistEntry> offered = waitlist.promote(px[0], SYSTEM, "r1", "ROOM", from, to);

        assertTrue(race.raced(), "the race was injected");
        assertEquals(0, count("SELECT COUNT(*) FROM RES_BOOKING"), "no second booking hold for the entrant");
        assertEquals(0, events(entry, "OFFERED"), "no second OFFERED event");
        assertEquals(List.of(), offered);
        assertEquals("OFFERED|peer-booking|2", stateBookingVersion(entry), "left as the peer set it");
    }

    @Test
    void withoutARaceAllotNextAllotsTheEntrant() {
        String entry = flatEntry();
        assertTrue(waitlist.allotNext(c, SYSTEM, WaitlistService.typeScope("FLAT"), "f1", from, to, "order-1").isPresent(),
                "control: the entrant is allotted");
        assertEquals("PROMOTED", state(entry));
        assertEquals(1, count("SELECT COUNT(*) FROM RES_ALLOTMENT"));
    }

    @Test
    void anEntryAPeerAllottedAfterTheSnapshotIsNotAllottedAgain() {
        String entry = flatEntry();
        Connection[] px = new Connection[1];
        SkipLockedRaceH2 race = SkipLockedRaceH2.after("FROM RES_WAITLIST w", real -> Jdbc.update(real,
                "UPDATE RES_WAITLIST SET state='PROMOTED', allotment_id='peer-allotment', row_version=row_version+1 WHERE entry_id=?",
                entry), c, px);

        boolean allotted = waitlist.allotNext(px[0], SYSTEM, WaitlistService.typeScope("FLAT"), "f1", from, to, "order-1").isPresent();

        assertTrue(race.raced(), "the race was injected");
        assertEquals(0, count("SELECT COUNT(*) FROM RES_ALLOTMENT"), "no second allotment for the entrant");
        assertEquals(0, events(entry, "ALLOTTED") + events(entry, "ALLOT_SKIPPED"), "the entry is not worked again");
        assertEquals(false, allotted);
        assertEquals("PROMOTED|2", state(entry) + "|" + version(entry), "left as the peer set it");
    }

    private String roomEntry() {
        return waitlist.enqueue(c, SYSTEM, new BookingModel.WaitlistRequest(null, "r1", "p1", null, null, null, null,
                from, to, 1, Map.of())).entryId();
    }

    private String flatEntry() {
        return waitlist.enqueue(c, SYSTEM, new BookingModel.WaitlistRequest("FLAT", null, "p1", null, null, null, null,
                null, null, 1, Map.of())).entryId();
    }

    private String state(String entry) {
        return Jdbc.one(c, "SELECT state FROM RES_WAITLIST WHERE entry_id=?", rs -> rs.getString(1), entry).orElseThrow();
    }

    private long version(String entry) {
        return Jdbc.one(c, "SELECT row_version FROM RES_WAITLIST WHERE entry_id=?", rs -> rs.getLong(1), entry).orElseThrow();
    }

    private String stateBookingVersion(String entry) {
        return Jdbc.one(c, "SELECT state, booking_id, row_version FROM RES_WAITLIST WHERE entry_id=?",
                rs -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getLong(3), entry).orElseThrow();
    }

    private int count(String sql) {
        return Jdbc.one(c, sql, rs -> rs.getInt(1)).orElse(0);
    }

    private int events(String entry, String type) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM RES_EVENT WHERE entity_type='WAITLIST' AND entity_id=? AND event_type=?",
                rs -> rs.getInt(1), entry, type).orElse(0);
    }
}
