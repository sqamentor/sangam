// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.IdempotencyPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.web.CommandCatalog;
import org.sangam.rest.web.IdempotencyFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RM-10: a replay is returned only after its class-B {@code IDEMPOTENT_REPLAY} audit row is written. When the
 * {@link AuditPort} fails, the filter answers {@code 503 AUDIT_UNAVAILABLE} with {@code Retry-After}, and none of the
 * stored result leaks: not its body, not its status, not the {@code Idempotent-Replayed} or resource-ref headers. The
 * command never runs again (no claim). With a working audit the stored response is replayed as before.
 * Written for the test phase (not run here).
 */
class IdempotencyFilterReplayAuditTest {

    static final byte[] STORED = "{\"id\":\"pi-1\",\"secret\":\"stored-body\"}".getBytes(StandardCharsets.UTF_8);

    static CommandCatalog catalog() {
        Map<String, Object> op = Map.of("x-sangam-command-type", "process.modify", "x-sangam-idempotent", true);
        return new CommandCatalog(Map.of("paths", Map.of("/things/{id}/modify", Map.of("post", op))));
    }

    static IdempotencyPort replaying(AtomicBoolean executed) {
        return new IdempotencyPort() {
            @Override
            public Begin begin(RequestContext ctx, String commandType, String key, byte[] fingerprint) {
                return new Replay(201, STORED, "pi-1", false, Instant.parse("2026-11-13T00:00:00Z"));
            }

            @Override
            public Outcome execute(Claimed claim, Command command) {
                executed.set(true);
                throw new AssertionError("a replay never executes the command");
            }
        };
    }

    static PermissionPort allowAll() {
        return (PermissionPort) Proxy.newProxyInstance(PermissionPort.class.getClassLoader(),
                new Class<?>[]{PermissionPort.class}, (p, m, a) -> switch (m.getName()) {
                    case "has", "mayReplay" -> true;
                    case "permissions" -> Set.of("*");
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    /** An audit port whose {@code record} fails (or counts) and that supports nothing else. */
    static AuditPort audit(boolean fail, AtomicInteger records) {
        return (AuditPort) Proxy.newProxyInstance(AuditPort.class.getClassLoader(), new Class<?>[]{AuditPort.class},
                (p, m, a) -> {
                    if (m.getName().equals("record")) {
                        records.incrementAndGet();
                        if (fail) {
                            throw new IllegalStateException("audit store down");
                        }
                        assertEquals("IDEMPOTENT_REPLAY", a[2]);
                        return "evt-1";
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    static MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/things/pi-1/modify");
        req.setContentType("application/json");
        req.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        req.addHeader("Idempotency-Key", "k-1");
        req.setAttribute(SangamPrincipal.ATTRIBUTE, new SangamPrincipal("t1", "u1", Set.of("sangam-user"), "OIDC",
                Map.of()));
        return req;
    }

    MockHttpServletResponse run(boolean auditFails, AtomicInteger records, AtomicBoolean executed,
                                AtomicBoolean chainCalled) throws Exception {
        IdempotencyFilter f = new IdempotencyFilter(catalog(), replaying(executed), allowAll(),
                audit(auditFails, records), new RestProperties());
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(request(), res, (rq, rs) -> chainCalled.set(true));
        return res;
    }

    @Test
    void aFailedReplayAuditReturns503AndNothingOfTheStoredResult() throws Exception {
        AtomicInteger records = new AtomicInteger();
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean chain = new AtomicBoolean();
        MockHttpServletResponse res = run(true, records, executed, chain);

        assertEquals(1, records.get(), "the replay audit was attempted once");
        assertEquals(503, res.getStatus());
        assertEquals(IdempotencyFilter.REPLAY_AUDIT_UNAVAILABLE, "AUDIT_UNAVAILABLE");
        String body = res.getContentAsString();
        assertTrue(body.contains("AUDIT_UNAVAILABLE"), body);
        assertFalse(body.contains("stored-body"), "the stored body never leaks: " + body);
        assertNull(res.getHeader("Idempotent-Replayed"), "not marked as a replay");
        assertNull(res.getHeader("Idempotency-Key-Expires"), "no header of the stored result");
        assertTrue(res.getHeader("Retry-After") != null, "the client is told to retry");
        assertFalse(executed.get(), "the command is not executed again");
        assertFalse(chain.get(), "the controller is not called");
    }

    @Test
    void aWrittenReplayAuditReturnsTheStoredResponse() throws Exception {
        AtomicInteger records = new AtomicInteger();
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean chain = new AtomicBoolean();
        MockHttpServletResponse res = run(false, records, executed, chain);

        assertEquals(1, records.get());
        assertEquals(201, res.getStatus());
        assertEquals("true", res.getHeader("Idempotent-Replayed"));
        assertTrue(res.getContentAsString().contains("stored-body"));
        assertFalse(executed.get());
        assertFalse(chain.get());
    }
}
