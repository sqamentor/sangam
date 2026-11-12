// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The CLI's OAuth session: turns token endpoint answers into stored {@link TokenStore.Credentials}, hands out the access
 * token, and renews it shortly before it expires or after the server answered 401.
 *
 * <ul>
 *   <li>With a refresh token: the {@code refresh_token} grant (RFC 6749 §6). A rotated refresh token replaces the old one;
 *       when the server sends none, the old one is kept.</li>
 *   <li>A service principal (login with {@code --secret-env}) usually gets no refresh token: it repeats the
 *       {@code client_credentials} grant with the secret read again from the same environment variable. Only the
 *       variable's name is stored, never the secret.</li>
 * </ul>
 * Errors name the OAuth error code only; token values never reach stdout or stderr.
 */
final class TokenSession {

    /** Renew this long before the access token expires. */
    static final long SKEW_SECONDS = 60;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Main.Http http;
    private final TokenStore store;
    private final Clock clock;
    private final Function<String, String> env;
    private final PrintStream err;

    TokenSession(Main.Http http, TokenStore store, Clock clock, Function<String, String> env, PrintStream err) {
        this.http = http;
        this.store = store;
        this.clock = clock;
        this.env = env;
        this.err = err;
    }

    /** Credentials from a token endpoint answer; {@code previousRefresh} is kept when the answer has no new one. */
    TokenStore.Credentials credentials(JsonNode token, String previousRefresh) {
        long expiresIn = token.path("expires_in").asLong(0);
        String refresh = token.hasNonNull("refresh_token") ? token.get("refresh_token").asText() : previousRefresh;
        return new TokenStore.Credentials(token.get("access_token").asText(), refresh,
                expiresIn > 0 ? clock.instant().getEpochSecond() + expiresIn : 0);
    }

    /**
     * The access token for the logged-in account, renewed first when it expires within {@link #SKEW_SECONDS}; {@code null}
     * when nobody is logged in.
     */
    String bearer(JsonNode config) throws IOException, InterruptedException {
        String account = config.path("account").asText("");
        if (account.isEmpty()) {
            return null;
        }
        TokenStore.Credentials c = store.load(account);
        if (c == null) {
            return null;
        }
        if (c.expiresAt() > 0 && clock.instant().getEpochSecond() >= c.expiresAt() - SKEW_SECONDS) {
            String renewed = renew(config, c);
            if (renewed != null) {
                return renewed;
            }
        }
        return c.accessToken();
    }

    /** Renews after a 401; returns the new access token or {@code null} when the session cannot be renewed. */
    String renewAfterRejection(JsonNode config) throws IOException, InterruptedException {
        String account = config.path("account").asText("");
        TokenStore.Credentials c = account.isEmpty() ? null : store.load(account);
        return c == null ? null : renew(config, c);
    }

    private String renew(JsonNode config, TokenStore.Credentials c) throws IOException, InterruptedException {
        String endpoint = config.path("tokenEndpoint").asText("");
        String client = config.path("clientId").asText("");
        if (endpoint.isEmpty() || client.isEmpty()) {
            return null; // a session saved before refresh support: log in again
        }
        Main.requireSecureUrl(endpoint);
        String secretEnv = config.path("secretEnv").asText("");
        String secret = secretEnv.isEmpty() ? null : env.apply(secretEnv);
        Map<String, String> f = new LinkedHashMap<>();
        if (c.refreshToken() != null) {
            f.put("grant_type", "refresh_token");
            f.put("refresh_token", c.refreshToken());
        } else if (secret != null) {
            f.put("grant_type", "client_credentials");
        } else {
            return null;
        }
        f.put("client_id", client);
        if (secret != null) {
            f.put("client_secret", secret);
        }
        JsonNode token = form(http, endpoint, f);
        if (!token.hasNonNull("access_token")) {
            err.println("session could not be renewed (" + token.path("error").asText("no access token") + "): run sangam login");
            return null;
        }
        TokenStore.Credentials renewed = credentials(token, c.refreshToken());
        store.save(config.path("account").asText(), renewed);
        return renewed.accessToken();
    }

    /** POSTs a form to an OAuth endpoint; a body that is not JSON reads as {@code {"error":"invalid_response"}}. */
    static JsonNode form(Main.Http http, String url, Map<String, String> fields) throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder();
        fields.forEach((k, v) -> b.append(b.isEmpty() ? "" : "&").append(enc(k)).append('=').append(enc(v)));
        String body = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())).build()).body();
        try {
            JsonNode n = JSON.readTree(body == null ? "" : body);
            if (n != null && n.isObject()) {
                return n;
            }
        } catch (IOException e) {
            // fall through: never echo the body, it may be an HTML error page or carry token material
        }
        return JSON.createObjectNode().put("error", "invalid_response");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
