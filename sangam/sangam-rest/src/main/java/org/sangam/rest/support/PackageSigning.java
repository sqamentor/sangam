// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.util.Optional;

import org.sangam.core.layer.AuditCheckpoints;

/**
 * M-074 (R43, X.2.4.5, owner Q-04): how this environment signs and verifies executable packages.
 *
 * <p>The signing seam is the core Ed25519 one ({@link AuditCheckpoints#ed25519}, {@link AuditCheckpoints#ed25519Keys}):
 * the package signing key is a key of its own per purpose and environment, resolved from the secret provider by the
 * server wiring; this record only carries the signer and the trusted public keys (the environment's own key and the
 * keys of the environments it imports from), never key material in configuration.
 *
 * @param signer         signs the raw 32-byte package hash; null when no package signing key is configured here
 * @param trusted        trusted public keys by key id (verification on deploy and on import)
 * @param allowUnsigned  development only: unsigned packages may be deployed and imported (never in production)
 * @param production     the SL-109 production predicate ({@code ProductionMode}); in production the ENV_BINDING approver
 *                       must not be a deployer of a package naming the binding (X.2.4.5)
 * @param environment    this environment's label ({@code sandbox}, {@code uat}, {@code prod} ...); null when not set
 */
public record PackageSigning(AuditCheckpoints.Signer signer, AuditCheckpoints.KeyLookup trusted, boolean allowUnsigned,
                             boolean production, String environment) {

    public PackageSigning {
        if (trusted == null) {
            trusted = id -> Optional.empty();
        }
        if (allowUnsigned && production) {
            throw new IllegalStateException("unsigned packages are never allowed in production (M-074)");
        }
        if (environment != null && environment.isBlank()) {
            environment = null;
        }
    }

    /** No key, nothing trusted, unsigned packages refused: the default without server wiring. */
    public static PackageSigning strict() {
        return new PackageSigning(null, null, false, false, null);
    }

    public boolean canSign() {
        return signer != null;
    }
}
