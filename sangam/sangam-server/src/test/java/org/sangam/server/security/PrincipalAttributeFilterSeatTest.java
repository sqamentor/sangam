// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.port.SeatHoldings;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Register E-24: what {@link PrincipalAttributeFilter} does for the seat context. The token's seats list limits
 * {@code X-Sangam-Seat} and never gives a seat; the holdings of the request come from {@code SecurityIdentityFilter}
 * (security module on: this filter sets none) or, with the module off, from the seat directory given to this filter
 * ({@link AdminStoreSeatHoldings}), asked only when the request names a seat.
 */
class PrincipalAttributeFilterSeatTest {

    private static MockHttpServletRequest request(Map<String, Object> claims, String seat, String actingFor) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/tasks");
        r.setAttribute(SangamPrincipal.ATTRIBUTE, new SangamPrincipal("t1", "kavita.sharma", Set.of("sangam-user"), "OIDC", claims));
        if (seat != null) {
            r.addHeader(PrincipalAttributeFilter.SEAT_HEADER, seat);
        }
        if (actingFor != null) {
            r.addHeader(PrincipalAttributeFilter.ACTING_FOR_HEADER, actingFor);
        }
        return r;
    }

    private static boolean passes(PrincipalAttributeFilter f, MockHttpServletRequest r, MockHttpServletResponse res) throws Exception {
        boolean[] reached = new boolean[1];
        f.doFilter(r, res, (rq, rs) -> reached[0] = true);
        return reached[0];
    }

    @Test
    void theTokensSeatsListLimitsTheHeader() throws Exception {
        PrincipalAttributeFilter f = new PrincipalAttributeFilter();
        MockHttpServletResponse refused = new MockHttpServletResponse();
        assertFalse(passes(f, request(Map.of("seats", List.of("DDO-1")), "PAO-1", null), refused));
        assertEquals(403, refused.getStatus());
        assertTrue(refused.getContentAsString().contains("SEAT_NOT_HELD"), refused.getContentAsString());
        assertTrue(passes(f, request(Map.of("seats", List.of("DDO-1")), "DDO-1", null), new MockHttpServletResponse()));
        assertTrue(passes(f, request(Map.of(), "PAO-1", null), new MockHttpServletResponse()),
                "no seats claim: the token limits nothing; the holdings of the request decide");
    }

    @Test
    void withTheSecurityModuleThisFilterResolvesNoHoldings() throws Exception {
        MockHttpServletRequest r = request(Map.of("seats", List.of("PAO-1")), "PAO-1", null);
        assertTrue(passes(new PrincipalAttributeFilter(), r, new MockHttpServletResponse()));
        assertNull(r.getAttribute(SeatHoldings.ATTRIBUTE), "a seats claim is never a holding");
    }

    @Test
    void withoutTheSecurityModuleTheSeatDirectoryIsAskedOnlyWhenASeatIsNamed() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        SeatHoldings held = new SeatHoldings(Set.of("DDO-1"), Set.of());
        PrincipalAttributeFilter f = new PrincipalAttributeFilter(p -> {
            asked.incrementAndGet();
            assertEquals("kavita.sharma", p.principalId());
            return held;
        });
        MockHttpServletRequest plain = request(Map.of(), null, null);
        assertTrue(passes(f, plain, new MockHttpServletResponse()));
        assertNull(plain.getAttribute(SeatHoldings.ATTRIBUTE));
        assertEquals(0, asked.get());
        MockHttpServletRequest seat = request(Map.of(), "PAO-1", null);
        assertTrue(passes(f, seat, new MockHttpServletResponse()));
        assertEquals(held, seat.getAttribute(SeatHoldings.ATTRIBUTE));
        MockHttpServletRequest acting = request(Map.of(), null, "PAO-1");
        assertTrue(passes(f, acting, new MockHttpServletResponse()));
        assertEquals(held, acting.getAttribute(SeatHoldings.ATTRIBUTE));
        assertEquals(2, asked.get());
    }

    private static AdminPort.Assignment assignment(String seat, String person, String kind, boolean ended) {
        return new AdminPort.Assignment("a-" + seat + "-" + person, "t1", seat, person, kind, LocalDate.of(2026, 11, 1), null,
                "SUBSTANTIVE".equals(kind) ? null : seat, null, "admin", Instant.EPOCH, ended);
    }

    /** The admin store's assignments in force on the date, as holdings: own, not ended; acting = not SUBSTANTIVE. */
    @Test
    void theAdminStoresAssignmentsAsHoldings() {
        SeatHoldings h = AdminStoreSeatHoldings.of(List.of(
                assignment("DDO-1", "kavita.sharma", "SUBSTANTIVE", false),
                assignment("TO-1", "kavita.sharma", "ADDITIONAL_CHARGE", false),
                assignment("AAO-1", "kavita.sharma", "LEAVE_SUBSTITUTE", false),
                assignment("PAO-1", "kavita.sharma", "SUBSTANTIVE", true),
                assignment("CAA", "sunita.rathore", "SUBSTANTIVE", false)), "kavita.sharma");
        assertEquals(Set.of("DDO-1", "TO-1", "AAO-1"), h.seats());
        assertEquals(Set.of("TO-1", "AAO-1"), h.actingFor());
    }

    @Test
    void anAdminStoreThatCannotBeReadGivesNoSeat() {
        SangamPrincipal p = new SangamPrincipal("t1", "kavita.sharma", Set.of(), "DEV_TOKEN", Map.of());
        assertEquals(SeatHoldings.NONE, new AdminStoreSeatHoldings(() -> null, org.sangam.rest.support.BusinessDates.utc()).apply(p));
    }
}
