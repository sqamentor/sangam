// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.SecretResolver;

/**
 * SL-17: configuration-built LIVE HTTP connector against a local JDK HttpServer stub. SG-36: {@code secret://} default
 * headers on the live path, under every auth mode.
 */
class LiveHttpConnectorsTest {

    HttpServer server;
    String base;
    final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
    final List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
    final AtomicInteger tokenCalls = new AtomicInteger();
    /** Operation ids (Idempotency-Key) whose response the stub withholds until {@link #release} opens. */
    final java.util.Set<String> stallOps = ConcurrentHashMap.newKeySet();
    /** Counted down once per stalled request that has fully arrived at the stub (it was sent). */
    final java.util.concurrent.CountDownLatch stalledArrived = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    final List<Integer> remotePorts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private void handle(HttpExchange x) throws java.io.IOException {
        String path = x.getRequestURI().getPath();
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);   // the connector appends the request's default path "/"
        }
        Map<String, String> h = new java.util.HashMap<>();
        x.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
        h.put(":path", path);
        seen.add(h);
        remotePorts.add(x.getRemoteAddress().getPort());
        byte[] body;
        int status;
        if (path.equals("/token")) {
            tokenCalls.incrementAndGet();
            status = 200;
            body = "{\"access_token\":\"tok-1\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
        } else if (stallOps.contains(String.valueOf(h.get("idempotency-key")))) {
            x.getRequestBody().readAllBytes();   // the whole request has arrived: it was sent
            stalledArrived.countDown();
            try {
                release.await();                 // no answer until the test is over (latch, not a sleep)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            x.close();
            return;
        } else {
            status = statusByPath.getOrDefault(path, 200);
            body = ("{\"result\":{\"ref\":\"REF-42\",\"amount\":7},\"status\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, body.length);
        x.getResponseBody().write(body);
        x.close();
    }

    private LiveHttpConnectors.Spec spec(String cls, String path, LiveHttpConnectors.Auth auth, boolean allowPrivate,
                                        Duration readTimeout) {
        return spec(cls, base + path, auth, allowPrivate, Duration.ofSeconds(2), readTimeout);
    }

    private static LiveHttpConnectors.Spec spec(String cls, String url, LiveHttpConnectors.Auth auth,
                                               boolean allowPrivate, Duration connectTimeout, Duration readTimeout) {
        return new LiveHttpConnectors.Spec("pay", url, "POST", cls, null, null, auth, null,
                connectTimeout, readTimeout, 1, List.of(), allowPrivate, Map.of(),
                Map.of("amount", "/result/amount"), "/result/ref");
    }

    private static ConnectorRequest request(UUID op) {
        return new ConnectorRequest("t1", op, "att-1", 1, "root-1", "bk-1", "pay", Map.of("body", Map.of("x", 1)),
                Instant.now().plusSeconds(30));
    }

    @Test
    void successMapsOutputAndProviderRefAndSendsTheOperationIdAsIdempotencyKey() {
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec("a", "/pay", LiveHttpConnectors.Auth.NONE, true,
                Duration.ofSeconds(2)), SecretResolver.of(Map.of()), false);
        assertEquals(ConnectorProfile.LIVE, c.descriptor().profile());
        UUID op = UUID.randomUUID();
        ConnectorResult r = c.invoke(request(op));
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, r, String.valueOf(r));
        assertEquals("REF-42", s.providerRef());
        assertEquals(7, ((Map<?, ?>) s.output().get("mapped")).get("amount"));
        assertEquals(op.toString(), seen.get(0).get("idempotency-key"));
        // registered per tenant through the existing registry
        ConnectorRegistry reg = new ConnectorRegistry().register("t1", c);
        assertTrue(reg.find("t1", "pay").isPresent());
        assertFalse(reg.find("t2", "pay").isPresent());
    }

    @Test
    void readTimeoutAfterSendIsOutcomeUnknown() throws Exception {
        // The request timeout also bounds the connect phase (JDK HttpClient), so a connect that is slow under machine
        // load used to end as "connect timed out" (correctly no-effect) and the test flaked. Deterministic harness:
        // generous connect timeout, a warm-up call that leaves a connected keep-alive connection in the client pool,
        // then the measured call reuses it and the stub withholds the answer on a latch.
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec("c", base + "/slow", LiveHttpConnectors.Auth.NONE, true,
                Duration.ofSeconds(30), Duration.ofSeconds(1)), SecretResolver.of(Map.of()), false);
        assertInstanceOf(ConnectorResult.Success.class, c.invoke(request(UUID.randomUUID())), "warm-up");
        UUID op = UUID.randomUUID();
        stallOps.add(op.toString());
        ConnectorResult r = c.invoke(request(op));
        assertTrue(stalledArrived.await(30, java.util.concurrent.TimeUnit.SECONDS), "the request reached the stub");
        assertEquals(remotePorts.get(0), remotePorts.get(1), "measured call reused the connected connection (no connect)");
        assertInstanceOf(ConnectorResult.OutcomeUnknown.class, r, String.valueOf(r));
    }

    @Test
    void connectTimeoutBeforeSendIsRetryableNoEffect() {
        // 10.255.255.1 is not routable: the TCP connect never completes (or the network is unreachable). Either way
        // nothing was sent, so even class (c) is a no-effect transient failure (retryable), never outcome-unknown.
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec("c", "http://10.255.255.1:81/pay",
                LiveHttpConnectors.Auth.NONE, true, Duration.ofMillis(300), Duration.ofSeconds(30)),
                SecretResolver.of(Map.of()), false);
        ConnectorResult r = c.invoke(request(UUID.randomUUID()));
        ConnectorResult.TransientFailure t = assertInstanceOf(ConnectorResult.TransientFailure.class, r, String.valueOf(r));
        assertTrue(t.noEffect(), "nothing was sent: " + t.message());
        assertTrue(t.message().contains("connect timeout") || t.message().contains("not sent"), t.message());
    }

    @Test
    void clientErrorIsBusinessFailure() {
        statusByPath.put("/bad", 422);
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec("a", "/bad", LiveHttpConnectors.Auth.NONE, true,
                Duration.ofSeconds(2)), SecretResolver.of(Map.of()), false);
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class,
                c.invoke(request(UUID.randomUUID())));
        assertEquals("HTTP_422", f.code());
    }

    @Test
    void serverErrorIsRetryable() {
        statusByPath.put("/down", 500);
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec("a", "/down", LiveHttpConnectors.Auth.NONE, true,
                Duration.ofSeconds(2)), SecretResolver.of(Map.of()), false);
        assertInstanceOf(ConnectorResult.TransientFailure.class, c.invoke(request(UUID.randomUUID())));
        statusByPath.put("/busy", 503);
        HttpRestConnector c2 = LiveHttpConnectors.build("t1", spec("c", "/busy", LiveHttpConnectors.Auth.NONE, true,
                Duration.ofSeconds(2)), SecretResolver.of(Map.of()), false);
        ConnectorResult.TransientFailure t = assertInstanceOf(ConnectorResult.TransientFailure.class,
                c2.invoke(request(UUID.randomUUID())));
        assertTrue(t.noEffect(), "503 is a no-effect transient failure even for class (c)");
    }

    @Test
    void loopbackIsRefusedUnlessExplicitlyAllowed() {
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec("a", "/pay", LiveHttpConnectors.Auth.NONE, false,
                Duration.ofSeconds(2)), SecretResolver.of(Map.of()), false);
        ConnectorResult.TransientFailure t = assertInstanceOf(ConnectorResult.TransientFailure.class,
                c.invoke(request(UUID.randomUUID())));
        assertTrue(t.noEffect());
        assertTrue(t.message().contains("EGRESS_DENIED"), t.message());
        assertTrue(seen.isEmpty(), "nothing reached the stub");
    }

    @Test
    void bearerAndOauth2HeadersAreSent() {
        HttpRestConnector bearer = LiveHttpConnectors.build("t1", spec("a", "/pay",
                new LiveHttpConnectors.Auth(LiveHttpConnectors.AuthType.BEARER, "secret://t/token", null, null, null, null),
                true, Duration.ofSeconds(2)), SecretResolver.of(Map.of("secret://t/token", "abc")), false);
        assertInstanceOf(ConnectorResult.Success.class, bearer.invoke(request(UUID.randomUUID())));
        assertEquals("Bearer abc", seen.get(0).get("authorization"));

        HttpRestConnector oauth = LiveHttpConnectors.build("t1", spec("a", "/pay",
                new LiveHttpConnectors.Auth(LiveHttpConnectors.AuthType.OAUTH2_CLIENT_CREDENTIALS, null, base + "/token",
                        "client-1", "secret://t/cs", "pay"), true, Duration.ofSeconds(2)),
                SecretResolver.of(Map.of("secret://t/cs", "s3")), false);
        assertInstanceOf(ConnectorResult.Success.class, oauth.invoke(request(UUID.randomUUID())));
        assertInstanceOf(ConnectorResult.Success.class, oauth.invoke(request(UUID.randomUUID())));
        assertEquals(1, tokenCalls.get(), "token cached");
        assertEquals("Bearer tok-1", seen.get(seen.size() - 1).get("authorization"));
    }

    @Test
    void productionRefusesHttpUrlsAndMissingOrLiteralSecrets() {
        SecretResolver none = SecretResolver.of(Map.of());
        IllegalStateException http = assertThrows(IllegalStateException.class, () -> LiveHttpConnectors.build("t1",
                spec("a", "/pay", LiveHttpConnectors.Auth.NONE, true, null), none, true));
        assertTrue(http.getMessage().contains("https"), http.getMessage());

        LiveHttpConnectors.Spec https = new LiveHttpConnectors.Spec("pay", "https://api.example.org/v1", "POST", "a", null,
                null, new LiveHttpConnectors.Auth(LiveHttpConnectors.AuthType.BEARER, "secret://t/missing", null, null, null,
                null), null, null, null, 1, List.of(), false, Map.of(), Map.of(), null);
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> LiveHttpConnectors.build("t1", https, none, true));
        assertTrue(missing.getMessage().contains("cannot be resolved"), missing.getMessage());

        LiveHttpConnectors.Spec literal = new LiveHttpConnectors.Spec("pay", "https://api.example.org/v1", "POST", "a", null,
                null, new LiveHttpConnectors.Auth(LiveHttpConnectors.AuthType.BEARER, "plain-token", null, null, null, null),
                null, null, null, 1, List.of(), false, Map.of(), Map.of(), null);
        assertThrows(IllegalStateException.class, () -> LiveHttpConnectors.build("t1", literal, none, true));

        LiveHttpConnectors.Spec noKey = new LiveHttpConnectors.Spec("pay", "https://api.example.org/v1", "POST", "a", null,
                null, new LiveHttpConnectors.Auth(LiveHttpConnectors.AuthType.MTLS, null, null, null, null, null),
                null, null, null, 1, List.of(), false, Map.of(), Map.of(), null);
        assertThrows(IllegalStateException.class, () -> LiveHttpConnectors.build("t1", noKey, none, true));

        // with a resolvable reference the same production spec builds
        LiveHttpConnectors.build("t1", https, SecretResolver.of(Map.of("secret://t/missing", "x")), true);
    }

    // ---- SG-36: secret:// default headers on the live path (SL-18) ---------------------------------------------------

    /** Edge service-token headers by reference, plus one plain literal header. */
    private static final Map<String, String> EDGE_HEADERS = Map.of("X-Edge-Client-Id", "secret://edge/client-id",
            "X-Edge-Client-Secret", "secret://edge/client-secret", "X-Plain", "literal");

    private static final String BASIC_CREDENTIAL = "svc-user:svc-pass-3Lm";

    /** The auth credentials of every mode, raw and as sent; none may appear in a header other than Authorization. */
    private static final List<String> AUTH_MATERIAL = List.of(BASIC_CREDENTIAL, "svc-pass-3Lm",
            Base64.getEncoder().encodeToString(BASIC_CREDENTIAL.getBytes(StandardCharsets.UTF_8)), "bearer-token-7Fa",
            "client-secret-2Hx", "ks-pass-4Nd", "tok-1", "basic:", "Basic ", "Bearer ");

    /** The secret port. It is read on every call, so a test can rotate a value after the build. */
    final Map<String, String> port = new ConcurrentHashMap<>(Map.of(
            "secret://edge/client-id", "edge-id-5Kq",
            "secret://edge/client-secret", "edge-secret-9Zr",
            "secret://t/basic", BASIC_CREDENTIAL,
            "secret://t/token", "bearer-token-7Fa",
            "secret://t/cs", "client-secret-2Hx",
            "secret://t/ks-pass", "ks-pass-4Nd"));

    @TempDir
    Path tmp;

    @ParameterizedTest
    @EnumSource(value = LiveHttpConnectors.AuthType.class, names = {"NONE", "MTLS", "BASIC", "BEARER", "OAUTH2_CLIENT_CREDENTIALS"})
    void secretDefaultHeadersCarryTheSecretPortValueAndNeverTheAuthCredential(LiveHttpConnectors.AuthType type)
            throws Exception {
        HttpRestConnector c = LiveHttpConnectors.build("t1", edgeSpec(base + "/pay", base + "/token", type, EDGE_HEADERS,
                true), SecretResolver.of(port), false);
        ConnectorResult r = c.invoke(request(UUID.randomUUID()));
        assertInstanceOf(ConnectorResult.Success.class, r, type + ": " + r);
        Map<String, String> h = lastBusinessCall();
        assertEquals("edge-id-5Kq", h.get("x-edge-client-id"), type + ": the port's value of secret://edge/client-id");
        assertEquals("edge-secret-9Zr", h.get("x-edge-client-secret"),
                type + ": the port's value of secret://edge/client-secret");
        assertEquals("literal", h.get("x-plain"), type + ": a literal default header is sent as is");
        assertEquals(expectedAuthorization(type), h.get("authorization"), type + ": Authorization is the auth credential");
        assertAuthMaterialOnlyInAuthorization(type, h);
    }

    @ParameterizedTest
    @EnumSource(value = LiveHttpConnectors.AuthType.class, names = {"NONE", "MTLS", "BASIC", "BEARER", "OAUTH2_CLIENT_CREDENTIALS"})
    void secretDefaultHeadersAreReadFromTheSecretPortOnEveryCall(LiveHttpConnectors.AuthType type) throws Exception {
        // SL-18 resolves at call time: a rotated edge service token is used by the next call, without a rebuild
        HttpRestConnector c = LiveHttpConnectors.build("t1", edgeSpec(base + "/pay", base + "/token", type, EDGE_HEADERS,
                true), SecretResolver.of(port), false);
        assertInstanceOf(ConnectorResult.Success.class, c.invoke(request(UUID.randomUUID())), type.name());
        assertEquals("edge-secret-9Zr", lastBusinessCall().get("x-edge-client-secret"), type.name());
        port.put("secret://edge/client-secret", "edge-secret-rotated-1Qw");
        assertInstanceOf(ConnectorResult.Success.class, c.invoke(request(UUID.randomUUID())), type.name());
        Map<String, String> h = lastBusinessCall();
        assertEquals("edge-secret-rotated-1Qw", h.get("x-edge-client-secret"), type + ": the rotated value");
        assertEquals(expectedAuthorization(type), h.get("authorization"), type.name());
        assertAuthMaterialOnlyInAuthorization(type, h);
    }

    @ParameterizedTest
    @EnumSource(value = LiveHttpConnectors.AuthType.class, names = {"NONE", "MTLS", "BASIC", "BEARER", "OAUTH2_CLIENT_CREDENTIALS"})
    void anUnknownOrEmptyDefaultHeaderReferenceFailsTheBuildLikeAnAuthReference(LiveHttpConnectors.AuthType type)
            throws Exception {
        Map<String, String> unknown = Map.of("X-Edge-Client-Id", "secret://edge/missing", "X-Plain", "literal");
        LiveHttpConnectors.Spec local = edgeSpec(base + "/pay", base + "/token", type, unknown, true);
        IllegalStateException dev = assertThrows(IllegalStateException.class,
                () -> LiveHttpConnectors.build("t1", local, SecretResolver.of(port), false), type.name());
        assertTrue(dev.getMessage().contains("default-headers.X-Edge-Client-Id cannot be resolved")
                && dev.getMessage().contains("secret://edge/missing"), type + ": " + dev.getMessage());

        // production profile: the rules of auth.secret-ref (https; the reference resolves to a non-empty value)
        String api = "https://api.example.org/v1";
        String tokenUrl = "https://login.example.org/token";
        LiveHttpConnectors.Spec prodUnknown = edgeSpec(api, tokenUrl, type, unknown, false);
        IllegalStateException prod = assertThrows(IllegalStateException.class,
                () -> LiveHttpConnectors.build("t1", prodUnknown, SecretResolver.of(port), true), type.name());
        assertTrue(prod.getMessage().contains("default-headers.X-Edge-Client-Id cannot be resolved"),
                type + ": " + prod.getMessage());
        port.put("secret://edge/blank", " ");
        LiveHttpConnectors.Spec prodBlank = edgeSpec(api, tokenUrl, type,
                Map.of("X-Edge-Client-Id", "secret://edge/blank"), false);
        IllegalStateException blank = assertThrows(IllegalStateException.class,
                () -> LiveHttpConnectors.build("t1", prodBlank, SecretResolver.of(port), true), type.name());
        assertTrue(blank.getMessage().contains("default-headers.X-Edge-Client-Id resolves to an empty value"),
                type + ": " + blank.getMessage());

        // with resolvable references the same production spec builds (a literal header is a plain value, not a
        // credential reference), and building sends nothing
        LiveHttpConnectors.build("t1", edgeSpec(api, tokenUrl, type, EDGE_HEADERS, false), SecretResolver.of(port), true);
        assertTrue(seen.isEmpty(), type + ": building never calls the endpoint");
    }

    @ParameterizedTest
    @EnumSource(value = LiveHttpConnectors.AuthType.class, names = {"NONE", "MTLS", "BASIC", "BEARER", "OAUTH2_CLIENT_CREDENTIALS"})
    void aDefaultHeaderSecretGoneAfterTheBuildMeansTheRequestIsNotSent(LiveHttpConnectors.AuthType type) throws Exception {
        // SL-61: the reference resolved when the connector was built; later the secret is deleted (or the vault is down)
        HttpRestConnector c = LiveHttpConnectors.build("t1", edgeSpec(base + "/pay", base + "/token", type, EDGE_HEADERS,
                true), SecretResolver.of(port), false);
        port.remove("secret://edge/client-secret");
        ConnectorResult r = c.invoke(request(UUID.randomUUID()));
        ConnectorResult.TransientFailure t = assertInstanceOf(ConnectorResult.TransientFailure.class, r, type + ": " + r);
        assertTrue(t.noEffect(), type + ": " + t.message());
        assertTrue(t.message().contains("secret://edge/client-secret"), type + ": " + t.message());
        assertTrue(seen.isEmpty(), type + ": nothing was sent, not even a token request");
    }

    /**
     * SL-65: a secret with leading or trailing whitespace or a control character (typically the line break a file store
     * adds) fails the build; it is never trimmed, since trimming could hide a wrong secret, and never repeated.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("untidySecrets")
    void aSecretWithWhitespaceOrAControlCharacterFailsTheBuildAndIsNotTrimmed(String why, LiveHttpConnectors.AuthType type,
            String reference, String value, String name) throws Exception {
        // SL-85: the spec (and, for mtls, the client key store with its valid password) is made first; the untidy value
        // then reaches Sangam only through the secret port. A JDK 21 PKCS#12 store cannot even be written with a control
        // character in its password, so writing it with the untidy value would fail in the fixture, not in Sangam's rule.
        LiveHttpConnectors.Spec spec = edgeSpec(base + "/pay", base + "/token", type, EDGE_HEADERS, true);
        port.put(reference, value);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LiveHttpConnectors.build("t1", spec, SecretResolver.of(port), false), why);
        assertTrue(e.getMessage().startsWith("connector t1/pay: " + name + " "), why + ": " + e.getMessage());
        assertFalse(e.getMessage().contains("s3cr3t"), why + ": the value is repeated: " + e.getMessage());
    }

    static Stream<Arguments> untidySecrets() {
        return Stream.of(
                Arguments.of("bearer token, trailing newline", LiveHttpConnectors.AuthType.BEARER, "secret://t/token",
                        "s3cr3t-token\n", "auth.secret-ref"),
                Arguments.of("basic credential, trailing space", LiveHttpConnectors.AuthType.BASIC, "secret://t/basic",
                        "svc-user:s3cr3t-pass ", "auth.secret-ref"),
                Arguments.of("oauth2 client secret, trailing CR LF", LiveHttpConnectors.AuthType.OAUTH2_CLIENT_CREDENTIALS,
                        "secret://t/cs", "s3cr3t-cs\r\n", "auth.client-secret-ref"),
                // the client secret goes out with HTTP Basic (an Authorization header), so the header rule applies
                Arguments.of("oauth2 client secret, trailing space", LiveHttpConnectors.AuthType.OAUTH2_CLIENT_CREDENTIALS,
                        "secret://t/cs", "s3cr3t-cs ", "auth.client-secret-ref"),
                Arguments.of("key-store password, trailing newline", LiveHttpConnectors.AuthType.MTLS, "secret://t/ks-pass",
                        "s3cr3t-ks\n", "tls.key-store-password-ref"),
                Arguments.of("edge default header, leading space", LiveHttpConnectors.AuthType.NONE,
                        "secret://edge/client-id", " s3cr3t-edge", "default-headers.X-Edge-Client-Id"));
    }

    @Test
    void aKeyStorePasswordMayBeginOrEndWithASpaceItNeverGoesIntoAHeader() throws Exception {
        // SL-65: only a secret that becomes a header value must be free of leading and trailing whitespace; a password
        // may legitimately have them, so only control characters (the line break of a file store) are refused for it
        port.put("secret://t/ks-pass", " ks-pass-4Nd ");
        HttpRestConnector c = LiveHttpConnectors.build("t1", edgeSpec(base + "/pay", base + "/token",
                LiveHttpConnectors.AuthType.MTLS, EDGE_HEADERS, true), SecretResolver.of(port), false);
        assertInstanceOf(ConnectorResult.Success.class, c.invoke(request(UUID.randomUUID())));
    }

    @ParameterizedTest
    @EnumSource(value = LiveHttpConnectors.AuthType.class, names = {"NONE", "MTLS", "BASIC", "BEARER", "OAUTH2_CLIENT_CREDENTIALS"})
    void aDefaultHeaderSecretRotatedToAnUntidyValueMeansTheRequestIsNotSent(LiveHttpConnectors.AuthType type)
            throws Exception {
        // SL-65 at call time: the secret was tidy when the connector was built, then rotated with a trailing line break
        HttpRestConnector c = LiveHttpConnectors.build("t1", edgeSpec(base + "/pay", base + "/token", type, EDGE_HEADERS,
                true), SecretResolver.of(port), false);
        port.put("secret://edge/client-secret", "s3cr3t-rotated\n");
        ConnectorResult r = c.invoke(request(UUID.randomUUID()));
        ConnectorResult.TransientFailure t = assertInstanceOf(ConnectorResult.TransientFailure.class, r, type + ": " + r);
        assertTrue(t.noEffect(), type + ": " + t.message());
        assertTrue(t.message().contains("credential unavailable") && t.message().contains("secret://edge/client-secret"),
                type + ": " + t.message());
        assertFalse(t.message().contains("s3cr3t"), type + ": the value is repeated: " + t.message());
        assertTrue(seen.isEmpty(), type + ": nothing was sent, not even a token request");
    }

    @Test
    void aHeaderOrMethodConfigurationErrorFailsTheBuildAndNamesTheConnector() throws Exception {
        // SL-62: header names are case-insensitive; SL-65: names, values and the method must be valid HTTP
        for (Map<String, String> headers : List.of(Map.of("X-Trace", "1", "x-trace", "2"), Map.of("X Bad", "v"),
                Map.of("X-Trace", "v\r\nX-Injected: 1"))) {
            LiveHttpConnectors.Spec spec = edgeSpec(base + "/pay", base + "/token", LiveHttpConnectors.AuthType.NONE,
                    headers, true);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> LiveHttpConnectors.build("t1", spec, SecretResolver.of(port), false), headers.keySet().toString());
            assertTrue(e.getMessage().startsWith("connector t1/pay: "), e.getMessage());
            assertFalse(e.getMessage().contains("X-Injected"), "the value is repeated: " + e.getMessage());
        }
        LiveHttpConnectors.Spec badMethod = new LiveHttpConnectors.Spec("pay", base + "/pay", "GE T", "a", null, null,
                LiveHttpConnectors.Auth.NONE, null, Duration.ofSeconds(2), Duration.ofSeconds(2), 1, List.of(), true,
                Map.of(), Map.of(), null);
        IllegalStateException method = assertThrows(IllegalStateException.class,
                () -> LiveHttpConnectors.build("t1", badMethod, SecretResolver.of(port), false));
        assertTrue(method.getMessage().startsWith("connector t1/pay: "), method.getMessage());
        assertTrue(seen.isEmpty(), "building never calls the endpoint");
    }

    /** A live spec in the given auth mode, with the auth references, token URL and client key store the mode needs. */
    private LiveHttpConnectors.Spec edgeSpec(String url, String tokenUrl, LiveHttpConnectors.AuthType type,
                                             Map<String, String> defaultHeaders, boolean allowPrivate) throws Exception {
        LiveHttpConnectors.Auth auth = switch (type) {
            case NONE, MTLS -> new LiveHttpConnectors.Auth(type, null, null, null, null, null);
            case BASIC -> new LiveHttpConnectors.Auth(type, "secret://t/basic", null, null, null, null);
            case BEARER -> new LiveHttpConnectors.Auth(type, "secret://t/token", null, null, null, null);
            case OAUTH2_CLIENT_CREDENTIALS -> new LiveHttpConnectors.Auth(type, null, tokenUrl, "client-1", "secret://t/cs",
                    "pay");
            // the provider modes (M-067) carry their credential outside Authorization or sign the request; they have their
            // own tests (ProviderAuthTest), so the header-isolation cases above run over the five classic modes only
            default -> throw new IllegalArgumentException("not a classic auth mode: " + type);
        };
        // mtls: a loadable PKCS#12 client key store, its password by reference. The stub speaks plain HTTP, so there is
        // no handshake: this covers the builder's mtls branch, and the header assembly does not depend on TLS.
        LiveHttpConnectors.Tls tls = type == LiveHttpConnectors.AuthType.MTLS
                ? new LiveHttpConnectors.Tls(clientKeyStore().toString(), "secret://t/ks-pass", null, null) : null;
        return new LiveHttpConnectors.Spec("pay", url, "POST", "a", null, null, auth, tls, Duration.ofSeconds(2),
                Duration.ofSeconds(2), 1, List.of(), allowPrivate, defaultHeaders, Map.of(), null);
    }

    private Path clientKeyStore() throws Exception {
        Path p = tmp.resolve("client.p12");
        if (Files.notExists(p)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            try (OutputStream out = Files.newOutputStream(p)) {
                ks.store(out, port.get("secret://t/ks-pass").toCharArray());
            }
        }
        return p;
    }

    private static String expectedAuthorization(LiveHttpConnectors.AuthType type) {
        return switch (type) {
            case NONE, MTLS -> null;
            case BASIC -> "Basic " + Base64.getEncoder().encodeToString(BASIC_CREDENTIAL.getBytes(StandardCharsets.UTF_8));
            case BEARER -> "Bearer bearer-token-7Fa";
            case OAUTH2_CLIENT_CREDENTIALS -> "Bearer tok-1";
            default -> throw new IllegalArgumentException("not a classic auth mode: " + type);
        };
    }

    /** Headers of the last business call (the OAuth2 token call is not one). */
    private Map<String, String> lastBusinessCall() {
        for (int i = seen.size() - 1; i >= 0; i--) {
            if (!"/token".equals(seen.get(i).get(":path"))) {
                return seen.get(i);
            }
        }
        throw new AssertionError("no business call reached the stub");
    }

    private static void assertAuthMaterialOnlyInAuthorization(LiveHttpConnectors.AuthType type, Map<String, String> h) {
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey().equals("authorization") || e.getKey().equals(":path")) {
                continue;
            }
            for (String m : AUTH_MATERIAL) {
                assertFalse(e.getValue().contains(m), type + ": header " + e.getKey() + " carries auth material: "
                        + e.getValue());
            }
        }
    }
}
