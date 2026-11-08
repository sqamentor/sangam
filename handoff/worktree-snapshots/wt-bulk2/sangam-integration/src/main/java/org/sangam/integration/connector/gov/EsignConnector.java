// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Hashes;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.sim.esign.EsignSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * eSign ASP connector (R57, GOV-7; CCA eSign API v2.1 request/response shape as simulated by {@link EsignSimulator}).
 * Sangam acts as the ASP: it sends the document <i>hash</i> to an ESP and receives the signature (hash signing; the
 * signer's consent and logs stay with the ASP and go to the audit as {@code SIGNATURE_APPLIED}).
 *
 * <p><b>Operation {@code sign}</b>: input {@code documentHash} (64 hex digits, SHA-256) and optional {@code docInfo}.
 * The hash must be bound to the approval it signs (R57, VI.4 rule 5): the input must also carry
 * {@code approvalPayloadHash} (hex) or {@code approvalPayload} (the canonical approval payload, hashed here with
 * SHA-256 over UTF-8), and the document hash must equal it; otherwise the call is refused with
 * {@code BusinessFailure(HASH_NOT_BOUND)} before anything is sent. The txn is the operationId (R36). Output
 * {@code signature} (Base64), {@code certificate}, {@code resCode}, {@code txn}. An {@code EsignResp status="0"} is a
 * {@code BusinessFailure} with the ESP's {@code errCode}.
 *
 * <p><b>Capability class (b)</b> by default: {@code GET status?txn=} looks the outcome up (404 → not found), but a
 * repeated request signs again, so it is never re-sent blindly. The request XML is passed through
 * {@link Config#requestSigner()} (the ASP's XML-DSig signer, required by CCA for the LIVE profile). XML answers are
 * parsed with the hardened parser ({@link GovXml}).
 */
public final class EsignConnector implements SangamConnector {

    public static final String TYPE = "esign";
    public static final String OP_SIGN = "sign";
    public static final String SIGN_PATH = "/esign/2.1/signdoc";
    public static final String STATUS_PATH = "/esign/2.1/status";

    private static final Logger LOG = LoggerFactory.getLogger(EsignConnector.class);
    private static final String SYSTEM = "eSign";
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Configuration of one ESP.
     *
     * @param id              connector instance id
     * @param baseUri         ESP base URI
     * @param aspId           the ASP id assigned by the ESP
     * @param responseUrl     the ASP's response URL sent in the request (may be null for the synchronous flow)
     * @param requestSigner   signs the request XML (enveloped XML-DSig with the ASP key) and returns the signed
     *                        document; required for LIVE, identity when null in the SIMULATOR profile
     * @param transport       HTTP transport
     * @param profile         live or simulator
     * @param capabilityClass A-2 class; null = (b)
     * @param timeout         per-request timeout; null = 60 s (the signer authenticates interactively at some ESPs)
     */
    public record Config(String id, URI baseUri, String aspId, URI responseUrl, UnaryOperator<String> requestSigner,
                         HttpTransport transport, ConnectorProfile profile, CapabilityClass capabilityClass,
                         Duration timeout) {
        public Config {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(aspId, "aspId");
            Objects.requireNonNull(transport, "transport");
            profile = profile == null ? ConnectorProfile.LIVE : profile;
            if (requestSigner == null) {
                if (profile == ConnectorProfile.LIVE) {
                    throw new IllegalArgumentException("a LIVE eSign connector needs a request signer (CCA eSign 2.1)");
                }
                requestSigner = UnaryOperator.identity();
            }
            capabilityClass = capabilityClass == null ? CapabilityClass.B_LOOKUP_ONLY : capabilityClass;
            timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        }
    }

    private final Config config;
    private final ConnectorDescriptor descriptor;

    public EsignConnector(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.descriptor = new ConnectorDescriptor(config.id(), TYPE, config.capabilityClass(), List.of(OP_SIGN),
                config.profile());
    }

    /** Wired to the eSign simulator of {@code sims}, class (b). */
    public static EsignConnector simulator(SimulatorProfile sims) {
        EsignSimulator sim = sims.esign();
        return new EsignConnector(new Config("esign-sim", sim.baseUri(), SimulatorProfile.ESIGN_ASP_ID, null, null,
                HttpTransport.forSimulator(), ConnectorProfile.SIMULATOR, CapabilityClass.B_LOOKUP_ONLY,
                Duration.ofSeconds(10)));
    }

    public Config config() {
        return config;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    // ---- sign ----------------------------------------------------------------------------------

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        if (!OP_SIGN.equals(request.operation())) {
            return ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION",
                    "eSign supports only '" + OP_SIGN + "', got '" + request.operation() + "'");
        }
        String hash = request.str("documentHash");
        if (hash == null || !SHA256_HEX.matcher(hash.trim()).matches()) {
            return ConnectorResult.BusinessFailure.of("INVALID_DOCUMENT_HASH",
                    "documentHash must be a SHA-256 digest in hex (64 characters)");
        }
        hash = hash.trim().toLowerCase(Locale.ROOT);
        String bound = boundHash(request);
        if (bound == null) {
            return ConnectorResult.BusinessFailure.of("HASH_NOT_BOUND",
                    "approvalPayloadHash or approvalPayload is required: the signature must be bound to the approval "
                            + "payload hash (VI.4 rule 5)");
        }
        if (!bound.equals(hash)) {
            return ConnectorResult.BusinessFailure.of("HASH_NOT_BOUND",
                    "documentHash differs from the approval payload hash (VI.4 rule 5)");
        }
        try {
            return sign(request, hash);
        } catch (RuntimeException e) {
            LOG.warn("eSign for operation {} failed unexpectedly", request.operationId(), e);
            return ConnectorResult.OutcomeUnknown.of("eSign: unexpected " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
    }

    private static String boundHash(ConnectorRequest request) {
        String h = request.str("approvalPayloadHash");
        if (h != null && !h.isBlank()) {
            return h.trim().toLowerCase(Locale.ROOT);
        }
        String payload = request.str("approvalPayload");
        if (payload != null) {
            return Hashes.hex(Hashes.sha256(payload));
        }
        return null;
    }

    private ConnectorResult sign(ConnectorRequest request, String hash) {
        String txn = request.operationId().toString();
        String docInfo = request.str("docInfo", "");
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Esign ver=\"2.1\" sc=\"Y\" ts=\"" + ZonedDateTime.now(IST).format(TS) + "\" txn=\"" + GovXml.esc(txn)
                + "\" ekycIdType=\"A\" aspId=\"" + GovXml.esc(config.aspId()) + "\" AuthMode=\"1\""
                + " responseSigType=\"pkcs7\""
                + (config.responseUrl() == null ? "" : " responseUrl=\"" + GovXml.esc(config.responseUrl().toString())
                + "\"")
                + "><Docs><InputHash id=\"1\" hashAlgorithm=\"SHA256\" docInfo=\"" + GovXml.esc(docInfo) + "\">"
                + hash + "</InputHash></Docs></Esign>";
        String signed = config.requestSigner().apply(xml);
        boolean idempotent = config.capabilityClass() == CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP;
        HttpTransport.Call call = new HttpTransport.Call("POST", GovHttp.join(config.baseUri(), SIGN_PATH),
                Map.of("Content-Type", "application/xml; charset=utf-8", "Accept", "application/xml"),
                signed.getBytes(StandardCharsets.UTF_8), GovHttp.timeout(config.timeout(), request), idempotent);
        HttpTransport.Outcome o = config.transport().execute(request.tenantId(), call, GovHttp.deadline(request));
        if (!(o instanceof HttpTransport.Outcome.Response r)) {
            return GovHttp.failure(SYSTEM, o, idempotent);
        }
        Element root = parse(r.body());
        if (!r.is2xx()) {
            String code = root == null ? null : emptyToNull(root.getAttribute("errCode"));
            String msg = root == null ? null : emptyToNull(root.getAttribute("errMsg"));
            return GovHttp.responseFailure(SYSTEM, r, idempotent, code, msg);
        }
        if (root == null || !"EsignResp".equals(root.getTagName())) {
            return ConnectorResult.OutcomeUnknown.of("eSign: unreadable answer (HTTP " + r.status() + ")");
        }
        String status = root.getAttribute("status");
        String respTxn = root.getAttribute("txn");
        if ("0".equals(status)) {
            String code = emptyToNull(root.getAttribute("errCode"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("txn", respTxn);
            out.put("errCode", code == null ? "UNKNOWN" : code);
            out.put("errMsg", root.getAttribute("errMsg"));
            return new ConnectorResult.BusinessFailure(code == null ? "ESIGN_REFUSED" : code,
                    "ESP refused the request: " + root.getAttribute("errMsg"), out);
        }
        if (!"1".equals(status)) {
            return ConnectorResult.OutcomeUnknown.of("eSign: unexpected status '" + status + "'");
        }
        if (!txn.equals(respTxn)) {
            return ConnectorResult.OutcomeUnknown.of("eSign answered for another txn: " + respTxn);
        }
        Map<String, Object> out = success(root, hash);
        if (out == null) {
            return ConnectorResult.OutcomeUnknown.of("eSign: success answer without a DocSignature");
        }
        String resCode = emptyToNull(root.getAttribute("resCode"));
        return new ConnectorResult.Success(out, resCode == null ? txn : resCode, false);
    }

    // ---- lookup --------------------------------------------------------------------------------

    @Override
    public LookupResult lookup(ConnectorRequest request) {
        if (config.capabilityClass() == CapabilityClass.C_NEITHER) {
            return new LookupResult.NotSupported();
        }
        try {
            String txn = request.operationId().toString();
            URI uri = URI.create(GovHttp.join(config.baseUri(), STATUS_PATH) + "?txn="
                    + URLEncoder.encode(txn, StandardCharsets.UTF_8));
            HttpTransport.Call call = new HttpTransport.Call("GET", uri, Map.of("Accept", "application/xml"), null,
                    GovHttp.timeout(config.timeout(), request), true);
            HttpTransport.Outcome o = config.transport().execute(request.tenantId(), call, GovHttp.deadline(request));
            if (!(o instanceof HttpTransport.Outcome.Response r)) {
                return GovHttp.lookupFailure(SYSTEM, o);
            }
            if (r.status() == 404) {
                return new LookupResult.NotFound();
            }
            if (!r.is2xx()) {
                return GovHttp.lookupFailure(SYSTEM, o);
            }
            Element root = parse(r.body());
            if (root == null || !"EsignResp".equals(root.getTagName())) {
                return new LookupResult.Unavailable("eSign: unreadable status answer");
            }
            if (!txn.equals(root.getAttribute("txn"))) {
                return new LookupResult.Unavailable("eSign status answered for another txn");
            }
            String status = root.getAttribute("status");
            if ("1".equals(status)) {
                Map<String, Object> out = success(root, null);
                if (out == null) {
                    return new LookupResult.Unavailable("eSign status without a DocSignature");
                }
                String resCode = emptyToNull(root.getAttribute("resCode"));
                return LookupResult.Found.success(out, resCode == null ? txn : resCode);
            }
            if ("0".equals(status)) {
                String code = emptyToNull(root.getAttribute("errCode"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("txn", txn);
                out.put("errMsg", root.getAttribute("errMsg"));
                return LookupResult.Found.failed(code == null ? "ESIGN_REFUSED" : code, out);
            }
            return new LookupResult.Unavailable("eSign status '" + status + "'");
        } catch (RuntimeException e) {
            LOG.warn("eSign status for operation {} failed unexpectedly", request.operationId(), e);
            return new LookupResult.Unavailable("eSign: unexpected " + e.getClass().getSimpleName());
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Output of a status="1" answer; null when it carries no signature. */
    private static Map<String, Object> success(Element root, String hash) {
        List<Element> sigs = GovXml.elements(root, "DocSignature");
        String signature = sigs.isEmpty() ? "" : sigs.get(0).getTextContent().trim();
        if (signature.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signature", signature);
        out.put("certificate", GovXml.text(root, "UserX509Certificate"));
        out.put("resCode", root.getAttribute("resCode"));
        out.put("txn", root.getAttribute("txn"));
        out.put("ts", root.getAttribute("ts"));
        out.put("sigHashAlgorithm", sigs.get(0).getAttribute("sigHashAlgorithm"));
        if (hash != null) {
            out.put("documentHash", hash);
        }
        return out;
    }

    private static Element parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            Document d = GovXml.parse(body);
            return d.getDocumentElement();
        } catch (Exception e) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
