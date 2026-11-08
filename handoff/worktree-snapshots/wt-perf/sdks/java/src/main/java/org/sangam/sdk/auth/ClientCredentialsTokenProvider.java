// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * OIDC / OAuth 2.0 client-credentials grant (RFC 6749 §4.4) against the identity provider's token endpoint
 * (for example Keycloak {@code /realms/<realm>/protocol/openid-connect/token}).
 *
 * <p>The token is cached and refreshed {@code refreshSkew} before {@code expires_in} ends. Refresh is
 * single-flight: concurrent callers wait for one request instead of stampeding the IdP.
 */
public final class ClientCredentialsTokenProvider implements TokenProvider {

    /** How the client authenticates at the token endpoint. */
    public enum ClientAuth { BASIC, POST_BODY }

    private final URI tokenEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final String audience;
    private final ClientAuth clientAuth;
    private final Duration refreshSkew;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String cached;
    private volatile Instant expiresAt = Instant.EPOCH;

    private ClientCredentialsTokenProvider(Builder b) {
        this.tokenEndpoint = Objects.requireNonNull(b.tokenEndpoint, "tokenEndpoint");
        this.clientId = Objects.requireNonNull(b.clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(b.clientSecret, "clientSecret");
        this.scope = b.scope;
        this.audience = b.audience;
        this.clientAuth = b.clientAuth;
        this.refreshSkew = b.refreshSkew;
        this.timeout = b.timeout;
        this.clock = b.clock;
        this.http = b.httpClient != null ? b.httpClient
                : HttpClient.newBuilder().connectTimeout(b.timeout).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String token() {
        String t = cached;
        if (t != null && clock.instant().isBefore(expiresAt.minus(refreshSkew))) {
            return t;
        }
        lock.lock();
        try {
            t = cached;
            if (t != null && clock.instant().isBefore(expiresAt.minus(refreshSkew))) {
                return t;
            }
            return fetch();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void invalidate() {
        lock.lock();
        try {
            cached = null;
            expiresAt = Instant.EPOCH;
        } finally {
            lock.unlock();
        }
    }

    private String fetch() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        if (scope != null) form.put("scope", scope);
        if (audience != null) form.put("audience", audience);
        HttpRequest.Builder rb = HttpRequest.newBuilder(tokenEndpoint)
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        if (clientAuth == ClientAuth.BASIC) {
            String basic = urlEncode(clientId) + ":" + urlEncode(clientSecret);
            rb.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
        } else {
            form.put("client_id", clientId);
            form.put("client_secret", clientSecret);
        }
        String body = form.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
        try {
            HttpResponse<String> resp = http.send(rb.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new TokenException("token endpoint returned HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode node = json.readTree(resp.body());
            String token = node.path("access_token").asText(null);
            if (token == null || token.isEmpty()) {
                throw new TokenException("token endpoint response has no access_token");
            }
            long expiresIn = node.path("expires_in").asLong(300);
            cached = token;
            expiresAt = clock.instant().plusSeconds(expiresIn);
            return token;
        } catch (IOException e) {
            throw new TokenException("token request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenException("token request interrupted", e);
        }
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Raised when no token can be obtained. */
    public static final class TokenException extends RuntimeException {
        public TokenException(String message) {
            super(message);
        }

        public TokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class Builder {
        private URI tokenEndpoint;
        private String clientId;
        private String clientSecret;
        private String scope;
        private String audience;
        private ClientAuth clientAuth = ClientAuth.BASIC;
        private Duration refreshSkew = Duration.ofSeconds(30);
        private Duration timeout = Duration.ofSeconds(10);
        private HttpClient httpClient;
        private Clock clock = Clock.systemUTC();

        public Builder tokenEndpoint(String uri) { this.tokenEndpoint = URI.create(uri); return this; }
        public Builder tokenEndpoint(URI uri) { this.tokenEndpoint = uri; return this; }
        public Builder clientId(String v) { this.clientId = v; return this; }
        public Builder clientSecret(String v) { this.clientSecret = v; return this; }
        public Builder scope(String v) { this.scope = v; return this; }
        public Builder audience(String v) { this.audience = v; return this; }
        public Builder clientAuth(ClientAuth v) { this.clientAuth = v; return this; }
        public Builder refreshSkew(Duration v) { this.refreshSkew = v; return this; }
        public Builder timeout(Duration v) { this.timeout = v; return this; }
        public Builder httpClient(HttpClient v) { this.httpClient = v; return this; }
        public Builder clock(Clock v) { this.clock = v; return this; }

        public ClientCredentialsTokenProvider build() {
            return new ClientCredentialsTokenProvider(this);
        }
    }
}
