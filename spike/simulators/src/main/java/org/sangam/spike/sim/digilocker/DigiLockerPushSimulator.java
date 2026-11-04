package org.sangam.spike.sim.digilocker;

import org.sangam.spike.sim.SimHttpServer;
import org.sangam.spike.sim.Xml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DigiLocker Issuer Push simulator (SANGAM.md IX.7e GOV-9: Issuer Push API v1.7.2). The request is
 * the simulator's approximation of the public "push URI" call ({@code POST /digilocker/issuer/push-uri}):
 * <pre>
 * &lt;PushUriRequest ver="1.0" ts="..." txn="..." orgId="..."&gt;
 *   &lt;UriDetails&gt;
 *     &lt;DocType&gt;CERTI&lt;/DocType&gt;
 *     &lt;DigiLockerId&gt;holder id&lt;/DigiLockerId&gt;
 *     &lt;URI&gt;in.gov.dept-CERTI-12345&lt;/URI&gt;
 *   &lt;/UriDetails&gt;
 * &lt;/PushUriRequest&gt;
 * </pre>
 * Response {@code <PushUriResponse status="1|0" ts txn duplicate="true|false" errCode errMsg/>}.
 *
 * <p>Idempotent by URI: pushing the same URI again for the same holder and doc type returns
 * {@code status="1" duplicate="true"} and records no new effect; the same URI with a different
 * holder or doc type is rejected ({@code URI_CONFLICT}). Ledger type {@code PUSH}, key = URI.
 */
public final class DigiLockerPushSimulator extends SimHttpServer {

    public static final String PUSH_PATH = "/digilocker/issuer/push-uri";
    public static final String EFFECT_PUSH = "PUSH";

    /** A certificate URI held in the simulated DigiLocker. */
    public record Pushed(String uri, String docType, String holderId, Instant at) { }

    private final Map<String, Pushed> byUri = new LinkedHashMap<>();

    public DigiLockerPushSimulator() {
        super("digilocker-push");
        route("POST", PUSH_PATH, this::push);
    }

    public Optional<Pushed> find(String uri) {
        synchronized (byUri) {
            return Optional.ofNullable(byUri.get(uri));
        }
    }

    public int size() {
        synchronized (byUri) {
            return byUri.size();
        }
    }

    private Response push(Request req) {
        Document doc;
        try {
            doc = Xml.parse(req.body());
        } catch (Exception e) {
            return Response.xml(400, resp("0", "", false, "BAD_XML", "request is not well-formed XML"));
        }
        Element root = doc.getDocumentElement();
        String txn = root.getAttribute("txn");
        if (!"PushUriRequest".equals(root.getTagName())) {
            return Response.xml(400, resp("0", txn, false, "BAD_REQUEST", "root element must be PushUriRequest"));
        }
        String docType = Xml.text(root, "DocType");
        String holder = Xml.text(root, "DigiLockerId");
        String uri = Xml.text(root, "URI");
        if (docType.isEmpty() || holder.isEmpty() || uri.isEmpty()) {
            return Response.xml(400, resp("0", txn, false, "MISSING_FIELD", "DocType, DigiLockerId and URI are required"));
        }
        synchronized (byUri) {
            Pushed existing = byUri.get(uri);
            if (existing != null) {
                if (existing.holderId().equals(holder) && existing.docType().equals(docType)) {
                    return Response.xml(200, resp("1", txn, true, null, null));
                }
                return Response.xml(409, resp("0", txn, false, "URI_CONFLICT",
                        "URI already issued to another holder or doc type"));
            }
            byUri.put(uri, new Pushed(uri, docType, holder, Instant.now()));
            ledger().record(EFFECT_PUSH, uri, Map.of("docType", docType, "holderId", holder));
        }
        return Response.xml(200, resp("1", txn, false, null, null));
    }

    private static String resp(String status, String txn, boolean duplicate, String errCode, String errMsg) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><PushUriResponse status=\"" + status + "\" ts=\""
                + Instant.now() + "\" txn=\"" + Xml.esc(txn) + "\" duplicate=\"" + duplicate + "\""
                + (errCode == null ? "" : " errCode=\"" + Xml.esc(errCode) + "\" errMsg=\"" + Xml.esc(errMsg) + "\"")
                + "/>";
    }
}
