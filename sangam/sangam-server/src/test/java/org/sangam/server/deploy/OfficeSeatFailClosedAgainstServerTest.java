// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.task.IdentityLink;
import org.operaton.bpm.engine.task.IdentityLinkType;
import org.sangam.server.bridge.PacksTenantDeployer;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-6 finding 4 (IX.5 rules 1 and 2; J-P1-E07): a step routed to a role must reach the seat of that role that serves
 * the case's office, never every holder of the role. Two officers hold the realm role {@code SO} (it is in their
 * token, as realm oneifms issues roles), each in a seat of designation {@code SO} in a different office. The core
 * sanction block ({@code sangam-core-sanction}, the competent authority of an amount up to 1 crore is {@code SO}) is
 * started the way a calling pack starts it, with no {@code officeId} on the case, so no seat resolves.
 * <p>
 * Before the fix, {@code SeatResolutionDelegate} put the role code itself on the task as its candidate group
 * ({@code orElse(r)}, "the role code doubles as a pooled seat group"), and {@code OperatonTaskAdapter.isCandidate}
 * matches the caller's token groups, so the officer of the other office was offered the task. Fail closed: with no
 * seat and no pack {@code fallbackSeat} the step is refused with {@code 422 SEAT_UNRESOLVED} and nothing is offered
 * to anybody; a pack that names a {@code fallbackSeat} keeps J-P1-E07 (the fallback seat gets the task).
 * <p>
 * The server is started as on demo.ifms.dev (the demo fragment's settings, production mode, the
 * {@link PacksOfBootstrapTenantAgainstServerTest.Host} harness); only the core pack is deployed for the tenant. The
 * same scenario runs on the PostgreSQL lane in {@link OfficeSeatFailClosedOnLaneDatabaseIT}. Observations are printed
 * as {@code OFFICE-FC | <lane> | <case> | ...} lines.
 */
class OfficeSeatFailClosedAgainstServerTest {

    static final String TENANT = "GOI.FIN";
    static final String KEY = "sangam-core-sanction";
    static final String OFFICE_A = "FC-OFFICE-A";
    static final String OFFICE_B = "FC-OFFICE-B";
    /** An office of the hierarchy with no SO seat (boundary: an office is named, still no seat). */
    static final String OFFICE_EMPTY = "FC-OFFICE-EMPTY";
    static final String SEAT_A = "FC-SO-A";
    static final String SEAT_B = "FC-SO-B";
    /** The initiator's seat in office A (IX.5 rule 7: a case started from it without an officeId is office A's). */
    static final String CLERK_A = "FC-CLERK-A";
    static final String OFFICER_A = "fc.so.a";
    static final String OFFICER_B = "fc.so.b";
    static final String INITIATOR = "fc.initiator";
    static final List<String> SO = List.of("SO", "sangam-user");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    @AfterAll
    static void releaseRealm() {
        DemoComposeFragmentStartupTest.RealmIdp.release();
    }

    @Test
    void aRoleStepWithoutAResolvableSeatIsOfferedToNobody() throws Exception {
        scenario(PacksOfBootstrapTenantAgainstServerTest.Host.h2(dir.resolve("h2")), "H2");
    }

    /** The whole scenario on one host (H2 here, PostgreSQL in the lane IT). */
    static void scenario(PacksOfBootstrapTenantAgainstServerTest.Host host, String lane) throws Exception {
        try (ConfigurableApplicationContext ctx = host.start(PacksOfBootstrapTenantAgainstServerTest.Host.WITHOUT_PACK_SETTINGS,
                "--sangam.packs.deploy-on-start=false")) {
            new Run(ctx, lane).all();
        }
    }

    /** One started server. */
    static final class Run {
        final ConfigurableApplicationContext ctx;
        final String lane;
        final String api;
        final ProcessEngine engine;
        final String admin;
        final List<String> wrong = new ArrayList<>();

        Run(ConfigurableApplicationContext ctx, String lane) throws Exception {
            this.ctx = ctx;
            this.lane = lane;
            this.api = "http://localhost:" + ctx.getEnvironment().getRequiredProperty("local.server.port") + "/api/v1";
            this.engine = ctx.getBean(ProcessEngine.class);
            this.admin = token("rajendra.sharma", List.of("sangam-tenant-admin", "sangam-user"));
        }

        void all() throws Exception {
            given();
            withOffice();
            restStartTakesTheStarterSeatsOffice();
            withPackFallbackSeat();
            withoutResolvableSeat("no-office", null);
            withoutResolvableSeat("office-without-SO-seat", OFFICE_EMPTY);
            assertTrue(wrong.isEmpty(), lane + ": " + wrong.size() + " finding(s): " + String.join("; ", wrong));
        }

        // ------------------------------------------------------------------ given

        /** Two offices with one SO seat each and one office without; the officers posted; only the core pack. */
        void given() throws Exception {
            PacksTenantDeployer packs = ctx.getBean(PacksTenantDeployer.class);
            packs.select(TENANT, Set.of("sangam.core"));
            Map<String, String> deployed = packs.deploy(TENANT);
            fact("given", "core pack deployed for " + TENANT + ": " + deployed.keySet());
            String today = LocalDate.now(ZoneOffset.UTC).toString();
            ok(send("PUT", "/admin/org-levels", admin, Map.of(), Map.of("tenantId", TENANT, "levels", List.of("OFFICE"))));
            // P-6 Step 2: the offices and seats are made the way the product makes them, through the admin API now
            // (POST /admin/seats places a seat from the moment it is created). PacksSeatDirectory resolves today's
            // seats at the instant of the action, so this holds at any time of day (before Step 2 a seat made after
            // 12:00 business time served no case until the next day).
            for (String office : List.of(OFFICE_A, OFFICE_B, OFFICE_EMPTY)) {
                ok(send("POST", "/admin/org-units", admin, Map.of(), map("id", office, "code", office, "name", office,
                        "level", "OFFICE", "parentId", null)));
            }
            for (String[] seat : List.of(new String[] {SEAT_A, "SO", OFFICE_A}, new String[] {SEAT_B, "SO", OFFICE_B},
                    new String[] {CLERK_A, "CLERK", OFFICE_A})) {
                ok(send("POST", "/admin/seats", admin, Map.of(), map("id", seat[0], "code", seat[0], "designation", seat[1],
                        "name", seat[0], "orgUnitId", seat[2], "reportsToSeatId", null, "roles", List.of())));
            }
            for (String person : List.of(OFFICER_A, OFFICER_B, INITIATOR)) {
                assertEquals(200, send("GET", "/me", token(person, SO), Map.of(), null).statusCode(), person + " signs in");
            }
            post(SEAT_A, OFFICER_A, today);
            post(SEAT_B, OFFICER_B, today);
            post(CLERK_A, INITIATOR, today);
        }

        // ------------------------------------------------------------------ normal case

        /** officeId names office A: the task is on seat A; officer A is offered it, officer B (role SO too) is not. */
        void withOffice() throws Exception {
            Started s = start("with-office", Map.of("officeId", OFFICE_A));
            check("with-office", s.refusal() == null, "the case starts (" + s.refusal() + ")");
            if (s.refusal() != null) {
                return;
            }
            fact("with-office", "candidate groups " + groups(s.taskId()) + ", assignmentBasis "
                    + engine.getRuntimeService().getVariable(s.pid(), "assignmentBasis"));
            check("with-office", groups(s.taskId()).equals(List.of(SEAT_A)), "the task is on " + SEAT_A + " only: " + groups(s.taskId()));
            check("with-office", listed(OFFICER_A, SEAT_A, s.bk()), "officer A in " + SEAT_A + " is offered the task");
            otherOfficerIsRefused("with-office", s);
        }

        /**
         * IX.5 rule 7: the initiator, acting in seat {@value #CLERK_A} of office A, starts the case with no officeId;
         * the case takes office A (resolved through PacksSeatDirectory on the server's org model), so the step is on
         * seat A only. The core sanction block is not startable from the tasklist (422 PROCESS_NOT_STARTABLE, a
         * product rule), so it is started as a calling pack starts it, with the starter's seat in {@code actingSeat}.
         */
        void restStartTakesTheStarterSeatsOffice() throws Exception {
            String label = "starter-seat-office";
            Started s = start(label, Map.of("actingSeat", CLERK_A));
            check(label, s.refusal() == null, "the case starts (" + s.refusal() + ")");
            if (s.refusal() != null) {
                return;
            }
            Object office = historic(s.pid(), "officeId");
            fact(label, "officeId " + office + ", task candidate groups " + groups(s.taskId()));
            check(label, OFFICE_A.equals(office), "the case takes the starter seat's office " + OFFICE_A + ": " + office);
            check(label, groups(s.taskId()).equals(List.of(SEAT_A)), "the task is on " + SEAT_A + " only: " + groups(s.taskId()));
            otherOfficerIsRefused(label, s);
        }

        // ------------------------------------------------------------------ boundary: J-P1-E07 kept

        /** No officeId, but the pack names a fallback seat: the task is on that seat (basis fallback), never a pool. */
        void withPackFallbackSeat() throws Exception {
            Started s = start("pack-fallback-seat", Map.of("fallbackSeat", SEAT_A));
            check("pack-fallback-seat", s.refusal() == null, "the case starts (" + s.refusal() + ")");
            if (s.refusal() != null) {
                return;
            }
            Object basis = engine.getRuntimeService().getVariable(s.pid(), "assignmentBasis");
            fact("pack-fallback-seat", "candidate groups " + groups(s.taskId()) + ", assignmentBasis " + basis);
            check("pack-fallback-seat", groups(s.taskId()).equals(List.of(SEAT_A)), "the task is on the fallback seat "
                    + SEAT_A + ": " + groups(s.taskId()));
            check("pack-fallback-seat", "fallback".equals(basis), "assignmentBasis is fallback: " + basis);
            otherOfficerIsRefused("pack-fallback-seat", s);
        }

        // ------------------------------------------------------------------ the finding

        /**
         * No seat resolves and the pack names no fallback seat. Fail closed: the step is refused with
         * {@code 422 SEAT_UNRESOLVED}; no task waits for the role. Whatever happens is printed first: if the case does
         * start, what officer B (other office) can do with its task is tried and printed (see, claim, complete).
         */
        void withoutResolvableSeat(String label, String office) throws Exception {
            Map<String, Object> vars = office == null ? Map.of() : Map.of("officeId", office);
            Started forClaim = start(label + "/claim", vars);
            Started forComplete = start(label + "/complete", vars);
            for (Started s : List.of(forClaim, forComplete)) {
                if (s.refusal() != null) {
                    fact(label, s.bk() + " refused: " + s.refusal());
                    check(label, s.refusal().contains("422 SEAT_UNRESOLVED"), "refused with 422 SEAT_UNRESOLVED: " + s.refusal());
                    check(label, engine.getTaskService().createTaskQuery().tenantIdIn(TENANT).processInstanceBusinessKey(s.bk())
                            .count() == 0, "no task of " + s.bk() + " waits");
                } else {
                    fact(label, s.bk() + " started; task " + s.taskId() + " candidate groups " + groups(s.taskId())
                            + ", assignmentBasis " + engine.getRuntimeService().getVariable(s.pid(), "assignmentBasis"));
                    wrong.add(label + ": " + s.bk() + " started with no resolvable seat; task candidate groups "
                            + groups(s.taskId()));
                }
            }
            if (forClaim.refusal() == null) {
                String b = token(OFFICER_B, SO);
                boolean seenSeat = listed(OFFICER_B, SEAT_B, forClaim.bk());
                boolean seenNoSeat = listed(OFFICER_B, null, forClaim.bk());
                HttpResponse<String> detail = send("GET", "/tasks/" + forClaim.taskId(), b, Map.of("X-Sangam-Seat", SEAT_B), null);
                fact(label, "officer B (" + SEAT_B + ", office B) GET /tasks?businessKey: listed with X-Sangam-Seat " + seenSeat
                        + ", without a seat header " + seenNoSeat + "; GET /tasks/{id} " + detail.statusCode());
                check(label, !seenSeat && !seenNoSeat, "officer B is not offered the task (list)");
                check(label, detail.statusCode() == 404, "officer B cannot open the task: " + detail.statusCode());
                HttpResponse<String> claim = send("POST", "/tasks/" + forClaim.taskId() + "/claim", b, Map.of("X-Sangam-Seat", SEAT_B), null);
                String assignee = engine.getTaskService().createTaskQuery().taskId(forClaim.taskId()).singleResult().getAssignee();
                fact(label, "officer B POST /tasks/{id}/claim: " + claim.statusCode() + " " + code(claim) + "; assignee now " + assignee);
                check(label, claim.statusCode() / 100 != 2 && assignee == null, "officer B cannot claim the task: " + claim.statusCode()
                        + ", assignee " + assignee);
            }
            if (forComplete.refusal() == null) {
                HttpResponse<String> done = send("POST", "/tasks/" + forComplete.taskId() + "/complete", token(OFFICER_B, SO),
                        Map.of("X-Sangam-Seat", SEAT_B), approve());
                long open = engine.getTaskService().createTaskQuery().taskId(forComplete.taskId()).count();
                fact(label, "officer B POST /tasks/{id}/complete (approve): " + done.statusCode() + " " + code(done)
                        + "; task still open " + (open == 1) + "; sanctionOutcome "
                        + historic(forComplete.pid(), "sanctionOutcome"));
                check(label, done.statusCode() / 100 != 2 && open == 1, "officer B cannot complete the task: "
                        + done.statusCode() + " " + done.body());
            }
        }

        /** Officer B (other office, role SO) is neither shown nor allowed to claim or complete the case's task. */
        private void otherOfficerIsRefused(String label, Started s) throws Exception {
            String b = token(OFFICER_B, SO);
            check(label, !listed(OFFICER_B, SEAT_B, s.bk()) && !listed(OFFICER_B, null, s.bk()), "officer B is not offered the task");
            HttpResponse<String> claim = send("POST", "/tasks/" + s.taskId() + "/claim", b, Map.of("X-Sangam-Seat", SEAT_B), null);
            HttpResponse<String> done = send("POST", "/tasks/" + s.taskId() + "/complete", b, Map.of("X-Sangam-Seat", SEAT_B), approve());
            fact(label, "officer B: claim " + claim.statusCode() + " " + code(claim) + ", complete " + done.statusCode() + " " + code(done));
            check(label, claim.statusCode() == 404 && done.statusCode() == 404, "officer B gets 404 on claim and complete");
            check(label, engine.getTaskService().createTaskQuery().taskId(s.taskId()).singleResult().getAssignee() == null,
                    "the task stays unclaimed");
        }

        // ------------------------------------------------------------------ helpers

        record Started(String bk, String pid, String taskId, String refusal) {
        }

        /** Starts the core sanction as a calling pack does (engine start, the case's variables). */
        Started start(String label, Map<String, Object> extra) {
            String bk = "FC/" + label + "/" + java.util.UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> vars = new LinkedHashMap<>(Map.of("initiator", INITIATOR, "amount", 5000, "caseType", "GENERAL"));
            vars.putAll(extra);
            try {
                String pid = engine.getRuntimeService().createProcessInstanceByKey(KEY).processDefinitionTenantId(TENANT)
                        .businessKey(bk).setVariables(vars).execute().getId();
                var task = engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey("sn_sanction")
                        .singleResult();
                if (task == null) {
                    return new Started(bk, pid, null, "started but no sn_sanction task: active "
                            + engine.getRuntimeService().getActiveActivityIds(pid));
                }
                return new Started(bk, pid, task.getId(), null);
            } catch (RuntimeException e) {
                return new Started(bk, null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        List<String> groups(String taskId) {
            List<String> out = new ArrayList<>();
            for (IdentityLink l : engine.getTaskService().getIdentityLinksForTask(taskId)) {
                if (IdentityLinkType.CANDIDATE.equals(l.getType()) && l.getGroupId() != null) {
                    out.add(l.getGroupId());
                }
            }
            return out;
        }

        Object historic(String pid, String name) {
            var v = engine.getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(pid).variableName(name)
                    .singleResult();
            return v == null ? null : v.getValue();
        }

        /** Whether {@code person} (role SO), optionally in {@code seat}, is shown a task of the business key. */
        boolean listed(String person, String seat, String bk) throws Exception {
            HttpResponse<String> r = send("GET", "/tasks?businessKey=" + URLEncoder.encode(bk, StandardCharsets.UTF_8),
                    token(person, SO), seat == null ? Map.of() : Map.of("X-Sangam-Seat", seat), null);
            assertEquals(200, r.statusCode(), person + " lists tasks: " + r.body());
            JsonNode body = JSON.readTree(r.body());
            return (body.isArray() ? body : body.path("items")).size() > 0;
        }

        static Map<String, Object> approve() {
            return map("decision", "APPROVED", "remarks", "Seen; in order.", "variables", Map.of("sanctionDecision", "approve"));
        }

        void check(String label, boolean ok, String what) {
            if (!ok) {
                wrong.add(label + ": " + what);
            }
        }

        void fact(String label, String what) {
            System.out.println("OFFICE-FC | " + lane + " | " + label + " | " + what);
        }

        private void post(String seat, String person, String from) throws Exception {
            ok(send("POST", "/admin/assignments", admin, Map.of(), map("seatId", seat, "personId", person, "kind", "SUBSTANTIVE",
                    "validFrom", from, "validTo", null, "orderRef", "Office order: office seat check")));
        }

        private HttpResponse<String> send(String method, String path, String token, Map<String, String> headers, Object body)
                throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + path)).header("Authorization", "Bearer " + token);
            headers.forEach(b::header);
            if (method.equals("GET")) {
                b.GET();
            } else {
                b.header("Content-Type", "application/json").header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "{}" : JSON.writeValueAsString(body)));
            }
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        private static String code(HttpResponse<String> r) {
            try {
                return JSON.readTree(r.body()).path("code").asText("");
            } catch (Exception e) {
                return "";
            }
        }

        private static void ok(HttpResponse<String> r) {
            assertTrue(r.statusCode() / 100 == 2, r.request().method() + " " + r.request().uri() + ": " + r.statusCode() + " " + r.body());
        }
    }

    static String token(String user, List<String> roles) throws Exception {
        return DemoComposeFragmentStartupTest.RealmIdp.get().tokenWithoutSub(TENANT, user, roles);
    }

    /** A map that may hold null values (JSON null), in the given order. */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
