package org.sangam.spike.sim.idp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.sangam.spike.sim.Http;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keycloak as the Parichay / JanParichay stand-in (see IdpNote.md). Opt-in with
 * {@code SPIKE_IDP=true}: the image is about 0.5 GB.
 */
class KeycloakIdpTest {

    static final String IMAGE = "quay.io/keycloak/keycloak:26.3.3";

    @Test
    @EnabledIfEnvironmentVariable(named = "SPIKE_IDP", matches = "true")
    void startsAndPublishesOidcAndSamlMetadata() throws Exception {
        try (GenericContainer<?> kc = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(8080)
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                .withCommand("start-dev")
                .waitingFor(Wait.forHttp("/realms/master").forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(4))) {
            kc.start();
            URI base = URI.create("http://" + kc.getHost() + ":" + kc.getMappedPort(8080));
            HttpResponse<String> oidc = Http.get(base.resolve("/realms/master/.well-known/openid-configuration"));
            assertEquals(200, oidc.statusCode());
            assertTrue(oidc.body().contains("\"authorization_endpoint\""));
            HttpResponse<String> saml = Http.get(base.resolve("/realms/master/protocol/saml/descriptor"));
            assertEquals(200, saml.statusCode());
            assertTrue(saml.body().contains("EntityDescriptor"));
        }
    }
}
