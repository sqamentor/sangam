// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.http.OAuth2ClientCredentials;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider access tokens fetched and refreshed by Sangam instead of by the secret binding (NG-14). One instance is meant to
 * live as long as the channel runtime, because {@link ChannelRuntime} rebuilds its connectors on every refresh: the cached
 * tokens survive that rebuild. The cache key holds a SHA-256 of the client secret, never the secret, so a rotated secret
 * starts a new token at once. Token requests go through the connector's own {@link HttpTransport}, so the egress
 * allow-list applies to the token endpoint as well.
 *
 * <ul>
 *   <li>{@link #weChat}: the public WeChat Official Account {@code POST /cgi-bin/stable_token} (grant type
 *       {@code client_credential}). The stable token is the same for every caller until it expires, so several nodes do not
 *       invalidate each other's token. {@link TokenSource#invalidate} asks for a forced refresh once.</li>
 *   <li>{@link #oauth2}: the OAuth 2.0 client-credentials grant, reusing {@link OAuth2ClientCredentials} (Bot Framework,
 *       Microsoft Graph).</li>
 * </ul>
 */
public final class ProviderTokens {

    /** Bot Framework token endpoint for multi-tenant bots (public Bot Framework documentation). */
    public static final URI BOT_FRAMEWORK_TOKEN_URI = URI.create("https://login.microsoftonline.com/botframework.com/oauth2/v2.0/token");
    public static final String BOT_FRAMEWORK_SCOPE = "https://api.botframework.com/.default";
    public static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";

    /** The token request failed: the business request was not sent (a no-effect failure). */
    public static final class TokenUnavailableException extends RuntimeException {
        public TokenUnavailableException(String message) {
            super(message);
        }
    }

    /** Where a connector gets its bearer token. */
    public interface TokenSource {
        /** The current token value (without a "Bearer " prefix). Throws {@link TokenUnavailableException}. */
        String token(String tenantId);

        /** The provider refused the token: the next {@link #token} call fetches a new one. */
        void invalidate(String tenantId);

        /** True when {@link #invalidate} can produce a different token, so one retry after a refusal is useful. */
        boolean refreshable();

        /** A token kept fresh by the secret binding (the behaviour before NG-14): never refreshed here. */
        static TokenSource ofSecret(SecretResolver secrets, String tokenRef) {
            return new TokenSource() {
                @Override
                public String token(String tenantId) {
                    return secrets.require(tokenRef);
                }

                @Override
                public void invalidate(String tenantId) {
                }

                @Override
                public boolean refreshable() {
                    return false;
                }
            };
        }
    }

    private record Cached(String value, Instant expiresAt) {
    }

    private final Clock clock;
    private final Map<String, Cached> weChatTokens = new ConcurrentHashMap<>();
    private final Map<String, OAuth2ClientCredentials> oauth = new ConcurrentHashMap<>();

    public ProviderTokens(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** WeChat stable access token for {@code appId}; the app secret is read from {@code appSecretRef} on every fetch. */
    public TokenSource weChat(URI apiBase, String appId, String appSecretRef, SecretResolver secrets, HttpTransport transport) {
        String base = apiBase.toString().replaceAll("/+$", "");
        return new TokenSource() {
            @Override
            public String token(String tenantId) {
                String secret = resolve(secrets, appSecretRef);
                String key = tenantId + "|" + base + "|" + appId + "|" + sha256(secret);
                Instant now = clock.instant();
                Cached c = weChatTokens.get(key);
                if (c == null || !now.isBefore(c.expiresAt())) {
                    c = fetchWeChat(tenantId, base, appId, secret, false, transport, now);
                    weChatTokens.put(key, c);
                }
                return c.value();
            }

            @Override
            public void invalidate(String tenantId) {
                String secret = resolve(secrets, appSecretRef);
                String key = tenantId + "|" + base + "|" + appId + "|" + sha256(secret);
                weChatTokens.put(key, fetchWeChat(tenantId, base, appId, secret, true, transport, clock.instant()));
            }

            @Override
            public boolean refreshable() {
                return true;
            }
        };
    }

    private static Cached fetchWeChat(String tenantId, String base, String appId, String secret, boolean force, HttpTransport transport, Instant now) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("grant_type", "client_credential");
        body.put("appid", appId);
        body.put("secret", secret);
        body.put("force_refresh", force);
        HttpTransport.Outcome o = transport.execute(tenantId, new HttpTransport.Call("POST", URI.create(base + "/cgi-bin/stable_token"),
                Map.of("Content-Type", "application/json", "Accept", "application/json"), Json.bytes(body), Duration.ofSeconds(15), true), 0);
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            throw new TokenUnavailableException("WeChat token endpoint: " + describe(o));
        }
        JsonNode t = Json.tree(r.body());
        if (t == null) {
            throw new TokenUnavailableException("WeChat token endpoint returned no JSON");
        }
        if (t.path("errcode").asInt(0) != 0) {
            // the errmsg is the provider's text; it never contains the secret
            throw new TokenUnavailableException("WeChat token endpoint: errcode " + t.path("errcode").asInt() + " " + t.path("errmsg").asText(""));
        }
        String token = t.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new TokenUnavailableException("WeChat token endpoint returned no access_token");
        }
        return new Cached(token, now.plusSeconds(lifetime(t.path("expires_in").asLong(7200))));
    }

    /**
     * OAuth 2.0 client credentials. {@code clientSecretRef} is resolved on every call so that a rotated secret takes effect
     * at once; the token itself is cached by {@link OAuth2ClientCredentials} until 30 seconds before it expires.
     */
    public TokenSource oauth2(URI tokenUri, String clientId, String clientSecretRef, String scope, SecretResolver secrets, HttpTransport transport) {
        return new TokenSource() {
            private String key(String tenantId, String secret) {
                return tenantId + "|" + tokenUri + "|" + clientId + "|" + scope + "|" + sha256(secret);
            }

            @Override
            public String token(String tenantId) {
                String secret = resolve(secrets, clientSecretRef);
                OAuth2ClientCredentials cc = oauth.computeIfAbsent(key(tenantId, secret),
                        k -> new OAuth2ClientCredentials(tokenUri, clientId, secret, scope, transport, clock));
                try {
                    String auth = cc.authorization(tenantId);
                    return auth.startsWith("Bearer ") ? auth.substring(7) : auth;
                } catch (RuntimeException e) {
                    throw new TokenUnavailableException("token endpoint: " + e.getMessage());
                }
            }

            @Override
            public void invalidate(String tenantId) {
                oauth.remove(key(tenantId, resolve(secrets, clientSecretRef)));
            }

            @Override
            public boolean refreshable() {
                return true;
            }
        };
    }

    /** Seconds to keep a token: one minute less than its life, but never less than half of it. */
    static long lifetime(long expiresIn) {
        long life = Math.max(0, expiresIn);
        return Math.max(life - 60, life / 2);
    }

    private static String resolve(SecretResolver secrets, String ref) {
        String v;
        try {
            v = secrets.resolve(ref).orElse(null);
        } catch (RuntimeException e) {
            throw new TokenUnavailableException("secret " + ref + " cannot be resolved");
        }
        if (v == null || v.isBlank()) {
            throw new TokenUnavailableException("secret " + ref + " is not set");
        }
        return v;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String describe(HttpTransport.Outcome o) {
        return switch (o) {
            case HttpTransport.Outcome.Response r -> "HTTP " + r.status();
            case HttpTransport.Outcome.NoEffect n -> n.reason();
            case HttpTransport.Outcome.Unknown u -> u.reason();
        };
    }
}
