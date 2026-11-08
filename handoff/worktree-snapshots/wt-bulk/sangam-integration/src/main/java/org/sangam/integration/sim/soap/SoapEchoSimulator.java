// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.soap;

import org.sangam.core.layer.Hashes;
import org.sangam.integration.sim.SimHttpServer;
import org.sangam.integration.sim.Xml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SOAP 1.1 endpoint simulator for the generic SOAP connector (SIMULATOR profile). {@code POST /soap/echo} with a
 * SOAP 1.1 envelope ({@code http://schemas.xmlsoap.org/soap/envelope/}, any prefix):
 * <pre>
 * &lt;soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"&gt;
 *   &lt;soapenv:Header&gt;&lt;MessageID&gt;operationId&lt;/MessageID&gt;&lt;/soapenv:Header&gt;
 *   &lt;soapenv:Body&gt;&lt;Echo&gt;&lt;message&gt;hello&lt;/message&gt;&lt;/Echo&gt;&lt;/soapenv:Body&gt;
 * &lt;/soapenv:Envelope&gt;
 * </pre>
 * The operation key is the first {@code MessageID} element (WS-Addressing style, header or body) or else the first
 * {@code operationId} element. Answers:
 * <ul>
 *   <li>200 {@code <EchoResponse><operationId/><echo/><duplicate>false</duplicate><receivedAt/></EchoResponse>}; the
 *       effect {@code SOAP_CALL} (key = operation key) is recorded once. {@code echo} is the text of the first
 *       {@code message} element, else the whole Body text.</li>
 *   <li>Idempotent by the key: a repeat with the same Body returns the stored response with
 *       {@code <duplicate>true</duplicate>} and no new effect; the same key with a different Body is a Client fault
 *       {@code MESSAGE_ID_REUSED}.</li>
 *   <li>A Body that contains an element {@code <fail>} gives a SOAP Fault {@code soapenv:Client} with the text of
 *       {@code <fail>} as {@code faultstring} (business refusal, no effect).</li>
 *   <li>Malformed XML, no Envelope/Body or no key: SOAP Fault {@code soapenv:Client}.</li>
 * </ul>
 * Faults use HTTP 500, as SOAP 1.1 over HTTP requires; {@code <detail><code>} carries a machine-readable code.
 */
public final class SoapEchoSimulator extends SimHttpServer {

    public static final String ECHO_PATH = "/soap/echo";
    public static final String EFFECT_SOAP_CALL = "SOAP_CALL";
    public static final String SOAP11_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    public static final String CONTENT_TYPE = "text/xml; charset=utf-8";

    /** One stored call. */
    public record StoredCall(String operationId, String bodyHash, String echo, Instant at) { }

    private final Map<String, StoredCall> byKey = new LinkedHashMap<>();

    public SoapEchoSimulator() {
        super("soap-echo");
        route("POST", ECHO_PATH, this::echo);
    }

    public Optional<StoredCall> find(String operationId) {
        synchronized (byKey) {
            return Optional.ofNullable(byKey.get(operationId));
        }
    }

    private Response echo(Request req) {
        Document doc;
        try {
            doc = Xml.parse(req.body());
        } catch (Exception e) {
            return fault("soapenv:Client", "BAD_XML", "request is not well-formed XML");
        }
        Element envelope = doc.getDocumentElement();
        if (!"Envelope".equals(Xml.localName(envelope))) {
            return fault("soapenv:Client", "NOT_AN_ENVELOPE", "root element must be a SOAP 1.1 Envelope");
        }
        List<Element> bodies = Xml.elementsByLocalName(envelope, "Body");
        if (bodies.isEmpty()) {
            return fault("soapenv:Client", "MISSING_BODY", "Envelope has no Body");
        }
        Element body = bodies.get(0);
        String key = Xml.textByLocalName(envelope, "MessageID");
        if (key.isEmpty()) {
            key = Xml.textByLocalName(envelope, "operationId");
        }
        if (key.isEmpty()) {
            return fault("soapenv:Client", "MISSING_MESSAGE_ID", "a MessageID or operationId element is required");
        }
        List<Element> fails = Xml.elementsByLocalName(body, "fail");
        if (!fails.isEmpty()) {
            String why = fails.get(0).getTextContent().trim();
            return fault("soapenv:Client", "BUSINESS_REFUSAL", why.isEmpty() ? "refused by the service" : why);
        }
        List<Element> messages = Xml.elementsByLocalName(body, "message");
        String echo = messages.isEmpty() ? body.getTextContent().trim() : messages.get(0).getTextContent().trim();
        String hash = Hashes.hex(Hashes.sha256(body.getTextContent().trim().replaceAll("\\s+", " ")));
        synchronized (byKey) {
            StoredCall existing = byKey.get(key);
            if (existing != null) {
                if (!existing.bodyHash().equals(hash)) {
                    return fault("soapenv:Client", "MESSAGE_ID_REUSED", "MessageID already used with a different Body");
                }
                return ok(existing, true);
            }
            StoredCall call = new StoredCall(key, hash, echo, Instant.now());
            byKey.put(key, call);
            ledger().record(EFFECT_SOAP_CALL, key, Map.of("bodyHash", hash));
            return ok(call, false);
        }
    }

    private static Response ok(StoredCall call, boolean duplicate) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"" + SOAP11_NS + "\"><soapenv:Body>"
                + "<EchoResponse>"
                + "<operationId>" + Xml.esc(call.operationId()) + "</operationId>"
                + "<echo>" + Xml.esc(call.echo()) + "</echo>"
                + "<duplicate>" + duplicate + "</duplicate>"
                + "<receivedAt>" + call.at() + "</receivedAt>"
                + "</EchoResponse>"
                + "</soapenv:Body></soapenv:Envelope>";
        return new Response(200, CONTENT_TYPE, xml.getBytes(StandardCharsets.UTF_8));
    }

    private static Response fault(String faultCode, String code, String message) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"" + SOAP11_NS + "\"><soapenv:Body>"
                + "<soapenv:Fault>"
                + "<faultcode>" + Xml.esc(faultCode) + "</faultcode>"
                + "<faultstring>" + Xml.esc(message) + "</faultstring>"
                + "<detail><code>" + Xml.esc(code) + "</code></detail>"
                + "</soapenv:Fault>"
                + "</soapenv:Body></soapenv:Envelope>";
        return new Response(500, CONTENT_TYPE, xml.getBytes(StandardCharsets.UTF_8));
    }
}
