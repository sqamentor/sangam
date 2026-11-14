// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import java.nio.file.Path;
import java.util.Map;

/**
 * Client side of the shared-token scheme (owner decision 153): {@code Authorization: Bearer <token>} with the first
 * token of a secret file. During a rotation the operator puts the new token first in the client's file once every
 * sidecar accepts it; the file is re-read without a restart ({@link TokenFile}).
 */
final class TokenFileCredentials implements SidecarCredentials {

    private final TokenFile file;

    TokenFileCredentials(Path file) {
        this.file = new TokenFile(file);
    }

    @Override
    public Map<String, String> headers() {
        SidecarTokens.Snapshot s = file.current();
        if (s.problem() != null) {
            throw new IllegalStateException(s.problem());
        }
        return Map.of("Authorization", "Bearer " + s.tokens().get(0));
    }

    @Override
    public String notReadyReason() {
        return file.current().problem();
    }

    @Override
    public String toString() {
        return "token file " + file.path(); // never the token
    }
}
