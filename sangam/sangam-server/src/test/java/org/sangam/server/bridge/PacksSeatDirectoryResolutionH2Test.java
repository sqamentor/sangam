// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.time.BusinessClock;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.admin.TenantAdminService;
import org.sangam.security.dialect.SecurityDialect;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * P-6 Step 2, the server's seat directory (SANGAM.md IX.5 rules 1, 2 and 7).
 * <ul>
 *   <li><b>Resolution instant.</b> "The person holding the seat on that date acts" (rule 1): for the current business
 *       date a seat resolves at the instant of the action. Before the fix every lookup was made at 12:00 business time,
 *       so a seat placed after noon (POST /admin/seats places it from its creation instant) served no case until the
 *       next day. Another date keeps its effective-dated meaning (that date's business noon): a seat placed today never
 *       serves yesterday, and a seat placed later than now does not serve yet.</li>
 *   <li><b>Ancestor walk.</b> Routing refers to hierarchy levels (rule 2): the seat of a role serving an office is the
 *       nearest one at the office or an ancestor unit, as {@code InMemorySeatDirectory} does. No office, or no seat up
 *       to the root, is no seat (never another branch's seat).</li>
 * </ul>
 * The business clock is fixed at 15:00 IST, so the test does not depend on the time of day.
 */
class PacksSeatDirectoryResolutionH2Test {

    static final String T = "t-p6-step2";
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate D = LocalDate.of(2026, 6, 10);
    static final Instant NOW = D.atTime(15, 0).atZone(IST).toInstant();
    static final Instant PAST = Instant.parse("2020-01-01T00:00:00Z");

    SangamSecurity security;
    Transactions tx;
    PacksSeatDirectory dir;
    SecurityIdentity setup;

    @BeforeEach
    void given() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:p6s2" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        tx = Transactions.of(ds);
        setup = SecurityIdentity.system(T, "p6-step2").withGroupsAndRoles(Set.of(),
                Set.of("system", TenantAdminService.PLATFORM_ADMIN_ROLE));
        tx.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO SEC_TENANT(tenant_id, name_en, status, config, quotas, four_eyes, created_at)"
                    + " VALUES (?,?,?,?,?,?,?)", T, "t", "ACTIVE", "{}", "{}", 0, PAST);
            security.orgUnits().defineLevels(c, setup, List.of("DEPT", "OFFICE", "SECTION"));
            // DEPT > OFFICE-A > SECTION-A1; DEPT > OFFICE-B; OFFICE-EMPTY under its own root without an SO seat
            security.orgUnits().createUnit(c, setup, "DEPT", "DEPT", null, "Dept", null, PAST);
            security.orgUnits().createUnit(c, setup, "ROOT-2", "DEPT", null, "Other root", null, PAST);
            security.orgUnits().createUnit(c, setup, "OFFICE-A", "OFFICE", "DEPT", "Office A", null, PAST);
            security.orgUnits().createUnit(c, setup, "OFFICE-B", "OFFICE", "DEPT", "Office B", null, PAST);
            security.orgUnits().createUnit(c, setup, "OFFICE-EMPTY", "OFFICE", "ROOT-2", "Empty", null, PAST);
            security.orgUnits().createUnit(c, setup, "SECTION-A1", "SECTION", "OFFICE-A", "Section A1", null, PAST);
            security.seats().createSeat(c, setup, "SO-A", "SO", "OFFICE-A", 1, false, null, PAST);
            security.seats().createSeat(c, setup, "HOD", "HOD", "DEPT", 1, false, null, PAST);
            // placed today after 12:00 and before the action (14:00 IST), and later than the action (16:00 IST)
            security.seats().createSeat(c, setup, "SO-B", "SO", "OFFICE-B", 1, false, null,
                    D.atTime(14, 0).atZone(IST).toInstant());
            security.seats().createSeat(c, setup, "DH-B", "DH", "OFFICE-B", 1, false, null,
                    D.atTime(16, 0).atZone(IST).toInstant());
            return null;
        });
        dir = new PacksSeatDirectory(security,
                new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)),
                BusinessClock.fixedZone(Clock.fixed(NOW, ZoneOffset.UTC), IST));
    }

    // ------------------------------------------------------------------ the resolution instant

    @Test
    void aSeatPlacedAfterNoonServesTheSameDay() {
        assertEquals(List.of("SO-B"), dir.eligibleSeats(T, "SO", "OFFICE-B", D));
        assertEquals(Optional.of("SO-B"), dir.seatForRole(T, "SO", "OFFICE-B"));
    }

    @Test
    void aSeatPlacedLaterThanTheActionDoesNotServeYet() {
        assertEquals(List.of(), dir.eligibleSeats(T, "DH", "OFFICE-B", D));
        assertEquals(Optional.empty(), dir.seatForRole(T, "DH", "OFFICE-B"));
    }

    @Test
    void anotherDateKeepsItsEffectiveDatedMeaning() {
        assertEquals(List.of(), dir.eligibleSeats(T, "SO", "OFFICE-B", D.minusDays(1)), "placed today, not yesterday");
        assertEquals(List.of("SO-B"), dir.eligibleSeats(T, "SO", "OFFICE-B", D.plusDays(1)));
        assertEquals(List.of("DH-B"), dir.eligibleSeats(T, "DH", "OFFICE-B", D.plusDays(1)), "in force tomorrow");
        assertEquals(List.of("SO-A"), dir.eligibleSeats(T, "SO", "OFFICE-A", D.minusDays(1)));
    }

    // ------------------------------------------------------------------ the ancestor walk (IX.5 rule 2)

    @Test
    void theNearestAncestorSeatServesASection() {
        assertEquals(Optional.of("SO-A"), dir.seatForRole(T, "SO", "SECTION-A1"));
        assertEquals(Optional.of("HOD"), dir.seatForRole(T, "HOD", "SECTION-A1"), "two levels up");
        assertEquals(Optional.of("SO-A"), dir.seatForRole(T, "SO", "OFFICE-A"), "the office's own seat first");
    }

    @Test
    void noOfficeOrNoSeatUpToTheRootIsNoSeat() {
        assertEquals(Optional.empty(), dir.seatForRole(T, "SO", null));
        assertEquals(Optional.empty(), dir.seatForRole(T, "SO", ""));
        assertEquals(Optional.empty(), dir.seatForRole(T, "SO", "OFFICE-EMPTY"), "never a seat of another branch");
        assertEquals(Optional.empty(), dir.seatForRole(T, "SO", "NO-SUCH-UNIT"));
        assertEquals(Optional.empty(), dir.seatForRole(T, "SO", "DEPT"), "never a seat below the office");
    }
}
