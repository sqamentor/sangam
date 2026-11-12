// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.aadhaar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.core.layer.Hashes;
import org.sangam.integration.sim.SimHttpServer;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Simulator of the Sangam AUA-neutral Sub-AUA contract v1 (M-078, owner decision 67): what a deployment's parent AUA,
 * or a per-AUA adapter in front of it, answers to {@code AadhaarSubAuaConnector}. It is NOT the UIDAI Auth API; the
 * wire format of a real AUA is mapped by a per-AUA adapter once that AUA and its specification are named.
 *
 * <ul>
 *   <li>{@code POST /sangam-aua/v1/otp} {@code {"txn","idType","idNumber","consent":true,"purpose"}} → 200
 *       {@code {"txn","status":"OTP_SENT","maskedMobile","duplicate"}}; effect {@code OTP_SENT}, key = txn.</li>
 *   <li>{@code POST /sangam-aua/v1/auth} {@code {"txn","idType","idNumber","authType":"OTP"|"DEMOGRAPHIC","otpTxn",
 *       "otp","demographics":{"name","dob","gender"},"consent":true,"purpose"}} → 200 {@code {"txn","authenticated",
 *       "authCode","errorCode","duplicate"}}; effect {@code AUTH}, key = txn. A one-time password is used once.</li>
 *   <li>{@code GET /sangam-aua/v1/txn/{txn}} → the stored answer of that txn, or 404 {@code TXN_NOT_FOUND}.</li>
 * </ul>
 * Every txn is idempotent: the same txn with the same request answers the stored result with {@code duplicate=true} and
 * no new effect; the same txn with a different request is 409 {@code TXN_REUSED}. Refusals: no consent 403
 * {@code CONSENT_REQUIRED}; biometric authentication or any biometric field 400 {@code BIOMETRIC_NOT_SUPPORTED}
 * (Sangam never captures or stores biometrics); a malformed number 400 {@code INVALID_ID}; an unknown number 404
 * {@code ID_NOT_FOUND}. The simulator keeps only a hash of each request and never the number in its ledger.
 */
public final class AadhaarSubAuaSimulator extends SimHttpServer {

    public static final String OTP_PATH = "/sangam-aua/v1/otp";
    public static final String AUTH_PATH = "/sangam-aua/v1/auth";
    public static final String TXN_PREFIX = "/sangam-aua/v1/txn/";
    public static final String EFFECT_OTP = "OTP_SENT";
    public static final String EFFECT_AUTH = "AUTH";
    /** A Verhoeff-valid test number (not a real resident's) and its fixed simulator one-time password. */
    public static final String TEST_NUMBER = "234123412346";
    public static final String TEST_OTP = "123456";

    /** A resident known to the simulator. */
    public record Resident(String number, String mobileLast4, String name, String dob, String gender) {
        public Resident {
            Objects.requireNonNull(number, "number");
        }
    }

    private record Stored(String requestHash, ObjectNode answer) {
    }

    private record PendingOtp(String number, boolean used) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Resident> residents = new LinkedHashMap<>();
    private final Map<String, Stored> byTxn = new LinkedHashMap<>();
    private final Map<String, PendingOtp> otps = new LinkedHashMap<>();

    public AadhaarSubAuaSimulator() {
        super("aadhaar-sub-aua");
        resident(new Resident(TEST_NUMBER, "4321", "Asha Devi", "1990-04-01", "F"));
        route("POST", OTP_PATH, this::otp);
        route("POST", AUTH_PATH, this::auth);
        prefixRoute("GET", TXN_PREFIX, this::txn);
    }

    /** Adds a resident (test set-up). */
    public AadhaarSubAuaSimulator resident(Resident r) {
        synchronized (byTxn) {
            residents.put(r.number(), r);
        }
        return this;
    }

    public Optional<JsonNode> answer(String txn) {
        synchronized (byTxn) {
            Stored s = byTxn.get(txn);
            return s == null ? Optional.empty() : Optional.of(s.answer().deepCopy());
        }
    }

    private Response otp(Request req) {
        JsonNode body = body(req);
        Response refused = common(body);
        if (refused != null) {
            return refused;
        }
        String txn = body.path("txn").asText();
        String number = body.path("idNumber").asText();
        synchronized (byTxn) {
            Response dup = duplicate(txn, req);
            if (dup != null) {
                return dup;
            }
            Resident r = residents.get(number);
            if (r == null) {
                return error(404, "ID_NOT_FOUND", "the number is not known to the simulator");
            }
            ObjectNode out = JSON.createObjectNode();
            out.put("txn", txn);
            out.put("status", "OTP_SENT");
            out.put("maskedMobile", "XXXXXX" + r.mobileLast4());
            store(txn, req, out);
            otps.put(txn, new PendingOtp(number, false));
            ledger().record(EFFECT_OTP, txn, Map.of());
            return Response.json(200, withDuplicate(out, false));
        }
    }

    private Response auth(Request req) {
        JsonNode body = body(req);
        Response refused = common(body);
        if (refused != null) {
            return refused;
        }
        String authType = body.path("authType").asText("");
        if ("BIOMETRIC".equalsIgnoreCase(authType)) {
            return error(400, "BIOMETRIC_NOT_SUPPORTED", "biometric authentication is not supported");
        }
        if (!"OTP".equals(authType) && !"DEMOGRAPHIC".equals(authType)) {
            return error(400, "BAD_REQUEST", "authType must be OTP or DEMOGRAPHIC");
        }
        String txn = body.path("txn").asText();
        String number = body.path("idNumber").asText();
        synchronized (byTxn) {
            Response dup = duplicate(txn, req);
            if (dup != null) {
                return dup;
            }
            Resident r = residents.get(number);
            if (r == null) {
                return error(404, "ID_NOT_FOUND", "the number is not known to the simulator");
            }
            String failure = "OTP".equals(authType) ? otpFailure(body, number) : demographicFailure(body, r);
            ObjectNode out = JSON.createObjectNode();
            out.put("txn", txn);
            out.put("authenticated", failure == null);
            if (failure == null) {
                out.put("authCode", Hashes.hex(Hashes.sha256(("auth|" + txn).getBytes(StandardCharsets.UTF_8))).substring(0, 32));
            } else {
                out.put("errorCode", failure);
            }
            store(txn, req, out);
            ledger().record(EFFECT_AUTH, txn, Map.of("authType", authType, "authenticated", String.valueOf(failure == null)));
            return Response.json(200, withDuplicate(out, false));
        }
    }

    private Response txn(Request req) {
        String txn = req.pathAfter(TXN_PREFIX);
        synchronized (byTxn) {
            Stored s = txn == null ? null : byTxn.get(txn);
            return s == null ? error(404, "TXN_NOT_FOUND", "unknown txn") : Response.json(200, s.answer().toString());
        }
    }

    // ------------------------------------------------------------------------------------------------ rules

    private String otpFailure(JsonNode body, String number) {
        PendingOtp p = otps.get(body.path("otpTxn").asText(""));
        if (p == null || !p.number().equals(number)) {
            return "OTP_TXN_UNKNOWN";
        }
        if (p.used()) {
            return "OTP_EXPIRED";
        }
        if (!TEST_OTP.equals(body.path("otp").asText(""))) {
            return "OTP_MISMATCH";
        }
        otps.put(body.path("otpTxn").asText(), new PendingOtp(p.number(), true));
        return null;
    }

    private static String demographicFailure(JsonNode body, Resident r) {
        JsonNode d = body.path("demographics");
        if (!d.isObject() || d.isEmpty()) {
            return "DEMOGRAPHICS_MISSING";
        }
        boolean ok = matches(d, "name", r.name(), true) && matches(d, "dob", r.dob(), false)
                && matches(d, "gender", r.gender(), true);
        return ok ? null : "DEMOGRAPHIC_MISMATCH";
    }

    private static boolean matches(JsonNode d, String field, String expected, boolean ignoreCase) {
        if (!d.has(field)) {
            return true;
        }
        String v = d.path(field).asText("").strip();
        return ignoreCase ? v.toLowerCase(Locale.ROOT).equals(String.valueOf(expected).toLowerCase(Locale.ROOT))
                : v.equals(expected);
    }

    /** Checks shared by both calls; null when the request may proceed. */
    private static Response common(JsonNode body) {
        if (body == null || !body.isObject()) {
            return error(400, "BAD_REQUEST", "body must be a JSON object");
        }
        for (String biometric : new String[]{"biometrics", "pid", "pidBlock", "fingerprint", "iris", "face"}) {
            if (body.has(biometric)) {
                return error(400, "BIOMETRIC_NOT_SUPPORTED", "biometric data is not accepted");
            }
        }
        if (body.path("txn").asText("").isBlank()) {
            return error(400, "BAD_REQUEST", "txn is required");
        }
        if (!body.path("consent").asBoolean(false)) {
            return error(403, "CONSENT_REQUIRED", "the resident's consent is required");
        }
        String type = body.path("idType").asText("");
        String number = body.path("idNumber").asText("");
        boolean valid = ("AADHAAR".equals(type) && number.matches("[2-9][0-9]{11}"))
                || ("VID".equals(type) && number.matches("[0-9]{16}"));
        return valid ? null : error(400, "INVALID_ID", "idType must be AADHAAR (12 digits) or VID (16 digits)");
    }

    private Response duplicate(String txn, Request req) {
        Stored s = byTxn.get(txn);
        if (s == null) {
            return null;
        }
        if (!s.requestHash().equals(hash(req))) {
            return error(409, "TXN_REUSED", "txn already used with a different request");
        }
        return Response.json(200, withDuplicate(s.answer().deepCopy(), true));
    }

    private void store(String txn, Request req, ObjectNode answer) {
        byTxn.put(txn, new Stored(hash(req), answer.deepCopy()));
    }

    private static String hash(Request req) {
        return Hashes.hex(Hashes.sha256(req.body()));
    }

    private static String withDuplicate(ObjectNode out, boolean duplicate) {
        out.put("duplicate", duplicate);
        return out.toString();
    }

    private static JsonNode body(Request req) {
        try {
            return JSON.readTree(req.body());
        } catch (Exception e) {
            return null;
        }
    }

    private static Response error(int status, String code, String msg) {
        ObjectNode out = JSON.createObjectNode();
        out.put("status", "ERROR");
        out.put("errorCode", code);
        out.put("message", msg);
        return Response.json(status, out.toString());
    }
}
