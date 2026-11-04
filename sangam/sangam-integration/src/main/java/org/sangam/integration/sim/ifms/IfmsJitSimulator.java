// SPDX-License-Identifier: Apache-2.0
// Ported from spike/simulators org.sangam.spike.sim.ifms.IfmsJitSimulator. Modified by Sangam.
package org.sangam.integration.sim.ifms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.sim.SimHttpServer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulator of the Odisha IFMS Just-in-Time (JIT) web services, shaped after the public eGov DIGIT
 * IFMS-adapter documentation (SANGAM.md IX.7d): {@code authenticate} gives a token, then
 * {@code get-jit-service-data} runs one service code:
 * <ul>
 *   <li>{@code PI} payment instruction: params {@code billId, amount, beneficiary{name,accountNo,ifsc}, operationId}</li>
 *   <li>{@code PD} payment status by {@code operationId}</li>
 *   <li>{@code FD} failures of the last {@code sinceHours} (default 72)</li>
 * </ul>
 * The JSON field names are this simulator's own design; the real schema is ON-REQUEST (RQ-3) and
 * will only change the adapter mapping. {@code VA} (virtual allotment) is not simulated.
 *
 * <p>Capability class ({@link org.sangam.core.layer.CapabilityClass}, A-2 / A-8): (a) idempotent by operationId +
 * PD lookup, the default; (b) PD lookup only, a duplicate PI pays twice; (c) neither, a duplicate PI pays twice and
 * PD is refused.
 *
 * <p>Ledger effect types: {@code PAYMENT} (money moved, key = operationId) and
 * {@code PAYMENT_FAILED} (bank rejected, no money moved).
 */
public final class IfmsJitSimulator extends SimHttpServer {

    public static final String AUTH_PATH = "/webservices/jitfs/v1.0/authenticate";
    public static final String DATA_PATH = "/webservices/jitfs/v1.0/get-jit-service-data";
    public static final String EFFECT_PAYMENT = "PAYMENT";
    public static final String EFFECT_PAYMENT_FAILED = "PAYMENT_FAILED";

    /** One payment executed (or failed) by the simulator. */
    public record Payment(String paymentRef, String operationId, String billId, BigDecimal amount,
                          String accountNo, String beneficiaryJson, String status, String reason, Instant at) { }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();
    private final Map<String, List<Payment>> byOperation = new LinkedHashMap<>();
    private final Set<String> failingAccounts = ConcurrentHashMap.newKeySet();
    private final AtomicLong refSeq = new AtomicLong();
    private volatile String clientId = "sim-client";
    private volatile String clientSecret = "sim-secret";
    private volatile Duration tokenTtl = Duration.ofMinutes(30);
    private volatile Clock clock = Clock.systemUTC();
    private volatile CapabilityClass capabilityClass = CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP;

    public IfmsJitSimulator() {
        super("ifms-jit");
        route("POST", AUTH_PATH, this::authenticate);
        route("POST", DATA_PATH, this::serviceData);
    }

    // ---- configuration -------------------------------------------------------------------------

    public IfmsJitSimulator credentials(String id, String secret) {
        this.clientId = id;
        this.clientSecret = secret;
        return this;
    }

    public IfmsJitSimulator tokenTtl(Duration ttl) {
        this.tokenTtl = ttl;
        return this;
    }

    public IfmsJitSimulator clock(Clock c) {
        this.clock = c;
        return this;
    }

    public IfmsJitSimulator capabilityClass(CapabilityClass c) {
        this.capabilityClass = c;
        return this;
    }

    /** Payments to this account fail at the "bank" and appear in the FD feed. */
    public IfmsJitSimulator failAccount(String accountNo) {
        failingAccounts.add(accountNo);
        return this;
    }

    public CapabilityClass capabilityClass() {
        return capabilityClass;
    }

    /** All payments recorded for an operationId (more than one only when idempotency is off). */
    public List<Payment> payments(String operationId) {
        synchronized (byOperation) {
            return List.copyOf(byOperation.getOrDefault(operationId, List.of()));
        }
    }

    // ---- routes --------------------------------------------------------------------------------

    private Response authenticate(Request req) throws Exception {
        JsonNode body = parse(req);
        if (body == null) {
            return error(400, "BAD_REQUEST", "body must be JSON");
        }
        if (!clientId.equals(body.path("clientId").asText()) || !clientSecret.equals(body.path("clientSecret").asText())) {
            return error(401, "INVALID_CREDENTIALS", "unknown client or wrong secret");
        }
        String token = UUID.randomUUID().toString();
        tokens.put(token, clock.instant().plus(tokenTtl));
        ObjectNode out = JSON.createObjectNode();
        out.put("status", "SUCCESS");
        out.put("authToken", token);
        out.put("expiresIn", tokenTtl.toSeconds());
        return Response.json(200, out.toString());
    }

    private Response serviceData(Request req) throws Exception {
        String auth = req.header("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null;
        Instant exp = token == null ? null : tokens.get(token);
        if (exp == null || !clock.instant().isBefore(exp)) {
            return error(401, "TOKEN_INVALID_OR_EXPIRED", "authenticate first");
        }
        JsonNode body = parse(req);
        if (body == null) {
            return error(400, "BAD_REQUEST", "body must be JSON");
        }
        JsonNode params = body.path("params");
        return switch (body.path("serviceCode").asText()) {
            case "PI" -> paymentInstruction(params);
            case "PD" -> paymentStatus(params);
            case "FD" -> failures(params);
            default -> error(400, "UNKNOWN_SERVICE_CODE", "supported: PI, PD, FD");
        };
    }

    private Response paymentInstruction(JsonNode p) {
        String operationId = p.path("operationId").asText("");
        String billId = p.path("billId").asText("");
        String accountNo = p.path("beneficiary").path("accountNo").asText("");
        BigDecimal amount;
        try {
            amount = new BigDecimal(p.path("amount").asText());
        } catch (NumberFormatException e) {
            return error(400, "INVALID_AMOUNT", "amount must be a decimal");
        }
        if (operationId.isBlank() || billId.isBlank() || accountNo.isBlank()) {
            return error(400, "MISSING_FIELD", "operationId, billId and beneficiary.accountNo are required");
        }
        if (amount.signum() <= 0) {
            return error(400, "INVALID_AMOUNT", "amount must be positive");
        }
        synchronized (byOperation) {
            List<Payment> existing = byOperation.get(operationId);
            if (capabilityClass == CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP && existing != null && !existing.isEmpty()) {
                Payment first = existing.get(0);
                if (!first.billId().equals(billId) || first.amount().compareTo(amount) != 0
                        || !first.accountNo().equals(accountNo)) {
                    return error(409, "OPERATION_ID_REUSED", "operationId already used with a different payload");
                }
                return Response.json(200, piResult(first, true).toString());
            }
            boolean fails = failingAccounts.contains(accountNo);
            Payment pay = new Payment("JIT" + String.format("%08d", refSeq.incrementAndGet()), operationId, billId,
                    amount, accountNo, p.path("beneficiary").toString(), fails ? "FAILED" : "SUCCESS",
                    fails ? "ACCOUNT_INVALID" : null, clock.instant());
            byOperation.computeIfAbsent(operationId, k -> new ArrayList<>()).add(pay);
            ledger().record(fails ? EFFECT_PAYMENT_FAILED : EFFECT_PAYMENT, operationId,
                    Map.of("paymentRef", pay.paymentRef(), "billId", billId, "amount", amount.toPlainString(),
                            "accountNo", accountNo));
            return Response.json(200, piResult(pay, false).toString());
        }
    }

    private Response paymentStatus(JsonNode p) {
        if (capabilityClass == CapabilityClass.C_NEITHER) {
            return error(400, "PD_NOT_SUPPORTED", "this IFMS instance offers no outcome lookup (capability class c)");
        }
        String operationId = p.path("operationId").asText("");
        if (operationId.isBlank()) {
            return error(400, "MISSING_FIELD", "operationId is required");
        }
        List<Payment> list = payments(operationId);
        ObjectNode out = JSON.createObjectNode();
        out.put("serviceCode", "PD");
        out.put("operationId", operationId);
        out.put("status", list.isEmpty() ? "NOT_FOUND" : list.get(list.size() - 1).status());
        ArrayNode arr = out.putArray("payments");
        list.forEach(pay -> arr.add(paymentJson(pay)));
        return Response.json(200, out.toString());
    }

    private Response failures(JsonNode p) {
        long hours = p.path("sinceHours").asLong(72);
        Instant since = clock.instant().minus(Duration.ofHours(hours));
        ObjectNode out = JSON.createObjectNode();
        out.put("serviceCode", "FD");
        ArrayNode arr = out.putArray("failures");
        synchronized (byOperation) {
            byOperation.values().stream().flatMap(List::stream)
                    .filter(pay -> "FAILED".equals(pay.status()) && !pay.at().isBefore(since))
                    .forEach(pay -> arr.add(paymentJson(pay)));
        }
        return Response.json(200, out.toString());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private ObjectNode piResult(Payment pay, boolean duplicate) {
        ObjectNode out = JSON.createObjectNode();
        out.put("serviceCode", "PI");
        out.put("status", "ACCEPTED");
        out.put("duplicate", duplicate);
        out.put("operationId", pay.operationId());
        out.put("paymentRef", pay.paymentRef());
        return out;
    }

    private static ObjectNode paymentJson(Payment pay) {
        ObjectNode n = JSON.createObjectNode();
        n.put("paymentRef", pay.paymentRef());
        n.put("operationId", pay.operationId());
        n.put("billId", pay.billId());
        n.put("amount", pay.amount().toPlainString());
        n.put("accountNo", pay.accountNo());
        n.put("status", pay.status());
        if (pay.reason() != null) {
            n.put("reason", pay.reason());
        }
        n.put("at", pay.at().toString());
        return n;
    }

    private static JsonNode parse(Request req) {
        try {
            JsonNode n = JSON.readTree(req.body());
            return n == null || !n.isObject() ? null : n;
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
