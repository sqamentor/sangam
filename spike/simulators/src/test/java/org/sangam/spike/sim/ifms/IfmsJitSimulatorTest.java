package org.sangam.spike.sim.ifms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.spike.sim.Fault;
import org.sangam.spike.sim.Http;
import org.sangam.spike.sim.ifms.IfmsJitSimulator.CapabilityClass;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IfmsJitSimulatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private IfmsJitSimulator sim;

    @BeforeEach
    void setUp() {
        sim = new IfmsJitSimulator().credentials("dept-1", "s3cret").failAccount("BADACC").start();
    }

    @AfterEach
    void tearDown() {
        sim.close();
    }

    private String token() throws IOException {
        HttpResponse<String> r = Http.post(sim.uri(IfmsJitSimulator.AUTH_PATH), "application/json",
                "{\"clientId\":\"dept-1\",\"clientSecret\":\"s3cret\"}");
        assertEquals(200, r.statusCode());
        return JSON.readTree(r.body()).path("authToken").asText();
    }

    private HttpResponse<String> data(String token, String body) throws IOException {
        return Http.post(sim.uri(IfmsJitSimulator.DATA_PATH), "application/json", body, "Authorization", "Bearer " + token);
    }

    private static String pi(String opId, String amount, String account) {
        return "{\"serviceCode\":\"PI\",\"params\":{\"billId\":\"BILL-1\",\"amount\":\"" + amount
                + "\",\"operationId\":\"" + opId + "\",\"beneficiary\":{\"name\":\"Asha\",\"accountNo\":\"" + account
                + "\",\"ifsc\":\"SBIN0000001\"}}}";
    }

    private static String pd(String opId) {
        return "{\"serviceCode\":\"PD\",\"params\":{\"operationId\":\"" + opId + "\"}}";
    }

    private static JsonNode json(HttpResponse<String> r) throws IOException {
        return JSON.readTree(r.body());
    }

    // ---- normal path ---------------------------------------------------------------------------

    @Test
    void authenticateRejectsWrongSecretAndUnauthenticatedCalls() throws IOException {
        assertEquals(401, Http.post(sim.uri(IfmsJitSimulator.AUTH_PATH), "application/json",
                "{\"clientId\":\"dept-1\",\"clientSecret\":\"nope\"}").statusCode());
        assertEquals(401, data("not-a-token", pi("op-1", "10.00", "ACC1")).statusCode());
        assertEquals(0, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT));
    }

    @Test
    void paymentInstructionThenStatusThenFailureFeed() throws IOException {
        String t = token();
        JsonNode r = json(data(t, pi("op-1", "1500.00", "ACC1")));
        assertEquals("ACCEPTED", r.path("status").asText());
        assertFalse(r.path("duplicate").asBoolean());
        assertEquals("SUCCESS", json(data(t, pd("op-1"))).path("status").asText());
        assertEquals("NOT_FOUND", json(data(t, pd("op-unknown"))).path("status").asText());

        data(t, pi("op-2", "99.50", "BADACC"));
        assertEquals("FAILED", json(data(t, pd("op-2"))).path("status").asText());
        JsonNode fd = json(data(t, "{\"serviceCode\":\"FD\",\"params\":{}}"));
        assertEquals(1, fd.path("failures").size());
        assertEquals("op-2", fd.path("failures").get(0).path("operationId").asText());
        assertEquals("ACCOUNT_INVALID", fd.path("failures").get(0).path("reason").asText());

        assertEquals(1, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT, "op-1"));
        assertEquals(1, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT_FAILED, "op-2"));
    }

    @Test
    void validationErrors() throws IOException {
        String t = token();
        assertEquals(400, data(t, pi("op-1", "-5", "ACC1")).statusCode());
        assertEquals(400, data(t, pi("", "5", "ACC1")).statusCode());
        assertEquals(400, data(t, "{\"serviceCode\":\"VA\",\"params\":{}}").statusCode());
        assertEquals(0, sim.ledger().all().size());
    }

    @Test
    void tokenExpires() throws IOException {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-05T10:00:00Z"));
        sim.clock(clock).tokenTtl(Duration.ofMinutes(10));
        String t = token();
        assertEquals(200, data(t, pd("op-1")).statusCode());
        clock.advance(Duration.ofMinutes(11));
        assertEquals(401, data(t, pd("op-1")).statusCode());
    }

    @Test
    void failureFeedHonoursWindow() throws IOException {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-05T10:00:00Z"));
        sim.clock(clock).tokenTtl(Duration.ofDays(10));
        String t = token();
        data(t, pi("op-old", "10.00", "BADACC"));
        clock.advance(Duration.ofHours(73));
        assertEquals(0, json(data(t, "{\"serviceCode\":\"FD\",\"params\":{}}")).path("failures").size());
        assertEquals(1, json(data(t, "{\"serviceCode\":\"FD\",\"params\":{\"sinceHours\":100}}")).path("failures").size());
    }

    // ---- idempotency and capability classes ----------------------------------------------------

    @Test
    void classA_duplicatePiDoesNotPayTwice() throws IOException {
        String t = token();
        String ref = json(data(t, pi("op-1", "100.00", "ACC1"))).path("paymentRef").asText();
        JsonNode again = json(data(t, pi("op-1", "100.00", "ACC1")));
        assertTrue(again.path("duplicate").asBoolean());
        assertEquals(ref, again.path("paymentRef").asText());
        assertEquals(1, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT, "op-1"));
        assertFalse(sim.ledger().hasDuplicates(IfmsJitSimulator.EFFECT_PAYMENT));
    }

    @Test
    void classA_operationIdReuseWithDifferentPayloadIsRejected() throws IOException {
        String t = token();
        data(t, pi("op-1", "100.00", "ACC1"));
        HttpResponse<String> r = data(t, pi("op-1", "200.00", "ACC1"));
        assertEquals(409, r.statusCode());
        assertEquals("OPERATION_ID_REUSED", json(r).path("errorCode").asText());
        assertEquals(1, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT));
    }

    @Test
    void classB_duplicatePaysTwiceButLookupWorks() throws IOException {
        sim.capabilityClass(CapabilityClass.B_LOOKUP_ONLY);
        String t = token();
        data(t, pi("op-1", "100.00", "ACC1"));
        data(t, pi("op-1", "100.00", "ACC1"));
        assertEquals(2, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT, "op-1"));
        JsonNode st = json(data(t, pd("op-1")));
        assertEquals(2, st.path("payments").size());
    }

    @Test
    void classC_duplicatePaysTwiceAndNoLookup() throws IOException {
        sim.capabilityClass(CapabilityClass.C_NEITHER);
        String t = token();
        data(t, pi("op-1", "100.00", "ACC1"));
        data(t, pi("op-1", "100.00", "ACC1"));
        assertTrue(sim.ledger().hasDuplicates(IfmsJitSimulator.EFFECT_PAYMENT));
        HttpResponse<String> st = data(t, pd("op-1"));
        assertEquals(400, st.statusCode());
        assertEquals("PD_NOT_SUPPORTED", json(st).path("errorCode").asText());
    }

    // ---- faults --------------------------------------------------------------------------------

    @Test
    void faultDelay() throws IOException {
        String t = token();
        sim.faults().once(Fault.delay(Duration.ofMillis(400)).onRoute(IfmsJitSimulator.DATA_PATH));
        long start = System.nanoTime();
        assertEquals(200, data(t, pi("op-1", "1.00", "ACC1")).statusCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 400);
        assertEquals(1, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT));
    }

    @Test
    void faultLostResponse_classA_retryWithSameOperationIdIsSafe() throws IOException {
        String t = token();
        sim.faults().once(Fault.dropResponse().onRoute(IfmsJitSimulator.DATA_PATH));
        assertThrows(IOException.class, () -> data(t, pi("op-1", "100.00", "ACC1")));
        assertEquals(1, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT, "op-1"), "payment happened");
        // UNKNOWN_OUTCOME -> reconciliation: lookup, then retry with the same operationId
        assertEquals("SUCCESS", json(data(t, pd("op-1"))).path("status").asText());
        assertTrue(json(data(t, pi("op-1", "100.00", "ACC1"))).path("duplicate").asBoolean());
        assertEquals(1, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT, "op-1"));
    }

    @Test
    void faultLostResponse_classC_blindRetryPaysTwice() throws IOException {
        sim.capabilityClass(CapabilityClass.C_NEITHER);
        String t = token();
        sim.faults().once(Fault.dropResponse().onRoute(IfmsJitSimulator.DATA_PATH));
        assertThrows(IOException.class, () -> data(t, pi("op-1", "100.00", "ACC1")));
        data(t, pi("op-1", "100.00", "ACC1"));
        assertEquals(2, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT, "op-1"));
    }

    @Test
    void faultDuplicateDelivery() throws IOException {
        String t = token();
        sim.faults().once(Fault.duplicate().onRoute(IfmsJitSimulator.DATA_PATH));
        assertEquals(200, data(t, pi("op-1", "100.00", "ACC1")).statusCode());
        assertEquals(1, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT, "op-1"), "class A absorbs it");

        sim.capabilityClass(CapabilityClass.C_NEITHER);
        sim.faults().once(Fault.duplicate().onRoute(IfmsJitSimulator.DATA_PATH));
        data(t, pi("op-2", "100.00", "ACC1"));
        assertEquals(2, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT, "op-2"), "class C pays twice");
    }

    @Test
    void faultErrorStatusHasNoEffect() throws IOException {
        String t = token();
        sim.faults().once(Fault.error(503).onRoute(IfmsJitSimulator.DATA_PATH));
        assertEquals(503, data(t, pi("op-1", "100.00", "ACC1")).statusCode());
        assertEquals(0, sim.ledger().all().size());
        assertEquals(200, data(t, pi("op-1", "100.00", "ACC1")).statusCode(), "one-shot fault consumed");
        assertEquals(1, sim.ledger().count(IfmsJitSimulator.EFFECT_PAYMENT));
    }

    @Test
    void stickyFaultUntilCleared() throws IOException {
        String t = token();
        sim.faults().always(Fault.error(500).onRoute(IfmsJitSimulator.DATA_PATH));
        assertEquals(500, data(t, pd("x")).statusCode());
        assertEquals(500, data(t, pd("x")).statusCode());
        assertEquals(200, Http.post(sim.uri(IfmsJitSimulator.AUTH_PATH), "application/json",
                "{\"clientId\":\"dept-1\",\"clientSecret\":\"s3cret\"}").statusCode(), "other route unaffected");
        sim.faults().clear();
        assertEquals(200, data(t, pd("x")).statusCode());
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
