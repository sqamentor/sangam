// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.SecretResolver;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-109: every production guard fires under the setting Helm uses ({@code sangam.security.production=true}, no
 * {@code prod} profile: profiles are the database type and {@code oidc}), and still under a {@code prod} profile.
 */
class ProductionGuardsTest {

    /** What the Helm chart produces: the property, profiles postgres + oidc. */
    private static MockEnvironment helm() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "true");
        env.setActiveProfiles("postgres", "oidc");
        return env;
    }

    /** The older trigger: a prod profile without the property. */
    private static MockEnvironment prodProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("postgres", "prod");
        return env;
    }

    private static MockEnvironment development() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("h2");
        return env;
    }

    private static void refusedInBothProductionForms(org.junit.jupiter.api.function.ThrowingConsumer<MockEnvironment> guard,
                                                    String message) {
        for (MockEnvironment env : new MockEnvironment[] {helm(), prodProfile()}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> guard.accept(env));
            assertTrue(e.getMessage().contains(message), e.getMessage());
        }
    }

    @Test
    void thePredicate() {
        assertTrue(ProductionEnvironment.isProduction(helm()));
        assertTrue(ProductionEnvironment.isProduction(prodProfile()));
        assertFalse(ProductionEnvironment.isProduction(development()));
    }

    @Test
    void debugger() {
        refusedInBothProductionForms(PortsWiring::refuseDebuggerInProduction, "sangam.debug.enabled must not be set");
        assertDoesNotThrow(() -> PortsWiring.refuseDebuggerInProduction(development()));
    }

    @Test
    void simulatorFallback() {
        PackBindingsProperties props = new PackBindingsProperties();
        props.setSimulatorFallback(true);
        refusedInBothProductionForms(env -> new IntegrationWiring().sangamIntegrationConnectorGateway(null, null, null,
                null, null, null, null, null, null, null, props, env), "simulator-fallback must not be enabled");
    }

    @Test
    void healthSimulator() {
        HealthConnectorsProperties props = new HealthConnectorsProperties();
        HealthConnectorsProperties.TenantConfig t = new HealthConnectorsProperties.TenantConfig();
        t.setMode("simulator");
        props.setTenants(Map.of("t1", t));
        refusedInBothProductionForms(env -> new HealthConnectorsWiring().sangamHealthSimulator(props, env),
                "simulator mode is not allowed");
    }

    @Test
    void webhooksRequireHttps() {
        WebhooksProperties props = new WebhooksProperties();
        WebhooksProperties.Subscription s = new WebhooksProperties.Subscription();
        s.setUrl("http://receiver.example.org/hook");
        s.setSecretRef("secret://hooks/receiver");
        props.setSubscriptions(Map.of("receiver", s));
        refusedInBothProductionForms(env -> new WebhooksWiring().sangamWebhookRegistration(props, null, null, null, env)
                .afterPropertiesSet(), "production profiles require https");
    }

    @Test
    void liveHttpConnectorsRequireHttps() {
        HttpConnectorsProperties props = new HttpConnectorsProperties();
        HttpConnectorsProperties.Endpoint e = new HttpConnectorsProperties.Endpoint();
        e.setBaseUrl("http://partner.example.org/api");
        props.setTenants(Map.of("t1", Map.of("partner", e)));
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        refusedInBothProductionForms(env -> new HttpConnectorsWiring().sangamHttpConnectorRegistration(props, env,
                bf.getBeanProvider(org.sangam.integration.connector.ConnectorRegistry.class),
                bf.getBeanProvider(org.sangam.integration.packs.PackConnectorBindings.class),
                bf.getBeanProvider(SecretResolver.class)).afterPropertiesSet(), "must be https on a production profile");
    }

    @Test
    void devTokenFilter() {
        SangamSecurityProperties props = new SangamSecurityProperties();
        props.getDevToken().setEnabled(true);
        props.getDevToken().setToken("a-development-token-long-enough-for-the-check");
        refusedInBothProductionForms(env -> SecurityConfig.devTokenFilter(props, env),
                "dev-token.enabled=true is not allowed in production");
    }

    @Test
    void serviceMocksAreOffInProduction() {
        assertNull(new ModellerWiring().sangamServiceMocks(helm(), null, null));
        assertNull(new ModellerWiring().sangamServiceMocks(prodProfile(), null, null));
    }

    @Test
    void urlAndCredentialGuardsUseThePredicate() {
        assertFalse(HostingWiring.loopbackViolations(prodProfile()).isEmpty(), "prod profile alone: URL rule applies");
        assertFalse(ProductionDatabaseCredentials.violations(prodProfile()).isEmpty(), "prod profile alone: SL-103 applies");
    }
}
