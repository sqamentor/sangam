// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The sidecar side of caller authentication (owner decision 153), behind a replaceable interface (per-service identity
 * or mTLS can replace the shared token later). It authenticates only: user permissions and tenant ownership stay with
 * the caller. A sidecar without usable credentials is not ready and refuses every request; there is no unauthenticated
 * bypass and no permanent fallback token.
 */
public interface SidecarAuthenticator {

    /** True only for a caller this sidecar accepts. Must not throw for any header value. */
    boolean authenticate(String authorizationHeader);

    /** Why no caller can be accepted now (missing or invalid credentials), or {@code null}. Never contains a secret. */
    String notReadyReason();

    /** Shared tokens from a secret file: one token per line, every listed token accepted (rotation, revocation). */
    static SidecarAuthenticator tokenFile(Path file) {
        return new TokenFileAuthenticator(file);
    }

    /** No credentials configured: not ready, every request refused. */
    static SidecarAuthenticator none(String setting) {
        return new SidecarAuthenticator() {
            @Override
            public boolean authenticate(String authorizationHeader) {
                return false;
            }

            @Override
            public String notReadyReason() {
                return "no caller credentials are configured (" + setting + ")";
            }
        };
    }

    /** The token of an {@code Authorization: Bearer <token>} header, or {@code null}. */
    static String bearer(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String h = authorizationHeader.strip();
        if (h.length() < 7 || !h.substring(0, 7).toLowerCase(Locale.ROOT).equals("bearer ")) {
            return null;
        }
        String token = h.substring(7).strip();
        return token.isEmpty() ? null : token;
    }
}
