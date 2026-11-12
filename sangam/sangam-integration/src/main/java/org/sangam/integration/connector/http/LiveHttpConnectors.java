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
 * {@link SecretResolver}; mTLS key and trust stores are file paths with password references. A default header whose
 * value is a {@code secret://} reference (SL-18, an edge service token) is checked when the connector is built and read
 * from the secret port on every call.
 *
 * <p>On a production profile the build is refused when the base URL (or token URL) is not {@code https}, when a
 * required credential is missing or unresolvable, or when a credential is a literal rather than a {@code secret://}
 * reference. On every profile a {@code secret://} reference that does not resolve to a non-empty value fails the build.
 * So does (SL-65: refused, never trimmed) a secret that becomes a header value with leading or trailing whitespace or a
 * control character, a key-store or trust-store password with a control character such as a line break, and a default
 * header or method that is not valid HTTP.
 */
public final class LiveHttpConnectors {

    public enum AuthType { NONE, BASIC, BEARER, OAUTH2_CLIENT_CREDENTIALS, MTLS, AWS_SIGV4, FUNCTION_KEY, GCP_ID_TOKEN }

    /**
     * Authentication. {@code secretRef}: BASIC {@code user:password}, BEARER the token, FUNCTION_KEY the key. OAUTH2:
     * {@code tokenUrl}, {@code clientId}, {@code clientSecretRef}, optional {@code scope}. MTLS: the {@link Tls} key
     * store. AWS_SIGV4, FUNCTION_KEY and GCP_ID_TOKEN (M-067 serverless providers) read {@link Provider}.
     */
    public record Auth(AuthType type, String secretRef, String tokenUrl, String clientId, String clientSecretRef,
                       String scope, Provider provider) {
        public static final Auth NONE = new Auth(AuthType.NONE, null, null, null, null, null, null);

        public Auth(AuthType type, String secretRef, String tokenUrl, String clientId, String clientSecretRef,
                    String scope) {
            this(type, secretRef, tokenUrl, clientId, clientSecretRef, scope, null);
        }
    }

    /**
     * M-067 provider authentication.
     *
     * <ul>
     *   <li>AWS_SIGV4: {@code region}, {@code service} (lambda, execute-api, ...), {@code accessKeyRef},
     *       {@code secretKeyRef}, optional {@code sessionTokenRef}; every request is signed (AWS Lambda invoke, Lambda
     *       function URLs and API Gateway with IAM auth).</li>
     *   <li>FUNCTION_KEY: the key is {@link Auth#secretRef()}, sent in {@code headerName} (default
     *       {@value #FUNCTION_KEY_HEADER}, Azure Functions); it is a default header read from the secret port on every
     *       call (SL-18).</li>
     *   <li>GCP_ID_TOKEN: {@code Bearer <ID token>} with {@code audience} (default: the base URL's origin, which is what
     *       Cloud Run and Cloud Functions expect), from {@code serviceAccountKeyRef} (a service-account key JSON by
     *       reference) or from the metadata server at {@code metadataUrl}. The metadata server is a link-local address:
     *       configuring {@code metadataUrl} is the explicit approval that adds its origin to the allow-list.</li>
     * </ul>
     */
    public record Provider(String region, String service, String accessKeyRef, String secretKeyRef, String sessionTokenRef,
                           String headerName, String audience, String serviceAccountKeyRef, String metadataUrl) {
    }

    /** Header of the Azure Functions key. */
    public static final String FUNCTION_KEY_HEADER = "x-functions-key";

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
                       Map<String, String> outputMapping, String providerRefPointer, Callback callback,
                       String failureHeader) {

        /** Without an async callback or a failure header (the shape before M-067). */
        public Spec(String id, String baseUrl, String method, String capabilityClass, String idempotencyHeader,
                    String lookupPath, Auth auth, Tls tls, Duration connectTimeout, Duration readTimeout, int maxAttempts,
                    List<String> egress, boolean allowPrivate, Map<String, String> defaultHeaders,
                    Map<String, String> outputMapping, String providerRefPointer) {
            this(id, baseUrl, method, capabilityClass, idempotencyHeader, lookupPath, auth, tls, connectTimeout, readTimeout,
                    maxAttempts, egress, allowPrivate, defaultHeaders, outputMapping, providerRefPointer, null, null);
        }
    }

    /** M-067 async callback configuration ({@link AsyncCallback}); {@code placement} is {@code body} or {@code headers}. */
    public record Callback(String url, String eventType, String placement, String field) {
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
        Provider provider = auth.provider();
        URI metadataUri = null;
        if (type == AuthType.GCP_ID_TOKEN) {
            boolean hasKey = provider != null && provider.serviceAccountKeyRef() != null
                    && !provider.serviceAccountKeyRef().isBlank();
            boolean hasMetadata = provider != null && provider.metadataUrl() != null && !provider.metadataUrl().isBlank();
            if (hasKey == hasMetadata) {
                throw new IllegalStateException(where + ": gcp-id-token needs exactly one of auth.service-account-key-ref"
                        + " and auth.metadata-url");
            }
            if (hasMetadata) {
                metadataUri = absolute(where, "auth.metadata-url", provider.metadataUrl(), false);
                // the operator named the metadata server explicitly: its (link-local) origin is approved, nothing else
                rules.add(EgressPolicy.Rule.parse(tenant, origin(metadataUri), true));
            }
        }
        GcpTokens gcp = null;
        if (type == AuthType.GCP_ID_TOKEN && metadataUri == null) {
            String keyRef = provider.serviceAccountKeyRef();
            if (production && !keyRef.startsWith("secret://")) {
                throw new IllegalStateException(where + ": auth.service-account-key-ref must be a secret:// reference on a"
                        + " production profile");
            }
            // the key is read and parsed now, so an unknown or unreadable key fails the build, not the first call
            URI tokenEndpoint;
            try {
                tokenEndpoint = GcpTokens.serviceAccount(keyRef, secrets,
                        HttpTransport.forSimulator(), Clock.systemUTC()).tokenEndpoint();
            } catch (HttpRestConnector.AuthUnavailableException e) {
                throw new IllegalStateException(where + ": auth.service-account-key-ref: " + e.getMessage());
            }
            absolute(where, "service-account token_uri", tokenEndpoint.toString(), production);
            if (s.egress() == null || s.egress().isEmpty()) {
                rules.add(EgressPolicy.Rule.parse(tenant, origin(tokenEndpoint), s.allowPrivate()));
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
        HttpRestConnector.RequestSigner signer = null;
        Map<String, String> defaultHeaders = new java.util.LinkedHashMap<>(
                s.defaultHeaders() == null ? Map.of() : s.defaultHeaders());
        switch (type) {
            case AWS_SIGV4 -> {
                if (provider == null || blank(provider.region()) || blank(provider.service())) {
                    throw new IllegalStateException(where + ": aws-sigv4 needs auth.region and auth.service");
                }
                headerSecret(where, "auth.access-key-ref", provider.accessKeyRef(), secrets, production);
                headerSecret(where, "auth.secret-key-ref", provider.secretKeyRef(), secrets, production);
                if (!blank(provider.sessionTokenRef())) {
                    headerSecret(where, "auth.session-token-ref", provider.sessionTokenRef(), secrets, production);
                }
                signer = AwsRequestSigner.signer(provider.region().trim(), provider.service().trim(), provider.accessKeyRef(),
                        provider.secretKeyRef(), blank(provider.sessionTokenRef()) ? null : provider.sessionTokenRef(),
                        secrets, Clock.systemUTC());
            }
            case FUNCTION_KEY -> {
                String header = provider == null || blank(provider.headerName()) ? FUNCTION_KEY_HEADER
                        : provider.headerName().trim();
                headerSecret(where, "auth.secret-ref", auth.secretRef(), secrets, production);
                if (defaultHeaders.keySet().stream().anyMatch(header::equalsIgnoreCase)) {
                    throw new IllegalStateException(where + ": default-headers." + header + " is the function key header");
                }
                // a default header by reference (SL-18): read from the secret port on every call, never stored
                defaultHeaders.put(header, auth.secretRef());
            }
            case GCP_ID_TOKEN -> {
                String audience = provider == null || blank(provider.audience()) ? origin(base) : provider.audience().trim();
                gcp = metadataUri != null ? GcpTokens.metadata(metadataUri, transport, Clock.systemUTC())
                        : GcpTokens.serviceAccount(provider.serviceAccountKeyRef(), secrets, transport, Clock.systemUTC());
                authorizer = gcp.idTokenAuthorizer(audience);
            }
            default -> {
                // the classic types below
            }
        }
        switch (type) {
            case BASIC -> {
                String v = headerSecret(where, "auth.secret-ref", auth.secretRef(), secrets, production);
                if (!v.contains(":")) {
                    throw new IllegalStateException(where + ": basic credential must be user:password");
                }
                staticAuth = "basic:" + v;
            }
            case BEARER -> staticAuth = "Bearer " + headerSecret(where, "auth.secret-ref", auth.secretRef(), secrets,
                    production);
            // the client secret goes to the token endpoint with HTTP Basic, in an Authorization header
            case OAUTH2_CLIENT_CREDENTIALS -> authorizer = new OAuth2ClientCredentials(tokenUri, auth.clientId(),
                    headerSecret(where, "auth.client-secret-ref", auth.clientSecretRef(), secrets, production),
                    auth.scope(), transport, Clock.systemUTC());
            case NONE, MTLS -> {
                // no header; MTLS authenticates with the client certificate
            }
        }
        // SL-18: a default header whose value is a secret:// reference (an edge service token) is a credential by
        // reference. It is checked here by the rules of the auth references, so an unknown or empty reference fails the
        // build, not the first call. The connector then reads it from the secret port on every call: a rotated value
        // needs no rebuild, and no value sits in the connector's configuration. A value without the prefix is a plain
        // header value.
        for (Map.Entry<String, String> h : defaultHeaders.entrySet()) {
            if (h.getValue() != null && h.getValue().startsWith("secret://")) {
                headerSecret(where, "default-headers." + h.getKey(), h.getValue(), secrets, production);
            }
        }
        CapabilityClass cls = CapabilityClass.fromDeclaration(s.capabilityClass());
        if (s.lookupPath() != null && !s.lookupPath().contains("{operationId}")) {
            throw new IllegalStateException(where + ": lookup-path must contain {operationId}");
        }
        // SG-36: the connector gets the real secret port, never a stand-in. The static credential goes in already
        // resolved ("Bearer ..." / "basic:..."), and SecretResolver#require never looks up a value without the secret://
        // prefix, so the port answers only the default-header references and the credential reaches only Authorization.
        HttpRestConnector.Config config;
        try {
            AsyncCallback callback = null;
            if (s.callback() != null && !blank(s.callback().url())) {
                URI cbUrl = absolute(where, "callback.url", s.callback().url(), production);
                callback = new AsyncCallback(cbUrl, s.callback().eventType(),
                        AsyncCallback.Placement.parse(s.callback().placement()), s.callback().field());
            }
            config = new HttpRestConnector.Config(s.id(), base, cls, s.idempotencyHeader(), s.lookupPath(), staticAuth,
                    defaultHeaders, s.readTimeout(), ConnectorProfile.LIVE, authorizer,
                    new HttpRestConnector.ResponseMapping(s.outputMapping(), s.providerRefPointer(), s.failureHeader()),
                    s.method(), signer, callback);
        } catch (IllegalArgumentException e) {
            // a header, method or response-mapping error names the connector like every other configuration error
            throw new IllegalStateException(where + ": " + e.getMessage(), e);
        }
        return new HttpRestConnector(config, transport, secrets);
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

    private static boolean blank(String s) {
        return s == null || s.isBlank();
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

    /**
     * SL-65: a secret that becomes (part of) a header value: no leading or trailing whitespace and no control character.
     * Refused, never trimmed (a trailing line break from a file store could hide a wrong secret).
     */
    private static String headerSecret(String where, String name, String ref, SecretResolver secrets, boolean production) {
        String v = secret(where, name, ref, secrets, production);
        if (!HttpRestConnector.isTidySecret(v)) {
            throw new IllegalStateException(where + ": " + name + HttpRestConnector.UNTIDY_SECRET);
        }
        return v;
    }

    /**
     * SL-65: a secret that never goes into a header (a key-store or trust-store password): no control character. Leading
     * or trailing spaces are legitimate password characters.
     */
    private static String passwordSecret(String where, String name, String ref, SecretResolver secrets,
                                         boolean production) {
        String v = secret(where, name, ref, secrets, production);
        if (!HttpRestConnector.hasNoControlCharacter(v)) {
            throw new IllegalStateException(where + ": " + name + HttpRestConnector.CONTROL_IN_SECRET);
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
        char[] kp = hasKey ? passwordSecret(where, "tls.key-store-password-ref", t.keyStorePasswordRef(), secrets,
                production).toCharArray() : null;
        char[] tp = hasTrust ? passwordSecret(where, "tls.trust-store-password-ref", t.trustStorePasswordRef(), secrets,
                production).toCharArray() : null;
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
