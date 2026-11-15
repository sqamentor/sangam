// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.http.LiveHttpConnectors;
import org.sangam.integration.connector.secrets.SecretProviders;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-067 (HTTP connector keys) and M-075 (secret provider settings) wiring. The M-072 AdultIdentityVerifier test moved
 * with its wiring to {@code org.sangam.server.connector.gov.DigiLockerWiringTest} (SL-104).
 */
class W3bConnectorWiringTest {

    @Test
    void secretSettingsAreReadOnlyForTheListedProviders() {
        MockEnvironment env = new MockEnvironment();
        // as in the running server: Spring Boot's conversion service reads "30s" as a Duration
        env.setConversionService(new org.springframework.boot.convert.ApplicationConversionService());
        env.withProperty("sangam.secrets.providers", "file,vault,env")
                .withProperty("sangam.secrets.file.root", "/run/secrets")
                .withProperty("sangam.secrets.vault.url", "https://vault.example:8200")
                .withProperty("sangam.secrets.vault.token-ref", "secret://vault/token")
                .withProperty("sangam.secrets.cache-ttl", "30s");
        SecretProviders.Settings s = SecretsWiring.settings(env);
        assertEquals(List.of(SecretProviders.Kind.FILE, SecretProviders.Kind.VAULT, SecretProviders.Kind.ENV), s.providers());
        assertEquals(Path.of("/run/secrets"), s.fileRoot());
        assertEquals("secret://vault/token", s.vault().tokenRef());
        assertEquals(java.time.Duration.ofSeconds(30), s.cacheTtl());
        assertNull(s.aws());
        assertNull(s.gcp());
    }

    @Test
    void aListedVaultWithoutUrlFailsStartup() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.secrets.providers", "vault");
        assertThrows(IllegalStateException.class, () -> SecretsWiring.settings(env));
    }

    /**
     * The platform resolver bean exists in every server context: a component that takes a {@code SecretResolver} (the
     * channel webhook controller) is wired with no provider configured, and then it is the environment resolver.
     */
    @Test
    void thePlatformSecretResolverBeanExistsWithoutProvidersAndReadsOnlyTheEnvironment() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(SecretsWiring.class, NeedsASecretResolver.class)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), String.valueOf(ctx.getStartupFailure()));
                    org.sangam.integration.connector.SecretResolver secrets =
                            ctx.getBean(org.sangam.integration.connector.SecretResolver.class);
                    assertTrue(secrets.resolve("secret://w3b/not-a-configured-secret").isEmpty());
                    assertEquals("literal", secrets.require("literal"));
                    assertEquals(secrets, ctx.getBean(NeedsASecretResolver.class).secrets);
                });
        assertFalse(SecretsWiring.providersConfigured(new MockEnvironment()));
        assertFalse(SecretsWiring.providersConfigured(new MockEnvironment().withProperty("sangam.secrets.providers", "false")));
        assertTrue(SecretsWiring.providersConfigured(new MockEnvironment().withProperty("sangam.secrets.providers", "env")));
    }

    @Test
    void aConfiguredProviderListIsBuiltAndAnEmptyOneFailsStartup() {
        SecretsWiring wiring = new SecretsWiring();
        assertNotNull(wiring.sangamSecretResolver(new MockEnvironment().withProperty("sangam.secrets.providers", "env")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> wiring.sangamSecretResolver(new MockEnvironment().withProperty("sangam.secrets.providers", "")));
        assertTrue(e.getMessage().contains("sangam.secrets.providers is empty"), e.getMessage());
    }

    /** A component with a mandatory {@code SecretResolver}, as {@code ChannelHooksController} has. */
    static final class NeedsASecretResolver {
        final org.sangam.integration.connector.SecretResolver secrets;

        NeedsASecretResolver(org.sangam.integration.connector.SecretResolver secrets) {
            this.secrets = secrets;
        }
    }

    @Test
    void theHttpConnectorKeysReachTheSpec() {
        HttpConnectorsProperties.Endpoint e = new HttpConnectorsProperties.Endpoint();
        e.setBaseUrl("https://lambda.ap-south-1.amazonaws.com");
        e.getAuth().setType("aws-sigv4");
        e.getAuth().setRegion("ap-south-1");
        e.getAuth().setService("lambda");
        e.getAuth().setAccessKeyRef("secret://aws/access");
        e.getAuth().setSecretKeyRef("secret://aws/secret");
        e.getResponse().setFailureHeader("X-Amz-Function-Error");
        HttpConnectorsProperties.Callback cb = new HttpConnectorsProperties.Callback();
        cb.setUrl("https://sangam.example/events/fn-done");
        cb.setEventType("sangam.fn.done");
        e.setCallback(cb);
        LiveHttpConnectors.Spec s = HttpConnectorsWiring.spec("fn", e);
        assertEquals(LiveHttpConnectors.AuthType.AWS_SIGV4, s.auth().type());
        assertEquals("lambda", s.auth().provider().service());
        assertEquals("secret://aws/secret", s.auth().provider().secretKeyRef());
        assertEquals("X-Amz-Function-Error", s.failureHeader());
        assertEquals("https://sangam.example/events/fn-done", s.callback().url());
        assertEquals("body", s.callback().placement());

        HttpConnectorsProperties.Endpoint plain = new HttpConnectorsProperties.Endpoint();
        plain.setBaseUrl("https://api.example.org");
        assertNull(HttpConnectorsWiring.spec("plain", plain).callback(), "no callback unless a url is set");
    }
}
