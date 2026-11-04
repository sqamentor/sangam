package org.sangam.spike.sim.esign;

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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsignSimulatorTest {

    private EsignSimulator sim;

    @BeforeEach
    void setUp() {
        sim = new EsignSimulator().start();
    }

    @AfterEach
    void tearDown() {
        sim.close();
    }

    static String sha256(String doc) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(doc.getBytes(StandardCharsets.UTF_8)));
    }

    static String request(String txn, String hash) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Esign ver=\"2.1\" sc=\"Y\" ts=\"2026-11-05T10:00:00\" txn=\""
                + txn + "\" aspId=\"ASP001\" ekycIdType=\"A\" AuthMode=\"1\" responseSigType=\"pkcs7\" "
                + "responseUrl=\"https://asp.example/cb\"><Docs><InputHash id=\"1\" hashAlgorithm=\"SHA256\" "
                + "docInfo=\"Licence certificate\">" + hash + "</InputHash></Docs><Signature>ASP-SIG</Signature></Esign>";
    }

    private HttpResponse<String> sign(String xml) throws IOException {
        return Http.post(sim.uri(EsignSimulator.SIGN_PATH), "application/xml", xml);
    }

    private static Element root(HttpResponse<String> r) throws Exception {
        return Xml.parse(r.body().getBytes(StandardCharsets.UTF_8)).getDocumentElement();
    }

    private static String signature(HttpResponse<String> r) throws Exception {
        return Xml.text(root(r), "DocSignature");
    }

    // ---- normal path ---------------------------------------------------------------------------

    @Test
    void signsHashAndSignatureBindsToHash() throws Exception {
        String h1 = sha256("document one");
        String h2 = sha256("document two");
        HttpResponse<String> r1 = sign(request("TXN-1", h1));
        assertEquals(200, r1.statusCode());
        Element e = root(r1);
        assertEquals("EsignResp", e.getTagName());
        assertEquals("1", e.getAttribute("status"));
        assertEquals("TXN-1", e.getAttribute("txn"));
        assertFalse(Xml.text(e, "UserX509Certificate").isEmpty());
        String s1 = signature(r1);
        assertTrue(sim.verify(h1, s1));
        assertFalse(sim.verify(h2, s1), "signature does not verify for another hash");

        String s2 = signature(sign(request("TXN-2", h2)));
        assertNotEquals(s1, s2, "different hash, different signature");
        assertEquals(s1, signature(sign(request("TXN-3", h1))), "same hash, same signature");
        assertEquals(1, sim.ledger().count(EsignSimulator.EFFECT_SIGN, "TXN-1"));
    }

    @Test
    void rejectsBadRequests() throws Exception {
        assertEquals("SIM-E06", root(sign(request("TXN-1", "not-a-hash"))).getAttribute("errCode"));
        assertEquals("SIM-E03", root(sign(request("TXN-1", sha256("x")).replace("ver=\"2.1\"", "ver=\"2.0\"")))
                .getAttribute("errCode"));
        assertEquals("SIM-E04", root(sign(request("", sha256("x")))).getAttribute("errCode"));
        assertEquals("SIM-E01", root(sign("<Esign")).getAttribute("errCode"));
        assertEquals("SIM-E01", root(sign("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><Esign/>"))
                .getAttribute("errCode"), "DOCTYPE refused");
        assertEquals(0, sim.ledger().all().size());
    }

    @Test
    void statusLookupByTxn() throws Exception {
        String h = sha256("doc");
        sign(request("TXN-9", h));
        HttpResponse<String> st = Http.get(sim.uri(EsignSimulator.STATUS_PATH + "?txn=TXN-9"));
        assertEquals(200, st.statusCode());
        assertTrue(sim.verify(h, signature(st)));
        assertEquals(404, Http.get(sim.uri(EsignSimulator.STATUS_PATH + "?txn=NOPE")).statusCode());
    }

    // ---- faults --------------------------------------------------------------------------------

    @Test
    void faultDelay() throws Exception {
        sim.faults().once(Fault.delay(Duration.ofMillis(300)));
        long t0 = System.nanoTime();
        assertEquals("1", root(sign(request("TXN-1", sha256("d")))).getAttribute("status"));
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() >= 300);
    }

    @Test
    void faultLostResponseThenLookupRecoversSignature() throws Exception {
        String h = sha256("d");
        sim.faults().once(Fault.dropResponse());
        assertThrows(IOException.class, () -> sign(request("TXN-1", h)));
        assertEquals(1, sim.ledger().count(EsignSimulator.EFFECT_SIGN, "TXN-1"));
        HttpResponse<String> st = Http.get(sim.uri(EsignSimulator.STATUS_PATH + "?txn=TXN-1"));
        assertTrue(sim.verify(h, signature(st)));
    }

    @Test
    void faultDuplicateSignsTwiceHarmlessly() throws Exception {
        String h = sha256("d");
        sim.faults().once(Fault.duplicate());
        String s = signature(sign(request("TXN-1", h)));
        assertEquals(2, sim.ledger().count(EsignSimulator.EFFECT_SIGN, "TXN-1"));
        assertTrue(sim.verify(h, s));
    }

    @Test
    void faultErrorStatus() throws Exception {
        sim.faults().once(Fault.error(502));
        assertEquals(502, sign(request("TXN-1", sha256("d"))).statusCode());
        assertEquals(0, sim.ledger().all().size());
    }
}
