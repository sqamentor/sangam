// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.port.SeatHoldings;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Register E-24 (IX.5 rules 1 and 4, K7): the request context takes a seat from the headers only when the host's
 * {@link SeatHoldings} of the request say the caller holds it now. This is the one place a request context is built
 * from a request, so no port or adapter can receive an unverified seat. Before, the header value was copied as it came.
 */
class RequestContextsSeatTest {

    private static MockHttpServletRequest request(String seat, String actingFor, SeatHoldings held) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/tasks");
        r.setAttribute(SangamPrincipal.ATTRIBUTE, new SangamPrincipal("t1", "kavita.sharma", Set.of("sangam-user"), "OIDC", Map.of()));
        if (seat != null) {
            r.addHeader(RequestContexts.SEAT_HEADER, seat);
        }
        if (actingFor != null) {
            r.addHeader(RequestContexts.ACTING_FOR_HEADER, actingFor);
        }
        if (held != null) {
            r.setAttribute(SeatHoldings.ATTRIBUTE, held);
        }
        return r;
    }

    private static void assertNotHeld(MockHttpServletRequest r) {
        ApiProblem p = assertThrows(ApiProblem.class, () -> RequestContexts.from(r));
        assertEquals(403, p.status());
        assertEquals("SEAT_NOT_HELD", p.code());
    }

    @Test
    void aRequestThatNamesNoSeatNeedsNoHolding() {
        RequestContext ctx = RequestContexts.from(request(null, null, null));
        assertNull(ctx.seatId());
        assertNull(ctx.actingForSeatId());
        RequestContext blank = RequestContexts.from(request("  ", " ", null));
        assertNull(blank.seatId(), "a blank header names no seat");
        assertNull(blank.actingForSeatId());
    }

    @Test
    void aNamedSeatIsTakenOnlyFromTheHoldingsOfTheRequest() {
        SeatHoldings held = new SeatHoldings(Set.of("DDO-1"), Set.of());
        assertEquals("DDO-1", RequestContexts.from(request("DDO-1", null, held)).seatId());
        assertEquals("DDO-1", RequestContexts.from(request(" DDO-1 ", null, held)).seatId(), "the header is trimmed");
        assertNotHeld(request("PAO-1", null, held));
        assertNotHeld(request("PAO-1", null, SeatHoldings.NONE));
    }

    /** Fail closed: a host that resolved no holdings for the request (no seat directory in the chain) gives no seat. */
    @Test
    void withoutHoldingsOnTheRequestEverySeatIsRefused() {
        assertNotHeld(request("DDO-1", null, null));
        assertNotHeld(request(null, "DDO-1", null));
        MockHttpServletRequest wrongType = request("DDO-1", null, null);
        wrongType.setAttribute(SeatHoldings.ATTRIBUTE, Set.of("DDO-1"));
        assertNotHeld(wrongType);
    }

    /** IX.5 rule 4: the acted-for seat is one held by additional charge or as leave substitute. */
    @Test
    void theActedForSeatMustBeAnActingHolding() {
        SeatHoldings held = new SeatHoldings(Set.of("DDO-1", "PAO-1"), Set.of("PAO-1"));
        RequestContext ctx = RequestContexts.from(request("DDO-1", "PAO-1", held));
        assertEquals("DDO-1", ctx.seatId());
        assertEquals("PAO-1", ctx.actingForSeatId());
        assertEquals("PAO-1", RequestContexts.from(request(null, "PAO-1", held)).actingForSeatId());
        assertEquals("PAO-1", RequestContexts.from(request("PAO-1", null, held)).seatId(), "an acting seat is a held seat");
        assertNotHeld(request("DDO-1", "DDO-1", held));
        assertNotHeld(request("DDO-1", "TO-1", held));
        assertNotHeld(request("TO-1", "PAO-1", held));
    }

    @Test
    void theContextOfARequestIsBuiltOnce() {
        MockHttpServletRequest r = request("DDO-1", null, new SeatHoldings(Set.of("DDO-1"), Set.of()));
        RequestContext first = RequestContexts.from(r);
        // a refused request has no cached context: the check runs again and refuses again
        MockHttpServletRequest refused = request("PAO-1", null, SeatHoldings.NONE);
        assertNotHeld(refused);
        assertNotHeld(refused);
        assertEquals(first, RequestContexts.from(r));
    }
}
