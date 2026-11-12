// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.secrets;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.sangam.integration.connector.SecretResolver;

/**
 * Builds the platform {@link SecretResolver} from configuration (M-075, SANGAM.md §7 "secret-provider SPI", §14.5).
 *
 * <p>{@code providers} is the order in which sources are asked: {@code file} (a mounted secrets directory),
 * {@code vault}, {@code aws}, {@code azure}, {@code gcp}, {@code env} ({@code SANGAM_SECRET_*}). The first source that
 * has the reference answers; a source that fails stops the read (an outage is never covered by another source). Found
 * values of the network providers are kept for {@code cacheTtl} (zero turns the cache off).
 *
 * <p>Bootstrap: the providers' own credentials (the Vault token, cloud keys) are themselves {@code secret://}
 * references, read only from the {@code file} directory (when configured) and the environment, never from a network
 * provider, so there is no loop and no credential in the configuration.
 *
 * <p>Nothing here is built unless the server sets {@code sangam.secrets.providers}; without it every consumer keeps
 * {@link SecretResolver#fromEnvironment()} and no outbound call is made (R56).
 */
public final class SecretProviders {

    public enum Kind { FILE, VAULT, AWS, AZURE, GCP, ENV }

    /** All settings; a provider's own block may be null when that provider is not listed. */
    public record Settings(List<Kind> providers, Duration cacheTtl, Path fileRoot, VaultSecretResolver.Settings vault,
                           AwsSecretsManagerResolver.Settings aws, AzureKeyVaultSecretResolver.Settings azure,
                           GcpSecretManagerResolver.Settings gcp) {
        public Settings {
            providers = providers == null ? List.of() : List.copyOf(providers);
        }
    }

    private SecretProviders() {
    }

    /** Parses {@code file,vault,env} (case-insensitive, duplicates refused). */
    public static List<Kind> parse(String list) {
        Set<Kind> out = new LinkedHashSet<>();
        if (list == null) {
            return List.of();
        }
        for (String p : list.split(",")) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            Kind k;
            try {
                k = Kind.valueOf(t.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("sangam.secrets.providers: unknown provider '" + t
                        + "' (file, vault, aws, azure, gcp, env)");
            }
            if (!out.add(k)) {
                throw new IllegalStateException("sangam.secrets.providers lists '" + t + "' twice");
            }
        }
        return List.copyOf(out);
    }

    /**
     * The resolver of the settings. Fails start-up (IllegalStateException) when a listed provider lacks its settings,
     * when a bootstrap credential cannot be read, or (production) when a provider URL is not https.
     */
    public static SecretResolver build(Settings s, boolean production, Clock clock) {
        if (s.providers().isEmpty()) {
            throw new IllegalStateException("sangam.secrets.providers is empty");
        }
        MountedFileSecretResolver file = null;
        if (s.providers().contains(Kind.FILE)) {
            if (s.fileRoot() == null) {
                throw new IllegalStateException("sangam.secrets.file.root is required for the file provider");
            }
            file = new MountedFileSecretResolver(s.fileRoot());
        }
        SecretResolver env = SecretResolver.fromEnvironment();
        SecretResolver bootstrap = file == null ? env : SecretResolver.chain(List.of(file, env));
        List<SecretResolver> chain = new ArrayList<>();
        for (Kind k : s.providers()) {
            chain.add(switch (k) {
                case FILE -> file;
                case ENV -> env;
                case VAULT -> cached(new VaultSecretResolver(require(s.vault(), "vault"), bootstrap, production), s, clock);
                case AWS -> cached(new AwsSecretsManagerResolver(require(s.aws(), "aws"), bootstrap, production), s, clock);
                case AZURE -> cached(new AzureKeyVaultSecretResolver(require(s.azure(), "azure"), bootstrap, production),
                        s, clock);
                case GCP -> cached(new GcpSecretManagerResolver(require(s.gcp(), "gcp"), bootstrap, production), s, clock);
            });
        }
        return chain.size() == 1 ? chain.get(0) : SecretResolver.chain(chain);
    }

    private static SecretResolver cached(SecretResolver r, Settings s, Clock clock) {
        Duration ttl = s.cacheTtl() == null ? Duration.ofMinutes(5) : s.cacheTtl();
        return ttl.isZero() ? r : new CachingSecretResolver(r, ttl, clock);
    }

    private static <T> T require(T settings, String name) {
        if (settings == null) {
            throw new IllegalStateException("sangam.secrets.providers lists " + name + " but sangam.secrets." + name
                    + ".* is not configured");
        }
        return settings;
    }
}
