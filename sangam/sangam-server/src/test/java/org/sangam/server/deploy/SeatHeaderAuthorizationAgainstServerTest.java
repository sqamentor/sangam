// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.SangamSecurity;
import org.sangam.security.support.Sql;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register E-24 (IX.5 rules 1 and 4, K7): the seat context a caller names in {@code X-Sangam-Seat} or
 * {@code X-Sangam-Acting-For-Seat} is accepted only when that person holds the seat on the action date. The server
 * is started as on demo.ifms.dev (the demo fragment's settings, production mode, {@link DemoComposeFragmentStartupTest}
 * harness) and every caller's token is shaped like realm oneifms's: no {@code seats} claim. Seats, persons, postings,
 * the additional charge and the end of a posting go through the product's REST API as a tenant administrator; the
 * approved leave is recorded through {@code PostingService.recordLeave} (see {@link #recordLeave}).
 * <p>
 * One user task per seat waits with that seat as its candidate group, the way the packs route a step to a seat
 * (plain seat id, as PAO-1 in the IFMS pack).
 * <p>
 * Fail-first on 6ce8f41c: the header was compared only with a token claim the identity provider does not issue
 * ({@code PrincipalAttributeFilter}), so a signed-in person who does not hold the seat was shown the seat's task and
 * could claim, complete and delegate it (CI run 44813780036, {@code DEMO-SEQUENCE | seat header}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeatHeaderAuthorizationAgainstServerTest {

    private static final String TENANT = "GOI.FIN";
    private static final String OFFICE = "SA-OFFICE";
    private static final String SEAT = "X-Sangam-Seat";
    private static final String ACTING = "X-Sangam-Acting-For-Seat";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final List<String> USER = List.of("sangam-user");

    /** Held substantively by sa.holder; sa.outsider holds SA-OTHER only (as Kavita Sharma holds DDO-1 only). */
    private static final String SUB = "SA-SUB";
    private static final String OTHER = "SA-OTHER";
    /** Vacant; sa.charge (own seat SA-CHARGE-OWN) holds it on additional charge from today for a week. */
    private static final String CHARGE = "SA-AC";
    private static final String CHARGE_OWN = "SA-CHARGE-OWN";
    /** Held by sa.onleave, who is on approved leave now; sa.substitute is the leave substitute. */
    private static final String LEAVE = "SA-LV";
    /** sa.former held it until the posting was ended a moment ago. */
    private static final String ENDED = "SA-END";
    /** sa.future is posted to it from tomorrow (posting dates are UTC days, SecurityAdminBridge.start). */
    private static final String FUTURE = "SA-FUT";

    @TempDir
    static Path dir;

    private ConfigurableApplicationContext ctx;
    private String api;
    private String admin;
    private ProcessEngine engine;
    private final Map<String, String> taskOf = new LinkedHashMap<>();   // business key -> task id
    private String leaveRecordedBy;

    @BeforeAll
    void startAndSeed() throws Exception {
        DemoComposeFragmentStartupTest.Keys keys = new DemoComposeFragmentStartupTest.Keys(dir);
        Map<String, String> bootstrap = Map.of("SANGAM_KEYS_BOOTSTRAP", "true",
                "SANGAM_KEYS_FINGERPRINT_EXPORT", dir.resolve("key-fingerprints.txt").toString(),
                "SANGAM_KEYS_ATTESTATION", "", "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", "");
        // as deployed: no pack is deployed at start (PacksWiring: on only under profile h2); the log archive is kept
        // out of the module directory
        ctx = DemoComposeFragmentStartupTest.start(keys, bootstrap, List.of("--sangam.packs.deploy-on-start=false",
                "--sangam.compliance.log-archive.root=" + dir.resolve("compliance-logs")), "h2", "oidc");
        api = "http://localhost:" + ctx.getEnvironment().getRequiredProperty("local.server.port") + "/api/v1";
        admin = token("rajendra.sharma", List.of("sangam-tenant-admin", "sangam-user"));
        engine = ctx.getBean(ProcessEngine.class);

        String today = LocalDate.now(ZoneOffset.UTC).toString();
        ok(send("PUT", "/admin/org-levels", admin, Map.of(), Map.of("tenantId", TENANT, "levels", List.of("OFFICE"))));
        ok(send("POST", "/admin/org-units", admin, Map.of(), map("id", OFFICE, "code", OFFICE, "name", "Seat check office",
                "level", "OFFICE", "parentId", null)));
        for (String seat : List.of(SUB, OTHER, CHARGE, CHARGE_OWN, LEAVE, ENDED, FUTURE)) {
            ok(send("POST", "/admin/seats", admin, Map.of(), map("id", seat, "code", seat, "designation", "Officer",
                    "name", seat, "orgUnitId", OFFICE, "reportsToSeatId", null, "roles", List.of())));
        }
        // every person signs in once (JIT person, as on the demo), then is posted
        for (String person : List.of("sa.holder", "sa.outsider", "sa.charge", "sa.onleave", "sa.substitute", "sa.former",
                "sa.future", "sa.delegate")) {
            assertEquals(200, send("GET", "/me", token(person, USER), Map.of(), null).statusCode(), person + " signs in");
        }
        post(SUB, "sa.holder", "SUBSTANTIVE", today, null);
        post(OTHER, "sa.outsider", "SUBSTANTIVE", today, null);
        post(CHARGE_OWN, "sa.charge", "SUBSTANTIVE", today, null);
        post(CHARGE, "sa.charge", "ADDITIONAL_CHARGE", today, LocalDate.now(ZoneOffset.UTC).plusDays(7).toString());
        post(LEAVE, "sa.onleave", "SUBSTANTIVE", today, null);
        String former = post(ENDED, "sa.former", "SUBSTANTIVE", today, null);
        post(FUTURE, "sa.future", "SUBSTANTIVE", LocalDate.now(ZoneOffset.UTC).plusDays(1).toString(), null);
        recordLeave("sa.onleave", "sa.substitute");

        engine.getRepositoryService().createDeployment().tenantId(TENANT).name("seat-check")
                .addModelInstance("seatcheck.bpmn", Bpmn.createExecutableProcess("seatcheck").operatonHistoryTimeToLive(1)
                        .startEvent().userTask("act").name("Act for the seat").operatonCandidateGroups("${seat}")
                        .endEvent().done()).deploy();
        for (String key : List.of(SUB + "/list", SUB + "/claim", SUB + "/complete", SUB + "/delegate", SUB + "/acting",
                SUB + "/claimed-token", SUB + "/holder", CHARGE + "/holder", LEAVE + "/substitute", LEAVE + "/onleave",
                ENDED + "/former", FUTURE + "/future")) {
            String seat = key.substring(0, key.indexOf('/'));
            String pi = engine.getRuntimeService().createProcessInstanceByKey("seatcheck").processDefinitionTenantId(TENANT)
                    .businessKey(key).setVariable("seat", seat).execute().getId();
            taskOf.put(key, engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId());
        }
        // the posting to SA-END ends now (relieved), after its task was created
        ok(send("POST", "/admin/assignments/" + former + "/end", admin, Map.of(), map("reason", "Relieved")));
    }

    @AfterAll
    void stop() {
        try {
            if (ctx != null) {
                ctx.close();
            }
        } finally {
            // the instance lives as long as the class: nothing of the closed server stays reachable from it
            ctx = null;
            engine = null;
            DemoComposeFragmentStartupTest.RealmIdp.release();
        }
    }

    // ------------------------------------------------------------------ a person who does not hold the seat

    /** (a), (c): the seat's task list is not shown; the header itself is refused. */
    @Test
    void aNonHolderNamingTheSeatIsRefusedTheTaskList() throws Exception {
        HttpResponse<String> r = send("GET", "/tasks?businessKey=" + enc(SUB + "/list"), token("sa.outsider", USER),
                Map.of(SEAT, SUB), null);
        assertSeatNotHeld(r, "sa.outsider (holds SA-OTHER only) lists tasks with X-Sangam-Seat SA-SUB");
    }

    @Test
    void aNonHolderNamingTheSeatIsRefusedTheTaskItself() throws Exception {
        String task = taskOf.get(SUB + "/list");
        for (String path : List.of("/tasks/" + task, "/tasks/" + task + "/variables", "/tasks/" + task + "/form")) {
            assertSeatNotHeld(send("GET", path, token("sa.outsider", USER), Map.of(SEAT, SUB), null), "GET " + path);
        }
    }

    /** (b): claim. */
    @Test
    void aNonHolderNamingTheSeatCannotClaimItsTask() throws Exception {
        String task = taskOf.get(SUB + "/claim");
        HttpResponse<String> r = send("POST", "/tasks/" + task + "/claim", token("sa.outsider", USER), Map.of(SEAT, SUB), null);
        assertSeatNotHeld(r, "claim");
        assertNull(engine.getTaskService().createTaskQuery().taskId(task).singleResult().getAssignee(), "the task stays unclaimed");
    }

    /** (b): complete. */
    @Test
    void aNonHolderNamingTheSeatCannotCompleteItsTask() throws Exception {
        String task = taskOf.get(SUB + "/complete");
        HttpResponse<String> r = send("POST", "/tasks/" + task + "/complete", token("sa.outsider", USER), Map.of(SEAT, SUB),
                Map.of("remarks", "Seat header check."));
        assertSeatNotHeld(r, "complete");
        assertEquals(1, engine.getTaskService().createTaskQuery().taskId(task).count(), "the task is still open");
    }

    /** (b): delegate (and return, the other command a candidate may send). */
    @Test
    void aNonHolderNamingTheSeatCannotDelegateOrReturnItsTask() throws Exception {
        String task = taskOf.get(SUB + "/delegate");
        assertSeatNotHeld(send("POST", "/tasks/" + task + "/delegate", token("sa.outsider", USER), Map.of(SEAT, SUB),
                map("toUserId", "sa.delegate", "reason", "Seat header check.")), "delegate");
        assertSeatNotHeld(send("POST", "/tasks/" + task + "/return", token("sa.outsider", USER), Map.of(SEAT, SUB),
                map("reason", "Seat header check.")), "return");
        org.operaton.bpm.engine.task.Task t = engine.getTaskService().createTaskQuery().taskId(task).singleResult();
        assertNull(t.getAssignee(), "the task is not handed to anybody");
        assertNull(t.getOwner(), "the task has no owner");
    }

    /** The same for the acting-for header, alone and next to the caller's own seat. */
    @Test
    void aNonHolderNamingTheSeatAsActedForIsRefused() throws Exception {
        String task = taskOf.get(SUB + "/acting");
        String outsider = token("sa.outsider", USER);
        assertSeatNotHeld(send("GET", "/tasks?businessKey=" + enc(SUB + "/acting"), outsider, Map.of(ACTING, SUB), null),
                "list with X-Sangam-Acting-For-Seat SA-SUB");
        assertSeatNotHeld(send("GET", "/me", outsider, Map.of(SEAT, OTHER, ACTING, SUB), null),
                "/me with the own seat and X-Sangam-Acting-For-Seat SA-SUB");
        assertSeatNotHeld(send("POST", "/tasks/" + task + "/complete", outsider, Map.of(SEAT, OTHER, ACTING, SUB),
                Map.of("remarks", "Seat header check.")), "complete with the own seat and X-Sangam-Acting-For-Seat SA-SUB");
        assertEquals(1, engine.getTaskService().createTaskQuery().taskId(task).count(), "the task is still open");
        // a seat held substantively is not a seat acted for (IX.5 rule 4: "acted for" is the substitute's label)
        assertSeatNotHeld(send("GET", "/me", token("sa.holder", USER), Map.of(ACTING, SUB), null),
                "the substantive holder naming the own seat as acted for");
    }

    /** The header is refused on every route, also where no task is read. */
    @Test
    void aNonHolderNamingTheSeatIsRefusedOnEveryRoute() throws Exception {
        String outsider = token("sa.outsider", USER);
        assertSeatNotHeld(send("GET", "/me", outsider, Map.of(SEAT, SUB), null), "/me");
        assertSeatNotHeld(send("GET", "/me", outsider, Map.of(SEAT, "NO-SUCH-SEAT"), null), "/me with a seat that does not exist");
        // control: the own seat, and no seat at all, are accepted
        HttpResponse<String> own = send("GET", "/me", outsider, Map.of(SEAT, OTHER), null);
        assertEquals(200, own.statusCode(), own.body());
        assertEquals(OTHER, JSON.readTree(own.body()).path("seatId").asText(), own.body());
        assertEquals(200, send("GET", "/me", outsider, Map.of(), null).statusCode());
    }

    /** Control: without the header a non-holder never saw the seat's task and cannot act on it. */
    @Test
    void aNonHolderWithoutTheHeaderDoesNotSeeTheSeatsTask() throws Exception {
        String outsider = token("sa.outsider", USER);
        assertEquals(List.of(), tasks(send("GET", "/tasks?businessKey=" + enc(SUB + "/list"), outsider, Map.of(), null)));
        assertEquals(List.of(), tasks(send("GET", "/tasks?businessKey=" + enc(SUB + "/list"), outsider, Map.of(SEAT, OTHER), null)));
        HttpResponse<String> r = send("POST", "/tasks/" + taskOf.get(SUB + "/list") + "/complete", outsider, Map.of(SEAT, OTHER),
                Map.of("remarks", "Seat header check."));
        assertEquals(404, r.statusCode(), r.body());
    }

    // ------------------------------------------------------------------ a person who holds the seat

    @Test
    void theSubstantiveHolderSeesAndCompletesTheSeatsTask() throws Exception {
        // a fact for the report, not asserted (not this lane's ruling): the seats GET /me offers the holder to choose from
        List<String> offered = new ArrayList<>();
        JSON.readTree(send("GET", "/me", token("sa.holder", USER), Map.of(), null).body()).path("seats")
                .forEach(s -> offered.add(s.path("seatId").asText()));
        System.out.println("SEAT-AUTHZ | /me seats | INFO | sa.holder (substantive holder of " + SUB + ", role sangam-user) is offered "
                + offered + " by GET /me");
        holderActs("sa.holder", SUB, SUB + "/holder");
    }

    /** Additional charge of a vacant seat (AJ-04 step 2): the charge holder acts in the seat, by either header. */
    @Test
    void theAdditionalChargeHolderSeesAndCompletesTheSeatsTask() throws Exception {
        HttpResponse<String> me = send("GET", "/me", token("sa.charge", USER), Map.of(SEAT, CHARGE_OWN, ACTING, CHARGE), null);
        assertEquals(200, me.statusCode(), "own seat + acted-for seat (the headers sangam-ui sends): " + me.body());
        assertEquals(CHARGE, JSON.readTree(me.body()).path("actingForSeatId").asText(), me.body());
        // a fact for the report, not asserted (not this lane's ruling): which tasks that header pair lists. The task
        // adapters add the seat of X-Sangam-Seat to the candidate groups, not the acted-for seat.
        System.out.println("SEAT-AUTHZ | acting-for list | INFO | sa.charge with X-Sangam-Seat " + CHARGE_OWN
                + " and X-Sangam-Acting-For-Seat " + CHARGE + " is shown " + tasks(send("GET", "/tasks?businessKey="
                + enc(CHARGE + "/holder"), token("sa.charge", USER), Map.of(SEAT, CHARGE_OWN, ACTING, CHARGE), null))
                + " of the acted-for seat's 1 open task");
        holderActs("sa.charge", CHARGE, CHARGE + "/holder");
    }

    /** Leave with a substitute (AJ-04 step 5): the substitute acts in the seat, the officer on leave does not (K7). */
    @Test
    void theLeaveSubstituteActsAndTheOfficerOnLeaveDoesNot() throws Exception {
        assertSeatNotHeld(send("GET", "/tasks?businessKey=" + enc(LEAVE + "/onleave"), token("sa.onleave", USER),
                Map.of(SEAT, LEAVE), null), "sa.onleave, on approved leave now (" + leaveRecordedBy + "), names SA-LV");
        HttpResponse<String> r = send("POST", "/tasks/" + taskOf.get(LEAVE + "/onleave") + "/complete", token("sa.onleave", USER),
                Map.of(SEAT, LEAVE), Map.of("remarks", "Seat header check."));
        assertSeatNotHeld(r, "sa.onleave completes while on leave");
        HttpResponse<String> me = send("GET", "/me", token("sa.substitute", USER), Map.of(ACTING, LEAVE), null);
        assertEquals(200, me.statusCode(), "the substitute names SA-LV as acted for: " + me.body());
        holderActs("sa.substitute", LEAVE, LEAVE + "/substitute");
    }

    // ------------------------------------------------------------------ effective dates

    @Test
    void anEndedPostingNoLongerGivesTheSeat() throws Exception {
        String former = token("sa.former", USER);
        assertSeatNotHeld(send("GET", "/tasks?businessKey=" + enc(ENDED + "/former"), former, Map.of(SEAT, ENDED), null),
                "sa.former, relieved from SA-END, lists its tasks");
        assertSeatNotHeld(send("POST", "/tasks/" + taskOf.get(ENDED + "/former") + "/complete", former, Map.of(SEAT, ENDED),
                Map.of("remarks", "Seat header check.")), "sa.former completes after being relieved");
        assertEquals(1, engine.getTaskService().createTaskQuery().taskId(taskOf.get(ENDED + "/former")).count());
    }

    @Test
    void aPostingThatStartsTomorrowDoesNotGiveTheSeatToday() throws Exception {
        String future = token("sa.future", USER);
        assertSeatNotHeld(send("GET", "/tasks?businessKey=" + enc(FUTURE + "/future"), future, Map.of(SEAT, FUTURE), null),
                "sa.future, posted to SA-FUT from tomorrow, lists its tasks");
        assertSeatNotHeld(send("POST", "/tasks/" + taskOf.get(FUTURE + "/future") + "/complete", future, Map.of(SEAT, FUTURE),
                Map.of("remarks", "Seat header check.")), "sa.future completes a day early");
    }

    /**
     * The same session, the same token: the seat is accepted while the posting is in force and refused from the
     * request after it ended (the resolved identity is re-read when a posting changes; no stale seat is served).
     */
    @Test
    void aPostingEndedDuringASessionIsRefusedOnTheNextRequest() throws Exception {
        ok(send("POST", "/admin/seats", admin, Map.of(), map("id", "SA-LIVE", "code", "SA-LIVE", "designation", "Officer",
                "name", "SA-LIVE", "orgUnitId", OFFICE, "reportsToSeatId", null, "roles", List.of())));
        String session = token("sa.delegate", USER);
        assertSeatNotHeld(send("GET", "/me", session, Map.of(SEAT, "SA-LIVE"), null), "before the posting");
        String posting = post("SA-LIVE", "sa.delegate", "SUBSTANTIVE", LocalDate.now(ZoneOffset.UTC).toString(), null);
        HttpResponse<String> held = send("GET", "/me", session, Map.of(SEAT, "SA-LIVE"), null);
        assertEquals(200, held.statusCode(), "posted: the next request of the same session is accepted: " + held.body());
        ok(send("POST", "/admin/assignments/" + posting + "/end", admin, Map.of(), map("reason", "Relieved")));
        assertSeatNotHeld(send("GET", "/me", session, Map.of(SEAT, "SA-LIVE"), null), "relieved: the next request of the same session");
    }

    // ------------------------------------------------------------------ a token that carries a seats claim

    /** The claim still limits the header, and it never gives a seat the person does not hold. */
    @Test
    void aSeatsClaimLimitsTheHeaderAndNeverGivesASeat() throws Exception {
        String path = "/tasks?businessKey=" + enc(SUB + "/claimed-token");
        // the holder, token limited to another seat: refused by the claim (as before)
        assertSeatNotHeld(send("GET", path, tokenWithSeats("sa.holder", List.of(OTHER)), Map.of(SEAT, SUB), null),
                "the holder's token names SA-OTHER only");
        // the holder, token naming the seat: accepted
        assertEquals(List.of("act"), tasks(send("GET", path, tokenWithSeats("sa.holder", List.of(SUB)), Map.of(SEAT, SUB), null)));
        // a non-holder whose token names the seat: the claim is not a posting
        assertSeatNotHeld(send("GET", path, tokenWithSeats("sa.outsider", List.of(SUB)), Map.of(SEAT, SUB), null),
                "sa.outsider's token names SA-SUB, the person holds SA-OTHER only");
    }

    // ------------------------------------------------------------------ helpers

    /** The holder is shown the seat's task and completes it; the audit names the seat. */
    private void holderActs(String person, String seat, String businessKey) throws Exception {
        String token = token(person, USER);
        assertEquals(List.of("act"), tasks(send("GET", "/tasks?businessKey=" + enc(businessKey), token, Map.of(SEAT, seat), null)),
                person + " is shown the task of " + seat);
        String task = taskOf.get(businessKey);
        HttpResponse<String> done = send("POST", "/tasks/" + task + "/complete", token, Map.of(SEAT, seat),
                Map.of("remarks", "Done by the seat holder."));
        assertEquals(200, done.statusCode(), person + " completes the task of " + seat + ": " + done.body());
        assertEquals(0, engine.getTaskService().createTaskQuery().taskId(task).count(), "the task is completed");
        // the audit trail an administrator reads: who completed the task, and in which seat
        HttpResponse<String> audit = send("GET", "/audit/events?type=TASK_COMPLETED&actor=" + enc(person) + "&size=100", admin, Map.of(), null);
        assertEquals(200, audit.statusCode(), audit.body());
        JsonNode events = JSON.readTree(audit.body());
        List<String> seen = new ArrayList<>();
        (events.isArray() ? events : events.path("items")).forEach(e -> seen.add(e.path("actor").asText() + " in "
                + e.path("seatId").asText() + " task " + e.path("payload").path("task_id").asText()));
        assertTrue(seen.contains(person + " in " + seat + " task " + task), "TASK_COMPLETED of " + person + " in " + seat + ": " + seen);
    }

    private static void assertSeatNotHeld(HttpResponse<String> r, String what) throws Exception {
        assertEquals(403, r.statusCode(), what + ": " + r.statusCode() + " " + r.body());
        assertEquals("SEAT_NOT_HELD", JSON.readTree(r.body()).path("code").asText(), what + ": " + r.body());
    }

    /** The task definition keys of a 200 task list. */
    private static List<String> tasks(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        JsonNode body = JSON.readTree(r.body());
        List<String> keys = new ArrayList<>();
        (body.isArray() ? body : body.path("items")).forEach(t -> keys.add(t.path("taskDefinitionKey").asText()));
        return keys;
    }

    /** One posting through {@code POST /admin/assignments}; returns its id. */
    private String post(String seat, String person, String kind, String from, String to) throws Exception {
        HttpResponse<String> r = send("POST", "/admin/assignments", admin, Map.of(), map("seatId", seat, "personId", person,
                "kind", kind, "validFrom", from, "validTo", to, "orderRef", "Office order: seat header check"));
        ok(r);
        return JSON.readTree(r.body()).path("id").asText();
    }

    /**
     * Approved leave of {@code person} from a minute ago for two days, with a substitute for the seats the person
     * holds. Recorded through {@code PostingService.recordLeave}, the service behind
     * {@code POST /admin/assignments} kind LEAVE_SUBSTITUTE, as another approver: over REST the administrator is
     * both the requester and the approver of the substitution, which the service refuses (requester ≠ approver,
     * AJ-04 step 5); what the REST call answered is kept in {@link #leaveRecordedBy} for the report.
     */
    private void recordLeave(String person, String substitute) throws Exception {
        HttpResponse<String> rest = send("POST", "/admin/assignments", admin, Map.of(), map("seatId", LEAVE, "personId", substitute,
                "kind", "LEAVE_SUBSTITUTE", "validFrom", LocalDate.now(ZoneOffset.UTC).toString(),
                "validTo", LocalDate.now(ZoneOffset.UTC).plusDays(2).toString(), "orderRef", "Leave order: seat header check"));
        if (rest.statusCode() / 100 == 2) {
            leaveRecordedBy = "POST /admin/assignments LEAVE_SUBSTITUTE " + rest.statusCode();
            return;
        }
        SangamSecurity security = ctx.getBean(SangamSecurity.class);
        ctx.getBean("sangamProductTransactions", Transactions.class).inTransaction(c -> {
            Instant now = Sql.dbNow(c, security.dialect());
            return security.postings().recordLeave(c, SecurityIdentity.system(TENANT, "seat-check").withPerson("rajendra.sharma"),
                    person, now.minusSeconds(60), now.plusSeconds(2 * 24 * 3600L), substitute, person);
        });
        leaveRecordedBy = "PostingService.recordLeave; POST /admin/assignments LEAVE_SUBSTITUTE answered " + rest.statusCode()
                + " " + JSON.readTree(rest.body()).path("code").asText();
        System.out.println("SEAT-AUTHZ | leave | INFO | " + leaveRecordedBy);
    }

    private static void ok(HttpResponse<String> r) {
        assertTrue(r.statusCode() / 100 == 2, r.request().method() + " " + r.request().uri() + ": " + r.statusCode() + " " + r.body());
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

    private static String token(String user, List<String> roles) throws Exception {
        return DemoComposeFragmentStartupTest.RealmIdp.get().tokenWithoutSub(TENANT, user, roles);
    }

    /** A token of an identity provider that does issue the seats claim (SANGAM_SECURITY_OIDC_SEATS_CLAIM=seats). */
    private static String tokenWithSeats(String user, List<String> seats) throws Exception {
        return DemoComposeFragmentStartupTest.RealmIdp.get().tokenWithoutSub(TENANT, user, USER, Map.of("seats", seats));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** A map that may hold null values (JSON null), in the given order. */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
