// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.server.acceptance.AcceptanceIdp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Owner decision 380 on the real server (h2, the whole filter chain, hosting on with a test catalogue key behind two
 * secret references): the hosted index, its signature and the artefacts are read without credentials and without a
 * tenant, because a consumer's {@code sangam.modeller.catalogue.url} names them; every other catalogue route still
 * needs a Sangam token. Before SecurityConfig permitted them, all three public routes answered 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class HostedCatalogueSecurityH2Test {

    static final String PRIVATE_REF = "secret://catalogue/host-private-r6";
    static final String PUBLIC_REF = "secret://catalogue/host-public-r6";
    static final KeyPair KEY = key();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    static KeyPair key() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Secrets {
        /** The host's catalogue key pair behind its two references; every other reference stays with the environment. */
        @Bean
        @Primary
        SecretResolver hostedCatalogueTestSecrets() {
            return SecretResolver.chain(List.of(SecretResolver.of(Map.of(
                    PRIVATE_REF, Base64.getEncoder().encodeToString(KEY.getPrivate().getEncoded()),
                    PUBLIC_REF, Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded()))),
                    SecretResolver.fromEnvironment()));
        }
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
        r.add("sangam.modeller.catalogue.host.enabled", () -> "true");
        r.add("sangam.modeller.catalogue.host.signing-key-ref", () -> PRIVATE_REF);
        r.add("sangam.modeller.catalogue.host.public-key-ref", () -> PUBLIC_REF);
    }

    @LocalServerPort
    int port;

    HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String code(HttpResponse<String> r) {
        return r.body() == null || r.body().isBlank() ? "" : Json.tree(r.body()).path("code").asText();
    }

    @Test
    void theHostedIndexSignatureAndArtefactsNeedNoCredentialsAndNoTenant() throws Exception {
        HttpResponse<String> index = send("GET", "/api/v1/catalogue/hosted/index.json", null);
        assertEquals(200, index.statusCode(), index.body());
        assertTrue(index.headers().firstValue("content-type").orElse("").startsWith("application/json"), index.body());

        HttpResponse<String> sig = send("GET", "/api/v1/catalogue/hosted/index.json.sig", null);
        assertEquals(200, sig.statusCode(), sig.body());
        assertFalse(sig.body().isBlank(), "a detached signature over the index bytes");

        HttpResponse<String> artefact = send("GET", "/api/v1/catalogue/hosted/artifacts/no-such-entry/1.0.0", null);
        assertEquals(404, artefact.statusCode(), "the controller's answer, not the filter chain's 401: " + artefact.body());
        assertEquals("NOT_FOUND", code(artefact), artefact.body());
    }

    /**
     * Follow-up (catalogue-scoped problem advice): a refusal of the consumer catalogue controller answers through
     * CatalogueProblemAdvice like the host controller's, with the route itself as {@code instance}. Before, it went
     * through the servlet error dispatch and named {@code /error}.
     */
    @Test
    void aSignedInCallerGetsTheCatalogueProblemOfTheRouteItself() throws Exception {
        String token = AcceptanceIdp.get().token("dev", "r6-catalogue-reader", List.of("sangam-admin"));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/catalogue"))
                .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, res.statusCode(), res.body());
        assertTrue(res.headers().firstValue("content-type").orElse("").startsWith("application/problem+json"), res.body());
        assertEquals("NOT_FOUND", code(res), res.body());
        assertEquals("/api/v1/catalogue", Json.tree(res.body()).path("instance").asText(), res.body());
    }

    @Test
    void everyOtherCatalogueRouteStillNeedsAToken() throws Exception {
        for (String[] r : new String[][] {
                {"GET", "/api/v1/catalogue/entries", null},
                {"GET", "/api/v1/catalogue/entries?state=SUBMITTED", null},
                {"POST", "/api/v1/catalogue/entries", "{}"},
                {"POST", "/api/v1/catalogue/entries/x/1.0.0/approve", "{}"},
                {"POST", "/api/v1/catalogue/entries/x/1.0.0/reject", "{\"reason\":\"r\"}"},
                {"GET", "/api/v1/catalogue", null},
                {"POST", "/api/v1/catalogue/by-id/by-version/install?id=x&version=1.0.0", "{}"},
                // only GET is public, and only the three exact shapes
                {"POST", "/api/v1/catalogue/hosted/index.json", "{}"},
                {"GET", "/api/v1/catalogue/hosted/other", null},
                {"GET", "/api/v1/catalogue/hosted/artifacts/x/1.0.0/more", null}}) {
            HttpResponse<String> res = send(r[0], r[1], r[2]);
            assertEquals(401, res.statusCode(), r[0] + " " + r[1] + " without a token: " + res.body());
        }
    }
}
