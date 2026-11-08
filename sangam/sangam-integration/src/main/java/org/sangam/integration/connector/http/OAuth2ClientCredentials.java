// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;

/**
 * OAuth 2.0 client-credentials grant (RFC 6749 §4.4) for a LIVE HTTP connector. The token request goes through the
 * connector's own {@link HttpTransport}, so the egress allow-list and private-range block apply to the token endpoint
 * as well. The token is cached per tenant until 30 seconds before {@code expires_in}. A failed token request throws
 * {@link HttpRestConnector.AuthUnavailableException}: the business request is then never sent.
 */
public final class OAuth2ClientCredentials implements HttpRestConnector.Authorizer {

    private record Token(String value, Instant expiresAt) {
    }

    private final URI tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final HttpTransport transport;
    private final Clock clock;
    private final Map<String, Token> cache = new LinkedHashMap<>();

    public OAuth2ClientCredentials(URI tokenUri, String clientId, String clientSecret, String scope, HttpTransport transport,
                                   Clock clock) {
        if (tokenUri == null || clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("oauth2 client credentials need token-url, client-id and a client secret");
        }
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.transport = transport;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public synchronized String authorization(String tenantId) {
        Token t = cache.get(tenantId);
        Instant now = clock.instant();
        if (t == null || !now.isBefore(t.expiresAt())) {
            t = fetch(tenantId, now);
            cache.put(tenantId, t);
        }
        return "Bearer " + t.value();
    }

    private Token fetch(String tenantId, Instant now) {
        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        if (scope != null && !scope.isBlank()) {
            form.append("&scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Accept", "application/json");
        h.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + ":" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)));
        HttpTransport.Outcome o = transport.execute(tenantId, new HttpTransport.Call("POST", tokenUri, h,
                form.toString().getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(15), true), 0);
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            throw new HttpRestConnector.AuthUnavailableException("token endpoint: " + describe(o));
        }
        JsonNode tree = Json.tree(r.body());
        String token = tree == null ? null : tree.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new HttpRestConnector.AuthUnavailableException("token endpoint returned no access_token");
        }
        long expiresIn = tree.path("expires_in").asLong(300);
        return new Token(token, now.plusSeconds(Math.max(0, expiresIn - 30)));
    }

    private static String describe(HttpTransport.Outcome o) {
        return switch (o) {
            case HttpTransport.Outcome.Response r -> "HTTP " + r.status();
            case HttpTransport.Outcome.NoEffect ne -> ne.reason();
            case HttpTransport.Outcome.Unknown u -> u.reason();
        };
    }
}
