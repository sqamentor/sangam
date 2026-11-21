// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Register E-32(a), E-32(b), E-32(e): what the server does today, measured on the production start
 * set, for the points that wait for a decision of the integrator (sync board REQUEST of 2026-11-21). Every fact is
 * printed as a line {@code SEAT-LEFT | <point> | INFO | ...}; the assertions hold only what a decision will not change
 * (status codes of accepted calls, the refusal of a seat that is not held, one AUTH_DENIED per refusal as ruled on
 * 2026-11-20).
 */
class SeatFactsAgainstServerTest extends SeatLeftoversHarness {

    private static final List<String> SECTIONS = List.of("MY_SEAT", "ACTING_FOR", "POOL", "ESCALATED", "RETURNED");

    @Override
    void seed() throws Exception {
        for (String s : List.of("F-OWN", "F-AC")) {
            seat(s);
        }
        signIn("sl.f.officer");
        post("F-OWN", "sl.f.officer", "SUBSTANTIVE", today(), null);
        post("F-AC", "sl.f.officer", "ADDITIONAL_CHARGE", today(), inDays(7));
        taskFor("F-OWN", "F-OWN/open");
        String claimed = taskFor("F-OWN", "F-OWN/claimed");
        taskFor("F-AC", "F-AC/open");
        ok(send("POST", "/tasks/" + claimed + "/claim", token("sl.f.officer", USER), Map.of(SEAT, "F-OWN"), null));
    }

    /**
     * E-32(e), E-32(a): the lists sangam-ui asks for per inbox section (endpoints.ts: section plus the mapped contract
     * filter), and the counts, under the own seat and under the header pair of the acted-for seat. The officer has
     * three tasks: F-OWN/open (own seat, unclaimed), F-OWN/claimed (own seat, claimed by him), F-AC/open (acted-for seat).
     */
    @Test
    void whatEachInboxSectionListsAndCounts() throws Exception {
        String officer = token("sl.f.officer", USER);
        Map<String, String> uiFilter = Map.of("MY_SEAT", "&mine=true", "ACTING_FOR", "", "POOL", "&unassigned=true",
                "ESCALATED", "&state=ESCALATED", "RETURNED", "&state=RETURNED");
        Map<String, Map<String, String>> contexts = new LinkedHashMap<>();
        contexts.put("own seat (X-Sangam-Seat F-OWN)", Map.of(SEAT, "F-OWN"));
        contexts.put("acting (X-Sangam-Seat F-OWN, X-Sangam-Acting-For-Seat F-AC)", Map.of(SEAT, "F-OWN", ACTING, "F-AC"));
        for (Map.Entry<String, Map<String, String>> context : contexts.entrySet()) {
            HttpResponse<String> counts = send("GET", "/tasks/counts", officer, context.getValue(), null);
            assertEquals(200, counts.statusCode(), counts.body());
            System.out.println("SEAT-LEFT | section counts | INFO | " + context.getKey() + ": " + counts.body());
            List<String> without = keys(send("GET", "/tasks?size=50", officer, context.getValue(), null));
            System.out.println("SEAT-LEFT | section list | INFO | " + context.getKey() + ", no section: " + without);
            for (String section : SECTIONS) {
                List<String> sectionOnly = keys(send("GET", "/tasks?size=50&section=" + section, officer, context.getValue(), null));
                List<String> asUi = keys(send("GET", "/tasks?size=50&section=" + section + uiFilter.get(section), officer,
                        context.getValue(), null));
                System.out.println("SEAT-LEFT | section list | INFO | " + context.getKey() + ", section=" + section + " alone: "
                        + sectionOnly + (sectionOnly.equals(without) ? " (= the list without a section)" : "")
                        + "; as sangam-ui sends it (" + (uiFilter.get(section).isEmpty() ? "nothing more" : uiFilter.get(section).substring(1))
                        + "): " + asUi);
            }
        }
    }

    /**
     * E-32(b): the cost of 1000 refused requests (GET /me under a seat the caller does not hold) next to 1000 accepted
     * ones, and what they leave on the audit trail. SANGAM.md X.2.3.3 rule 2 batches logins only (LOGIN_BATCH per 1 s).
     */
    @Test
    void theCostOfAThousandRefusedRequests() throws Exception {
        String officer = token("sl.f.officer", USER);
        int n = 1000;
        for (int i = 0; i < 50; i++) {   // warm-up, both paths
            send("GET", "/me", officer, Map.of(SEAT, "F-OWN"), null);
        }
        long acceptedStart = System.nanoTime();
        for (int i = 0; i < n; i++) {
            assertEquals(200, send("GET", "/me", officer, Map.of(SEAT, "F-OWN"), null).statusCode());
        }
        long acceptedMs = (System.nanoTime() - acceptedStart) / 1_000_000;
        int before = denied().size();
        long bytesBefore = auditBytes();
        long refusedStart = System.nanoTime();
        for (int i = 0; i < n; i++) {
            assertEquals(403, send("GET", "/me", officer, Map.of(SEAT, "F-NOT-HELD"), null).statusCode());
        }
        long refusedMs = (System.nanoTime() - refusedStart) / 1_000_000;
        int written = denied().size() - before;
        long bytes = auditBytes() - bytesBefore;
        System.out.println("SEAT-LEFT | AUTH_DENIED cost | INFO | " + n + " accepted GET /me: " + acceptedMs + " ms ("
                + String.format(java.util.Locale.ROOT, "%.2f", acceptedMs / (double) n) + " ms each); " + n + " refused GET /me (403 SEAT_NOT_HELD): "
                + refusedMs + " ms (" + String.format(java.util.Locale.ROOT, "%.2f", refusedMs / (double) n) + " ms each); AUTH_DENIED events written: "
                + written + "; envelope + payload text added: " + bytes + " characters (" + (written == 0 ? 0 : bytes / written)
                + " per event); database H2 in memory, one client thread, local machine");
        assertEquals(n, written, "one AUTH_DENIED for every refused request (ruling of 2026-11-20)");
    }

    private List<String> denied() throws Exception {
        return events("AUTH_DENIED").stream().filter(e -> e.contains("SEAT_NOT_HELD")).toList();
    }

    /** Characters of envelope and payload of every AUTH_DENIED of the tenant. */
    private long auditBytes() throws Exception {
        long total = 0;
        for (String e : new ArrayList<>(events("AUTH_DENIED"))) {
            total += e.length();
        }
        return total;
    }
}
