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
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.sim.ifms.IfmsJitSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * State treasury connector for the Odisha IFMS Just-in-Time (JIT) web services (R58, IX.4 P1 bill/payment; IX.7d
 * shape as simulated by {@link IfmsJitSimulator}; the real schema is ON-REQUEST, RQ-3, and only changes this mapping).
 *
 * <p><b>Authentication.</b> {@code authenticate} with client id and secret (secret references, R73) gives a bearer
 * token that is cached until shortly before {@code expiresIn}. A 401 from the data service invalidates the token and
 * re-authenticates; authentication has no business effect, so it is always safe. The 401 is the token check that
 * precedes processing, so the payment instruction was not processed: in class (a) it is re-sent once in the same call
 * (also harmless because PI dedups by operationId); in classes (b)/(c) the call returns
 * {@code TransientFailure(noEffect=true)} so the invoker re-dispatches through the ledger with the fresh token.
 *
 * <p><b>Operation {@code pay}</b> (PI): input {@code billId}, {@code amount} (decimal string, R33),
 * {@code beneficiary{name, accountNo, ifsc}}; {@code operationId} (R36) is sent as the PI's idempotency key.
 * Output {@code paymentRef}, {@code status} ({@code ACCEPTED}: the final bank status arrives through PD / FD),
 * {@code duplicate}.
 *
 * <p><b>Lookup</b> (PD, by operationId): {@code NOT_FOUND} → {@link LookupResult.NotFound}; {@code SUCCESS} →
 * found, succeeded; {@code FAILED} → found, failed with the bank's reason. Class (c) sites answer
 * {@link LookupResult.NotSupported} without a call.
 *
 * <p><b>Capability class</b> is per site (A-2): (a) by default (PI idempotent by operationId + PD), (b) when PI does
 * not dedup, (c) when PD is not offered either. The declared class decides whether the HTTP call is marked
 * idempotent, which in turn decides in-call retries and the 5xx mapping (see {@link GovHttp}).
 *
 * <p><b>Failure feed</b> (FD): {@link #failures(int)} lists bank rejections for the bank-acknowledgement feed
 * ({@link BankAcknowledgementFeed}).
 */
public final class IfmsJitConnector implements SangamConnector {

    public static final String TYPE = "ifms-jit";
    public static final String OP_PAY = "pay";
    public static final String AUTH_PATH = "/webservices/jitfs/v1.0/authenticate";
    public static final String DATA_PATH = "/webservices/jitfs/v1.0/get-jit-service-data";

    private static final Logger LOG = LoggerFactory.getLogger(IfmsJitConnector.class);
    private static final String SYSTEM = "IFMS";
    private static final Pattern IFSC = Pattern.compile("[A-Z]{4}0[A-Z0-9]{6}");
    private static final String PLATFORM_TENANT = "*";

    /**
     * Configuration of one IFMS site.
     *
     * @param id              connector instance id
     * @param baseUri         IFMS base URI (scheme, host, optional context path)
     * @param clientIdRef     client id or {@code secret://} reference
     * @param clientSecretRef client secret {@code secret://} reference (a literal only in the simulator profile)
     * @param secrets         resolver for the references
     * @param transport       HTTP transport (egress policy, mTLS, retries)
     * @param profile         live or simulator
     * @param capabilityClass A-2 class of this site; null = (a)
     * @param timeout         per-request timeout; null = 30 s
     */
    public record Config(String id, URI baseUri, String clientIdRef, String clientSecretRef, SecretResolver secrets,
                         HttpTransport transport, ConnectorProfile profile, CapabilityClass capabilityClass,
                         Duration timeout) {
        public Config {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(clientIdRef, "clientIdRef");
            Objects.requireNonNull(clientSecretRef, "clientSecretRef");
            Objects.requireNonNull(transport, "transport");
            secrets = secrets == null ? SecretResolver.fromEnvironment() : secrets;
            profile = profile == null ? ConnectorProfile.LIVE : profile;
            capabilityClass = capabilityClass == null ? CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP : capabilityClass;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }

    /** One bank rejection from the FD feed. */
    public record FailureRecord(String paymentRef, String operationId, String billId, String amount, String accountNo,
                                String status, String reason, Instant at) {
    }

    /** The FD feed could not be read (network, auth, malformed answer). */
    public static final class FeedUnavailableException extends RuntimeException {
        public FeedUnavailableException(String message) {
            super(message);
        }
    }

    private record Token(String value, Instant refreshAt) {
    }

    /** Authentication result: a token, or why there is none. */
    private record Auth(String token, String failure) {
    }

    private final Config config;
    private final Clock clock;
    private final ConnectorDescriptor descriptor;
    private final Object authLock = new Object();
    private volatile Token token;

    public IfmsJitConnector(Config config) {
        this(config, Clock.systemUTC());
    }

    public IfmsJitConnector(Config config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.descriptor = new ConnectorDescriptor(config.id(), TYPE, config.capabilityClass(), List.of(OP_PAY),
                config.profile());
    }

    /** Wired to the IFMS simulator of {@code sims}, class (a). */
    public static IfmsJitConnector simulator(SimulatorProfile sims) {
        return simulator(sims, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
    }

    /** Wired to the IFMS simulator of {@code sims}, which is switched to the same capability class (A-2: must be true). */
    public static IfmsJitConnector simulator(SimulatorProfile sims, CapabilityClass capabilityClass) {
        IfmsJitSimulator sim = sims.ifms();
        sim.capabilityClass(capabilityClass);
        return new IfmsJitConnector(new Config("ifms-jit-sim", sim.baseUri(), SimulatorProfile.IFMS_CLIENT_ID,
                SimulatorProfile.IFMS_CLIENT_SECRET, SecretResolver.of(Map.of()), HttpTransport.forSimulator(),
                ConnectorProfile.SIMULATOR, capabilityClass, Duration.ofSeconds(10)));
    }

    public Config config() {
        return config;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    // ---- pay (PI) ------------------------------------------------------------------------------

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        if (!OP_PAY.equals(request.operation())) {
            return ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION",
                    "IFMS JIT supports only '" + OP_PAY + "', got '" + request.operation() + "'");
        }
        Map<String, Object> params;
        try {
            params = piParams(request);
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_INPUT", e.getMessage());
        }
        try {
            return pay(request, params);
        } catch (RuntimeException e) {
            LOG.warn("IFMS PI for operation {} failed unexpectedly", request.operationId(), e);
            return ConnectorResult.OutcomeUnknown.of("IFMS: unexpected " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
    }

    private ConnectorResult pay(ConnectorRequest request, Map<String, Object> params) {
        long deadline = GovHttp.deadline(request);
        boolean idempotent = config.capabilityClass() == CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP;
        Auth auth = authenticate(request.tenantId(), deadline, false);
        if (auth.token() == null) {
            return ConnectorResult.TransientFailure.noEffect("IFMS authentication failed: " + auth.failure());
        }
        Map<String, Object> body = Map.of("serviceCode", "PI", "params", params);
        HttpTransport.Outcome o = dataCall(request, auth.token(), body, idempotent, deadline);
        if (o instanceof HttpTransport.Outcome.Response r && r.status() == 401) {
            invalidate(auth.token());
            Auth again = authenticate(request.tenantId(), deadline, true);
            if (again.token() == null) {
                return ConnectorResult.TransientFailure.noEffect("IFMS re-authentication failed: " + again.failure());
            }
            if (!idempotent) {
                return new ConnectorResult.TransientFailure(
                        "IFMS rejected the token (401) before processing; re-authenticated, retry", true, Duration.ZERO);
            }
            o = dataCall(request, again.token(), body, true, deadline);
            if (o instanceof HttpTransport.Outcome.Response r2 && r2.status() == 401) {
                invalidate(again.token());
                return ConnectorResult.TransientFailure.noEffect("IFMS rejected a fresh token (401)");
            }
        }
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            return GovHttp.failure(SYSTEM, o, idempotent);
        }
        JsonNode n = Json.tree(r.body());
        if (n == null || !n.isObject()) {
            return ConnectorResult.OutcomeUnknown.of("IFMS: unreadable PI answer (HTTP " + r.status() + ")");
        }
        String status = GovHttp.text(n, "status");
        if (!"ACCEPTED".equals(status)) {
            String code = GovHttp.text(n, "errorCode");
            return ConnectorResult.BusinessFailure.of(code == null ? "IFMS_" + status : code,
                    "IFMS refused the payment instruction: " + GovHttp.text(n, "message"));
        }
        String echoed = GovHttp.text(n, "operationId");
        if (echoed != null && !echoed.equals(request.operationId().toString())) {
            return ConnectorResult.OutcomeUnknown.of("IFMS answered for another operationId: " + echoed);
        }
        String paymentRef = GovHttp.text(n, "paymentRef");
        if (paymentRef == null || paymentRef.isBlank()) {
            return ConnectorResult.OutcomeUnknown.of("IFMS accepted the PI without a paymentRef");
        }
        boolean duplicate = n.path("duplicate").asBoolean(false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentRef", paymentRef);
        out.put("status", status);
        out.put("duplicate", duplicate);
        out.put("operationId", request.operationId().toString());
        return new ConnectorResult.Success(out, paymentRef, duplicate);
    }

    private static Map<String, Object> piParams(ConnectorRequest request) {
        String billId = request.required("billId");
        String amountText = request.required("amount");
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountText.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("amount must be a decimal string: " + amountText);
        }
        if (amount.signum() <= 0 || amount.scale() > 2) {
            throw new IllegalArgumentException("amount must be positive with at most 2 decimals: " + amountText);
        }
        Map<String, Object> b = request.map("beneficiary");
        String name = str(b, "name");
        String accountNo = str(b, "accountNo");
        String ifsc = str(b, "ifsc");
        if (name == null || accountNo == null || ifsc == null) {
            throw new IllegalArgumentException("beneficiary.name, beneficiary.accountNo and beneficiary.ifsc are required");
        }
        if (!IFSC.matcher(ifsc).matches()) {
            throw new IllegalArgumentException("beneficiary.ifsc is not a valid IFSC: " + ifsc);
        }
        Map<String, Object> beneficiary = new LinkedHashMap<>();
        beneficiary.put("name", name);
        beneficiary.put("accountNo", accountNo);
        beneficiary.put("ifsc", ifsc);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("operationId", request.operationId().toString());
        p.put("billId", billId);
        p.put("amount", amount.toPlainString());
        p.put("beneficiary", beneficiary);
        return p;
    }

    // ---- lookup (PD) ---------------------------------------------------------------------------

    @Override
    public LookupResult lookup(ConnectorRequest request) {
        if (config.capabilityClass() == CapabilityClass.C_NEITHER) {
            return new LookupResult.NotSupported();
        }
        try {
            return paymentStatus(request);
        } catch (RuntimeException e) {
            LOG.warn("IFMS PD for operation {} failed unexpectedly", request.operationId(), e);
            return new LookupResult.Unavailable("IFMS: unexpected " + e.getClass().getSimpleName());
        }
    }

    private LookupResult paymentStatus(ConnectorRequest request) {
        long deadline = GovHttp.deadline(request);
        Map<String, Object> body = Map.of("serviceCode", "PD",
                "params", Map.of("operationId", request.operationId().toString()));
        HttpTransport.Outcome o = authorizedReadCall(request.tenantId(), request, body, deadline);
        if (o == null) {
            return new LookupResult.Unavailable("IFMS authentication failed");
        }
        if (!(o instanceof HttpTransport.Outcome.Response r)) {
            return GovHttp.lookupFailure(SYSTEM, o);
        }
        if (r.status() == 400 && "PD_NOT_SUPPORTED".equals(GovHttp.errorCode(r, null))) {
            return new LookupResult.NotSupported();
        }
        if (!r.is2xx()) {
            return GovHttp.lookupFailure(SYSTEM, o);
        }
        JsonNode n = Json.tree(r.body());
        if (n == null || !n.isObject()) {
            return new LookupResult.Unavailable("IFMS: unreadable PD answer");
        }
        String status = String.valueOf(GovHttp.text(n, "status"));
        JsonNode payments = n.path("payments");
        JsonNode last = payments.isArray() && !payments.isEmpty() ? payments.get(payments.size() - 1) : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("paymentCount", payments.isArray() ? payments.size() : 0);
        if (last != null) {
            out.putAll(GovHttp.jsonMap(last));
        }
        String paymentRef = last == null ? null : GovHttp.text(last, "paymentRef");
        return switch (status) {
            case "NOT_FOUND" -> new LookupResult.NotFound();
            case "SUCCESS" -> LookupResult.Found.success(out, paymentRef);
            case "FAILED" -> {
                String reason = last == null ? null : GovHttp.text(last, "reason");
                yield new LookupResult.Found(false, out, paymentRef, reason == null ? "PAYMENT_FAILED" : reason);
            }
            default -> new LookupResult.Unavailable("IFMS reports status " + status);
        };
    }

    // ---- failures (FD) -------------------------------------------------------------------------

    /** FD feed of the last {@code sinceHours} hours, through platform egress rules (tenant {@code *}). */
    public List<FailureRecord> failures(int sinceHours) {
        return failures(PLATFORM_TENANT, sinceHours);
    }

    /**
     * Bank rejections of the last {@code sinceHours} hours (FD), oldest first as IFMS lists them.
     *
     * @throws FeedUnavailableException when the feed cannot be read
     */
    public List<FailureRecord> failures(String tenantId, int sinceHours) {
        if (sinceHours <= 0) {
            throw new IllegalArgumentException("sinceHours must be positive");
        }
        long deadline = clock.millis() + config.timeout().toMillis() * 2;
        Map<String, Object> body = Map.of("serviceCode", "FD", "params", Map.of("sinceHours", sinceHours));
        HttpTransport.Outcome o = authorizedReadCall(tenantId, null, body, deadline);
        if (o == null) {
            throw new FeedUnavailableException("IFMS authentication failed");
        }
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            throw new FeedUnavailableException(describe(o));
        }
        JsonNode n = Json.tree(r.body());
        if (n == null || !n.path("failures").isArray()) {
            throw new FeedUnavailableException("IFMS: unreadable FD answer");
        }
        List<FailureRecord> out = new ArrayList<>();
        for (JsonNode f : n.path("failures")) {
            String at = GovHttp.text(f, "at");
            Instant when;
            try {
                when = at == null ? null : Instant.parse(at);
            } catch (RuntimeException e) {
                when = null;
            }
            out.add(new FailureRecord(GovHttp.text(f, "paymentRef"), GovHttp.text(f, "operationId"),
                    GovHttp.text(f, "billId"), GovHttp.text(f, "amount"), GovHttp.text(f, "accountNo"),
                    GovHttp.text(f, "status"), GovHttp.text(f, "reason"), when));
        }
        return out;
    }

    // ---- health --------------------------------------------------------------------------------

    /** True when a token is cached or can be obtained (authentication has no effect). */
    @Override
    public boolean healthy() {
        Token t = token;
        if (t != null && clock.instant().isBefore(t.refreshAt())) {
            return true;
        }
        return authenticate(PLATFORM_TENANT, clock.millis() + 5_000, false).token() != null;
    }

    // ---- HTTP plumbing -------------------------------------------------------------------------

    /** Read-only data call (PD, FD) with token and one re-authentication on 401; null when there is no token. */
    private HttpTransport.Outcome authorizedReadCall(String tenantId, ConnectorRequest request, Map<String, Object> body,
                                                     long deadline) {
        Auth auth = authenticate(tenantId, deadline, false);
        if (auth.token() == null) {
            return null;
        }
        HttpTransport.Outcome o = post(tenantId, request, auth.token(), body, true, deadline);
        if (o instanceof HttpTransport.Outcome.Response r && r.status() == 401) {
            invalidate(auth.token());
            Auth again = authenticate(tenantId, deadline, true);
            if (again.token() == null) {
                return null;
            }
            o = post(tenantId, request, again.token(), body, true, deadline);
        }
        return o;
    }

    private HttpTransport.Outcome dataCall(ConnectorRequest request, String bearer, Map<String, Object> body,
                                           boolean idempotent, long deadline) {
        return post(request.tenantId(), request, bearer, body, idempotent, deadline);
    }

    private HttpTransport.Outcome post(String tenantId, ConnectorRequest request, String bearer,
                                       Map<String, Object> body, boolean idempotent, long deadline) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + bearer);
        Duration timeout = config.timeout();
        if (request != null) {
            headers.put("Idempotency-Key", request.operationId().toString());
            timeout = GovHttp.timeout(config.timeout(), request);
        }
        HttpTransport.Call call = new HttpTransport.Call("POST", GovHttp.join(config.baseUri(), DATA_PATH), headers,
                Json.bytes(body), timeout, idempotent);
        return config.transport().execute(tenantId, call, deadline);
    }

    /** Cached token, or a new one; {@code force} skips the cache. Never throws. */
    private Auth authenticate(String tenantId, long deadline, boolean force) {
        Token t = token;
        if (!force && t != null && clock.instant().isBefore(t.refreshAt())) {
            return new Auth(t.value(), null);
        }
        synchronized (authLock) {
            t = token;
            if (!force && t != null && clock.instant().isBefore(t.refreshAt())) {
                return new Auth(t.value(), null);
            }
            try {
                String clientId = config.secrets().require(config.clientIdRef());
                String secret = config.secrets().require(config.clientSecretRef());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("clientId", clientId);
                body.put("clientSecret", secret);
                HttpTransport.Call call = new HttpTransport.Call("POST", GovHttp.join(config.baseUri(), AUTH_PATH),
                        Map.of("Content-Type", "application/json", "Accept", "application/json"), Json.bytes(body),
                        config.timeout(), true);
                HttpTransport.Outcome o = config.transport().execute(tenantId, call, deadline);
                if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
                    return new Auth(null, describe(o));
                }
                JsonNode n = Json.tree(r.body());
                String value = GovHttp.text(n, "authToken");
                if (n == null || !"SUCCESS".equals(GovHttp.text(n, "status")) || value == null || value.isBlank()) {
                    return new Auth(null, "unexpected authenticate answer");
                }
                long ttl = Math.max(1, n.path("expiresIn").asLong(300));
                long margin = Math.min(60, ttl / 2);
                token = new Token(value, clock.instant().plusSeconds(ttl - margin));
                return new Auth(value, null);
            } catch (RuntimeException e) {
                return new Auth(null, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private void invalidate(String rejected) {
        synchronized (authLock) {
            Token t = token;
            if (t != null && t.value().equals(rejected)) {
                token = null;
            }
        }
    }

    private static String describe(HttpTransport.Outcome o) {
        return switch (o) {
            case HttpTransport.Outcome.NoEffect ne -> "not sent: " + ne.reason();
            case HttpTransport.Outcome.Unknown u -> "no answer: " + u.reason();
            case HttpTransport.Outcome.Response r -> "HTTP " + r.status()
                    + (GovHttp.errorCode(r, null) == null ? "" : " " + GovHttp.errorCode(r, null));
        };
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }
}
