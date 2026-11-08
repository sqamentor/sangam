// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves secret references ({@code secret://ifms/client-secret}) to values. Connector configuration holds only
 * references (R73 "credentials by reference only"); the integrator wires a vault-backed implementation in
 * sangam-server. {@link #fromEnvironment()} reads {@code SANGAM_SECRET_<NAME>} environment variables for development.
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

    /** {@code secret://a/b-c} → environment variable {@code SANGAM_SECRET_A_B_C}. */
    static SecretResolver fromEnvironment() {
        return ref -> {
            String name = "SANGAM_SECRET_" + ref.replaceFirst("^secret://", "").replaceAll("[^A-Za-z0-9]", "_")
                    .toUpperCase(java.util.Locale.ROOT);
            return Optional.ofNullable(System.getenv(name));
        };
    }
}
