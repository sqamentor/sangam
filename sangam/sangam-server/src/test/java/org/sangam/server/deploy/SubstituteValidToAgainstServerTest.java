// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register E-31(b). SANGAM.md J-P1-E06 ("Delegate expired: substitute's valid-to passed while task was in their queue;
 * detected by K7 at action and a timer at valid-to; the task leaves the 'acting for' section; audit DELEGATION_EXPIRED"),
 * AJ-04 step 4 ("charge ends: open forms get CX-1, tasks back to the seat's holder; DELEGATION_EXPIRED"), PER-4 ("must
 * never use substitute authority after valid-to"), IX.5 rule 4, K7. The "delegate" of the specification is the holder
 * of an acting posting (additional charge, leave substitute): {@code DELEGATION_GRANTED} and {@code DELEGATION_EXPIRED}
 * are the events of such a posting.
 * <p>
 * Acting under the posting within its dates and the refusal after valid-to were in place (E-24). Fail-first on
 * 42439c36: no timer ran at valid-to ({@code PostingService.expireDue} had no caller), so {@code DELEGATION_EXPIRED}
 * was never written.
 */
class SubstituteValidToAgainstServerTest extends SeatLeftoversHarness {

    private String charge;
    private String task;

    @Override
    List<String> moreSettings() {
        return List.of("--sangam.security.posting-expiry-interval=PT1S");
    }

    @Override
    void seed() throws Exception {
        for (String s : List.of("VT-AC", "VT-OWN")) {
            seat(s);
        }
        signIn("sl.vt.charge");
        post("VT-OWN", "sl.vt.charge", "SUBSTANTIVE", today(), null);
        charge = post("VT-AC", "sl.vt.charge", "ADDITIONAL_CHARGE", today(), inDays(7));
        task = taskFor("VT-AC", "VT-AC/open");
    }

    @Test
    void theSubstituteActsWithinTheDatesIsRefusedAfterValidToAndTheExpiryIsAudited() throws Exception {
        String officer = token("sl.vt.charge", USER);
        Map<String, String> pair = Map.of(SEAT, "VT-OWN", ACTING, "VT-AC");
        // within the dates: the seat is offered with its last day, and its task is listed
        assertEquals(inDays(7), seatsOf("sl.vt.charge", Map.of()).get("VT-AC").path("validTo").asText());
        assertEquals(List.of("VT-AC/open"), keys(send("GET", "/tasks?businessKey=" + enc("VT-AC/open"), officer, pair, null)));
        assertEquals(0, expired().size(), "nothing expired yet: " + expired());

        // valid-to passes (the charge is ended now)
        ok(send("POST", "/admin/assignments/" + charge + "/end", admin, Map.of(), map("reason", "Charge ended")));

        // K7 at action: the next request under the seat is refused, nothing is changed, the seat is no longer offered
        assertProblem(403, "SEAT_NOT_HELD", send("GET", "/tasks?businessKey=" + enc("VT-AC/open"), officer, pair, null), "list after valid-to");
        assertProblem(403, "SEAT_NOT_HELD", send("POST", "/tasks/" + task + "/complete", officer, pair, Map.of("remarks", "Too late")),
                "complete after valid-to");
        assertEquals(1, engine.getTaskService().createTaskQuery().taskId(task).count(), "the task stays with the seat");
        assertTrue(!seatsOf("sl.vt.charge", Map.of()).containsKey("VT-AC"), "GET /me no longer offers VT-AC");

        // the timer at valid-to: DELEGATION_EXPIRED, once, naming the posting, the seat and the person
        long until = System.currentTimeMillis() + 30_000;
        while (expired().isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(500);
        }
        assertEquals(1, expired().size(), "one DELEGATION_EXPIRED for the ended charge: " + expired());
        for (String part : List.of(charge, "VT-AC", "sl.vt.charge", "ADDITIONAL_CHARGE")) {
            assertTrue(expired().get(0).contains(part), "DELEGATION_EXPIRED names " + part + ": " + expired().get(0));
        }
        Thread.sleep(3_000);   // further sweeps write nothing more
        assertEquals(1, expired().size(), "written once: " + expired());
    }

    private List<String> expired() throws Exception {
        return events("DELEGATION_EXPIRED");
    }
}
