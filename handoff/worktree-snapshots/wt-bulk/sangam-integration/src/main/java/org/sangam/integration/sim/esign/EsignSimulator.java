// SPDX-License-Identifier: Apache-2.0
// Ported from spike/simulators org.sangam.spike.sim.esign.EsignSimulator. Modified by Sangam.
package org.sangam.integration.sim.esign;

import org.sangam.integration.sim.SimHttpServer;
import org.sangam.integration.sim.Xml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * eSign Service Provider (ESP) simulator, shaped after the CCA eSign API v2.1 request/response
 * (SANGAM.md IX.7d, GOV-7). Request ({@code POST /esign/2.1/signdoc}):
 * <pre>
 * &lt;Esign ver="2.1" sc="Y" ts="..." txn="ASP-TXN-1" aspId="ASP001" ekycIdType="A" AuthMode="1"
 *        responseSigType="pkcs7" responseUrl="..."&gt;
 *   &lt;Docs&gt;&lt;InputHash id="1" hashAlgorithm="SHA256" docInfo="..."&gt;64 hex chars&lt;/InputHash&gt;&lt;/Docs&gt;
 *   &lt;Signature&gt;(ASP signature, not checked by the simulator)&lt;/Signature&gt;
 * &lt;/Esign&gt;
 * </pre>
 * Response: {@code <EsignResp ver="2.1" status="1|0" ts txn resCode errCode errMsg>} with
 * {@code <UserX509Certificate>} and {@code <Signatures><DocSignature id sigHashAlgorithm>}.
 *
 * <p>The "signature" is fake: Base64(HMAC-SHA256(simulator key, "SHA256:" + lower-case hash)). It
 * binds to the hash (a different hash gives a different signature) and is deterministic for one
 * simulator instance, so re-signing the same hash is harmless. {@link #verify(String, String)}
 * checks it. Error codes are simulator-own ({@code SIM-Exx}); the real ESP codes are not modelled.
 *
 * <p>{@code GET /esign/2.1/status?txn=...} returns the last response for a txn (an outcome lookup,
 * capability class (b) "to verify" in X.4 J-P2-E07). Ledger type {@code SIGN}, key = txn.
 */
public final class EsignSimulator extends SimHttpServer {

    public static final String SIGN_PATH = "/esign/2.1/signdoc";
    public static final String STATUS_PATH = "/esign/2.1/status";
    public static final String EFFECT_SIGN = "SIGN";

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");
    private static final String FAKE_CERT = Base64.getEncoder().encodeToString(
            "SANGAM SIMULATED SIGNER CERTIFICATE - NOT A REAL X.509".getBytes(StandardCharsets.US_ASCII));

    private final byte[] key;
    private final Map<String, String> lastByTxn = new ConcurrentHashMap<>();

    public EsignSimulator() {
        super("esign");
        key = new byte[32];
        new SecureRandom().nextBytes(key);
        route("POST", SIGN_PATH, this::sign);
        route("GET", STATUS_PATH, this::status);
    }

    /** Fake signature for a hex SHA-256 document hash. */
    public String signatureFor(String hashHex) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] sig = mac.doFinal(("SHA256:" + hashHex.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** True if {@code signatureB64} is this simulator's signature over {@code hashHex}. */
    public boolean verify(String hashHex, String signatureB64) {
        return MessageDigest.isEqual(signatureFor(hashHex).getBytes(StandardCharsets.US_ASCII),
                signatureB64.getBytes(StandardCharsets.US_ASCII));
    }

    private Response sign(Request req) {
        Document doc;
        try {
            doc = Xml.parse(req.body());
        } catch (Exception e) {
            return Response.xml(200, errorResp("", "SIM-E01", "request is not well-formed XML"));
        }
        Element root = doc.getDocumentElement();
        String txn = root.getAttribute("txn");
        if (!"Esign".equals(root.getTagName())) {
            return Response.xml(200, errorResp(txn, "SIM-E02", "root element must be Esign"));
        }
        if (!"2.1".equals(root.getAttribute("ver"))) {
            return Response.xml(200, errorResp(txn, "SIM-E03", "only ver=2.1 is supported"));
        }
        if (txn.isBlank() || root.getAttribute("aspId").isBlank()) {
            return Response.xml(200, errorResp(txn, "SIM-E04", "txn and aspId are required"));
        }
        List<Element> hashes = Xml.elements(root, "InputHash");
        if (hashes.isEmpty()) {
            return Response.xml(200, errorResp(txn, "SIM-E05", "at least one Docs/InputHash is required"));
        }
        StringBuilder sigs = new StringBuilder();
        for (Element h : hashes) {
            String alg = h.getAttribute("hashAlgorithm");
            String hash = h.getTextContent().trim();
            if (!(alg.isEmpty() || alg.equalsIgnoreCase("SHA256")) || !SHA256_HEX.matcher(hash).matches()) {
                return Response.xml(200, errorResp(txn, "SIM-E06", "InputHash must be a SHA256 hex digest"));
            }
            String id = h.getAttribute("id").isEmpty() ? "1" : h.getAttribute("id");
            sigs.append("<DocSignature id=\"").append(Xml.esc(id)).append("\" sigHashAlgorithm=\"SHA256\" error=\"\">")
                    .append(signatureFor(hash)).append("</DocSignature>");
        }
        // Effect only after the whole request validated: every hash is signed.
        for (Element h : hashes) {
            ledger().record(EFFECT_SIGN, txn, Map.of("hash", h.getTextContent().trim().toLowerCase(Locale.ROOT),
                    "aspId", root.getAttribute("aspId")));
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<EsignResp ver=\"2.1\" status=\"1\" ts=\"" + Instant.now() + "\" txn=\"" + Xml.esc(txn)
                + "\" resCode=\"" + Xml.esc("RES-" + txn) + "\" errCode=\"NA\" errMsg=\"NA\">"
                + "<UserX509Certificate>" + FAKE_CERT + "</UserX509Certificate>"
                + "<Signatures>" + sigs + "</Signatures>"
                + "<Signature>SIMULATED-ESP-RESPONSE-SIGNATURE</Signature>"
                + "</EsignResp>";
        lastByTxn.put(txn, xml);
        return Response.xml(200, xml);
    }

    private Response status(Request req) {
        String txn = req.queryParam("txn");
        String xml = txn == null ? null : lastByTxn.get(txn);
        if (xml == null) {
            return Response.xml(404, errorResp(txn == null ? "" : txn, "SIM-E10", "unknown txn"));
        }
        return Response.xml(200, xml);
    }

    private static String errorResp(String txn, String code, String msg) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<EsignResp ver=\"2.1\" status=\"0\" ts=\"" + Instant.now() + "\" txn=\"" + Xml.esc(txn)
                + "\" resCode=\"\" errCode=\"" + code + "\" errMsg=\"" + Xml.esc(msg) + "\"/>";
    }
}
