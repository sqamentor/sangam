// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.pay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.integration.sim.SimHttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Generic government payment gateway simulator (R58 gateway adapters: NTRP / PayGov / GRAS-like). The JSON design
 * is this simulator's own; real gateway schemas are on request and only change the adapter mapping.
 *
 * <ul>
 *   <li>{@code POST /pg/v1/orders} {@code {orderId, amount, currency, purpose, payerRef, returnUrl}} ({@code orderId}
 *       = operationId, {@code amount} a decimal string) → 200 {@code {orderId, pgRef, status, amount, currency,
 *       duplicate}} with status {@code CREATED | PAID | FAILED}. Idempotent by orderId: the same order again → 200
 *       with the current state and {@code duplicate=true}; the same orderId with a different amount, currency or
 *       payerRef → 409 {@code ORDER_ID_REUSED}. Validation errors → 400.</li>
 *   <li>{@code GET /pg/v1/orders/{orderId}} → 200 with the current state, or 404 {@code ORDER_NOT_FOUND}.</li>
 * </ul>
 * Settlement: with {@link #settleImmediately(boolean)} on (default) an order settles while it is created; otherwise
 * {@link #settle(String)} settles it later. {@link #failOrder(String)} makes that order's settlement fail.
 *
 * <p>Callbacks (R27 "authenticate callbacks"): when {@link #callback(URI, String)} is configured, every settlement
 * is POSTed asynchronously as JSON {@code {orderId, pgRef, status, amount, currency, settledAt}} with header
 * {@code X-PG-Signature: t=<epochSeconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 *
 * <p>Ledger types: {@code PG_ORDER} (order created, key = orderId), {@code PG_PAID} (money collected, key =
 * orderId) and {@code PG_FAILED} (settlement failed, no money moved).
 */
public final class PaymentGatewaySimulator extends SimHttpServer {

    public static final String ORDERS_PATH = "/pg/v1/orders";
    public static final String ORDER_PREFIX = ORDERS_PATH + "/";
    public static final String SIGNATURE_HEADER = "X-PG-Signature";
    public static final String EFFECT_ORDER = "PG_ORDER";
    public static final String EFFECT_PAID = "PG_PAID";
    public static final String EFFECT_FAILED = "PG_FAILED";

    /** One order held by the gateway. */
    public record Order(String orderId, String pgRef, BigDecimal amount, String currency, String purpose,
                        String payerRef, String returnUrl, String status, Instant createdAt, Instant settledAt) {
        Order settled(String newStatus, Instant at) {
            return new Order(orderId, pgRef, amount, currency, purpose, payerRef, returnUrl, newStatus, createdAt, at);
        }
    }

    /** One callback delivery attempt, for assertions. */
    public record CallbackDelivery(String orderId, String status, String body, String signatureHeader,
                                   int httpStatus, String error, Instant at) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final Set<String> failingOrders = ConcurrentHashMap.newKeySet();
    private final List<CallbackDelivery> deliveries = new CopyOnWriteArrayList<>();
    private final AtomicLong refSeq = new AtomicLong();
    private final ExecutorService callbackExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile boolean settleImmediately = true;
    private volatile URI callbackUrl;
    private volatile byte[] callbackSecret;
    private volatile Clock clock = Clock.systemUTC();

    public PaymentGatewaySimulator() {
        super("payment-gateway");
        route("POST", ORDERS_PATH, this::create);
        prefixRoute("GET", ORDER_PREFIX, this::get);
    }

    // ---- configuration -------------------------------------------------------------------------

    public PaymentGatewaySimulator settleImmediately(boolean on) {
        this.settleImmediately = on;
        return this;
    }

    /** The settlement of this order fails (the payer's bank declines). */
    public PaymentGatewaySimulator failOrder(String orderId) {
        failingOrders.add(orderId);
        return this;
    }

    /** Send settlement callbacks to {@code url}, signed with {@code secret}; null url turns callbacks off. */
    public PaymentGatewaySimulator callback(URI url, String secret) {
        this.callbackSecret = secret == null ? null : secret.getBytes(StandardCharsets.UTF_8);
        this.callbackUrl = url;
        return this;
    }

    public PaymentGatewaySimulator clock(Clock c) {
        this.clock = c;
        return this;
    }

    public Optional<Order> order(String orderId) {
        synchronized (orders) {
            return Optional.ofNullable(orders.get(orderId));
        }
    }

    public List<CallbackDelivery> callbackDeliveries() {
        return List.copyOf(deliveries);
    }

    /**
     * Settles a CREATED order now (PAID, or FAILED for {@link #failOrder(String)}) and sends the callback. Returns the
     * resulting status; an already settled order keeps its status and sends no callback.
     */
    public String settle(String orderId) {
        Order settled;
        synchronized (orders) {
            Order o = orders.get(orderId);
            if (o == null) {
                throw new IllegalArgumentException("unknown order " + orderId);
            }
            if (!"CREATED".equals(o.status())) {
                return o.status();
            }
            settled = settleLocked(o);
        }
        sendCallback(settled);
        return settled.status();
    }

    /** HMAC-SHA256 signature value as sent in {@value #SIGNATURE_HEADER}: {@code t=<t>,v1=<hex>}. */
    public static String signatureHeader(byte[] secret, long epochSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] sig = mac.doFinal((epochSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return "t=" + epochSeconds + ",v1=" + HexFormat.of().formatHex(sig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        super.close();
        callbackExecutor.shutdownNow();
    }

    // ---- routes --------------------------------------------------------------------------------

    private Response create(Request req) {
        JsonNode b;
        try {
            b = JSON.readTree(req.body());
        } catch (Exception e) {
            b = null;
        }
        if (b == null || !b.isObject()) {
            return error(400, "BAD_REQUEST", "body must be a JSON object");
        }
        String orderId = b.path("orderId").asText("");
        String currency = b.path("currency").asText("");
        String payerRef = b.path("payerRef").asText("");
        if (orderId.isBlank()) {
            return error(400, "MISSING_FIELD", "orderId is required");
        }
        if (!CURRENCY.matcher(currency).matches()) {
            return error(400, "INVALID_CURRENCY", "currency must be an ISO 4217 code");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(b.path("amount").asText());
        } catch (NumberFormatException e) {
            return error(400, "INVALID_AMOUNT", "amount must be a decimal string");
        }
        if (amount.signum() <= 0 || amount.scale() > 2) {
            return error(400, "INVALID_AMOUNT", "amount must be positive with at most 2 decimals");
        }
        Order result;
        boolean duplicate;
        boolean notify = false;
        synchronized (orders) {
            Order existing = orders.get(orderId);
            if (existing != null) {
                if (existing.amount().compareTo(amount) != 0 || !existing.currency().equals(currency)
                        || !existing.payerRef().equals(payerRef)) {
                    return error(409, "ORDER_ID_REUSED", "orderId already used with a different payload");
                }
                result = existing;
                duplicate = true;
            } else {
                Order o = new Order(orderId, "PG" + String.format("%08d", refSeq.incrementAndGet()), amount, currency,
                        b.path("purpose").asText(""), payerRef, b.path("returnUrl").asText(""), "CREATED",
                        clock.instant(), null);
                orders.put(orderId, o);
                ledger().record(EFFECT_ORDER, orderId, Map.of("pgRef", o.pgRef(), "amount", amount.toPlainString(),
                        "currency", currency, "payerRef", payerRef));
                if (settleImmediately) {
                    o = settleLocked(o);
                    notify = true;
                }
                result = o;
                duplicate = false;
            }
        }
        if (notify) {
            sendCallback(result);
        }
        return Response.json(200, orderJson(result, duplicate).toString());
    }

    private Response get(Request req) {
        String orderId = req.pathAfter(ORDER_PREFIX);
        Optional<Order> o = orderId == null ? Optional.empty() : order(orderId);
        if (o.isEmpty()) {
            return error(404, "ORDER_NOT_FOUND", "unknown order " + orderId);
        }
        return Response.json(200, orderJson(o.get(), false).toString());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Order settleLocked(Order o) {
        boolean fails = failingOrders.contains(o.orderId());
        Order s = o.settled(fails ? "FAILED" : "PAID", clock.instant());
        orders.put(o.orderId(), s);
        ledger().record(fails ? EFFECT_FAILED : EFFECT_PAID, o.orderId(),
                Map.of("pgRef", s.pgRef(), "amount", s.amount().toPlainString(), "currency", s.currency()));
        return s;
    }

    private void sendCallback(Order o) {
        URI url = callbackUrl;
        byte[] secret = callbackSecret;
        if (url == null || secret == null) {
            return;
        }
        ObjectNode n = JSON.createObjectNode();
        n.put("orderId", o.orderId());
        n.put("pgRef", o.pgRef());
        n.put("status", o.status());
        n.put("amount", o.amount().toPlainString());
        n.put("currency", o.currency());
        n.put("settledAt", String.valueOf(o.settledAt()));
        String body = n.toString();
        String sig = signatureHeader(secret, clock.instant().getEpochSecond(), body);
        callbackExecutor.execute(() -> {
            int status = -1;
            String err = null;
            try {
                HttpRequest r = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header(SIGNATURE_HEADER, sig)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
                status = http.send(r, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                err = "interrupted";
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            deliveries.add(new CallbackDelivery(o.orderId(), o.status(), body, sig, status, err, Instant.now()));
        });
    }

    private static ObjectNode orderJson(Order o, boolean duplicate) {
        ObjectNode n = JSON.createObjectNode();
        n.put("orderId", o.orderId());
        n.put("pgRef", o.pgRef());
        n.put("status", o.status());
        n.put("amount", o.amount().toPlainString());
        n.put("currency", o.currency().toUpperCase(Locale.ROOT));
        n.put("duplicate", duplicate);
        n.put("createdAt", o.createdAt().toString());
        if (o.settledAt() != null) {
            n.put("settledAt", o.settledAt().toString());
        }
        return n;
    }

    private static Response error(int status, String code, String msg) {
        ObjectNode out = JSON.createObjectNode();
        out.put("status", "ERROR");
        out.put("errorCode", code);
        out.put("message", msg);
        return Response.json(status, out.toString());
    }
}
