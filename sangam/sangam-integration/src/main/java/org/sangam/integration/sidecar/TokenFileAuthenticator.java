// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Shared-token check (owner decision 153). The secret file lists every token currently accepted, one per line: during
 * a rotation it holds the old and the new token, afterwards only the new one; removing a line revokes that token. The
 * comparison is over SHA-256 digests with {@link MessageDigest#isEqual} (constant time for equal-length inputs), and
 * every listed token is compared so the time does not reveal which one matched. One shared token cannot tell callers
 * apart; give each caller its own token line where callers must be distinguished, and limit who receives the file.
 */
final class TokenFileAuthenticator implements SidecarAuthenticator {

    private final TokenFile file;

    TokenFileAuthenticator(Path file) {
        this.file = new TokenFile(file);
    }

    @Override
    public boolean authenticate(String authorizationHeader) {
        SidecarTokens.Snapshot s = file.current();
        if (s.problem() != null) {
            return false;
        }
        String presented = SidecarAuthenticator.bearer(authorizationHeader);
        if (presented == null || SidecarTokens.problem(presented) != null) {
            return false;
        }
        byte[] digest = SidecarTokens.sha256(presented);
        boolean match = false;
        for (String accepted : s.tokens()) {
            match |= MessageDigest.isEqual(digest, SidecarTokens.sha256(accepted));
        }
        return match;
    }

    @Override
    public String notReadyReason() {
        return file.current().problem();
    }

    @Override
    public String toString() {
        return "token file " + file.path();
    }
}
