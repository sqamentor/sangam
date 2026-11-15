// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.gov.DltSmsConnector;
import org.sangam.integration.publish.WebhookSignature;
import org.sangam.server.acceptance.AcceptanceIdp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Owner decisions 304 and 305 on the real server (h2, the whole filter chain, the simulator's DLT SMS connector
 * {@code sms-dlt-sim}).
 * <ul>
 *   <li>The delivery-report hook is reachable without a bearer token, but only with a valid signature: a signed report
 *       is taken (200) and writes NOTIFICATION_DELIVERED once; without a signature, or with a wrong one, the answer is
 *       the controller's 401 WEBHOOK_SIGNATURE_INVALID. Nothing else under the hook path is opened: another method or
 *       another path there still needs a Sangam token.</li>
 *   <li>The registration routes need a Sangam token and the permission: no token 401, a signed-in user without
 *       {@code dlt.registration.*} 403, a tenant administrator 200; a registered template writes
 *       DLT_TEMPLATE_REGISTERED.</li>
 * </ul>
 * Before the wiring every one of these routes answered 401 or 404: the contract route had no controller and no matcher.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class DltRoutesSecurityH2Test {

    static final String TENANT = "dev";
    static final String CONNECTOR = "sms-dlt-sim";
    static final String SECRET_REF = "secret://dlt/sim-webhook";
    static final String SECRET = "delivery-report-signing-value-of-this-test";
    static final String HOOK = "/api/v1/hooks/sms-dlt/" + TENANT + "/" + CONNECTOR + "/delivery-reports";
    static final String REGISTRATION = "/api/v1/admin/dlt-registration";
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @TestConfiguration(proxyBeanMethods = false)
    static class Secrets {
        /** The provider's signing secret behind its reference; every other reference stays with the environment. */
        @Bean
        @Primary
        SecretResolver dltTestSecrets() {
            return SecretResolver.chain(List.of(SecretResolver.of(Map.of(SECRET_REF, SECRET)), SecretResolver.fromEnvironment()));
        }
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
        r.add("sangam.integration.dlt.delivery-reports.secrets.sim.connector", () -> CONNECTOR);
        r.add("sangam.integration.dlt.delivery-reports.secrets.sim.secret-ref", () -> SECRET_REF);
    }

    @LocalServerPort
    int port;
    @Autowired
    Environment env;
    @Autowired
    DataSource dataSource;
    @Autowired
    ConnectorCallJournal journal;

    HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String signature(String body, String secret) {
        return WebhookSignature.header(Instant.now().getEpochSecond(), body.getBytes(StandardCharsets.UTF_8), List.of(secret));
    }

    static String code(HttpResponse<String> r) {
        return r.body() == null || r.body().isBlank() ? "" : Json.tree(r.body()).path("code").asText();
    }

    String adminToken() {
        return "Bearer " + env.getRequiredProperty("sangam.security.dev-token.token");
    }

    String plainUserToken() {
        return "Bearer " + AcceptanceIdp.get().token(TENANT, "dlt-plain-user", List.of("sangam-user"));
    }

    long events(String type) {
        return Transactions.of(dataSource).inTransaction(c -> Jdbc.query(c,
                "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? AND event_type=?", rs -> rs.getString(1), TENANT, type)).size();
    }

    /** An accepted send through the simulator connector: its CONNECTOR_CALL row, as the invoker leaves it. */
    UUID sent() {
        UUID op = UUID.randomUUID();
        Transactions.of(dataSource).inTransaction(c -> {
            journal.recordAttempt(c, op, TENANT, CONNECTOR, DltSmsConnector.OP_SEND, "case-" + op, "notice:1",
                    "{\"templateId\":\"1107000000000000001\",\"mobile\":\"9876543210\"}");
            journal.recordResult(c, op, ConnectorCallJournal.LastResult.SUCCESS, "msg-" + op, null);
            return null;
        });
        return op;
    }

    @Test
    void theHookIsReachableWithoutABearerTokenButOnlyWithAValidSignature() throws Exception {
        UUID op = sent();
        String body = "{\"reportId\":\"r-" + op + "\",\"clientRef\":\"" + op + "\",\"status\":\"DELIVERED\"}";
        long before = events("NOTIFICATION_DELIVERED");

        HttpResponse<String> unsigned = send("POST", HOOK, body);
        assertEquals(401, unsigned.statusCode(), unsigned.body());
        assertEquals("WEBHOOK_SIGNATURE_INVALID", code(unsigned), "the controller's refusal, not the filter chain's");
        assertTrue(unsigned.headers().firstValue("content-type").orElse("").startsWith("application/problem+json"));
        HttpResponse<String> wrong = send("POST", HOOK, body, WebhookSignature.HEADER, signature(body, "another-value"));
        assertEquals(401, wrong.statusCode(), wrong.body());
        assertEquals("WEBHOOK_SIGNATURE_INVALID", code(wrong));
        assertEquals(before, events("NOTIFICATION_DELIVERED"), "a refused report writes nothing");

        HttpResponse<String> signed = send("POST", HOOK, body, WebhookSignature.HEADER, signature(body, SECRET));
        assertEquals(200, signed.statusCode(), "no bearer token, a valid signature: " + signed.body());
        JsonNode answer = Json.tree(signed.body());
        assertEquals(1, answer.path("accepted").asInt(), signed.body());
        assertEquals(before + 1, events("NOTIFICATION_DELIVERED"));

        HttpResponse<String> replay = send("POST", HOOK, body, WebhookSignature.HEADER, signature(body, SECRET));
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(1, Json.tree(replay.body()).path("duplicates").asInt(), replay.body());
        assertEquals(before + 1, events("NOTIFICATION_DELIVERED"), "the same report again writes nothing");
    }

    @Test
    void nothingElseUnderTheHookPathIsOpened() throws Exception {
        String body = "{\"clientRef\":\"x\",\"status\":\"DELIVERED\"}";
        String signature = signature(body, SECRET);
        HttpResponse<String> otherPath = send("POST", "/api/v1/hooks/sms-dlt/" + TENANT + "/" + CONNECTOR + "/other", body,
                WebhookSignature.HEADER, signature);
        assertEquals(401, otherPath.statusCode(), otherPath.body());
        assertNotEquals("WEBHOOK_SIGNATURE_INVALID", code(otherPath), "refused by the filter chain: a Sangam token is needed");
        HttpResponse<String> deeper = send("POST", HOOK + "/more", body, WebhookSignature.HEADER, signature);
        assertEquals(401, deeper.statusCode(), deeper.body());
        HttpResponse<String> otherMethod = send("GET", HOOK, null, WebhookSignature.HEADER, signature);
        assertEquals(401, otherMethod.statusCode(), "only POST is public: " + otherMethod.body());
    }

    @Test
    void theRegistrationRoutesNeedATokenAndThePermission() throws Exception {
        assertEquals(401, send("GET", REGISTRATION, null).statusCode(), "no token");
        String template = "{\"templateId\":\"1107000000000000009\",\"header\":\"GOVSVC\",\"body\":\"Application {#var#} received - GOVSVC\"}";
        assertEquals(401, send("POST", REGISTRATION + "/templates", template, "Idempotency-Key", UUID.randomUUID().toString())
                .statusCode(), "no token");

        HttpResponse<String> plainRead = send("GET", REGISTRATION, null, "Authorization", plainUserToken());
        assertEquals(403, plainRead.statusCode(), "a signed-in user without dlt.registration.read: " + plainRead.body());
        long before = events("DLT_TEMPLATE_REGISTERED");
        for (String[] change : new String[][] {
                {"PUT", "/entity", "{\"entityId\":\"1101000000000000001\"}"},
                {"POST", "/headers", "{\"header\":\"GOVSVC\"}"},
                {"POST", "/templates", template},
                {"POST", "/urls", "{\"prefix\":\"https://services.example.gov.in/\"}"}}) {
            HttpResponse<String> refused = send(change[0], REGISTRATION + change[1], change[2], "Authorization",
                    plainUserToken(), "Idempotency-Key", UUID.randomUUID().toString());
            assertEquals(403, refused.statusCode(), change[1] + " without dlt.registration.write: " + refused.body());
        }
        assertEquals(before, events("DLT_TEMPLATE_REGISTERED"), "a refused change writes nothing");

        HttpResponse<String> registered = send("POST", REGISTRATION + "/templates", template, "Authorization", adminToken(),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(200, registered.statusCode(), registered.body());
        assertTrue(Json.tree(registered.body()).path("changed").asBoolean(), registered.body());
        assertEquals(before + 1, events("DLT_TEMPLATE_REGISTERED"), "written with the template");

        HttpResponse<String> read = send("GET", REGISTRATION, null, "Authorization", adminToken());
        assertEquals(200, read.statusCode(), read.body());
        assertTrue(read.body().contains("1107000000000000009"), read.body());

        HttpResponse<String> invalid = send("POST", REGISTRATION + "/headers", "{\"header\":\"no\"}", "Authorization",
                adminToken(), "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(422, invalid.statusCode(), invalid.body());
        assertEquals("HEADER_INVALID", code(invalid));
    }
}
