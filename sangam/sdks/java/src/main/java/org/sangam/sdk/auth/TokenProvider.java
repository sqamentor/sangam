// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.auth;

/**
 * Supplies the credential sent in the {@code Authorization} header (ARCHITECTURE §4: {@code Bearer <OIDC JWT>}).
 * Implementations must be thread-safe.
 */
public interface TokenProvider {

    /** Returns a currently valid access token, fetching or refreshing it when needed (null = anonymous). */
    String token();

    /** Full {@code Authorization} header value; bearer by default. */
    default String authorization() {
        String t = token();
        return t == null || t.isEmpty() ? null : "Bearer " + t;
    }

    /** Called after the server answered 401; the next {@link #token()} must not return the cached token. */
    default void invalidate() {
    }

    /** No authentication (local dev profile without a token). */
    static TokenProvider none() {
        return () -> null;
    }

    /** A fixed token, for the dev profile's static token or a token managed elsewhere. */
    static TokenProvider of(String token) {
        return new StaticTokenProvider(token);
    }

    /**
     * HTTP Basic credentials (Flowable external-client parity, FC-37). Only for dev/test gateways that accept
     * Basic; the Sangam server itself expects OIDC bearer tokens.
     */
    static TokenProvider basic(String user, String password) {
        String v = "Basic " + java.util.Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new TokenProvider() {
            @Override public String token() { return null; }
            @Override public String authorization() { return v; }
        };
    }
}
