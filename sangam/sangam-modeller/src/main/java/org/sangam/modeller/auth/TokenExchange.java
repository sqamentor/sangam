// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth 2.0 on-behalf-of token exchange (RFC 8693) for service calls. Tokens are audience-scoped and short-lived, cached per (tenant, user,
 * audience) only, and refreshed before expiry. A job with no user context is refused, never served with a service token. The returned
 * record names the acting user, which the caller writes to the R42 audit event.
 */
public final class TokenExchange {

    public record Config(URI tokenEndpoint, String clientId, String clientSecretRef) {
    }

    public record ExchangedToken(String accessToken, String actingUser, String audience, Instant expiresAt) {
    }

    public static final class ExchangeFailed extends RuntimeException {
        public ExchangeFailed(String m) {
            super(m);
        }
    }

    private static final long SKEW_SECONDS = 30;

    private final Config config;
    private final HttpTransport transport;
    private final SecretResolver secrets;
    private final Clock clock;
    private final Map<String, ExchangedToken> cache = new ConcurrentHashMap<>();

    public TokenExchange(Config config, HttpTransport transport, SecretResolver secrets, Clock clock) {
        this.config = config;
        this.transport = transport;
        this.secrets = secrets;
        this.clock = clock;
    }

    /** {@code subjectToken} is the acting user's own token; null means no user context. */
    public ExchangedToken exchange(String tenantId, String user, String subjectToken, String audience) {
        if (user == null || user.isBlank() || subjectToken == null || subjectToken.isBlank()) {
            throw new ExchangeFailed("TOKEN_EXCHANGE_NO_USER_CONTEXT");
        }
        String key = tenantId + "\n" + user + "\n" + audience;
        ExchangedToken cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(clock.instant().plusSeconds(SKEW_SECONDS))) {
            return cached;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        form.put("subject_token", subjectToken);
        form.put("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
        form.put("requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
        form.put("audience", audience);
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&").append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((config.clientId() + ":" + secrets.require(config.clientSecretRef())).getBytes(StandardCharsets.UTF_8)));
        HttpTransport.Outcome o = transport.execute(tenantId, HttpTransport.Call.post(config.tokenEndpoint(), h, body.toString().getBytes(StandardCharsets.UTF_8), false), 0);
        if (!(o instanceof HttpTransport.Outcome.Response r)) {
            throw new ExchangeFailed("token endpoint unreachable: " + o);
        }
        if (!r.is2xx()) {
            throw new ExchangeFailed("token endpoint HTTP " + r.status());
        }
        JsonNode t = Json.tree(r.body());
        if (t == null || !t.hasNonNull("access_token")) {
            throw new ExchangeFailed("token endpoint gave no access_token");
        }
        ExchangedToken out = new ExchangedToken(t.get("access_token").asText(), user, audience, clock.instant().plusSeconds(t.path("expires_in").asLong(60)));
        cache.put(key, out);
        return out;
    }
}
