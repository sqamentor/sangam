// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.sim.aadhaar.AadhaarSubAuaSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Aadhaar authentication as a Sub-AUA (M-078, owner decision 67), over Sangam's AUA-NEUTRAL contract v1. Sangam does
 * not speak any one AUA's wire format: the deployment's parent AUA, or a per-AUA adapter in front of it, serves the
 * neutral JSON calls below ({@link AadhaarSubAuaSimulator} is the reference). A per-AUA connector follows once the
 * AUA and its specification are named.
 *
 * <p><b>Operations</b> (the txn is the operationId, so every call is idempotent: class (a) with lookup):
 * <ul>
 *   <li>{@code otp}: input {@code idType} ({@code AADHAAR} or {@code VID}), {@code idNumber}, {@code consent}
 *       (must be {@code true}), {@code purpose}; output {@code status}, {@code maskedMobile}, {@code otpTxn}.</li>
 *   <li>{@code authenticate}: input as above plus {@code authType} ({@code OTP} with {@code otpTxn} and {@code otp},
 *       or {@code DEMOGRAPHIC} with {@code demographics} {@code name}/{@code dob}/{@code gender}); output
 *       {@code authenticated}, {@code authCode} or {@code errorCode}. A yes/no answer only: no e-KYC data is asked
 *       for or kept.</li>
 * </ul>
 * Refused before any call: no consent ({@code CONSENT_REQUIRED}, the resident's informed consent comes first);
 * biometric authentication or any biometric input ({@code BIOMETRIC_NOT_SUPPORTED}: Sangam never captures, sends or
 * stores biometrics); a malformed number ({@code INVALID_ID}). The number is sent only in the request body: it is
 * never logged, never put in the output (only the last four digits, masked) and never used as a provider reference.
 * Keep numbers in the national-id vault (sangam-compliance) and pass them in only for the call.
 */
public final class AadhaarSubAuaConnector implements SangamConnector {

    public static final String TYPE = "aadhaar-sub-aua";
    public static final String OP_OTP = "otp";
    public static final String OP_AUTHENTICATE = "authenticate";
    public static final String OTP_PATH = AadhaarSubAuaSimulator.OTP_PATH;
    public static final String AUTH_PATH = AadhaarSubAuaSimulator.AUTH_PATH;
    public static final String TXN_PREFIX = AadhaarSubAuaSimulator.TXN_PREFIX;
    /** Header naming this deployment to its parent AUA. */
    public static final String SUB_AUA_HEADER = "X-Sub-AUA";

    private static final Logger LOG = LoggerFactory.getLogger(AadhaarSubAuaConnector.class);
    private static final String SYSTEM = "Sub-AUA";
    private static final Set<String> BIOMETRIC_INPUTS = Set.of("biometrics", "pid", "pidBlock", "fingerprint", "iris", "face");

    /**
     * @param id         connector instance id
     * @param baseUri    the parent AUA (or its adapter) base URI
     * @param subAuaCode this deployment's Sub-AUA code, issued by the parent AUA
     * @param transport  HTTP transport (mutual TLS and credentials are the transport's)
     * @param profile    live or simulator
     * @param timeout    per-request timeout; null = 30 s
     */
    public record Config(String id, URI baseUri, String subAuaCode, HttpTransport transport, ConnectorProfile profile,
                         Duration timeout) {
        public Config {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(subAuaCode, "subAuaCode");
            Objects.requireNonNull(transport, "transport");
            profile = profile == null ? ConnectorProfile.LIVE : profile;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }

    private final Config config;
    private final ConnectorDescriptor descriptor;

    public AadhaarSubAuaConnector(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.descriptor = new ConnectorDescriptor(config.id(), TYPE, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of(OP_OTP, OP_AUTHENTICATE), config.profile());
    }

    /** Wired to a running simulator (tests and the SIMULATOR profile). */
    public static AadhaarSubAuaConnector simulator(AadhaarSubAuaSimulator sim) {
        return new AadhaarSubAuaConnector(new Config("aadhaar-sub-aua-sim", sim.baseUri(), "SIM-SUBAUA",
                HttpTransport.forSimulator(), ConnectorProfile.SIMULATOR, Duration.ofSeconds(10)));
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        String path = switch (String.valueOf(request.operation())) {
            case OP_OTP -> OTP_PATH;
            case OP_AUTHENTICATE -> AUTH_PATH;
            default -> null;
        };
        if (path == null) {
            return ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION", "the Sub-AUA connector supports '"
                    + OP_OTP + "' and '" + OP_AUTHENTICATE + "', got '" + request.operation() + "'");
        }
        Map<String, Object> body;
        try {
            body = body(request);
        } catch (Refusal r) {
            return ConnectorResult.BusinessFailure.of(r.code, r.getMessage());
        }
        try {
            HttpTransport.Outcome o = config.transport().execute(request.tenantId(), new HttpTransport.Call("POST",
                    GovHttp.join(config.baseUri(), path), headers(), Json.bytes(body),
                    GovHttp.timeout(config.timeout(), request), true), GovHttp.deadline(request));
            if (!(o instanceof HttpTransport.Outcome.Response r)) {
                return GovHttp.failure(SYSTEM, o, true);
            }
            if (!r.is2xx()) {
                return GovHttp.failure(SYSTEM, o, true);
            }
            JsonNode n = Json.tree(r.body());
            if (n == null || !n.isObject() || !txn(request).equals(GovHttp.text(n, "txn"))) {
                return ConnectorResult.OutcomeUnknown.of(SYSTEM + ": unreadable answer");
            }
            return success(request, n);
        } catch (RuntimeException e) {
            LOG.warn("Sub-AUA {} for operation {} failed unexpectedly ({})", request.operation(), request.operationId(),
                    e.getClass().getSimpleName());
            return ConnectorResult.OutcomeUnknown.of(SYSTEM + ": unexpected " + e.getClass().getSimpleName());
        }
    }

    /** Looks the txn (= operationId) up: found means the AUA answered it; the stored answer is returned. */
    @Override
    public LookupResult lookup(ConnectorRequest request) {
        HttpTransport.Outcome o = config.transport().execute(request.tenantId(),
                HttpTransport.Call.get(GovHttp.join(config.baseUri(), TXN_PREFIX + txn(request)), headers()),
                GovHttp.deadline(request));
        if (o instanceof HttpTransport.Outcome.Response r && r.status() == 404) {
            return new LookupResult.NotFound();
        }
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            return GovHttp.lookupFailure(SYSTEM, o);
        }
        JsonNode n = Json.tree(r.body());
        if (n == null || !n.isObject()) {
            return new LookupResult.Unavailable(SYSTEM + ": unreadable lookup answer");
        }
        Map<String, Object> out = output(request, n);
        return LookupResult.Found.success(out, txn(request));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static final class Refusal extends RuntimeException {
        private final String code;

        Refusal(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** The neutral request body; refuses before any call what Sangam must never send. */
    static Map<String, Object> body(ConnectorRequest request) {
        Map<String, Object> in = request.input() == null ? Map.of() : request.input();
        for (String k : in.keySet()) {
            if (BIOMETRIC_INPUTS.contains(k)) {
                throw new Refusal("BIOMETRIC_NOT_SUPPORTED", "biometric data is never captured, sent or stored by Sangam");
            }
        }
        if (!Boolean.TRUE.equals(in.get("consent"))) {
            throw new Refusal("CONSENT_REQUIRED", "the resident's consent (consent=true) is required before authentication");
        }
        String type = String.valueOf(in.get("idType"));
        String number = in.get("idNumber") == null ? "" : String.valueOf(in.get("idNumber")).replace(" ", "");
        boolean valid = ("AADHAAR".equals(type) && number.matches("[2-9][0-9]{11}"))
                || ("VID".equals(type) && number.matches("[0-9]{16}"));
        if (!valid) {
            throw new Refusal("INVALID_ID", "idType must be AADHAAR (12 digits) or VID (16 digits) with a matching idNumber");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txn", txn(request));
        body.put("idType", type);
        body.put("idNumber", number);
        body.put("consent", true);
        if (in.get("purpose") != null) {
            body.put("purpose", String.valueOf(in.get("purpose")));
        }
        if (OP_AUTHENTICATE.equals(request.operation())) {
            String authType = String.valueOf(in.get("authType"));
            if ("BIOMETRIC".equalsIgnoreCase(authType)) {
                throw new Refusal("BIOMETRIC_NOT_SUPPORTED", "biometric authentication is not supported");
            }
            if ("OTP".equals(authType)) {
                if (in.get("otpTxn") == null || in.get("otp") == null) {
                    throw new Refusal("INVALID_INPUT", "OTP authentication needs otpTxn and otp");
                }
                body.put("otpTxn", String.valueOf(in.get("otpTxn")));
                body.put("otp", String.valueOf(in.get("otp")));
            } else if ("DEMOGRAPHIC".equals(authType)) {
                if (!(in.get("demographics") instanceof Map<?, ?> d) || d.isEmpty()) {
                    throw new Refusal("INVALID_INPUT", "DEMOGRAPHIC authentication needs demographics");
                }
                Map<String, Object> demo = new LinkedHashMap<>();
                for (String f : List.of("name", "dob", "gender")) {
                    if (d.get(f) != null) {
                        demo.put(f, String.valueOf(d.get(f)));
                    }
                }
                body.put("demographics", demo);
            } else {
                throw new Refusal("INVALID_INPUT", "authType must be OTP or DEMOGRAPHIC");
            }
            body.put("authType", authType);
        }
        return body;
    }

    private ConnectorResult success(ConnectorRequest request, JsonNode n) {
        boolean duplicate = n.path("duplicate").asBoolean(false);
        return new ConnectorResult.Success(output(request, n), txn(request), duplicate);
    }

    private static Map<String, Object> output(ConnectorRequest request, JsonNode n) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("txn", txn(request));
        out.put("idMasked", masked(request));
        if (OP_OTP.equals(request.operation())) {
            out.put("otpTxn", txn(request));
            put(out, "status", GovHttp.text(n, "status"));
            put(out, "maskedMobile", GovHttp.text(n, "maskedMobile"));
        } else {
            out.put("authenticated", n.path("authenticated").asBoolean(false));
            put(out, "authCode", GovHttp.text(n, "authCode"));
            put(out, "errorCode", GovHttp.text(n, "errorCode"));
        }
        return out;
    }

    private static void put(Map<String, Object> out, String key, String value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    /** All but the last four digits masked; never the number itself. */
    static String masked(ConnectorRequest request) {
        Object v = request.input() == null ? null : request.input().get("idNumber");
        String n = v == null ? "" : String.valueOf(v).replace(" ", "");
        return n.length() < 4 ? "XXXX" : "X".repeat(n.length() - 4) + n.substring(n.length() - 4);
    }

    private static String txn(ConnectorRequest request) {
        return request.operationId().toString();
    }

    private Map<String, String> headers() {
        return Map.of("Content-Type", "application/json", "Accept", "application/json", SUB_AUA_HEADER, config.subAuaCode());
    }
}
