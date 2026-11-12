// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves secret references ({@code secret://<connector>/client-secret}) to values. Connector configuration holds only
 * references (R73 "credentials by reference only"). {@link #fromEnvironment()} reads {@code SANGAM_SECRET_<NAME>}
 * environment variables and stays the fallback when no provider is configured. M-075: the provider resolvers (a mounted
 * secrets directory, HashiCorp Vault, AWS Secrets Manager, Azure Key Vault, GCP Secret Manager) are in
 * {@code org.sangam.integration.connector.secrets} and are combined by {@code SecretProviders}; sangam-server publishes
 * the result as a bean only when {@code sangam.secrets.providers} is set (no outbound call by default, R56).
 */
@FunctionalInterface
public interface SecretResolver {

    Optional<String> resolve(String reference);

    default String require(String reference) {
        if (reference == null) {
            return null;
        }
        if (!reference.startsWith("secret://")) {
            return reference;   // a literal (development and simulator profiles only)
        }
        return resolve(reference).orElseThrow(() -> new IllegalStateException("secret not found: " + reference));
    }

    static SecretResolver of(Map<String, String> values) {
        return ref -> Optional.ofNullable(values.get(ref));
    }

    /**
     * M-075: tries each resolver in order and answers the first value found. A resolver that answers empty passes the
     * reference on; a resolver that fails (the provider is unreachable or refuses) stops the chain with its exception, so
     * an outage never silently falls back to another source.
     */
    static SecretResolver chain(java.util.List<SecretResolver> resolvers) {
        java.util.List<SecretResolver> all = java.util.List.copyOf(resolvers);
        return ref -> {
            for (SecretResolver r : all) {
                Optional<String> v = r.resolve(ref);
                if (v != null && v.isPresent()) {
                    return v;
                }
            }
            return Optional.empty();
        };
    }

    /** {@code secret://a/b-c} → environment variable {@code SANGAM_SECRET_A_B_C}. */
    static SecretResolver fromEnvironment() {
        return ref -> {
            String name = "SANGAM_SECRET_" + ref.replaceFirst("^secret://", "").replaceAll("[^A-Za-z0-9]", "_")
                    .toUpperCase(java.util.Locale.ROOT);
            return Optional.ofNullable(System.getenv(name));
        };
    }
}
