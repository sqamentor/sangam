// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import java.time.Instant;

/**
 * One use per (issuer, {@code jti}) while a client-signed token is valid (SL-138). The plain-Java contract of the
 * replay check: the Spring host's client JWT verifier calls it, and {@link JdbcReplayGuard} is the cluster-wide store.
 *
 * <p>M-141a (decisions 72-75): it was the nested {@code ClientJwtVerifier.ReplayGuard}; it is a top-level type so the
 * framework-free sangam-security jar keeps the store while the verifier (Spring Security) lives in the host.
 */
@FunctionalInterface
public interface JtiReplayGuard {

    /** @return true on the first use; false when the id was already used and has not expired */
    boolean firstUse(String issuer, String jti, Instant expiresAt);
}
