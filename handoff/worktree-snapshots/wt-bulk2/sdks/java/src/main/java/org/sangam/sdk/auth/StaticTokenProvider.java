// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.auth;

import java.util.Objects;

/** A token that never changes (dev profile static token, or a token injected by the platform). */
public final class StaticTokenProvider implements TokenProvider {

    private final String token;

    public StaticTokenProvider(String token) {
        this.token = Objects.requireNonNull(token, "token");
    }

    @Override
    public String token() {
        return token;
    }
}
