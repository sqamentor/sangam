// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Register E-32(e), E-32(a); joint decision EV-M-0962 (Q-B), integrator ACK after IFMS [AGREE] EV-M-0965. SANGAM.md S03
 * ("Sections: My seat, Acting for, Pool (claimable), Escalated, Returned to me ... counts from same predicate"), AJ-04
 * step 3 ("Sees Acting for seat A section"), J-P1-E06 ("the task leaves the acting for section"). Contract: GET /tasks
 * {@code section} is the predicate of the section of the same name in GET /tasks/counts, under the same seat context.
 * <p>
 * The officer holds F-OWN (substantive) and F-AC (additional charge). Tasks: F-OWN/open (own seat, unclaimed),
 * F-OWN/claimed (own seat, claimed by him), F-OWN/esc (own seat, escalated), F-OWN/ret (own seat, returned),
 * F-AC/open (acted-for seat).
 * <p>
 * Fail-first on 27428010: GET /tasks ignored {@code section}, so list and count disagreed in 4 of 5 sections (measured
 * by SeatFactsAgainstServerTest), and an unknown section was answered 200.
 */
class TaskSectionsAgainstServerTest extends SeatLeftoversHarness {

    private static final List<String> SECTIONS = List.of("MY_SEAT", "ACTING_FOR", "POOL", "ESCALATED", "RETURNED", "ALL");
    private static final Map<String, String> OWN = Map.of(SEAT, "F-OWN");
    private static final Map<String, String> PAIR = Map.of(SEAT, "F-OWN", ACTING, "F-AC");

    @Override
    void seed() throws Exception {
        seat("F-OWN");
        seat("F-AC");
        signIn("sl.s.officer");
        post("F-OWN", "sl.s.officer", "SUBSTANTIVE", today(), null);
        post("F-AC", "sl.s.officer", "ADDITIONAL_CHARGE", today(), inDays(7));
        taskFor("F-OWN", "F-OWN/open");
        String claimed = taskFor("F-OWN", "F-OWN/claimed");
        String esc = taskFor("F-OWN", "F-OWN/esc");
        engine.getTaskService().setVariableLocal(esc, "sangamEscalated", true);   // OperatonWorkspaceAdapter.ESCALATED
        String ret = taskFor("F-OWN", "F-OWN/ret");
        engine.getRuntimeService().setVariable(engine.getTaskService().createTaskQuery().taskId(ret).singleResult()
                .getProcessInstanceId(), "sangamDecision", "RETURNED");   // OperatonTaskAdapter.DECISION
        taskFor("F-AC", "F-AC/open");
        ok(send("POST", "/tasks/" + claimed + "/claim", token("sl.s.officer", USER), OWN, null));
    }

    /** Condition (2): in every section and both seat contexts the list is exactly what its count counts. */
    @Test
    void theListOfEachSectionIsWhatItsCountCounts() throws Exception {
        String officer = token("sl.s.officer", USER);
        Map<String, Map<String, List<String>>> expected = new LinkedHashMap<>();
        expected.put("own", Map.of(
                "MY_SEAT", List.of("F-OWN/claimed", "F-OWN/esc", "F-OWN/open", "F-OWN/ret"),
                "ACTING_FOR", List.of("F-AC/open"),
                "POOL", List.of("F-OWN/esc", "F-OWN/open", "F-OWN/ret"),
                "ESCALATED", List.of("F-OWN/esc"),
                "RETURNED", List.of("F-OWN/ret"),
                "ALL", List.of("F-OWN/claimed", "F-OWN/esc", "F-OWN/open", "F-OWN/ret")));
        expected.put("pair", Map.of(
                "MY_SEAT", List.of("F-AC/open", "F-OWN/claimed"),
                "ACTING_FOR", List.of("F-AC/open"),
                "POOL", List.of("F-AC/open"),
                "ESCALATED", List.of(),
                "RETURNED", List.of(),
                "ALL", List.of("F-AC/open", "F-OWN/claimed")));
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<String>>> context : expected.entrySet()) {
            Map<String, String> headers = context.getKey().equals("own") ? OWN : PAIR;
            HttpResponse<String> counts = send("GET", "/tasks/counts", officer, headers, null);
            assertEquals(200, counts.statusCode(), counts.body());
            JsonNode count = JSON.readTree(counts.body());
            for (String section : SECTIONS) {
                HttpResponse<String> list = send("GET", "/tasks?size=50&section=" + section, officer, headers, null);
                List<String> listed = keys(list);
                String total = list.headers().firstValue("X-Total-Count").orElse("?");
                List<String> want = context.getValue().get(section);
                if (count.path(section).asLong(-1) != listed.size() || !total.equals(String.valueOf(listed.size()))
                        || !listed.equals(want)) {
                    wrong.add(context.getKey() + " " + section + ": listed " + listed + " (X-Total-Count " + total + "), counted "
                            + count.path(section) + ", specified " + want);
                }
            }
        }
        assertEquals(List.of(), wrong, "sections whose list and count disagree or differ from S03");
    }

    /** Condition (1): without section, GET /tasks lists exactly what it listed before the change (visibility only). */
    @Test
    void withoutASectionTheListIsUnchanged() throws Exception {
        String officer = token("sl.s.officer", USER);
        assertEquals(List.of("F-OWN/claimed", "F-OWN/esc", "F-OWN/open", "F-OWN/ret"),
                keys(send("GET", "/tasks?size=50", officer, OWN, null)), "own seat context");
        assertEquals(List.of("F-AC/open", "F-OWN/claimed"), keys(send("GET", "/tasks?size=50", officer, PAIR, null)),
                "acting pair: the acted-for seat's tasks and the caller's own claimed task (ruling 2026-11-20)");
        assertEquals(List.of("F-OWN/claimed"), keys(send("GET", "/tasks?size=50&mine=true", officer, OWN, null)), "mine=true");
        assertEquals(List.of("F-OWN/esc", "F-OWN/open", "F-OWN/ret"),
                keys(send("GET", "/tasks?size=50&unassigned=true", officer, OWN, null)), "unassigned=true");
    }

    /** Condition (3): an unknown section is refused. */
    @Test
    void anUnknownSectionIsRefused() throws Exception {
        String officer = token("sl.s.officer", USER);
        assertProblem(400, "VALIDATION_FAILED", send("GET", "/tasks?section=MINE", officer, OWN, null), "section=MINE");
        assertProblem(400, "VALIDATION_FAILED", send("GET", "/tasks?section=my_seat", officer, OWN, null),
                "section names are upper case, as GET /tasks/counts names them");
    }
}
