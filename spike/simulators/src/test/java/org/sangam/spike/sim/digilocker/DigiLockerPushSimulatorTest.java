package org.sangam.spike.sim.digilocker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.spike.sim.Fault;
import org.sangam.spike.sim.Http;
import org.sangam.spike.sim.Xml;
import org.w3c.dom.Element;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigiLockerPushSimulatorTest {

    private DigiLockerPushSimulator sim;

    @BeforeEach
    void setUp() {
        sim = new DigiLockerPushSimulator().start();
    }

    @AfterEach
    void tearDown() {
        sim.close();
    }

    static String push(String txn, String docType, String holder, String uri) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><PushUriRequest ver=\"1.0\" ts=\"2026-11-05T10:00:00\" txn=\""
                + txn + "\" orgId=\"in.gov.dept\"><UriDetails><DocType>" + docType + "</DocType><DigiLockerId>"
                + holder + "</DigiLockerId><URI>" + uri + "</URI></UriDetails></PushUriRequest>";
    }

    private HttpResponse<String> send(String xml) throws IOException {
        return Http.post(sim.uri(DigiLockerPushSimulator.PUSH_PATH), "application/xml", xml);
    }

    private static Element root(HttpResponse<String> r) throws Exception {
        return Xml.parse(r.body().getBytes(StandardCharsets.UTF_8)).getDocumentElement();
    }

    @Test
    void pushIsIdempotentByUri() throws Exception {
        HttpResponse<String> r = send(push("T1", "CERTI", "holder-1", "in.gov.dept-CERTI-1"));
        assertEquals(200, r.statusCode());
        assertEquals("1", root(r).getAttribute("status"));
        assertEquals("false", root(r).getAttribute("duplicate"));
        HttpResponse<String> again = send(push("T2", "CERTI", "holder-1", "in.gov.dept-CERTI-1"));
        assertEquals("1", root(again).getAttribute("status"));
        assertEquals("true", root(again).getAttribute("duplicate"));
        assertEquals(1, sim.ledger().count(DigiLockerPushSimulator.EFFECT_PUSH, "in.gov.dept-CERTI-1"));
        assertEquals("holder-1", sim.find("in.gov.dept-CERTI-1").orElseThrow().holderId());
    }

    @Test
    void sameUriForAnotherHolderIsAConflict() throws Exception {
        send(push("T1", "CERTI", "holder-1", "U-1"));
        HttpResponse<String> r = send(push("T2", "CERTI", "holder-2", "U-1"));
        assertEquals(409, r.statusCode());
        assertEquals("URI_CONFLICT", root(r).getAttribute("errCode"));
        assertEquals(1, sim.size());
    }

    @Test
    void missingFieldsAreRejected() throws Exception {
        assertEquals(400, send(push("T1", "", "holder-1", "U-1")).statusCode());
        assertEquals(400, send("<Other/>").statusCode());
        assertEquals(400, send("not xml").statusCode());
        assertEquals(0, sim.ledger().all().size());
    }

    @Test
    void faultDelay() throws Exception {
        sim.faults().once(Fault.delay(Duration.ofMillis(300)));
        long t0 = System.nanoTime();
        assertEquals(200, send(push("T1", "CERTI", "h", "U-1")).statusCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() >= 300);
    }

    @Test
    void faultLostResponseThenRetryIsSafe() throws Exception {
        sim.faults().once(Fault.dropResponse());
        assertThrows(IOException.class, () -> send(push("T1", "CERTI", "h", "U-1")));
        assertEquals("true", root(send(push("T1", "CERTI", "h", "U-1"))).getAttribute("duplicate"));
        assertEquals(1, sim.ledger().count(DigiLockerPushSimulator.EFFECT_PUSH, "U-1"));
    }

    @Test
    void faultDuplicateDeliveryRecordsOnce() throws Exception {
        sim.faults().once(Fault.duplicate());
        assertEquals("false", root(send(push("T1", "CERTI", "h", "U-1"))).getAttribute("duplicate"),
                "caller sees the first answer");
        assertEquals(1, sim.ledger().count(DigiLockerPushSimulator.EFFECT_PUSH, "U-1"));
    }

    @Test
    void faultErrorStatus() throws Exception {
        sim.faults().once(Fault.error(503));
        assertEquals(503, send(push("T1", "CERTI", "h", "U-1")).statusCode());
        assertEquals(0, sim.size());
    }
}
