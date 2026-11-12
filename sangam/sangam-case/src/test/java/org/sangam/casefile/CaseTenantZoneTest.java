// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.sangam.casefile.common.NumberService;
import org.sangam.casefile.config.CaseConfig;
import org.sangam.casefile.deadline.DeadlineService;
import org.sangam.casefile.ports.CaseFilePorts;
import org.sangam.core.layer.Dialect;

/**
 * SL-104 (ADR-D-20): case numbers and deadlines use each tenant's business zone, never one deployment-wide or
 * country zone. Test data only: one tenant's profile is in a +05:30 zone, another in a DST zone; a tenant without a
 * profile is in UTC.
 */
class CaseTenantZoneTest {

    private static final Function<String, ZoneId> ZONES = t -> switch (t == null ? "" : t) {
        case "plus-0530" -> ZoneId.of("Asia/Kolkata");
        case "dst" -> ZoneId.of("Europe/Berlin");
        default -> null; // no profile: UTC
    };

    @Test
    void deadlineArithmeticRunsInTheTenantsZone() {
        DeadlineService d = new DeadlineService(null, null, null, CaseFilePorts.Calendars.PLAIN, null, null, ZONES);
        Instant sat = Instant.parse("2026-03-28T12:00:00Z"); // the night before the 2026 European DST change
        assertEquals(Instant.parse("2026-03-29T11:00:00Z"), d.add("dst", null, sat, "P1D"), "13:00 CET + 1 day = 13:00 CEST");
        assertEquals(Instant.parse("2026-03-29T12:00:00Z"), d.add("no-profile", null, sat, "P1D"), "UTC");
    }

    @Test
    void theNumberingYearIsTheTenantsYear() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:case_zone_" + UUID.randomUUID() + ";MODE=PostgreSQL")) {
            CaseFileSchema.create(c, Dialect.POSTGRESQL);
            NumberService numbers = new NumberService(Dialect.POSTGRESQL, ZONES);
            CaseConfig.Numbering n = new CaseConfig.Numbering("file", "{prefix}{yyyy}/{seq:4}", "F-", "YEARLY");
            Instant yearEnd = Instant.parse("2026-12-31T20:00:00Z"); // already 2027 at +05:30
            assertEquals("F-2027/0001", numbers.next(c, "plus-0530", n, null, yearEnd));
            assertEquals("F-2026/0001", numbers.next(c, "no-profile", n, null, yearEnd));
        }
    }
}