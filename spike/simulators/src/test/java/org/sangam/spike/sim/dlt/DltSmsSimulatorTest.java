package org.sangam.spike.sim.dlt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.spike.sim.Fault;
import org.sangam.spike.sim.Http;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DltSmsSimulatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEMPLATE = "1107161234567890123";
    private DltSmsSimulator sim;

    @BeforeEach
    void setUp() {
        sim = new DltSmsSimulator()
                .registerTemplate(TEMPLATE, "DEPTGV",
                        "Your application {#var#} is approved. Track at https://dept.gov.in/track/{#var#} -Dept")
                .whitelistUrl("https://dept.gov.in/track/")
                .start();
    }

    @AfterEach
    void tearDown() {
        sim.close();
    }

    private HttpResponse<String> sms(String header, String template, String mobile, String message) throws IOException {
        ObjectNode b = JSON.createObjectNode();
        b.put("header", header);
        b.put("templateId", template);
        b.put("mobile", mobile);
        b.put("message", message);
        return Http.post(sim.uri(DltSmsSimulator.SMS_PATH), "application/json", b.toString());
    }

    private static String reason(HttpResponse<String> r) throws IOException {
        return JSON.readTree(r.body()).path("reason").asText();
    }

    private static final String OK_MSG = "Your application APP-42 is approved. Track at https://dept.gov.in/track/APP-42 -Dept";

    @Test
    void acceptsValidMessageWithAndWithoutGovSuffix() throws IOException {
        HttpResponse<String> r = sms("DEPTGV", TEMPLATE, "9876543210", OK_MSG);
        assertEquals(200, r.statusCode());
        assertEquals("ACCEPTED", JSON.readTree(r.body()).path("status").asText());
        assertEquals(200, sms("DEPTGV-G", TEMPLATE, "919876543210", OK_MSG).statusCode());
        assertEquals(2, sim.ledger().count(DltSmsSimulator.EFFECT_SMS));
    }

    @Test
    void rejectsEachRuleViolation() throws IOException {
        assertEquals("TEMPLATE_NOT_REGISTERED", reason(sms("DEPTGV", "999", "9876543210", OK_MSG)));
        assertEquals("HEADER_MISMATCH", reason(sms("OTHERH", TEMPLATE, "9876543210", OK_MSG)));
        assertEquals("HEADER_MISMATCH", reason(sms("DEPTGV-X", TEMPLATE, "9876543210", OK_MSG)));
        assertEquals("INVALID_MOBILE", reason(sms("DEPTGV", TEMPLATE, "12345", OK_MSG)));
        assertEquals("CONTENT_MISMATCH", reason(sms("DEPTGV", TEMPLATE, "9876543210", "Win a prize now")));
        assertEquals("URL_NOT_WHITELISTED", reason(sms("DEPTGV", TEMPLATE, "9876543210",
                "Your application APP-42 is approved. Track at https://dept.gov.in/track/x https://evil.example/ -Dept")));
        assertEquals(400, sms("DEPTGV", TEMPLATE, "9876543210", "Win").statusCode());
        assertEquals(0, sim.ledger().all().size());
    }

    @Test
    void urlInVariableMustBeWhitelisted() throws IOException {
        sim.registerTemplate("T2", "DEPTGV", "Details: {#var#}");
        assertEquals("URL_NOT_WHITELISTED", reason(sms("DEPTGV", "T2", "9876543210", "Details: http://bit.ly/abc")));
        assertEquals(200, sms("DEPTGV", "T2", "9876543210", "Details: https://dept.gov.in/track/9.").statusCode());
    }

    @Test
    void faultDelay() throws IOException {
        sim.faults().once(Fault.delay(Duration.ofMillis(300)));
        long t0 = System.nanoTime();
        assertEquals(200, sms("DEPTGV", TEMPLATE, "9876543210", OK_MSG).statusCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() >= 300);
    }

    @Test
    void faultLostResponseStillSends() {
        sim.faults().once(Fault.dropResponse());
        assertThrows(IOException.class, () -> sms("DEPTGV", TEMPLATE, "9876543210", OK_MSG));
        assertEquals(1, sim.ledger().count(DltSmsSimulator.EFFECT_SMS));
    }

    @Test
    void faultDuplicateSendsTwice() throws IOException {
        sim.faults().once(Fault.duplicate());
        assertEquals(200, sms("DEPTGV", TEMPLATE, "9876543210", OK_MSG).statusCode());
        assertEquals(2, sim.ledger().count(DltSmsSimulator.EFFECT_SMS), "SMS gateways are not idempotent");
    }

    @Test
    void faultErrorStatus() throws IOException {
        sim.faults().once(Fault.error(429));
        assertEquals(429, sms("DEPTGV", TEMPLATE, "9876543210", OK_MSG).statusCode());
        assertEquals(0, sim.ledger().all().size());
    }
}
