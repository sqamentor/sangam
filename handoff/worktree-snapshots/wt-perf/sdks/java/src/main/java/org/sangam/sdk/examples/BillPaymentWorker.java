// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.examples;

import org.sangam.sdk.SangamClient;
import org.sangam.sdk.auth.ClientCredentialsTokenProvider;
import org.sangam.sdk.auth.TokenProvider;
import org.sangam.sdk.model.TypedValue;
import org.sangam.sdk.worker.BpmnError;
import org.sangam.sdk.worker.OutcomeUnknownException;
import org.sangam.sdk.worker.SangamWorker;
import org.sangam.sdk.worker.TaskContext;
import org.sangam.sdk.worker.TaskFailedException;
import org.sangam.sdk.worker.TopicSubscription;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * P1 bill/payment example worker (IX.4 P1, AC-01, AC-03) for the {@code payment} topic of the bill model
 * ({@code wf1_payment}, "Payment instruction").
 *
 * <p>It posts a payment instruction to the treasury/payment gateway with {@code Idempotency-Key = operationId}
 * (R36), so a retried attempt can never pay twice. A connect failure is safe to retry (nothing was sent); a
 * timeout after sending is reported as {@code outcome-unknown} (AC-03: payment success with lost response →
 * UNKNOWN_OUTCOME → reconciliation). A rejection by the gateway is a BPMN error {@code PAYMENT_REJECTED}.
 *
 * <p>Environment: {@code SANGAM_URL} (default http://localhost:8080), either {@code SANGAM_TOKEN} or
 * {@code SANGAM_OIDC_TOKEN_URL} + {@code SANGAM_CLIENT_ID} + {@code SANGAM_CLIENT_SECRET}, optional
 * {@code SANGAM_TOPIC} (default {@code payment}) and {@code TREASURY_URL} (unset = built-in simulator).
 */
public final class BillPaymentWorker {

    private BillPaymentWorker() {
    }

    public static void main(String[] args) throws Exception {
        String topic = env("SANGAM_TOPIC", "payment");
        SangamClient client = SangamClient.builder()
                .baseUrl(env("SANGAM_URL", "http://localhost:8080"))
                .tokenProvider(tokenProvider())
                .build();
        String treasury = System.getenv("TREASURY_URL");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        SangamWorker worker = SangamWorker.builder(client)
                .workerId(env("SANGAM_WORKER_ID", null))
                .maxConcurrency(4)
                .subscribe(TopicSubscription.topic(topic, ctx -> pay(ctx, http, treasury))
                        .lockDuration(Duration.ofMinutes(2))
                        .retries(3)
                        // a payment is irreversible: unexpected errors become outcome-unknown, never a blind retry
                        .irreversible(true)
                        .build())
                .build();
        worker.run();
    }

    static Map<String, Object> pay(TaskContext ctx, HttpClient http, String treasuryUrl) throws Exception {
        BigDecimal amount = ctx.variable("amount", BigDecimal.class);
        String beneficiary = ctx.variable("beneficiaryAccount", String.class);
        String billNo = ctx.businessKey();
        if (amount == null || amount.signum() <= 0) {
            throw new BpmnError("PAYMENT_REJECTED", "bill " + billNo + " has no positive amount");
        }
        if (beneficiary == null || beneficiary.isBlank()) {
            throw new BpmnError("PAYMENT_REJECTED", "bill " + billNo + " has no beneficiary account");
        }
        ctx.throwIfCancelled();
        ctx.logger().info("p1.payment.instruct", "sending payment instruction",
                Map.of("amount", amount.toPlainString()));

        if (treasuryUrl == null) {
            // simulator: deterministic reference derived from the operationId (same op -> same reference)
            return Map.of("paymentStatus", "ACKNOWLEDGED",
                    "paymentReference", "SIM-" + ctx.operationId().substring(0, 8),
                    "paidAmount", TypedValue.decimal(amount));
        }

        String body = ctx.client().transport().json().writeValueAsString(Map.of(
                "billNumber", billNo, "amount", amount.toPlainString(), "beneficiaryAccount", beneficiary,
                "operationId", ctx.operationId()));
        HttpRequest req = HttpRequest.newBuilder(URI.create(treasuryUrl + "/payment-instructions"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", ctx.idempotencyKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            throw new TaskFailedException("treasury unreachable (nothing sent)", e);
        } catch (HttpTimeoutException e) {
            throw new OutcomeUnknownException("treasury did not answer in time; payment may have been made",
                    "POST " + treasuryUrl + "/payment-instructions", Map.of("billNumber", billNo), e);
        }
        int s = resp.statusCode();
        if (s == 200 || s == 201) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ack = ctx.client().transport().json().readValue(resp.body(), Map.class);
            return Map.of("paymentStatus", "ACKNOWLEDGED",
                    "paymentReference", String.valueOf(ack.getOrDefault("reference", "")),
                    "paidAmount", TypedValue.decimal(amount));
        }
        if (s == 422 || s == 400) {
            throw new BpmnError("PAYMENT_REJECTED", "treasury rejected the instruction: " + resp.body(),
                    Map.of("paymentStatus", "REJECTED"));
        }
        if (s >= 500) {
            throw new OutcomeUnknownException("treasury answered " + s + "; outcome unknown", resp.body(), null, null);
        }
        throw new TaskFailedException("unexpected treasury status " + s);
    }

    private static TokenProvider tokenProvider() {
        String tokenUrl = System.getenv("SANGAM_OIDC_TOKEN_URL");
        if (tokenUrl != null) {
            return ClientCredentialsTokenProvider.builder()
                    .tokenEndpoint(tokenUrl)
                    .clientId(System.getenv("SANGAM_CLIENT_ID"))
                    .clientSecret(System.getenv("SANGAM_CLIENT_SECRET"))
                    .scope(System.getenv("SANGAM_SCOPE"))
                    .build();
        }
        String token = System.getenv("SANGAM_TOKEN");
        return token != null ? TokenProvider.of(token) : TokenProvider.none();
    }

    private static String env(String name, String dflt) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? dflt : v;
    }
}
