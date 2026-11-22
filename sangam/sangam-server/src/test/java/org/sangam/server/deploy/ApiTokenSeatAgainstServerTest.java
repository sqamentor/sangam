// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.SangamSecurity;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register E-31(e). An API token ({@code sgm_...}) holds no seat (IX.5 rule 1: the person holding the seat acts; ruling
 * of 2026-11-20, Q3): a seat header sent with one is refused and the refusal is audited. SANGAM.md S02: "only seats
 * valid today (K7) offered", and every seat {@code GET /me} offers must be accepted when it is sent back.
 * <p>
 * Fail-first on 42439c36: {@code GET /me} with the API token offered the owner's seats (2), each of which the server
 * then refused with 403 SEAT_NOT_HELD.
 */
class ApiTokenSeatAgainstServerTest extends SeatLeftoversHarness {

    private String apiToken;

    @Override
    void seed() throws Exception {
        seat("AT-OWN");
        seat("AT-AC");
        signIn("sl.at.officer");
        post("AT-OWN", "sl.at.officer", "SUBSTANTIVE", today(), null);
        post("AT-AC", "sl.at.officer", "ADDITIONAL_CHARGE", today(), inDays(7));
        SangamSecurity security = ctx.getBean(SangamSecurity.class);
        SecurityIdentity owner = new SecurityIdentity(TENANT, "sl.at.officer", "sl.at.officer", Set.of(), Set.of(), Set.of(), Set.of(),
                "OIDC", null, null, false, Map.of());
        apiToken = ctx.getBean("sangamProductTransactions", Transactions.class).inTransaction(c ->
                security.tokens().issue(c, owner, "sl.at.officer", "seat-leftovers", Set.of(), Instant.now().plusSeconds(3600))).plaintext();
        assertTrue(apiToken.startsWith("sgm_"), "an API token");
    }

    @Test
    void anApiTokenIsOfferedNoSeatAndASeatHeaderFromItIsRefusedAndAudited() throws Exception {
        // the owner, signed in, is offered both seats
        assertEquals(Set.of("AT-OWN", "AT-AC"), seatsOf("sl.at.officer", Map.of()).keySet());
        // the owner's API token works without a seat, and is offered none
        HttpResponse<String> me = send("GET", "/me", apiToken, Map.of(), null);
        assertEquals(200, me.statusCode(), me.body());
        JsonNode body = JSON.readTree(me.body());
        assertEquals("API_KEY", body.path("authMethod").asText());
        assertEquals(0, body.path("seats").size(), "an API token holds no seat, so GET /me offers it none: " + body.path("seats"));
        // a seat header from it is refused, on the own seat and on the acted-for seat, and each refusal is audited
        int before = denied().size();
        assertProblem(403, "SEAT_NOT_HELD", send("GET", "/me", apiToken, Map.of(SEAT, "AT-OWN"), null), "API token names AT-OWN");
        assertProblem(403, "SEAT_NOT_HELD", send("GET", "/tasks", apiToken, Map.of(SEAT, "AT-OWN", ACTING, "AT-AC"), null),
                "API token names the pair");
        assertEquals(2, denied().size() - before, "one AUTH_DENIED per refusal");
    }

    private List<String> denied() throws Exception {
        return events("AUTH_DENIED").stream().filter(e -> e.contains("SEAT_NOT_HELD")).toList();
    }
}
