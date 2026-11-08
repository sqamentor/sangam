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
import org.sangam.integration.sim.pay.PaymentGatewaySimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Government payment gateway adapter (R58 gateway adapters NTRP / PayGov / GRAS; IX.4 P4 fees and P1 receipts), in
 * the generic shape of {@link PaymentGatewaySimulator}; a concrete gateway changes only this mapping.
 *
 * <p><b>Operation {@code createOrder}</b>: input {@code amount} (decimal string, R33), optional {@code currency}
 * (default from config), {@code purpose}, {@code payerRef}, {@code returnUrl}. The orderId is the operationId (R36),
 * also sent as {@code Idempotency-Key}. Output {@code orderId}, {@code pgRef}, {@code status}
 * ({@code CREATED | PAID | FAILED}), {@code duplicate}. The final payment status arrives by signed callback
 * ({@link PaymentGatewayCallbackVerifier}) or by lookup.
 *
 * <p><b>Capability class (a)</b>: the gateway de-duplicates by orderId (a repeat answers {@code duplicate=true};
 * the same orderId with another amount is refused with 409), and {@code GET /pg/v1/orders/{orderId}} looks the order
 * up: {@code PAID} → found, succeeded; {@code FAILED} → found, failed; {@code CREATED} → {@link LookupResult.Unavailable}
 * ("pending": the payer has not completed payment yet); 404 → {@link LookupResult.NotFound}.
 */
public final class PaymentGatewayConnector implements SangamConnector {

    public static final String TYPE = "payment-gateway";
    public static final String OP_CREATE_ORDER = "createOrder";
    public static final String ORDERS_PATH = "/pg/v1/orders";

    private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayConnector.class);
    private static final String SYSTEM = "payment gateway";
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    /**
     * Configuration of one gateway merchant account.
     *
     * @param id               connector instance id
     * @param baseUri          gateway base URI
     * @param apiKeyRef        merchant API key reference (sent as bearer token); null = none
     * @param secrets          resolver for the key reference
     * @param transport        HTTP transport
     * @param profile          live or simulator
     * @param defaultCurrency  ISO 4217 currency when the input has none; null = INR
     * @param defaultReturnUrl return URL when the input has none; may be null
     * @param timeout          per-request timeout; null = 30 s
     */
    public record Config(String id, URI baseUri, String apiKeyRef, SecretResolver secrets, HttpTransport transport,
                         ConnectorProfile profile, String defaultCurrency, URI defaultReturnUrl, Duration timeout) {
        public Config {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(transport, "transport");
            secrets = secrets == null ? SecretResolver.fromEnvironment() : secrets;
            profile = profile == null ? ConnectorProfile.LIVE : profile;
            defaultCurrency = defaultCurrency == null ? "INR" : defaultCurrency.toUpperCase(Locale.ROOT);
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }

    private final Config config;
    private final ConnectorDescriptor descriptor;

    public PaymentGatewayConnector(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.descriptor = new ConnectorDescriptor(config.id(), TYPE, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of(OP_CREATE_ORDER), config.profile());
    }

    /** Wired to the payment gateway simulator of {@code sims}. */
    public static PaymentGatewayConnector simulator(SimulatorProfile sims) {
        PaymentGatewaySimulator sim = sims.paymentGateway();
        return new PaymentGatewayConnector(new Config("payment-gateway-sim", sim.baseUri(), null,
                SecretResolver.of(Map.of()), HttpTransport.forSimulator(), ConnectorProfile.SIMULATOR, "INR", null,
                Duration.ofSeconds(10)));
    }

    public Config config() {
        return config;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        if (!OP_CREATE_ORDER.equals(request.operation())) {
            return ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION",
                    "payment gateway supports only '" + OP_CREATE_ORDER + "', got '" + request.operation() + "'");
        }
        Map<String, Object> body;
        try {
            body = orderBody(request);
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_INPUT", e.getMessage());
        }
        try {
            return create(request, body);
        } catch (RuntimeException e) {
            LOG.warn("payment order for operation {} failed unexpectedly", request.operationId(), e);
            return ConnectorResult.OutcomeUnknown.of("payment gateway: unexpected " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private Map<String, Object> orderBody(ConnectorRequest request) {
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
        String currency = request.str("currency", config.defaultCurrency()).toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code: " + currency);
        }
        String returnUrl = request.str("returnUrl",
                config.defaultReturnUrl() == null ? "" : config.defaultReturnUrl().toString());
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("orderId", request.operationId().toString());
        b.put("amount", amount.toPlainString());
        b.put("currency", currency);
        b.put("purpose", request.str("purpose", ""));
        b.put("payerRef", request.str("payerRef", ""));
        b.put("returnUrl", returnUrl);
        return b;
    }

    private ConnectorResult create(ConnectorRequest request, Map<String, Object> body) {
        Map<String, String> headers;
        try {
            headers = headers(request, true);
        } catch (IllegalStateException e) {
            return ConnectorResult.TransientFailure.noEffect("payment gateway: " + e.getMessage());
        }
        HttpTransport.Call call = new HttpTransport.Call("POST", GovHttp.join(config.baseUri(), ORDERS_PATH),
                headers, Json.bytes(body), GovHttp.timeout(config.timeout(), request), true);
        HttpTransport.Outcome o = config.transport().execute(request.tenantId(), call, GovHttp.deadline(request));
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            return GovHttp.failure(SYSTEM, o, true);
        }
        JsonNode n = Json.tree(r.body());
        if (n == null || !n.isObject()) {
            return ConnectorResult.OutcomeUnknown.of("payment gateway: unreadable answer (HTTP " + r.status() + ")");
        }
        String orderId = GovHttp.text(n, "orderId");
        if (!request.operationId().toString().equals(orderId)) {
            return ConnectorResult.OutcomeUnknown.of("payment gateway answered for another order: " + orderId);
        }
        String status = GovHttp.text(n, "status");
        String pgRef = GovHttp.text(n, "pgRef");
        if (status == null || pgRef == null || !List.of("CREATED", "PAID", "FAILED").contains(status)) {
            return ConnectorResult.OutcomeUnknown.of("payment gateway: unexpected answer status " + status);
        }
        boolean duplicate = n.path("duplicate").asBoolean(false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("pgRef", pgRef);
        out.put("status", status);
        out.put("duplicate", duplicate);
        String amount = GovHttp.text(n, "amount");
        if (amount != null) {
            out.put("amount", amount);
        }
        return new ConnectorResult.Success(out, pgRef, duplicate);
    }

    @Override
    public LookupResult lookup(ConnectorRequest request) {
        try {
            String orderId = request.operationId().toString();
            URI uri = URI.create(GovHttp.join(config.baseUri(), ORDERS_PATH) + "/"
                    + URLEncoder.encode(orderId, StandardCharsets.UTF_8));
            HttpTransport.Call call = new HttpTransport.Call("GET", uri, headers(request, false), null,
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
            JsonNode n = Json.tree(r.body());
            if (n == null || !n.isObject() || !orderId.equals(GovHttp.text(n, "orderId"))) {
                return new LookupResult.Unavailable("payment gateway: unreadable order answer");
            }
            Map<String, Object> out = GovHttp.jsonMap(n);
            String pgRef = GovHttp.text(n, "pgRef");
            String status = String.valueOf(GovHttp.text(n, "status"));
            return switch (status) {
                case "PAID" -> LookupResult.Found.success(out, pgRef);
                case "FAILED" -> new LookupResult.Found(false, out, pgRef, "PAYMENT_FAILED");
                case "CREATED" -> new LookupResult.Unavailable("pending");
                default -> new LookupResult.Unavailable("payment gateway status " + status);
            };
        } catch (RuntimeException e) {
            LOG.warn("payment order lookup for operation {} failed unexpectedly", request.operationId(), e);
            return new LookupResult.Unavailable("payment gateway: unexpected " + e.getClass().getSimpleName());
        }
    }

    private Map<String, String> headers(ConnectorRequest request, boolean withBody) {
        Map<String, String> h = new LinkedHashMap<>();
        if (withBody) {
            h.put("Content-Type", "application/json");
            h.put("Idempotency-Key", request.operationId().toString());
        }
        h.put("Accept", "application/json");
        if (config.apiKeyRef() != null) {
            h.put("Authorization", "Bearer " + config.secrets().require(config.apiKeyRef()));
        }
        return h;
    }
}
