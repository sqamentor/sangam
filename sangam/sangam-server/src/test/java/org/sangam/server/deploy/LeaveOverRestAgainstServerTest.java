// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register E-31(a). SANGAM.md AJ-04 step 5 ("PER-1 to controlling officer, S14 to A03: leave substitute request and
 * approval; requester cannot approve; date range enforced"), S14 ("Request leave substitute (goes to PER-5 or
 * controlling officer)"), S02 ("charges pending approval"), K7; contract: {@code POST /me/leave-requests} "creates a
 * LEAVE_SUBSTITUTE assignment pending approval", {@code POST /admin/assignments/{id}/approve} "four-eyes decision on
 * a pending assignment (leave, additional charge)".
 * <p>
 * Fail-first on 42439c36: {@code POST /admin/assignments} kind LEAVE_SUBSTITUTE answered 422 SOD_VIOLATION for every
 * administrator (the caller was passed as the requester and as the approver), and an officer's own request was
 * refused, so no leave substitute could be recorded over REST at all.
 */
class LeaveOverRestAgainstServerTest extends SeatLeftoversHarness {

    private static final String SECOND_ADMIN = "meenakshi.kanwar";

    @Override
    void seed() throws Exception {
        for (String s : List.of("LV-A", "LV-ADM", "LV-MK", "LV-REQ", "LV-REJ", "LV-BYSEAT", "LV-SUBSEAT", "LV-OTHER")) {
            seat(s);
        }
        for (String p : List.of("sl.a", "sl.sub.a", "sl.sub.adm", "sl.sub.mk", "sl.req", "sl.sub.req", "sl.rej", "sl.sub.rej",
                "sl.byseat", "sl.subseat", "sl.other")) {
            signIn(p);
        }
        assertEquals(200, send("GET", "/me", token(SECOND_ADMIN, ADMIN), Map.of(), null).statusCode());
        post("LV-A", "sl.a", "SUBSTANTIVE", today(), null);
        post("LV-ADM", "rajendra.sharma", "SUBSTANTIVE", today(), null);
        post("LV-MK", SECOND_ADMIN, "SUBSTANTIVE", today(), null);
        post("LV-REQ", "sl.req", "SUBSTANTIVE", today(), null);
        post("LV-REJ", "sl.rej", "SUBSTANTIVE", today(), null);
        post("LV-BYSEAT", "sl.byseat", "SUBSTANTIVE", today(), null);
        post("LV-SUBSEAT", "sl.subseat", "SUBSTANTIVE", today(), null);
        post("LV-OTHER", "sl.other", "SUBSTANTIVE", today(), null);
    }

    // ------------------------------------------------------------------ A03: the administrator records the leave

    /** The administrator approves and records the leave of another officer: the officer is the requester. */
    @Test
    void anAdministratorRecordsTheLeaveOfAnOfficer() throws Exception {
        HttpResponse<String> r = send("POST", "/admin/assignments", admin, Map.of(), map("seatId", "LV-A", "personId", "sl.sub.a",
                "kind", "LEAVE_SUBSTITUTE", "validFrom", today(), "validTo", inDays(2), "orderRef", "Leave order 1"));
        assertEquals(201, r.statusCode(), "the administrator records the leave of sl.a with substitute sl.sub.a: " + r.body());
        String id = JSON.readTree(r.body()).path("id").asText();
        assertEquals(List.of("sl.a|sl.a|rajendra.sharma|APPROVED"), leaves("sl.a"), "person|requested_by|approved_by|status");
        // the substitute acts in the seat, the officer on leave does not (K7)
        assertEquals("LEAVE_SUBSTITUTE", seatsOf("sl.sub.a", Map.of()).get("LV-A").path("kind").asText());
        assertEquals(200, send("GET", "/me", token("sl.sub.a", USER), Map.of(ACTING, "LV-A"), null).statusCode());
        assertProblem(403, "SEAT_NOT_HELD", send("GET", "/me", token("sl.a", USER), Map.of(SEAT, "LV-A"), null), "sl.a on leave");
        // the id the answer gave can be read back
        HttpResponse<String> read = send("GET", "/admin/assignments/" + id, admin, Map.of(), null);
        assertEquals(200, read.statusCode(), "GET /admin/assignments/" + id + ": " + read.body());
        assertEquals("LV-A sl.sub.a LEAVE_SUBSTITUTE", JSON.readTree(read.body()).path("seatId").asText() + " "
                + JSON.readTree(read.body()).path("personId").asText() + " " + JSON.readTree(read.body()).path("kind").asText());
    }

    /** A real four-eyes violation is still refused: the administrator cannot approve his own leave. */
    @Test
    void anAdministratorCannotRecordHisOwnLeave() throws Exception {
        HttpResponse<String> r = send("POST", "/admin/assignments", admin, Map.of(), map("seatId", "LV-ADM", "personId", "sl.sub.adm",
                "kind", "LEAVE_SUBSTITUTE", "validFrom", today(), "validTo", inDays(2), "orderRef", "Leave order 2"));
        assertProblem(422, "SOD_VIOLATION", r, "rajendra.sharma records his own leave from LV-ADM");
        assertEquals(List.of(), leaves("rajendra.sharma"), "nothing is recorded");
        assertTrue(!seatsOf("sl.sub.adm", Map.of()).containsKey("LV-ADM"), "the substitute holds nothing");
        assertEquals(200, send("GET", "/me", admin, Map.of(SEAT, "LV-ADM"), null).statusCode(), "the administrator still holds LV-ADM");
    }

    // ------------------------------------------------------------------ S14: the officer requests, another approves

    @Test
    void anOfficerRequestsLeaveAndTheAdministratorApproves() throws Exception {
        HttpResponse<String> req = send("POST", "/me/leave-requests", token("sl.req", USER), Map.of(SEAT, "LV-REQ"), map(
                "seatId", "LV-REQ", "substitutePersonId", "sl.sub.req", "from", today(), "to", inDays(2), "reason", "Earned leave"));
        assertEquals(201, req.statusCode(), "sl.req asks for leave from LV-REQ: " + req.body());
        String id = JSON.readTree(req.body()).path("id").asText();
        // pending: nothing has taken effect
        assertEquals(List.of("sl.req|sl.req|null|REQUESTED"), leaves("sl.req"));
        assertProblem(403, "SEAT_NOT_HELD", send("GET", "/me", token("sl.sub.req", USER), Map.of(ACTING, "LV-REQ"), null),
                "the substitute before the approval");
        assertEquals(200, send("GET", "/me", token("sl.req", USER), Map.of(SEAT, "LV-REQ"), null).statusCode());
        // approval by the administrator (PER-5)
        HttpResponse<String> ok = send("POST", "/admin/assignments/" + id + "/approve", admin, Map.of(), Map.of("reason", "Leave sanctioned"));
        assertEquals(200, ok.statusCode(), "the administrator approves: " + ok.body());
        assertEquals(List.of("sl.req|sl.req|rajendra.sharma|APPROVED"), leaves("sl.req"));
        assertEquals(200, send("GET", "/me", token("sl.sub.req", USER), Map.of(ACTING, "LV-REQ"), null).statusCode(),
                "the substitute acts for LV-REQ after the approval");
        assertProblem(403, "SEAT_NOT_HELD", send("GET", "/me", token("sl.req", USER), Map.of(SEAT, "LV-REQ"), null), "sl.req on leave");
        // decided once
        assertProblem(409, "INVALID_TRANSITION", send("POST", "/admin/assignments/" + id + "/approve", token(SECOND_ADMIN, ADMIN),
                Map.of(), Map.of("reason", "Again")), "a second decision");
    }

    /** Requester cannot approve (AJ-04 step 5), also when the requester is an administrator. */
    @Test
    void theRequesterCannotApproveTheRequest() throws Exception {
        String second = token(SECOND_ADMIN, ADMIN);
        HttpResponse<String> req = send("POST", "/me/leave-requests", second, Map.of(SEAT, "LV-MK"), map(
                "seatId", "LV-MK", "substitutePersonId", "sl.sub.mk", "from", today(), "to", inDays(2), "reason", "Casual leave"));
        assertEquals(201, req.statusCode(), SECOND_ADMIN + " asks for leave from LV-MK: " + req.body());
        String id = JSON.readTree(req.body()).path("id").asText();
        assertProblem(422, "SOD_VIOLATION", send("POST", "/admin/assignments/" + id + "/approve", second, Map.of(),
                Map.of("reason", "My own leave")), "the requester approves her own request");
        assertEquals(List.of(SECOND_ADMIN + "|" + SECOND_ADMIN + "|null|REQUESTED"), leaves(SECOND_ADMIN), "still pending");
        assertTrue(!seatsOf("sl.sub.mk", Map.of()).containsKey("LV-MK"), "the substitute holds nothing");
        assertEquals(200, send("POST", "/admin/assignments/" + id + "/approve", admin, Map.of(), Map.of("reason", "Sanctioned")).statusCode());
        assertEquals("LEAVE_SUBSTITUTE", seatsOf("sl.sub.mk", Map.of()).get("LV-MK").path("kind").asText());
    }

    @Test
    void aRejectedRequestGivesNoSeat() throws Exception {
        HttpResponse<String> req = send("POST", "/me/leave-requests", token("sl.rej", USER), Map.of(SEAT, "LV-REJ"), map(
                "seatId", "LV-REJ", "substitutePersonId", "sl.sub.rej", "from", today(), "to", inDays(2), "reason", "Leave"));
        assertEquals(201, req.statusCode(), req.body());
        String id = JSON.readTree(req.body()).path("id").asText();
        assertEquals(200, send("POST", "/admin/assignments/" + id + "/reject", admin, Map.of(), Map.of("reason", "Not now")).statusCode());
        assertEquals(List.of("sl.rej|sl.rej|rajendra.sharma|REJECTED"), leaves("sl.rej"));
        assertTrue(!seatsOf("sl.sub.rej", Map.of()).containsKey("LV-REJ"), "the substitute holds nothing");
        assertEquals(200, send("GET", "/me", token("sl.rej", USER), Map.of(SEAT, "LV-REJ"), null).statusCode(), "sl.rej still holds LV-REJ");
        assertProblem(409, "INVALID_TRANSITION", send("POST", "/admin/assignments/" + id + "/approve", admin, Map.of(),
                Map.of("reason", "After all")), "approving a rejected request");
    }

    /** sangam-ui names the substitute by seat (MyWork.tsx): the seat's holder becomes the substitute. */
    @Test
    void theSubstituteMayBeNamedBySeat() throws Exception {
        HttpResponse<String> req = send("POST", "/me/leave-requests", token("sl.byseat", USER), Map.of(SEAT, "LV-BYSEAT"), map(
                "seatId", "LV-BYSEAT", "substituteSeatId", "LV-SUBSEAT", "from", today(), "to", inDays(2), "reason", "Leave"));
        assertEquals(201, req.statusCode(), "substitute named by seat LV-SUBSEAT: " + req.body());
        assertEquals("sl.subseat", JSON.readTree(req.body()).path("personId").asText());
    }

    @Test
    void anOfficerCannotRequestLeaveForASeatHeldByAnother() throws Exception {
        HttpResponse<String> req = send("POST", "/me/leave-requests", token("sl.other", USER), Map.of(SEAT, "LV-OTHER"), map(
                "seatId", "LV-BYSEAT", "substitutePersonId", "sl.sub.rej", "from", today(), "to", inDays(2), "reason", "Leave"));
        assertProblem(403, "SEAT_NOT_HELD", req, "sl.other asks for leave from LV-BYSEAT");
    }

    /** The leave rows of a person: person|requested_by|approved_by|status (read-only). */
    private List<String> leaves(String person) throws Exception {
        List<String> out = new ArrayList<>();
        try (var c = ctx.getBean(javax.sql.DataSource.class).getConnection();
             var ps = c.prepareStatement("SELECT person_id, requested_by, approved_by, status FROM SEC_LEAVE WHERE tenant_id=?"
                     + " AND person_id=? ORDER BY created_at")) {
            ps.setString(1, TENANT);
            ps.setString(2, person);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4));
                }
            }
        }
        return out;
    }
}
