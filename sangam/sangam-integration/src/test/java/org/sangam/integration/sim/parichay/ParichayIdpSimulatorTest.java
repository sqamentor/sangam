// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.parichay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.http.Jwt;

/** M-077 (W3-D request): the Parichay OIDC simulator's discovery, code exchange and ID token. */
class ParichayIdpSimulatorTest {

    private ParichayIdpSimulator sim;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() {
        sim = new ParichayIdpSimulator().start();
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    private HttpResponse<String> token(String code, String secret) throws Exception {
        String form = "grant_type=authorization_code&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + "&client_id=" + ParichayIdpSimulator.CLIENT_ID + "&client_secret=" + secret;
        return http.send(HttpRequest.newBuilder(sim.uri("/token")).header("Content-Type",
                "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void discoveryNamesTheEndpointsAndRs256() throws Exception {
        JsonNode d = Json.MAPPER.readTree(http.send(HttpRequest.newBuilder(sim.uri("/.well-known/openid-configuration"))
                .build(), HttpResponse.BodyHandlers.ofString()).body());
        assertEquals(sim.issuer(), d.path("issuer").asText());
        assertEquals(sim.uri("/token").toString(), d.path("token_endpoint").asText());
        assertEquals("RS256", d.path("id_token_signing_alg_values_supported").path(0).asText());
        JsonNode jwks = Json.MAPPER.readTree(http.send(HttpRequest.newBuilder(URI.create(d.path("jwks_uri").asText()))
                .build(), HttpResponse.BodyHandlers.ofString()).body());
        assertEquals(ParichayIdpSimulator.KEY_ID, jwks.path("keys").path(0).path("kid").asText());
    }

    @Test
    void aCodeGivesAVerifiableIdTokenWithTheFixtureClaimsOnce() throws Exception {
        String code = sim.login(null, "n-1");
        HttpResponse<String> r = token(code, ParichayIdpSimulator.CLIENT_SECRET);
        assertEquals(200, r.statusCode());
        String idToken = Json.MAPPER.readTree(r.body()).path("id_token").asText();
        JsonNode claims = Jwt.verifyRs256(idToken, sim.publicKey()).orElseThrow();
        assertEquals(sim.issuer(), claims.path("iss").asText());
        assertEquals(ParichayIdpSimulator.CLIENT_ID, claims.path("aud").asText());
        assertEquals("n-1", claims.path("nonce").asText());
        for (String c : ParichayIdpSimulator.FIXTURE_CLAIMS.keySet()) {
            assertTrue(claims.has(c), c);
        }
        assertEquals(400, token(code, ParichayIdpSimulator.CLIENT_SECRET).statusCode(), "a code works once");
    }

    @Test
    void aWrongClientSecretIsRefused() throws Exception {
        assertEquals(401, token(sim.login(null, null), "wrong").statusCode());
    }
}
