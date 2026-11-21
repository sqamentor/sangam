// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register E-32(g). SANGAM.md X.2.3 identity events ({@code AUTH_DENIED} with action, reason, policy_version), CX-1:
 * every refused seat context is on the audit trail. A token whose {@code seats} claim does not name the seat of
 * {@code X-Sangam-Seat} is refused by {@code PrincipalAttributeFilter} before the identity is resolved.
 * <p>
 * Fail-first on 42439c36: that refusal answered 403 SEAT_NOT_HELD and wrote nothing.
 */
class SeatsClaimRefusalAuditAgainstServerTest extends SeatLeftoversHarness {

    @Override
    void seed() throws Exception {
        seat("CL-A");
        seat("CL-B");
        signIn("sl.cl.holder");
        post("CL-A", "sl.cl.holder", "SUBSTANTIVE", today(), null);
        taskFor("CL-A", "CL-A/claim");
    }

    @Test
    void aSeatRefusedByTheTokensSeatsClaimIsWrittenAsAuthDenied() throws Exception {
        List<String> before = denied();
        // the holder, with a token limited to another seat
        assertProblem(403, "SEAT_NOT_HELD", send("GET", "/tasks?businessKey=" + enc("CL-A/claim"),
                token("sl.cl.holder", USER, Map.of("seats", List.of("CL-B"))), Map.of(SEAT, "CL-A"), null), "token names CL-B only");
        List<String> written = new ArrayList<>(denied());
        written.removeAll(before);
        assertEquals(1, written.size(), "one AUTH_DENIED for the refused request: " + written);
        for (String part : List.of("SEAT_NOT_HELD", "CL-A", "sl.cl.holder", "GET /api/v1/tasks", "policy_version", "sec:" + TENANT)) {
            assertTrue(written.get(0).contains(part), "AUTH_DENIED names " + part + ": " + written.get(0));
        }
    }

    /** Control: a token whose claim names the seat is accepted and writes nothing. */
    @Test
    void anAcceptedSeatWritesNoAuthDenied() throws Exception {
        List<String> before = denied();
        assertEquals(List.of("CL-A/claim"), keys(send("GET", "/tasks?businessKey=" + enc("CL-A/claim"),
                token("sl.cl.holder", USER, Map.of("seats", List.of("CL-A"))), Map.of(SEAT, "CL-A"), null)));
        assertEquals(before.size(), denied().size());
    }

    private List<String> denied() throws Exception {
        return events("AUTH_DENIED").stream().filter(e -> e.contains("SEAT_NOT_HELD")).toList();
    }
}
