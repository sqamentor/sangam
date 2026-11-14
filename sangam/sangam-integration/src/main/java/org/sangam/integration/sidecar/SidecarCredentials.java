// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import java.nio.file.Path;
import java.util.Map;

/**
 * What a sidecar client sends to prove it is an authorised caller (owner decision 153). Replaceable: the 0.1.0
 * implementation is a bearer token from a secret file ({@link #tokenFile(Path)}); a per-service identity or mTLS can
 * replace it later without touching the clients. The credentials authenticate the calling service only; user
 * permissions and tenant ownership stay with the caller.
 */
public interface SidecarCredentials {

    /** Request headers to add. Throws {@link IllegalStateException} when {@link #notReadyReason()} is not null. */
    Map<String, String> headers();

    /** Why no credentials can be sent now, or {@code null} when ready. Never contains a secret. */
    String notReadyReason();

    /** Bearer token read from {@code file} (the first token line; re-read on rotation). */
    static SidecarCredentials tokenFile(Path file) {
        return new TokenFileCredentials(file);
    }

    /** No credentials configured: never ready (there is no unauthenticated mode). */
    static SidecarCredentials none(String setting) {
        return new SidecarCredentials() {
            @Override
            public Map<String, String> headers() {
                throw new IllegalStateException(notReadyReason());
            }

            @Override
            public String notReadyReason() {
                return "no service credentials are configured (" + setting + "); owner decision 153 allows no"
                        + " unauthenticated calls";
            }
        };
    }
}
