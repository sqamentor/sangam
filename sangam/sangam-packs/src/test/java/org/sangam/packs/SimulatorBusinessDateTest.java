// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.time.BusinessClock;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.SimulatorConnectorGateway;

/**
 * SL-149: the simulated {@code court.limbs} next hearing is 30 days after the pack's business date (the pack clock
 * in the tenant's business zone, {@code Vars.today}), not after the JVM's {@code LocalDate.now()}. The pack clock is
 * fixed at 2026-12-31T20:00Z, where a tenant at UTC+9 is already on 2027-01-01.
 */
class SimulatorBusinessDateTest {

    static final Instant AT = Instant.parse("2026-12-31T20:00:00Z");
    BusinessClock previous;

    @BeforeEach
    void clocks() {
        PackRuntime.install(PackRuntime.defaults().withClock(Clock.fixed(AT, ZoneOffset.UTC)));
        // the business clock's own instant source is deliberately different: only the pack clock may be used
        previous = BusinessClock.install(BusinessClock.of(Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"),
                ZoneOffset.UTC), t -> "east".equals(t) ? ZoneId.of("Asia/Tokyo") : null));
    }

    @AfterEach
    void restore() {
        BusinessClock.install(previous);
        PackRuntime.reset();
    }

    static Object nextHearing(String tenant) {
        ConnectorRequest r = new ConnectorRequest(tenant, "court.limbs", "status", null, "CASE-1", null, Map.of(), null);
        return new SimulatorConnectorGateway().query(r).data().get("nextHearing");
    }

    @Test
    void theNextHearingFollowsThePackClockInTheTenantsZone() {
        assertEquals("2027-01-31", nextHearing("east"), "UTC+9: business date 2027-01-01");
        assertEquals("2027-01-30", nextHearing("west"), "no profile: the UTC fallback, business date 2026-12-31");
    }

    @Test
    void aRequestWithoutATenantUsesTheDocumentedFallbackZone() {
        assertEquals("2027-01-30", nextHearing(null));
    }
}
