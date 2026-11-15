// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.secrets.AwsSecretsManagerResolver;
import org.sangam.integration.connector.secrets.AzureKeyVaultSecretResolver;
import org.sangam.integration.connector.secrets.GcpSecretManagerResolver;
import org.sangam.integration.connector.secrets.SecretProviders;
import org.sangam.integration.connector.secrets.VaultSecretResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * M-075: the platform {@link SecretResolver} bean. The providers are opt-in: a provider chain is built only when
 * {@code sangam.secrets.providers} is set (comma list, asked in order: file, vault, aws, azure, gcp, env). Without it the
 * bean is {@code SecretResolver.fromEnvironment()}, the resolver every consumer falls back to, so no outbound call is
 * made by default (R56). The bean always exists, so a component of any module that takes a {@link SecretResolver} (the
 * channel webhook controller of sangam-rest) is wired whether or not providers are configured.
 *
 * <pre>
 * sangam.secrets.providers=file,vault,env        sangam.secrets.cache-ttl=300s
 * sangam.secrets.file.root=/var/run/sangam/secrets
 * sangam.secrets.vault.url / .mount (secret) / .path-prefix / .namespace / .token-ref / .allow-private
 * sangam.secrets.aws.region / .endpoint / .name-prefix / .access-key-ref / .secret-key-ref / .session-token-ref
 * sangam.secrets.azure.vault-url / .tenant-id / .client-id / .client-secret-ref / .authority
 * sangam.secrets.gcp.project / .service-account-key-ref / .name-prefix / .endpoint
 * </pre>
 * Every {@code *-ref} is a {@code secret://} reference read from the file directory and the environment only.
 */
@Configuration(proxyBeanMethods = false)
public class SecretsWiring {

    static final String P = "sangam.secrets.";

    @Bean
    public SecretResolver sangamSecretResolver(Environment env) {
        if (!providersConfigured(env)) {
            return SecretResolver.fromEnvironment();
        }
        return SecretProviders.build(settings(env), ProductionEnvironment.isProduction(env), Clock.systemUTC());
    }

    /** Set and not {@code false}; an empty list is a configuration error that {@link SecretProviders#build} names. */
    static boolean providersConfigured(Environment env) {
        String providers = env.getProperty(P + "providers");
        return providers != null && !"false".equalsIgnoreCase(providers.trim());
    }

    static SecretProviders.Settings settings(Environment env) {
        List<SecretProviders.Kind> kinds = SecretProviders.parse(env.getProperty(P + "providers", ""));
        String root = env.getProperty(P + "file.root");
        return new SecretProviders.Settings(kinds,
                env.getProperty(P + "cache-ttl", Duration.class, Duration.ofMinutes(5)),
                root == null || root.isBlank() ? null : Path.of(root),
                kinds.contains(SecretProviders.Kind.VAULT) ? new VaultSecretResolver.Settings(
                        uri(env, "vault.url", true), env.getProperty(P + "vault.mount"), env.getProperty(P + "vault.path-prefix"),
                        env.getProperty(P + "vault.namespace"), env.getProperty(P + "vault.token-ref"),
                        env.getProperty(P + "vault.allow-private", Boolean.class, false)) : null,
                kinds.contains(SecretProviders.Kind.AWS) ? new AwsSecretsManagerResolver.Settings(
                        env.getProperty(P + "aws.region"), uri(env, "aws.endpoint", false), env.getProperty(P + "aws.name-prefix"),
                        env.getProperty(P + "aws.access-key-ref"), env.getProperty(P + "aws.secret-key-ref"),
                        env.getProperty(P + "aws.session-token-ref")) : null,
                kinds.contains(SecretProviders.Kind.AZURE) ? new AzureKeyVaultSecretResolver.Settings(
                        uri(env, "azure.vault-url", true), env.getProperty(P + "azure.tenant-id"),
                        env.getProperty(P + "azure.client-id"), env.getProperty(P + "azure.client-secret-ref"),
                        uri(env, "azure.authority", false)) : null,
                kinds.contains(SecretProviders.Kind.GCP) ? new GcpSecretManagerResolver.Settings(
                        env.getProperty(P + "gcp.project"), env.getProperty(P + "gcp.service-account-key-ref"),
                        env.getProperty(P + "gcp.name-prefix"), uri(env, "gcp.endpoint", false)) : null);
    }

    private static URI uri(Environment env, String key, boolean required) {
        String v = env.getProperty(P + key);
        if (v == null || v.isBlank()) {
            if (required) {
                throw new IllegalStateException(P + key + " is required");
            }
            return null;
        }
        try {
            return URI.create(v.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(P + key + " is not a URI: " + v);
        }
    }
}
