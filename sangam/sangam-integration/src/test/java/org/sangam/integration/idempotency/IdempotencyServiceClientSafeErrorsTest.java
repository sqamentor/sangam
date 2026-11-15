// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.IdempotencyStore;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;

/**
 * Code scanning java/error-message-exposure (#10): {@link IdempotencyService#execute} answered every
 * {@code IllegalArgumentException} of the fingerprint or the claim with its message as the 400 detail, choosing the
 * code by searching that message. Only the typed {@link IdempotencyStore.InvalidRequestException} (code and fixed
 * detail) is answered now; any other one is an internal error and leaves {@code execute} unanswered (500 upstream).
 */
class IdempotencyServiceClientSafeErrorsTest {

    private static final String SECRET = "ORA-12345 secret";

    private final AtomicInteger commandRuns = new AtomicInteger();

    /** A replay authorizer that permits nothing. */
    private static final ReplayAuthorizer REFUSE_REPLAY = new ReplayAuthorizer() {
        @Override
        public boolean mayReplay(String tenantId, String principalId, String commandType, String resourceRef) {
            return false;
        }

        @Override
        public byte[] mask(String tenantId, String principalId, String resourceRef, byte[] storedBody) {
            return new byte[0];
        }
    };

    /** A claim transaction that fails inside the driver with an IllegalArgumentException. */
    private static Transactions failing(RuntimeException failure) {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                throw failure;
            }
        };
    }

    private IdempotencyService service(Transactions tx) {
        // RM-27: profile and replay authorizer are stated; these tests never reach a replay
        return new IdempotencyService(new IdempotencyStore(null, tx, "node-1"), null, tx, IdempotencyProfile.optional(),
                REFUSE_REPLAY, true);
    }

    private static IdempotentRequest request(String key, Object body) {
        return new IdempotentRequest("T1", "user-1", "task.complete", "POST", "/api/v1/tasks/{id}/complete",
                Map.of("id", "t-1"), Map.of(), body, key, null, null, 0);
    }

    private IdempotentResponse run(IdempotencyService s, IdempotentRequest r) {
        return s.execute(r, ctx -> {
            commandRuns.incrementAndGet();
            return new IdempotentResponse(200, new byte[0], null, Map.of());
        });
    }

    private static JsonNode problem(IdempotentResponse r) {
        assertEquals("application/problem+json", r.contentType());
        JsonNode json = Json.tree(r.body());
        assertNotNull(json, "the body is valid JSON: " + new String(r.body(), StandardCharsets.UTF_8));
        return json;
    }

    @Test
    void anInternalIllegalArgumentExceptionOfTheClaimIsNotAnsweredWithItsText() {
        IllegalArgumentException internal = new IllegalArgumentException(SECRET);
        IdempotencyService s = service(failing(internal));
        IdempotentResponse answered;
        try {
            answered = run(s, request("key-1", Map.of("approved", true)));
        } catch (IllegalArgumentException propagated) {
            assertSame(internal, propagated, "it leaves execute for the generic 500 path");
            assertEquals(0, commandRuns.get());
            return;
        }
        fail("the internal error was answered to the caller: " + answered.status() + " "
                + new String(answered.body(), StandardCharsets.UTF_8));
    }

    @Test
    void anInvalidKeyIsStill400WithItsCodeAndFixedDetail() {
        IdempotencyService s = service(failing(new IllegalStateException("the claim must not be reached")));
        IdempotentResponse nonAscii = run(s, request("clé-key", Map.of()));
        assertEquals(400, nonAscii.status());
        assertEquals("IDEMPOTENCY_KEY_INVALID", problem(nonAscii).path("code").asText());
        assertEquals("printable ASCII only", problem(nonAscii).path("detail").asText());

        IdempotentResponse tooLong = run(s, request("k".repeat(129), Map.of()));
        assertEquals(400, tooLong.status());
        assertEquals("IDEMPOTENCY_KEY_INVALID", problem(tooLong).path("code").asText());
        assertEquals("length must be 1..128", problem(tooLong).path("detail").asText());
        assertEquals(0, commandRuns.get());
    }

    @Test
    void aBodyThatCannotBeFingerprintedIs400WithAFixedDetailThatDoesNotEchoTheValue() {
        IdempotencyService s = service(failing(new IllegalStateException("the claim must not be reached")));
        IdempotentResponse r = run(s, request("key-2", Map.of("amount", 9173.25d)));
        assertEquals(400, r.status());
        JsonNode p = problem(r);
        assertEquals("INVALID_REQUEST", p.path("code").asText());
        assertEquals("the request holds a number with a fraction; decimals must be sent as strings", p.path("detail").asText());
        assertFalse(new String(r.body(), StandardCharsets.UTF_8).contains("9173"), "the caller's value is not echoed");
        assertEquals(0, commandRuns.get());
    }

    @Test
    void theStoreThrowsTheTypedExceptionAndKeepsItsMessageForOlderCallers() {
        IdempotencyStore store = new IdempotencyStore(null, failing(new IllegalStateException("not reached")), "node-1");
        IdempotencyStore.InvalidRequestException e = assertThrows(IdempotencyStore.InvalidRequestException.class,
                () -> store.begin("T1", "task.complete", "", "user-1", new byte[32]));
        assertEquals(IdempotencyStore.CODE_KEY_INVALID, e.code());
        assertEquals("length must be 1..128", e.detail());
        assertEquals("400 IDEMPOTENCY_KEY_INVALID: length must be 1..128", e.getMessage());
        IdempotencyStore.InvalidRequestException f = assertThrows(IdempotencyStore.InvalidRequestException.class,
                () -> IdempotencyStore.fingerprint("c", "POST", "/p", Map.of(), Map.of(), Map.of("x", 1.5d)));
        assertEquals(IdempotencyStore.CODE_REQUEST_INVALID, f.code());
        assertFalse(f.detail().contains("1.5"));
    }

    @Test
    void aProblemDocumentIsValidJsonForAnyDetail() {
        String hostile = "line1\r\nline2\t\"quoted\" \\ <script>\u0001";
        IdempotentResponse r = IdempotentResponse.problem(503, "BACKPRESSURE", hostile, Map.of("Retry-After", "5"));
        JsonNode p = problem(r);
        assertEquals(hostile, p.path("detail").asText(), "escaped on the way out, intact after parsing");
        assertEquals("BACKPRESSURE", p.path("title").asText());
        assertEquals(503, p.path("status").asInt());
        assertEquals("urn:sangam:problem:backpressure", p.path("type").asText());
        assertFalse(new String(r.body(), StandardCharsets.UTF_8).contains("\n"), "no raw control character");
        assertFalse(problem(IdempotentResponse.problem(403, "FORBIDDEN", null, null)).has("detail"));
    }
}
