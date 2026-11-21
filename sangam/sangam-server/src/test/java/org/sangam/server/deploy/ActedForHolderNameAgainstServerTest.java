// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.SangamSecurity;
import org.sangam.security.support.Sql;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register E-32(c). SANGAM.md S02 ("header shows 'Acting as ...'"), J-P2-E03 ("header shows 'Acting for DS'"), contract
 * {@code SeatHolding.actingForPersonName}: {@code GET /me} names the officer whose seat the caller acts for. The name
 * comes from the seat directory for the caller's own acting holdings only; the administrators' posting directory
 * stays closed to an officer.
 * <p>
 * Fail-first on 42439c36: since E-24 follow-up 1 the name was left out ({@code actingForPersonName} null), so the UI
 * text "acting for {{name}}" had no name.
 */
class ActedForHolderNameAgainstServerTest extends SeatLeftoversHarness {

    @Override
    void seed() throws Exception {
        for (String s : List.of("HN-LV", "HN-NONAME", "HN-VACANT", "HN-OWN")) {
            seat(s);
        }
        // the officer on leave signed in with a display name (the token's name claim, kept on the JIT person)
        assertEquals(200, send("GET", "/me", token("sl.hn.onleave", USER, Map.of("name", "Asha Verma")), Map.of(), null).statusCode());
        for (String p : List.of("sl.hn.sub", "sl.hn.noname", "sl.hn.sub2", "sl.hn.charge")) {
            signIn(p);
        }
        post("HN-LV", "sl.hn.onleave", "SUBSTANTIVE", today(), null);
        post("HN-NONAME", "sl.hn.noname", "SUBSTANTIVE", today(), null);
        post("HN-OWN", "sl.hn.charge", "SUBSTANTIVE", today(), null);
        post("HN-VACANT", "sl.hn.charge", "ADDITIONAL_CHARGE", today(), inDays(7));
        leave("sl.hn.onleave", "sl.hn.sub");
        leave("sl.hn.noname", "sl.hn.sub2");
    }

    @Test
    void meNamesTheOfficerTheLeaveSubstituteActsFor() throws Exception {
        JsonNode held = seatsOf("sl.hn.sub", Map.of()).get("HN-LV");
        assertEquals("LEAVE_SUBSTITUTE", held.path("kind").asText());
        assertEquals("Asha Verma", held.path("actingForPersonName").asText(null), "GET /me of the substitute: " + held);
        // the same under the header pair the UI sends
        assertEquals("Asha Verma", seatsOf("sl.hn.sub", Map.of(ACTING, "HN-LV")).get("HN-LV").path("actingForPersonName").asText(null));
    }

    /** A person without a display name is named by the person id (what the field held before). */
    @Test
    void aHolderWithoutADisplayNameIsNamedByThePersonId() throws Exception {
        String name = seatsOf("sl.hn.sub2", Map.of()).get("HN-NONAME").path("actingForPersonName").asText(null);
        assertTrue("sl.hn.noname".equals(name), "the officer on leave, by id or by the name the directory holds: " + name);
    }

    /** A vacant seat on additional charge has nobody to name; an own seat names nobody. */
    @Test
    void aVacantSeatAndAnOwnSeatNameNobody() throws Exception {
        Map<String, JsonNode> seats = seatsOf("sl.hn.charge", Map.of());
        assertEquals("ADDITIONAL_CHARGE", seats.get("HN-VACANT").path("kind").asText());
        assertTrue(seats.get("HN-VACANT").path("actingForPersonName").isNull() || seats.get("HN-VACANT").path("actingForPersonName").isMissingNode(),
                String.valueOf(seats.get("HN-VACANT")));
        assertTrue(seats.get("HN-OWN").path("actingForPersonName").isNull() || seats.get("HN-OWN").path("actingForPersonName").isMissingNode(),
                String.valueOf(seats.get("HN-OWN")));
    }

    /** The name does not come from the administrators' posting directory: an officer still may not read it. */
    @Test
    void theOfficerStillMayNotReadThePostingDirectory() throws Exception {
        HttpResponse<String> r = send("GET", "/admin/assignments?seatId=HN-LV", token("sl.hn.sub", USER), Map.of(), null);
        assertEquals(403, r.statusCode(), r.body());
    }

    /** Approved leave from a minute ago for two days with a substitute, approved by another person (four eyes). */
    private void leave(String person, String substitute) {
        SangamSecurity security = ctx.getBean(SangamSecurity.class);
        ctx.getBean("sangamProductTransactions", Transactions.class).inTransaction(c -> {
            Instant now = Sql.dbNow(c, security.dialect());
            return security.postings().recordLeave(c, SecurityIdentity.system(TENANT, "seat-leftovers").withPerson("rajendra.sharma"),
                    person, now.minusSeconds(60), now.plusSeconds(2 * 24 * 3600L), substitute, person);
        });
    }
}
