// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.sangam.server.acceptance.AcceptanceIdp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SL-138 / M-006 end to end on the server (h2, OIDC decoder on): an EHR calls {@code /cds-services} with a JWT signed
 * by its own key. Before the fix the route was {@code authenticated()}: the Sangam decoder did not know the EHR's
 * issuer and the call ended with 401 before the controller, so CDS Hooks could never work with OIDC on. Now the
 * filter chain lets it through and the controller's {@code CdsClientVerifier} decides; a Sangam login is not a CDS
 * client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext
class CdsServicesSecurityH2Test {

    static final String BASE_URL = "https://sangam.example.org";
    static final RSAKey EHR_KEY;

    static {
        try {
            EHR_KEY = new RSAKeyGenerator(2048).keyID("ehr-1").generate();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> AcceptanceIdp.get().issuer());
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> AcceptanceIdp.get().jwksUri());
        r.add("sangam.connectors-health.cds-service.base-url", () -> BASE_URL);
        r.add("sangam.connectors-health.cds-service.clients.ehr.issuer", () -> HealthConnectorsPropertiesCdsTest.EHR);
        r.add("sangam.connectors-health.cds-service.clients.ehr.jwks",
                () -> new JWKSet(EHR_KEY.toPublicJWK()).toString());
    }

    @LocalServerPort
    int port;

    HttpResponse<String> discovery(String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/cds-services"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anEhrTokenForThisEndpointReachesTheServices() throws Exception {
        HttpResponse<String> r = discovery(HealthConnectorsPropertiesCdsTest.token(EHR_KEY, BASE_URL + "/cds-services"));
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("services"), r.body());
    }

    @Test
    void theControllerNotTheSangamDecoderRejectsAnEhrTokenForAnotherEndpoint() throws Exception {
        HttpResponse<String> r = discovery(HealthConnectorsPropertiesCdsTest.token(EHR_KEY, BASE_URL + "/cds-services/x"));
        assertEquals(401, r.statusCode(), r.body());
        assertTrue(r.body().contains("CDS_CLIENT_REJECTED") && r.body().contains("WRONG_AUDIENCE"), r.body());
    }

    @Test
    void aSangamLoginIsNotACdsClient() throws Exception {
        String login = AcceptanceIdp.get().token("dev", "sl138-u-user", List.of("sangam-user"));
        HttpResponse<String> r = discovery(login);
        assertEquals(401, r.statusCode(), r.body());
        assertTrue(r.body().contains("CDS_CLIENT_REJECTED"), r.body());
    }

    @Test
    void otherRoutesStillNeedASangamLogin() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer "
                        + HealthConnectorsPropertiesCdsTest.token(EHR_KEY, BASE_URL + "/cds-services")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, r.statusCode(), "an EHR token is not a Sangam login: " + r.body());
    }
}
