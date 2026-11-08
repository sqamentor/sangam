// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.TlsConfig;

/**
 * Builds a LIVE {@link HttpRestConnector} from configuration (SL-17). Every LIVE connector gets its own
 * {@link HttpTransport} with an {@link EgressPolicy} built from the connector's egress rules (default: exactly the
 * origin of its base URL), so loopback, private, link-local/metadata, CGNAT, ULA and multicast destinations are refused
 * unless the configuration sets {@code allowPrivate} explicitly. Credentials are references resolved through the
 * {@link SecretResolver}; mTLS key and trust stores are file paths with password references.
 *
 * <p>On a production profile the build is refused when the base URL (or token URL) is not {@code https}, when a
 * required credential is missing or unresolvable, or when a credential is a literal rather than a {@code secret://}
 * reference.
 */
public final class LiveHttpConnectors {

    public enum AuthType { NONE, BASIC, BEARER, OAUTH2_CLIENT_CREDENTIALS, MTLS }

    /**
     * Authentication. {@code secretRef}: BASIC {@code user:password}, BEARER the token. OAUTH2: {@code tokenUrl},
     * {@code clientId}, {@code clientSecretRef}, optional {@code scope}. MTLS: the {@link Tls} key store.
     */
    public record Auth(AuthType type, String secretRef, String tokenUrl, String clientId, String clientSecretRef,
                       String scope) {
        public static final Auth NONE = new Auth(AuthType.NONE, null, null, null, null, null);
    }

    /** mTLS / private trust: PKCS#12 or JKS paths; passwords by reference. */
    public record Tls(String keyStore, String keyStorePasswordRef, String trustStore, String trustStorePasswordRef) {
    }

    /**
     * One configured endpoint.
     *
     * @param maxAttempts transport attempts for retryable outcomes (1 = no transport retry; the engine retries)
     * @param egress      allow-list entries {@code scheme://host[:port][/path]}; empty = the base URL's origin
     */
    public record Spec(String id, String baseUrl, String method, String capabilityClass, String idempotencyHeader,
                       String lookupPath, Auth auth, Tls tls, Duration connectTimeout, Duration readTimeout, int maxAttempts,
                       List<String> egress, boolean allowPrivate, Map<String, String> defaultHeaders,
                       Map<String, String> outputMapping, String providerRefPointer) {
    }

    private LiveHttpConnectors() {
    }

    public static HttpRestConnector build(String tenant, Spec s, SecretResolver secrets, boolean production) {
        String where = "connector " + tenant + "/" + (s == null ? "?" : s.id());
        if (s == null || s.id() == null || s.id().isBlank()) {
            throw new IllegalStateException(where + ": id is required");
        }
        if (s.baseUrl() == null || s.baseUrl().isBlank()) {
            throw new IllegalStateException(where + ": base-url is required");
        }
        URI base = absolute(where, "base-url", s.baseUrl(), production);
        Auth auth = s.auth() == null ? Auth.NONE : s.auth();
        AuthType type = auth.type() == null ? AuthType.NONE : auth.type();

        List<EgressPolicy.Rule> rules = new ArrayList<>();
        List<String> egress = s.egress() == null || s.egress().isEmpty() ? List.of(origin(base)) : s.egress();
        for (String spec : egress) {
            rules.add(EgressPolicy.Rule.parse(tenant, spec, s.allowPrivate()));
        }
        URI tokenUri = null;
        if (type == AuthType.OAUTH2_CLIENT_CREDENTIALS) {
            if (auth.tokenUrl() == null || auth.tokenUrl().isBlank() || auth.clientId() == null || auth.clientId().isBlank()) {
                throw new IllegalStateException(where + ": oauth2 needs auth.token-url and auth.client-id");
            }
            tokenUri = absolute(where, "auth.token-url", auth.tokenUrl(), production);
            if (s.egress() == null || s.egress().isEmpty()) {
                rules.add(EgressPolicy.Rule.parse(tenant, origin(tokenUri), s.allowPrivate()));
            }
        }
        TlsConfig tls = tls(where, s.tls(), secrets, production, type == AuthType.MTLS);
        int attempts = Math.max(1, s.maxAttempts());
        HttpTransport transport = new HttpTransport(EgressPolicy.of(rules, 3, 32L * 1024 * 1024), tls,
                s.connectTimeout() == null ? Duration.ofSeconds(10) : s.connectTimeout(),
                s.readTimeout() == null ? Duration.ofSeconds(30) : s.readTimeout(),
                attempts == 1 ? RetryPolicy.none() : new RetryPolicy(attempts, Duration.ofMillis(200), Duration.ofSeconds(5), 0.5));

        String staticAuth = null;
        HttpRestConnector.Authorizer authorizer = null;
        switch (type) {
            case BASIC -> {
                String v = secret(where, "auth.secret-ref", auth.secretRef(), secrets, production);
                if (!v.contains(":")) {
                    throw new IllegalStateException(where + ": basic credential must be user:password");
                }
                staticAuth = "basic:" + v;
            }
            case BEARER -> staticAuth = "Bearer " + secret(where, "auth.secret-ref", auth.secretRef(), secrets, production);
            case OAUTH2_CLIENT_CREDENTIALS -> authorizer = new OAuth2ClientCredentials(tokenUri, auth.clientId(),
                    secret(where, "auth.client-secret-ref", auth.clientSecretRef(), secrets, production), auth.scope(),
                    transport, Clock.systemUTC());
            case NONE, MTLS -> {
                // no header; MTLS authenticates with the client certificate
            }
        }
        // the connector resolves a static value through the resolver again: hand it the already-resolved literal
        String fixed = staticAuth;
        SecretResolver resolved = ref -> java.util.Optional.ofNullable(fixed);
        CapabilityClass cls = CapabilityClass.fromDeclaration(s.capabilityClass());
        if (s.lookupPath() != null && !s.lookupPath().contains("{operationId}")) {
            throw new IllegalStateException(where + ": lookup-path must contain {operationId}");
        }
        HttpRestConnector.Config config = new HttpRestConnector.Config(s.id(), base, cls, s.idempotencyHeader(),
                s.lookupPath(), fixed, s.defaultHeaders(), s.readTimeout(), ConnectorProfile.LIVE, authorizer,
                new HttpRestConnector.ResponseMapping(s.outputMapping(), s.providerRefPointer()), s.method());
        return new HttpRestConnector(config, transport, resolved);
    }

    private static URI absolute(String where, String name, String value, boolean production) {
        URI u;
        try {
            u = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + ": " + name + " is not a URI: " + value);
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (u.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new IllegalStateException(where + ": " + name + " must be an absolute http(s) URL: " + value);
        }
        if (u.getUserInfo() != null) {
            throw new IllegalStateException(where + ": " + name + " must not carry credentials");
        }
        if (production && !scheme.equals("https")) {
            throw new IllegalStateException(where + ": " + name + " must be https on a production profile: " + value);
        }
        return u;
    }

    private static String origin(URI u) {
        return u.getScheme() + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort());
    }

    private static String secret(String where, String name, String ref, SecretResolver secrets, boolean production) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException(where + ": " + name + " is required");
        }
        if (production && !ref.startsWith("secret://")) {
            throw new IllegalStateException(where + ": " + name + " must be a secret:// reference on a production profile");
        }
        String v;
        try {
            v = secrets.require(ref);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(where + ": " + name + " cannot be resolved (" + e.getMessage() + ")");
        }
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(where + ": " + name + " resolves to an empty value");
        }
        return v;
    }

    private static TlsConfig tls(String where, Tls t, SecretResolver secrets, boolean production, boolean mutualRequired) {
        boolean hasKey = t != null && t.keyStore() != null && !t.keyStore().isBlank();
        boolean hasTrust = t != null && t.trustStore() != null && !t.trustStore().isBlank();
        if (mutualRequired && !hasKey) {
            throw new IllegalStateException(where + ": mtls needs tls.key-store");
        }
        if (!hasKey && !hasTrust) {
            return null;   // JDK default trust anchors
        }
        Path key = hasKey ? existing(where, "tls.key-store", t.keyStore()) : null;
        Path trust = hasTrust ? existing(where, "tls.trust-store", t.trustStore()) : null;
        char[] kp = hasKey ? secret(where, "tls.key-store-password-ref", t.keyStorePasswordRef(), secrets, production)
                .toCharArray() : null;
        char[] tp = hasTrust ? secret(where, "tls.trust-store-password-ref", t.trustStorePasswordRef(), secrets, production)
                .toCharArray() : null;
        return new TlsConfig(key, kp, trust, tp, null);
    }

    private static Path existing(String where, String name, String file) {
        Path p = Path.of(file);
        if (!Files.isReadable(p)) {
            throw new IllegalStateException(where + ": " + name + " is not a readable file: " + file);
        }
        return p;
    }
}
