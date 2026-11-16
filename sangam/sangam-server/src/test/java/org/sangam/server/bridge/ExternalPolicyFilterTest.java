// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.sangam.core.engine.LockScope;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.IdempotencyPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.web.CommandCatalog;
import org.sangam.rest.web.IdempotencyFilter;
import org.sangam.rest.web.Problems;
import org.sangam.security.policy.external.ExternalPolicyCheck;
import org.sangam.security.policy.external.ExternalPolicyCommands;
import org.sangam.security.policy.external.ExternalPolicyDecision;
import org.sangam.security.policy.external.ExternalPolicyGate;
import org.sangam.security.policy.external.ExternalPolicyUnavailable;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Owner decision 166 (M-083) at the servlet entry, chained in front of the real {@link IdempotencyFilter} as the
 * server orders them. Proves the ordering (the policy call comes before the idempotency claim, the command and its L2
 * lock, and sees no lock held), deny wins with {@code 403 POLICY_DENIED} and no claim, an allow never overrides
 * Sangam's own refusal, an unavailable service fails closed for a required command ({@code 503 POLICY_UNAVAILABLE}) and
 * lets a best-effort one continue, each refusal is recorded as {@code AUTH_DENIED} on the tenant's security chain (and
 * still refused when that record fails), and requests that are no command make no call. Decision 200: external-worker
 * calls ({@code work.*}) and state-changing {@code /engine-rest} calls ({@code engine-rest}) are checked the same way. Written for the test phase
 * (not run here).
 */
class ExternalPolicyFilterTest {

    static final ObjectMapper JSON = new ObjectMapper();

    final List<String> events = new CopyOnWriteArrayList<>();
    final List<Object[]> audits = new ArrayList<>();

    static CommandCatalog catalog() {
        Map<String, Object> complete = Map.of("x-sangam-command-type", "task.complete", "x-sangam-idempotent", true);
        Map<String, Object> start = Map.of("x-sangam-command-type", "process.start", "x-sangam-idempotent", true);
        return new CommandCatalog(Map.of("paths", Map.of(
                "/tasks/{taskId}/complete", Map.of("post", complete),
                "/processes", Map.of("post", start))));
    }

    /** Decision 200: the catalogue plus two fenced worker operations of the contract shape, plus engine-rest. */
    static ExternalPolicyTargets targets() {
        Map<String, Object> fenced = Map.of("x-sangam-fenced", true, "x-sangam-idempotent", false);
        return new ExternalPolicyTargets(catalog(), Map.of("paths", Map.of(
                "/work/fetch-and-lock", Map.of("post", fenced),
                "/work/{id}/complete", Map.of("post", fenced),
                "/work/{id}/step", Map.of("get", fenced, "put", fenced))));
    }

    ExternalPolicyGate gate(ExternalPolicyCheck answer, List<String> required, List<String> bestEffort) {
        ExternalPolicyCheck recording = r -> {
            events.add("policy lockHeld=" + LockScope.isLockHeld() + " " + r.commandType() + " "
                    + r.pathParams().get("taskId") + " " + r.actorId() + "@" + r.tenantId());
            return answer.check(r);
        };
        List<String> known = List.copyOf(targets().commandTypes());
        return new ExternalPolicyGate(recording, ExternalPolicyCommands.of(known, required, bestEffort), null);
    }

    ExternalPolicyFilter filter(ExternalPolicyGate gate, boolean auditFails) {
        return new ExternalPolicyFilter(targets(), gate, (ctx, chainId, type, payload) -> {
            if (auditFails) {
                throw new IllegalStateException("audit store down");
            }
            audits.add(new Object[]{ctx.tenantId(), chainId, type, Map.copyOf(payload)});
        }, 7);
    }

    IdempotencyFilter idempotency() {
        IdempotencyPort port = new IdempotencyPort() {
            @Override
            public Begin begin(RequestContext ctx, String commandType, String key, byte[] fingerprint) {
                events.add("claim " + commandType);
                return new Claimed("h");
            }

            @Override
            public Outcome execute(Claimed claim, Command command) throws Exception {
                events.add("transaction");
                return command.run();
            }
        };
        PermissionPort permissions = (PermissionPort) Proxy.newProxyInstance(PermissionPort.class.getClassLoader(),
                new Class<?>[]{PermissionPort.class}, (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
        AuditPort audit = (AuditPort) Proxy.newProxyInstance(AuditPort.class.getClassLoader(),
                new Class<?>[]{AuditPort.class}, (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
        return new IdempotencyFilter(catalog(), port, permissions, audit, new RestProperties());
    }

    /** The command: takes the L2 lock (as the engine does), runs Sangam's own check, answers. */
    HttpServlet command(boolean sangamDenies) {
        return new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
                LockScope.enter();
                try {
                    events.add("command lockHeld=" + LockScope.isLockHeld());
                    if (sangamDenies) {
                        Problems.write(req, res, ApiProblem.notPermitted("task.write"));
                        return;
                    }
                    res.setStatus(201);
                    res.getOutputStream().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                } finally {
                    LockScope.exit();
                }
            }
        };
    }

    static MockHttpServletRequest request(String method, String uri, boolean authenticated) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setContentType("application/json");
        req.setContent("{\"outcome\":\"approved\"}".getBytes(StandardCharsets.UTF_8));
        req.addHeader("Idempotency-Key", "k-1");
        if (authenticated) {
            req.setAttribute(SangamPrincipal.ATTRIBUTE, new SangamPrincipal("t1", "u1", Set.of("clerk"), "OIDC", Map.of()));
        }
        return req;
    }

    MockHttpServletResponse run(ExternalPolicyGate gate, boolean sangamDenies, boolean auditFails,
                                MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        Filter[] filters = {filter(gate, auditFails), idempotency()};
        new MockFilterChain(command(sangamDenies), filters).doFilter(req, res);
        return res;
    }

    static JsonNode problem(MockHttpServletResponse res) throws IOException {
        return JSON.readTree(res.getContentAsByteArray());
    }

    @Test
    void thePolicyCallComesBeforeTheClaimTheTransactionAndTheLock() throws Exception {
        MockHttpServletResponse res = run(gate(r -> ExternalPolicyDecision.allowContinue(null), List.of("*"), List.of()),
                false, false, request("POST", "/api/v1/tasks/task-1/complete", true));
        assertEquals(201, res.getStatus());
        assertEquals(List.of("policy lockHeld=false task.complete task-1 u1@t1", "claim task.complete", "transaction",
                "command lockHeld=true"), events);
        assertTrue(audits.isEmpty());
    }

    @Test
    void aDenyRefusesWith403BeforeAnyClaimTransactionOrLock() throws Exception {
        MockHttpServletResponse res = run(gate(r -> ExternalPolicyDecision.deny("OUTSIDE_HOURS", "pol-3"),
                List.of("*"), List.of()), false, false, request("POST", "/api/v1/tasks/task-1/complete", true));
        assertEquals(403, res.getStatus());
        JsonNode p = problem(res);
        assertEquals(ExternalPolicyFilter.POLICY_DENIED, p.get("code").asText());
        assertEquals("OUTSIDE_HOURS", p.findValue("reason_code").asText());
        assertEquals(1, events.size(), "only the policy call: " + events);
        assertEquals(1, audits.size());
        assertEquals("t1", audits.get(0)[0]);
        assertEquals("sec:t1", audits.get(0)[1]);
        assertEquals("AUTH_DENIED", audits.get(0)[2]);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) audits.get(0)[3];
        assertEquals("task.complete", payload.get("action"));
        assertEquals("DENIED", payload.get("outcome"));
        assertEquals("POLICY_DENIED", payload.get("reason"));
        assertEquals("external", payload.get("policy_source"));
        assertEquals("OUTSIDE_HOURS", payload.get("external_reason_code"));
        assertEquals("pol-3", payload.get("external_policy_id"));
    }

    @Test
    void aDenyWinsEvenForABestEffortCommand() throws Exception {
        MockHttpServletResponse res = run(gate(r -> ExternalPolicyDecision.deny(null, null), List.of(), List.of("*")),
                false, false, request("POST", "/api/v1/tasks/task-1/complete", true));
        assertEquals(403, res.getStatus());
        assertEquals(ExternalPolicyFilter.POLICY_DENIED, problem(res).get("code").asText());
        assertEquals(ExternalPolicyDecision.UNSPECIFIED, problem(res).findValue("reason_code").asText());
    }

    @Test
    void anAllowNeverOverridesSangamsOwnRefusal() throws Exception {
        MockHttpServletResponse res = run(gate(r -> ExternalPolicyDecision.allowContinue(null), List.of("*"), List.of()),
                true, false, request("POST", "/api/v1/tasks/task-1/complete", true));
        assertEquals(403, res.getStatus());
        assertEquals("ACTION_NOT_PERMITTED", problem(res).get("code").asText());
    }

    @Test
    void anUnavailableServiceFailsClosedForARequiredCommand() throws Exception {
        ExternalPolicyCheck timeout = r -> {
            throw new ExternalPolicyUnavailable(ExternalPolicyUnavailable.Reason.TIMEOUT, "slow");
        };
        MockHttpServletResponse res = run(gate(timeout, List.of("task.complete"), List.of()), false, false,
                request("POST", "/api/v1/tasks/task-1/complete", true));
        assertEquals(503, res.getStatus());
        assertEquals("7", res.getHeader("Retry-After"));
        assertEquals(ExternalPolicyFilter.POLICY_UNAVAILABLE, problem(res).get("code").asText());
        assertEquals(1, events.size(), "no claim, no transaction, no command: " + events);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) audits.get(0)[3];
        assertEquals("POLICY_UNAVAILABLE", payload.get("reason"));
        assertEquals("TIMEOUT", payload.get("external_reason_code"));
    }

    @Test
    void anUnavailableServiceLetsABestEffortCommandContinue() throws Exception {
        ExternalPolicyCheck down = r -> {
            throw new ExternalPolicyUnavailable(ExternalPolicyUnavailable.Reason.TRANSPORT, "refused");
        };
        MockHttpServletResponse res = run(gate(down, List.of(), List.of("task.complete")), false, false,
                request("POST", "/api/v1/tasks/task-1/complete", true));
        assertEquals(201, res.getStatus());
        assertTrue(audits.isEmpty());
    }

    @Test
    void aFailedRefusalRecordDoesNotLetTheCommandThrough() throws Exception {
        MockHttpServletResponse res = run(gate(r -> ExternalPolicyDecision.deny("X", null), List.of("*"), List.of()),
                false, true, request("POST", "/api/v1/tasks/task-1/complete", true));
        assertEquals(403, res.getStatus());
        assertEquals(1, events.size(), "the command never ran: " + events);
    }

    @Test
    void requestsThatAreNoCheckedCommandMakeNoCall() throws Exception {
        ExternalPolicyGate gate = gate(r -> ExternalPolicyDecision.deny("X", null), List.of("task.complete"), List.of());
        assertEquals(201, run(gate, false, false, request("GET", "/api/v1/tasks/task-1", true)).getStatus());
        assertEquals(201, run(gate, false, false, request("POST", "/api/v1/not-a-command", true)).getStatus());
        assertEquals(201, run(gate, false, false, request("POST", "/api/v1/processes", true)).getStatus(),
                "process.start is not configured for the check");
        assertFalse(events.stream().anyMatch(e -> e.startsWith("policy")), "no policy call: " + events);
    }

    @Test
    void anUnauthenticatedRequestIsLeftToTheAuthenticationRefusal() throws Exception {
        ExternalPolicyGate gate = gate(r -> ExternalPolicyDecision.deny("X", null), List.of("*"), List.of());
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(gate, false).doFilter(request("POST", "/api/v1/tasks/task-1/complete", false), res,
                (rq, rs) -> events.add("next"));
        assertEquals(List.of("next"), events);
    }

    /** Runs the policy filter alone in front of a terminal step (worker and engine-rest calls skip the idempotency filter). */
    MockHttpServletResponse alone(ExternalPolicyGate gate, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(gate, false).doFilter(req, res, (rq, rs) -> {
            events.add("next");
            ((HttpServletResponse) rs).setStatus(200);
        });
        return res;
    }

    @Test
    void externalWorkerCallsAreCheckedAtEntry() throws Exception {
        // decision 200: the fenced worker calls of the contract, with their own command types
        ExternalPolicyGate gate = gate(r -> ExternalPolicyDecision.deny("WORKER_BLOCKED", null), List.of("*"), List.of());
        MockHttpServletResponse res = alone(gate, request("POST", "/api/v1/work/w-9/complete", true));
        assertEquals(403, res.getStatus());
        assertEquals(ExternalPolicyFilter.POLICY_DENIED, problem(res).get("code").asText());
        assertEquals("policy lockHeld=false work.complete null u1@t1", events.get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) audits.get(0)[3];
        assertEquals("work.complete", payload.get("action"));
        events.clear();
        assertEquals(403, alone(gate, request("POST", "/api/v1/work/fetch-and-lock", true)).getStatus());
        assertTrue(events.get(0).contains("work.fetch-and-lock"), events.toString());
        events.clear();
        assertEquals(403, alone(gate, request("PUT", "/api/v1/work/w-9/step", true)).getStatus());
        assertTrue(events.get(0).contains("work.step "), events.toString());
        events.clear();
        assertEquals(200, alone(gate, request("GET", "/api/v1/work/w-9/step", true)).getStatus(), "a read");
        assertEquals(List.of("next"), events);
    }

    @Test
    void engineRestCommandsAreCheckedAtEntry() throws Exception {
        List<String> paths = new ArrayList<>();
        ExternalPolicyGate gate = gate(r -> {
            paths.add(r.method() + " " + r.pathTemplate());
            return ExternalPolicyDecision.deny("ENGINE_REST_BLOCKED", null);
        }, List.of("engine-rest"), List.of());
        MockHttpServletResponse res = alone(gate,
                request("POST", "/engine-rest/process-definition/key/claim/start", true));
        assertEquals(403, res.getStatus());
        assertEquals(List.of("POST /engine-rest/process-definition/key/claim/start"), paths);
        assertEquals(403, alone(gate, request("DELETE", "/engine-rest/process-instance/pi-1", true)).getStatus());
        events.clear();
        assertEquals(200, alone(gate, request("GET", "/engine-rest/process-instance", true)).getStatus(),
                "reads are not commands");
        assertEquals(List.of("next"), events);
    }

    @Test
    void anUncheckedTypeMakesNoCallAndNeedsNoTenant() throws Exception {
        ExternalPolicyGate gate = gate(r -> ExternalPolicyDecision.deny("X", null), List.of("task.complete"), List.of());
        assertEquals(200, alone(gate, request("POST", "/engine-rest/task/t-1/complete", true)).getStatus());
        assertEquals(200, alone(gate, request("POST", "/api/v1/work/w-1/complete", true)).getStatus());
        assertFalse(events.stream().anyMatch(e -> e.startsWith("policy")), events.toString());
    }

    @Test
    void theTargetsKnowEveryCheckedType() {
        Set<String> types = targets().commandTypes();
        assertTrue(types.containsAll(Set.of("task.complete", "process.start", "work.fetch-and-lock", "work.complete",
                "work.step", ExternalPolicyTargets.ENGINE_REST)), types.toString());
        assertEquals("work.cancel-ack", ExternalPolicyTargets.workerCommandType("/work/{id}/cancel-ack"));
    }
}
