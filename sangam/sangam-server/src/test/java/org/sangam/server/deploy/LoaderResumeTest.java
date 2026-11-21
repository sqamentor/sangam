// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LOADER-WALKTHROUGH, a reload after a partial load: what {@code load_presentation_data.py} does with a case whose
 * business key is already taken. The server has no rule that a business key is unique
 * ({@code OperatonProcessAdapter.start}: a second {@code POST /process-instances} with the same key is a second
 * instance), so the loader is the only guard against a duplicate, and the instance it finds is the one it must
 * continue and judge.
 * <p>
 * The script runs unchanged ({@code python -I}) against a small answering machine that speaks the five routes the
 * loader reads and records every write, so each state of an earlier, unfinished load can be set up exactly: two
 * instances on one key, a case that ended rejected, a case stopped at an incident, a case that does not wait for the
 * outside answer the data set declares. The same against a real server: {@link LoaderWalkthroughAgainstServerTest}.
 */
class LoaderResumeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path LOADER = Path.of("..", "deploy", "presentation", "load_presentation_data.py").toAbsolutePath().normalize();
    private static final String BK = "JPR-DDO-0420/BILL/2026-27/9001";

    @TempDir
    Path dir;

    private HttpServer api;
    /** The instances of the tenant, as {@code GET /process-instances} lists them. */
    private final List<Map<String, Object>> instances = new CopyOnWriteArrayList<>();
    /** Ids of the instances that carry an incident ({@code withIncidents=true}). */
    private final List<String> withIncident = new CopyOnWriteArrayList<>();
    private final Map<String, String> caseState = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> starts = new CopyOnWriteArrayList<>();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    /** What a delivered message does to the first instance (the outside answer closes the case). */
    private volatile boolean messageClosesTheCase;

    @BeforeEach
    void startApi() throws Exception {
        api = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        api.createContext("/api/v1", x -> {
            try {
                String path = x.getRequestURI().getPath().substring("/api/v1".length());
                String query = x.getRequestURI().getRawQuery() == null ? "" : URLDecoder.decode(x.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                byte[] in = x.getRequestBody().readAllBytes();
                Object out;
                int status = 200;
                if (x.getRequestMethod().equals("GET") && path.equals("/process-instances")) {
                    out = query.contains("withIncidents=true")
                            ? instances.stream().filter(i -> withIncident.contains(i.get("id"))).toList() : instances;
                } else if (x.getRequestMethod().equals("GET") && path.endsWith("/variables")) {
                    String id = path.split("/")[2];
                    out = caseState.containsKey(id) ? Map.of("caseState", caseState.get(id)) : Map.of();
                } else if (x.getRequestMethod().equals("GET") && path.startsWith("/process-instances/")) {
                    String id = path.split("/")[2];
                    out = instances.stream().filter(i -> id.equals(i.get("id"))).findFirst().orElseThrow();
                } else if (x.getRequestMethod().equals("GET") && path.equals("/tasks")) {
                    out = List.of();
                } else if (x.getRequestMethod().equals("POST") && path.equals("/process-instances")) {
                    starts.add(new String(in, StandardCharsets.UTF_8));
                    instances.add(instance("started-" + starts.size(), false, "ACTIVE", List.of("bl_payee")));
                    out = instances.get(instances.size() - 1);
                    status = 201;
                } else if (x.getRequestMethod().equals("POST") && path.equals("/messages")) {
                    messages.add(new String(in, StandardCharsets.UTF_8));
                    if (messageClosesTheCase) {
                        String id = String.valueOf(instances.get(0).get("id"));
                        instances.set(0, instance(id, true, "COMPLETED", List.of()));
                        caseState.put(id, "CLOSED");
                    }
                    out = List.of();
                } else {
                    out = Map.of("code", "NOT_FOUND");
                    status = 404;
                }
                byte[] body = JSON.writeValueAsBytes(out);
                x.getResponseHeaders().set("Content-Type", "application/json");
                x.sendResponseHeaders(status, body.length);
                x.getResponseBody().write(body);
            } finally {
                x.close();
            }
        });
        api.start();
    }

    @AfterEach
    void stopApi() {
        api.stop(0);
    }

    /** Two instances on one business key: the loader cannot know which one is the case, so it touches neither. */
    @Test
    void twoInstancesOnOneBusinessKeyAreReportedAndNothingIsStartedOrContinued() throws Exception {
        instances.add(instance("a", false, "ACTIVE", List.of("bl_payee")));
        instances.add(instance("b", false, "ACTIVE", List.of("bl_payee")));
        PresentationSeedAgainstServerTest.Seed run = load(aCase("COMPLETED", null));
        assertEquals(1, run.exit(), run.out());
        assertTrue(run.out().contains("2 instances carry this business key"), run.out());
        assertEquals(List.of(), starts, "nothing is started");
    }

    /** An earlier load left the case ended, but rejected: it is found, not started again, and not reported as done. */
    @Test
    void aCaseThatEndedRejectedIsFoundNotStartedAgainAndNotAtItsTarget() throws Exception {
        instances.add(instance("a", true, "COMPLETED", List.of()));
        caseState.put("a", "REJECTED");
        PresentationSeedAgainstServerTest.Seed run = load(aCase("COMPLETED", null));
        assertEquals(List.of(), starts, "the business key is taken: nothing is started\n" + run.out());
        assertEquals(1, run.exit(), "ended, but not CLOSED (PresentationDataSetH2Test: COMPLETED = ended and CLOSED):\n" + run.out());
        assertTrue(run.out().contains("NOT AT TARGET: ended, case state REJECTED"), run.out());
    }

    /** The control of the test above: ended and CLOSED is the target; a second run starts nothing and changes nothing. */
    @Test
    void aCaseThatEndedClosedIsAtItsTargetAndARerunStartsNothing() throws Exception {
        instances.add(instance("a", true, "COMPLETED", List.of()));
        caseState.put("a", "CLOSED");
        for (int i = 0; i < 2; i++) {
            PresentationSeedAgainstServerTest.Seed run = load(aCase("COMPLETED", null));
            assertEquals(0, run.exit(), run.out());
            assertTrue(run.out().contains("exists: continued"), run.out());
        }
        assertEquals(List.of(), starts);
        assertEquals(List.of(), messages);
    }

    /** Open, no task for anyone, past the PAO: that is PAYMENT_HELD only when no step of the case failed. */
    @Test
    void aCaseStoppedAtAnIncidentIsNotReportedAsHeldPayment() throws Exception {
        instances.add(instance("a", false, "ACTIVE", List.of("ex_submit")));
        caseState.put("a", "EXECUTION");
        withIncident.add("a");
        PresentationSeedAgainstServerTest.Seed run = load(aCase("PAYMENT_HELD", null));
        assertEquals(1, run.exit(), run.out());
        assertTrue(run.out().contains("NOT AT TARGET") && run.out().contains("incident"), run.out());
        assertEquals(List.of(), starts);
    }

    /** The control: the same case without an incident is a held payment. */
    @Test
    void anOpenCasePastThePaoWithoutAnIncidentIsAHeldPayment() throws Exception {
        instances.add(instance("a", false, "ACTIVE", List.of("ex_wait")));
        caseState.put("a", "EXECUTION");
        PresentationSeedAgainstServerTest.Seed run = load(aCase("PAYMENT_HELD", null));
        assertEquals(0, run.exit(), run.out());
    }

    /**
     * The data set says where the outside answer is awaited ({@code events[].when}). A case that stands somewhere else
     * (here at a task nobody of the data set can see) gets no message: the engine would refuse it (no subscription),
     * and where a subscription of the same name waits elsewhere it would be the wrong answer.
     */
    @Test
    void theOutsideAnswerIsNotSentToACaseThatDoesNotWaitForIt() throws Exception {
        instances.add(instance("a", false, "ACTIVE", List.of("gv_examine")));
        caseState.put("a", "SUBMITTED");
        PresentationSeedAgainstServerTest.Seed run = load(aCase("COMPLETED", "gv_feedbackWait"));
        assertEquals(List.of(), messages, "no message for a case that does not wait at gv_feedbackWait:\n" + run.out());
        assertEquals(1, run.exit(), run.out());
    }

    /** The control: where the case waits for it, the answer is sent once and the case closes. */
    @Test
    void theOutsideAnswerIsSentOnceWhereTheCaseWaitsForIt() throws Exception {
        instances.add(instance("a", false, "ACTIVE", List.of("gv_feedbackWait")));
        caseState.put("a", "RESOLVED");
        messageClosesTheCase = true;
        PresentationSeedAgainstServerTest.Seed run = load(aCase("COMPLETED", "gv_feedbackWait"));
        assertEquals(1, messages.size(), run.out());
        assertEquals(0, run.exit(), run.out());
        PresentationSeedAgainstServerTest.Seed again = load(aCase("COMPLETED", "gv_feedbackWait"));
        assertEquals(1, messages.size(), "a second run sends nothing:\n" + again.out());
        assertEquals(0, again.exit(), again.out());
        assertFalse(again.out().contains("started"), again.out());
    }

    private static Map<String, Object> instance(String id, boolean ended, String state, List<String> active) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("id", id);
        i.put("businessKey", BK);
        i.put("state", state);
        i.put("ended", ended);
        i.put("activeActivityIds", active);
        i.put("superProcessInstanceId", null);
        return i;
    }

    /** A data set of one case, started by the one person it names. */
    private Path aCase(String target, String eventWhen) throws Exception {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", "CASE-1");
        c.put("process", "sangam-ifms-bill");
        c.put("businessKey", BK);
        c.put("startedBy", "kavita.sharma");
        c.put("target", target);
        c.put("variables", Map.of("amount", 100));
        if (eventWhen != null) {
            c.put("events", List.of(Map.of("when", eventWhen, "message", "sangam.grievanceFeedback", "variables", Map.of("rating", "good"))));
        }
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("dataSet", "loader-resume-test");
        ds.put("version", "1");
        ds.put("tenant", "GOI.FIN");
        ds.put("people", Map.of("kavita.sharma", Map.of("seat", "DDO-1", "name", "Kavita Sharma")));
        ds.put("cases", List.of(c));
        Path file = dir.resolve("one-case.json");
        Files.writeString(file, JSON.writeValueAsString(ds), StandardCharsets.UTF_8);
        return file;
    }

    private PresentationSeedAgainstServerTest.Seed load(Path data) throws Exception {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SANGAM_API_BASE", "http://localhost:" + api.getAddress().getPort() + "/api/v1");
        env.put("SANGAM_PRESENTATION_TOKEN_KAVITA_SHARMA", "token-of-the-test");
        env.put("SANGAM_PRESENTATION_SETTLE_SECONDS", "8");
        List<String> args = new ArrayList<>(List.of("--data", data.toString()));
        return PresentationSeedAgainstServerTest.script(LOADER, args, env);
    }
}
