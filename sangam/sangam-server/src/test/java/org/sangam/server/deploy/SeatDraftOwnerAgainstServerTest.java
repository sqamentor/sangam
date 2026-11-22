// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Register E-32(f). SANGAM.md X.2.5.8 rule 3 ("for seat-owned work the default is SEAT: when the seat holder changes,
 * the new holder sees the draft labelled 'drafted by ...'"), T77 ("a seat handover makes a SEAT draft visible to the
 * new holder with attribution"), CX-1 ("task stays with the seat"): a draft written while acting for a seat belongs to
 * the seat acted for, the seat the task waits for.
 * <p>
 * Fail-first on 42439c36: under the header pair sangam-ui sends (X-Sangam-Seat = own seat, X-Sangam-Acting-For-Seat =
 * the seat acted for) the draft was owned by the officer's own seat, so the next holder of the acted-for seat never
 * saw it.
 */
class SeatDraftOwnerAgainstServerTest extends SeatLeftoversHarness {

    private String actedTask;
    private String ownTask;
    private String charge;

    @Override
    void seed() throws Exception {
        for (String s : List.of("DR-AC", "DR-OWN")) {
            seat(s);
        }
        for (String p : List.of("sl.dr.charge", "sl.dr.next")) {
            signIn(p);
        }
        post("DR-OWN", "sl.dr.charge", "SUBSTANTIVE", today(), null);
        charge = post("DR-AC", "sl.dr.charge", "ADDITIONAL_CHARGE", today(), inDays(7));
        actedTask = taskFor("DR-AC", "DR-AC/draft");
        ownTask = taskFor("DR-OWN", "DR-OWN/draft");
    }

    @Test
    void aDraftWrittenWhileActingBelongsToTheSeatActedForAndPassesToItsNextHolder() throws Exception {
        String officer = token("sl.dr.charge", USER);
        Map<String, String> pair = Map.of(SEAT, "DR-OWN", ACTING, "DR-AC");
        HttpResponse<String> saved = send("POST", "/forms/drafts", officer, pair, map("taskId", actedTask, "formKey", "seatleft-form",
                "data", Map.of("remarks", "Half written while acting for DR-AC")));
        assertEquals(201, saved.statusCode(), saved.body());
        JsonNode draft = JSON.readTree(saved.body());
        assertEquals("SEAT DR-AC", draft.path("ownerKind").asText() + " " + draft.path("ownerSeatId").asText(),
                "the draft of a task of DR-AC, written under the pair " + pair);
        String id = draft.path("draftId").asText();
        // the writer finds it again under the pair, and not among the drafts of the own seat
        assertEquals(List.of(id), drafts(officer, pair, actedTask));
        assertEquals(List.of(), drafts(officer, Map.of(SEAT, "DR-OWN"), actedTask), "under the own seat alone");
        // handover: the charge ends, DR-AC gets a holder; the new holder sees the draft, attributed to its writer
        ok(send("POST", "/admin/assignments/" + charge + "/end", admin, Map.of(), map("reason", "Charge ended")));
        post("DR-AC", "sl.dr.next", "SUBSTANTIVE", today(), null);
        String next = token("sl.dr.next", USER);
        assertEquals(List.of(id), drafts(next, Map.of(SEAT, "DR-AC"), actedTask), "the new holder of DR-AC");
        HttpResponse<String> read = send("GET", "/forms/drafts/" + id, next, Map.of(SEAT, "DR-AC"), null);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals("sl.dr.charge", JSON.readTree(read.body()).path("lastEditorPrincipalId").asText(), "drafted by");
    }

    /** Control: a draft of an own-seat task under the own seat belongs to the own seat (unchanged). */
    @Test
    void aDraftUnderTheOwnSeatBelongsToTheOwnSeat() throws Exception {
        HttpResponse<String> saved = send("POST", "/forms/drafts", token("sl.dr.charge", USER), Map.of(SEAT, "DR-OWN"),
                map("taskId", ownTask, "formKey", "seatleft-form", "data", Map.of("remarks", "Own seat")));
        assertEquals(201, saved.statusCode(), saved.body());
        assertEquals("SEAT DR-OWN", JSON.readTree(saved.body()).path("ownerKind").asText() + " "
                + JSON.readTree(saved.body()).path("ownerSeatId").asText());
    }

    private List<String> drafts(String token, Map<String, String> headers, String task) throws Exception {
        HttpResponse<String> r = send("GET", "/forms/drafts?taskId=" + enc(task), token, headers, null);
        assertEquals(200, r.statusCode(), r.body());
        JsonNode body = JSON.readTree(r.body());
        List<String> ids = new ArrayList<>();
        (body.isArray() ? body : body.path("items")).forEach(d -> ids.add(d.path("draftId").asText()));
        return ids;
    }
}
