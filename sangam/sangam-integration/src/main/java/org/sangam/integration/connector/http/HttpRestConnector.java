// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic HTTP/REST connector (R71; FLOWABLE-INTO-OPERATON FC-06 HTTP task; Operaton connect-plugin parity B-16).
 *
 * <p>Request: {@code method}, {@code path} (appended to the base URI; {@code {name}} placeholders are filled from the
 * input, URL-encoded), {@code query} (map), {@code headers} (map), {@code body} (any JSON value; a string is sent as
 * is). The operationId is sent in the configured idempotency header (default {@code Idempotency-Key}). Timeouts, retries
 * and mTLS come from the {@link HttpTransport}; egress is checked on every hop (R27).
 *
 * <p>Headers (SL-62): names are case-insensitive and each is sent once. The connector's own headers (Authorization, the
 * idempotency header, {@code X-Sangam-*}) replace a configured default header of the same name. The request's
 * {@code headers} come from the process, so a name the connector owns or fills from the secret port, or a name given
 * twice, is refused as {@code INVALID_REQUEST} and nothing is sent: credentials and Sangam's own headers never come
 * from process input. A {@code secret://} value the secret port cannot supply at call time means the request is not
 * sent (SL-61): a no-effect transient failure, or an unavailable lookup.
 *
 * <p>Validity (SL-65): a request header name or value, or a method, that is not valid HTTP is refused as
 * {@code INVALID_REQUEST} before anything is sent; configured headers and the default method are checked when the
 * connector is built. A secret with leading or trailing whitespace or a control character (the line break a file store
 * adds) is refused, never trimmed. No message shows a header value.
 *
 * <p>Response mapping: 2xx → success with {@code status}, {@code headers} and {@code body} (parsed JSON when the
 * content type is JSON); 4xx except 408/409/429 → business failure {@code HTTP_<status>}; 409 → business failure
 * unless the body says it is a duplicate of the same operation; 408/429/503 → transient no-effect; other 5xx and
 * transport losses → outcome unknown, or transient for an idempotent (class a) endpoint.
 *
 * <p>Lookup (class a/b): {@code GET lookupPath} with {@code {operationId}}; 200 → found (a body field
 * {@code status=FAILED} marks a failed effect), 404 → not found.
 *
 * <p>Serverless (M-067): a {@link RequestSigner} signs the final request (AWS SigV4); an {@link AsyncCallback} tells the
 * function where to send its result (the operationId is the correlation key) and a 2xx answer then means "accepted";
 * {@link ResponseMapping#failureHeader()} turns a 2xx that reports a function error into a business failure.
 */
public final class HttpRestConnector implements SangamConnector {

    /** Static configuration of one REST endpoint. */
    public record Config(String id, URI baseUri, CapabilityClass capabilityClass, String idempotencyHeader,
                         String lookupPath, String authorization, Map<String, String> defaultHeaders, Duration timeout,
                         ConnectorProfile profile, Authorizer authorizer, ResponseMapping mapping, String defaultMethod,
                         RequestSigner signer, AsyncCallback callback) {
        public Config {
            idempotencyHeader = idempotencyHeader == null ? "Idempotency-Key" : idempotencyHeader;
            defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
            checkConfiguredHeaders("default header", defaultHeaders);
            mapping = mapping == null ? ResponseMapping.NONE : mapping;
            defaultMethod = defaultMethod == null || defaultMethod.isBlank() ? "POST" : defaultMethod.toUpperCase(java.util.Locale.ROOT);
            if (!isMethod(defaultMethod)) {
                throw new IllegalArgumentException("method '" + HttpTransport.printable(defaultMethod)
                        + "' is not a valid HTTP method");
            }
        }

        /** No request signer and no async callback (the shape before M-067). */
        public Config(String id, URI baseUri, CapabilityClass capabilityClass, String idempotencyHeader, String lookupPath,
                      String authorization, Map<String, String> defaultHeaders, Duration timeout, ConnectorProfile profile,
                      Authorizer authorizer, ResponseMapping mapping, String defaultMethod) {
            this(id, baseUri, capabilityClass, idempotencyHeader, lookupPath, authorization, defaultHeaders, timeout, profile,
                    authorizer, mapping, defaultMethod, null, null);
        }

        /** Static {@code authorization} secret reference only, no response mapping. */
        public Config(String id, URI baseUri, CapabilityClass capabilityClass, String idempotencyHeader, String lookupPath,
                      String authorization, Map<String, String> defaultHeaders, Duration timeout, ConnectorProfile profile) {
            this(id, baseUri, capabilityClass, idempotencyHeader, lookupPath, authorization, defaultHeaders, timeout, profile,
                    null, null, null, null, null);
        }
    }

    /**
     * Dynamic {@code Authorization} header (for example an OAuth2 client-credentials token). Returns the header value,
     * or null for none. Throwing {@link AuthUnavailableException} means the request was not sent (no effect).
     */
    @FunctionalInterface
    public interface Authorizer {
        String authorization(String tenantId);
    }

    /**
     * M-067: signs the final request (AWS SigV4, {@link AwsRequestSigner#signer}). It sees the method, URI, every header that
     * will be sent and the body, and returns the headers to add; a header it returns replaces one of the same name.
     * Throwing {@link AuthUnavailableException} means the request was not sent (no effect).
     */
    @FunctionalInterface
    public interface RequestSigner {
        Map<String, String> sign(String tenantId, String method, URI uri, Map<String, String> headers, byte[] body);
    }

    /** The credential for a call could not be obtained; the call is not sent. */
    public static final class AuthUnavailableException extends RuntimeException {
        public AuthUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Mapping of a 2xx JSON body: {@code output} name → JSON pointer (added to the success output under {@code mapped});
     * {@code providerRefPointer} names the downstream reference (else the {@code Location} header). M-067:
     * {@code failureHeader} names a response header whose presence on a 2xx answer means the called code failed (AWS
     * Lambda's {@code X-Amz-Function-Error}); such an answer is a business failure {@code FUNCTION_ERROR} with the header
     * value as {@code errorType}, never a success.
     */
    public record ResponseMapping(Map<String, String> output, String providerRefPointer, String failureHeader) {
        public static final ResponseMapping NONE = new ResponseMapping(Map.of(), null, null);

        public ResponseMapping {
            output = output == null ? Map.of() : Map.copyOf(output);
            for (String p : output.values()) {
                requirePointer(p);
            }
            if (providerRefPointer != null) {
                requirePointer(providerRefPointer);
            }
            failureHeader = failureHeader == null || failureHeader.isBlank() ? null : failureHeader;
            if (failureHeader != null && !HttpTransport.isToken(failureHeader)) {
                throw new IllegalArgumentException("failure header name '" + HttpTransport.printable(failureHeader)
                        + "' is not valid HTTP");
            }
        }

        public ResponseMapping(Map<String, String> output, String providerRefPointer) {
            this(output, providerRefPointer, null);
        }

        private static void requirePointer(String p) {
            if (p == null || !(p.isEmpty() || p.startsWith("/"))) {
                throw new IllegalArgumentException("response mapping needs a JSON pointer (/a/b): " + p);
            }
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");
    private static final String SECRET_PREFIX = "secret://";
    /** Lower-case prefix of Sangam's own headers (operation id, attempt id); all of them belong to the connector. */
    private static final String SANGAM_HEADER_PREFIX = "x-sangam-";

    private final Config config;
    private final HttpTransport transport;
    private final SecretResolver secrets;
    private final ConnectorDescriptor descriptor;
    /** Default headers the connector fills from the secret port (SL-18); names compared case-insensitively. */
    private final Set<String> secretHeaders;

    public HttpRestConnector(Config config, HttpTransport transport, SecretResolver secrets) {
        this.config = config;
        this.transport = transport;
        this.secrets = secrets == null ? SecretResolver.fromEnvironment() : secrets;
        this.descriptor = new ConnectorDescriptor(config.id(), "http", config.capabilityClass(), List.of(), config.profile());
        Set<String> fromPort = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        config.defaultHeaders().forEach((name, value) -> {
            if (value.startsWith(SECRET_PREFIX)) {
                fromPort.add(name);
            }
        });
        this.secretHeaders = Collections.unmodifiableSet(fromPort);
    }

    /** Simulator profile against {@code RestEchoSimulator} ({@code POST /api/operations}, lookup by id), class (a). */
    public static HttpRestConnector simulator(String id, URI simulatorBase) {
        return new HttpRestConnector(new Config(id, simulatorBase, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, "Idempotency-Key",
                "/api/operations/{operationId}", null, Map.of(), Duration.ofSeconds(10), ConnectorProfile.SIMULATOR),
                HttpTransport.forSimulator(), SecretResolver.of(Map.of()));
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Owner decision 320: read-only when the method this request resolves to (its {@code method}, else the configured
     * default, exactly as {@link #invoke} resolves it) is {@code GET} or {@code HEAD}; the operation name plays no part.
     * Never with an {@link AsyncCallback}: such a call asks the endpoint to do work and send a result later.
     */
    @Override
    public boolean readOnly(ConnectorRequest r) {
        String method = r.str("method", config.defaultMethod()).toUpperCase(java.util.Locale.ROOT);
        return config.callback() == null && (method.equals("GET") || method.equals("HEAD"));
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        String method = r.str("method", config.defaultMethod()).toUpperCase(java.util.Locale.ROOT);
        URI uri;
        Map<String, String> requestHeaders;
        try {
            if (!isMethod(method)) {
                // SL-65: the method comes from the process; refused here rather than by the HTTP client
                throw new IllegalArgumentException("method '" + HttpTransport.printable(method)
                        + "' is not a valid HTTP method");
            }
            uri = resolve(r.str("path", "/"), r, r.map("query"));
            requestHeaders = requestHeaders(r);
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", e.getMessage());
        }
        Map<String, String> headers;
        try {
            headers = headers(r, requestHeaders);
        } catch (AuthUnavailableException e) {
            return ConnectorResult.TransientFailure.noEffect("credential unavailable, request not sent: " + e.getMessage());
        }
        AsyncCallback cb = config.callback();
        String correlationKey = r.operationId().toString();
        byte[] body;
        try {
            Object input = r.input().get("body");
            body = cb != null && cb.placement() == AsyncCallback.Placement.BODY
                    ? Json.bytes(withCallback(input, cb, correlationKey)) : bodyOf(input);
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", e.getMessage());
        }
        if (cb != null && cb.placement() == AsyncCallback.Placement.HEADERS) {
            cb.headers(correlationKey).forEach((k, v) -> own(headers, k, v));
        }
        if (body.length > 0) {
            headers.putIfAbsent("Content-Type", "application/json");
        }
        if (config.signer() != null) {
            try {
                Map<String, String> sig = config.signer().sign(r.tenantId(), method, uri,
                        Collections.unmodifiableMap(new LinkedHashMap<>(headers)), body);
                sig.forEach((k, v) -> own(headers, k, v));
            } catch (AuthUnavailableException e) {
                return ConnectorResult.TransientFailure.noEffect("credential unavailable, request not sent: "
                        + e.getMessage());
            }
        }
        boolean idempotent = method.equals("GET") || method.equals("PUT") || method.equals("DELETE")
                || config.capabilityClass() == CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP;
        HttpTransport.Call call = new HttpTransport.Call(method, uri, headers, body, config.timeout(), idempotent);
        HttpTransport.Outcome o = transport.execute(r.tenantId(), call, deadline(r));
        String failureHeader = config.mapping().failureHeader();
        if (failureHeader != null && o instanceof HttpTransport.Outcome.Response resp && resp.is2xx()
                && resp.header(failureHeader).isPresent()) {
            // M-067: the call reached the code and the code failed (Lambda X-Amz-Function-Error): never a success
            Map<String, Object> out = responseMap(resp);
            out.put("errorType", resp.header(failureHeader).get());
            return new ConnectorResult.BusinessFailure("FUNCTION_ERROR", abbreviate(resp.bodyString()), out);
        }
        ConnectorResult result = applyMapping(map(o, idempotent));
        if (cb != null && result instanceof ConnectorResult.Success s) {
            Map<String, Object> out = new LinkedHashMap<>(s.output());
            out.put("callback", cb.awaiting(correlationKey));
            return new ConnectorResult.Success(out, s.providerRef(), s.duplicate());
        }
        return result;
    }

    /**
     * M-067: the body with the callback envelope. A JSON object gets the envelope as field {@code cb.field()} (refused
     * when the process already set that field); any other body is wrapped as {@code {"payload": body, field: envelope}}.
     */
    @SuppressWarnings("unchecked")
    static Object withCallback(Object body, AsyncCallback cb, String correlationKey) {
        Object value = body;
        if (body instanceof String s) {
            var tree = Json.tree(s);
            value = tree == null ? s : Json.MAPPER.convertValue(tree, Object.class);
        } else if (body instanceof byte[]) {
            throw new IllegalArgumentException("an async callback needs a JSON body, not bytes");
        }
        if (value instanceof Map<?, ?> m) {
            if (m.containsKey(cb.field())) {
                throw new IllegalArgumentException("body field '" + cb.field() + "' is reserved for the callback");
            }
            Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) m);
            out.put(cb.field(), cb.envelope(correlationKey));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("payload", value);
        out.put(cb.field(), cb.envelope(correlationKey));
        return out;
    }

    /** Applies {@link ResponseMapping} to a success: mapped fields under {@code mapped}, providerRef from the body. */
    private ConnectorResult applyMapping(ConnectorResult result) {
        ResponseMapping m = config.mapping();
        if (!(result instanceof ConnectorResult.Success s) || (m.output().isEmpty() && m.providerRefPointer() == null)) {
            return result;
        }
        Object body = s.output() == null ? null : s.output().get("body");
        com.fasterxml.jackson.databind.JsonNode tree = body == null || body instanceof String ? null
                : Json.MAPPER.valueToTree(body);
        Map<String, Object> out = new LinkedHashMap<>(s.output());
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : m.output().entrySet()) {
            com.fasterxml.jackson.databind.JsonNode n = tree == null ? null : tree.at(e.getValue());
            mapped.put(e.getKey(), n == null || n.isMissingNode() || n.isNull() ? null
                    : Json.MAPPER.convertValue(n, Object.class));
        }
        if (!mapped.isEmpty()) {
            out.put("mapped", mapped);
        }
        String ref = s.providerRef();
        if (m.providerRefPointer() != null && tree != null) {
            com.fasterxml.jackson.databind.JsonNode n = tree.at(m.providerRefPointer());
            if (!n.isMissingNode() && !n.isNull()) {
                ref = n.isValueNode() ? n.asText() : n.toString();
            }
        }
        return new ConnectorResult.Success(out, ref, s.duplicate());
    }

    @Override
    public LookupResult lookup(ConnectorRequest r) {
        if (config.lookupPath() == null || config.capabilityClass() == CapabilityClass.C_NEITHER) {
            return new LookupResult.NotSupported();
        }
        URI uri = resolve(config.lookupPath(), r, Map.of());
        Map<String, String> lookupHeaders;
        try {
            lookupHeaders = headers(r, requestHeaders(r));
            if (config.signer() != null) {
                config.signer().sign(r.tenantId(), "GET", uri, Collections.unmodifiableMap(new LinkedHashMap<>(lookupHeaders)),
                        new byte[0]).forEach((k, v) -> own(lookupHeaders, k, v));
            }
        } catch (IllegalArgumentException e) {
            return new LookupResult.Unavailable("INVALID_REQUEST: " + e.getMessage());
        } catch (AuthUnavailableException e) {
            return new LookupResult.Unavailable("credential unavailable: " + e.getMessage());
        }
        HttpTransport.Outcome o = transport.execute(r.tenantId(), HttpTransport.Call.get(uri, lookupHeaders), deadline(r));
        return switch (o) {
            case HttpTransport.Outcome.Response resp when resp.status() == 404 -> new LookupResult.NotFound();
            case HttpTransport.Outcome.Response resp when resp.is2xx() -> {
                Map<String, Object> out = responseMap(resp);
                Object b = out.get("body");
                boolean failed = b instanceof Map<?, ?> m && "FAILED".equalsIgnoreCase(String.valueOf(m.get("status")));
                yield failed ? LookupResult.Found.failed("DOWNSTREAM_FAILED", out)
                        : LookupResult.Found.success(out, resp.header("Location").orElse(null));
            }
            case HttpTransport.Outcome.Response resp -> new LookupResult.Unavailable("lookup HTTP " + resp.status());
            case HttpTransport.Outcome.NoEffect ne -> new LookupResult.Unavailable(ne.reason());
            case HttpTransport.Outcome.Unknown u -> new LookupResult.Unavailable(u.reason());
        };
    }

    /** Shared mapping of a transport outcome to a connector result (also used by {@link SoapConnector}). */
    static ConnectorResult map(HttpTransport.Outcome o, boolean idempotent) {
        return switch (o) {
            case HttpTransport.Outcome.NoEffect ne -> ConnectorResult.TransientFailure.noEffect(ne.reason());
            case HttpTransport.Outcome.Unknown u -> idempotent
                    ? new ConnectorResult.TransientFailure(u.reason(), false, null)
                    : ConnectorResult.OutcomeUnknown.of(u.reason());
            case HttpTransport.Outcome.Response resp -> {
                int s = resp.status();
                Map<String, Object> out = responseMap(resp);
                if (resp.is2xx()) {
                    Object b = out.get("body");
                    boolean dup = b instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("duplicate"));
                    yield new ConnectorResult.Success(out, resp.header("Location").orElse(null), dup);
                }
                if (s == 408 || s == 429 || s == 503) {
                    yield new ConnectorResult.TransientFailure("HTTP " + s, true, resp.retryAfter().orElse(null));
                }
                if (s >= 400 && s < 500) {
                    yield new ConnectorResult.BusinessFailure("HTTP_" + s, abbreviate(resp.bodyString()), out);
                }
                yield idempotent ? new ConnectorResult.TransientFailure("HTTP " + s, false, null)
                        : ConnectorResult.OutcomeUnknown.of("HTTP " + s + " after the request was sent");
            }
        };
    }

    static Map<String, Object> responseMap(HttpTransport.Outcome.Response resp) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", resp.status());
        String ct = resp.header("Content-Type").orElse("");
        if (ct.contains("json")) {
            var tree = Json.tree(resp.body());
            out.put("body", tree == null ? resp.bodyString() : Json.MAPPER.convertValue(tree, Object.class));
        } else {
            out.put("body", resp.bodyString());
        }
        return out;
    }

    /**
     * SL-62: the request's own headers (the {@code headers} input, which comes from the process). A name the connector
     * owns (Authorization, the idempotency header, any {@code X-Sangam-} header) or fills from the secret port is
     * refused, and so is a name given twice in different letter case: credentials and Sangam's own headers never come
     * from process input, and a header is never sent twice. SL-65: a name or value that is not valid HTTP is refused
     * here too, and the message never shows the value.
     *
     * @throws IllegalArgumentException the request is invalid (INVALID_REQUEST; nothing is sent)
     */
    private Map<String, String> requestHeaders(ConnectorRequest r) {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Object> e : r.map("headers").entrySet()) {
            String name = e.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("request header without a name");
            }
            if (!HttpTransport.isToken(name)) {
                throw new IllegalArgumentException("request header name '" + HttpTransport.printable(name)
                        + "' is not valid HTTP");
            }
            if (!HttpTransport.isFieldValue(String.valueOf(e.getValue()))) {
                throw new IllegalArgumentException("request header '" + name + "' has a value that is not valid HTTP (a "
                        + "control character such as a line break, or a character above U+00FF; the value is not shown)");
            }
            if (name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase(config.idempotencyHeader())
                    || name.toLowerCase(Locale.ROOT).startsWith(SANGAM_HEADER_PREFIX)) {
                throw new IllegalArgumentException("request header '" + name + "' is reserved for the connector");
            }
            if (secretHeaders.contains(name)) {
                throw new IllegalArgumentException("request header '" + name
                        + "' is filled by the connector from the secret port");
            }
            if (out.put(name, String.valueOf(e.getValue())) != null) {
                throw new IllegalArgumentException("request header '" + name
                        + "' is given twice (header names are case-insensitive)");
            }
        }
        return out;
    }

    private Map<String, String> headers(ConnectorRequest r, Map<String, String> requestHeaders) {
        // SL-62: one value per header name, whatever its letter case
        Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        // SL-18: a default header value that is a secret reference (secret://...) is read from the secret port at call time, so static service
        // tokens for an edge access gate (for example a client-id / client-secret header pair) never sit in config.
        for (Map.Entry<String, String> e : config.defaultHeaders().entrySet()) {
            h.put(e.getKey(), configured("default header " + e.getKey(), e.getValue()));
        }
        h.putAll(requestHeaders);
        own(h, config.idempotencyHeader(), r.operationId().toString());
        own(h, "X-Sangam-Operation-Id", r.operationId().toString());
        if (r.attemptId() != null) {
            own(h, "X-Sangam-Attempt-Id", r.attemptId());
        }
        String authorization = null;
        if (config.authorization() != null) {
            authorization = configured("authorization", config.authorization());
            if (authorization.startsWith("basic:")) {
                authorization = "Basic " + Base64.getEncoder().encodeToString(authorization.substring(6)
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
        if (config.authorizer() != null) {
            String a = config.authorizer().authorization(r.tenantId());
            if (a != null) {
                authorization = a;
            }
        }
        if (authorization != null) {
            own(h, "Authorization", authorization);
        }
        return h;
    }

    /** SL-62: a header the connector sets replaces a configured one of the same name in any letter case. */
    static void own(Map<String, String> headers, String name, String value) {
        headers.remove(name);
        headers.put(name, value);
    }

    /**
     * SL-61: a configured header value; a {@code secret://} reference is read from the secret port now. A reference the
     * port cannot supply (unknown, blank, or the port itself failed) is a credential the call cannot get, so the request
     * is not sent ({@link AuthUnavailableException}, no effect). No exception leaves the connector: the invoker would
     * have to record it as an unknown outcome although nothing was sent. SL-65: so is a secret that is not tidy
     * ({@link #isTidySecret}); it is never trimmed, and its value is never shown.
     */
    private String configured(String what, String value) {
        if (!value.startsWith(SECRET_PREFIX)) {
            return value;
        }
        String v;
        try {
            v = secrets.require(value);
        } catch (RuntimeException e) {
            throw new AuthUnavailableException(what + ": " + value + " cannot be resolved (" + e.getMessage() + ")");
        }
        if (v == null || v.isBlank()) {
            throw new AuthUnavailableException(what + ": " + value + " resolves to an empty value");
        }
        if (!isTidySecret(v)) {
            throw new AuthUnavailableException(what + ": " + value + UNTIDY_SECRET);
        }
        return v;
    }

    /** Why a secret that becomes a header value is refused rather than trimmed (SL-65); the value is never shown. */
    static final String UNTIDY_SECRET = " resolves to a value with leading or trailing whitespace or a control character "
            + "such as a line break; secrets are never trimmed, since that could hide a wrong secret "
            + "(the value is not shown)";

    /** Why a secret that never goes into a header (a password) is refused (SL-65); the value is never shown. */
    static final String CONTROL_IN_SECRET = " resolves to a value with a control character such as a line break; "
            + "secrets are never trimmed, since that could hide a wrong secret (the value is not shown)";

    /**
     * SL-65: a secret that becomes (part of) a header value (a token, a basic credential, a client secret sent with HTTP
     * Basic, a default header): no leading or trailing whitespace, which a header value cannot carry, and no control
     * character (the line break a file store adds, a tab, NUL). Refused, never trimmed: trimming could hide a wrong secret.
     */
    static boolean isTidySecret(String s) {
        return s != null && !s.isEmpty() && !Character.isWhitespace(s.charAt(0))
                && !Character.isWhitespace(s.charAt(s.length() - 1)) && hasNoControlCharacter(s);
    }

    /**
     * SL-65: a secret that never goes into a header (a key-store or trust-store password) only has to be free of
     * control characters, which catches the line break of a file store; leading or trailing spaces are legitimate
     * password characters.
     */
    static boolean hasNoControlCharacter(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** A method the HTTP client can send: an RFC 9110 token, and not CONNECT (a tunnel, never a connector call). */
    static boolean isMethod(String method) {
        return HttpTransport.isToken(method) && !method.equals("CONNECT");
    }

    /**
     * SL-62 / SL-65: configured headers must be valid HTTP and each name given once, whatever its letter case. The
     * message names the header and never shows a value.
     *
     * @throws IllegalArgumentException the configuration is invalid
     */
    static void checkConfiguredHeaders(String what, Map<String, String> headers) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            String name = h.getKey();
            if (!HttpTransport.isToken(name)) {
                throw new IllegalArgumentException(what + " name '" + HttpTransport.printable(name) + "' is not valid HTTP");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException(what + " '" + name
                        + "' is given twice (header names are case-insensitive)");
            }
            if (!HttpTransport.isFieldValue(h.getValue())) {
                throw new IllegalArgumentException(what + " '" + name + "' has a value that is not valid HTTP (a control "
                        + "character such as a line break, or a character above U+00FF; the value is not shown)");
            }
        }
    }

    private URI resolve(String pathTemplate, ConnectorRequest r, Map<String, Object> query) {
        Matcher m = PLACEHOLDER.matcher(pathTemplate);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String v = key.equals("operationId") ? r.operationId().toString() : r.str(key);
            if (v == null) {
                throw new IllegalArgumentException("path placeholder {" + key + "} has no input value");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20")));
        }
        m.appendTail(sb);
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, Object> e : query.entrySet()) {
            q.append(q.isEmpty() ? "?" : "&").append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        String base = config.baseUri().toString();
        String path = sb.toString();
        if (base.endsWith("/") && path.startsWith("/")) {
            path = path.substring(1);
        } else if (!base.endsWith("/") && !path.startsWith("/") && !path.isEmpty()) {
            path = "/" + path;
        }
        URI uri = URI.create(base + path + q);
        if (!uri.getHost().equalsIgnoreCase(config.baseUri().getHost())) {
            throw new IllegalArgumentException("path must stay on the configured host");
        }
        return uri;
    }

    private static byte[] bodyOf(Object body) {
        if (body == null) {
            return new byte[0];
        }
        if (body instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        if (body instanceof byte[] b) {
            return b;
        }
        return Json.bytes(body);
    }

    private static long deadline(ConnectorRequest r) {
        return r.deadline() == null ? 0 : r.deadline().toEpochMilli();
    }

    static String abbreviate(String s) {
        return s == null ? null : s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
}
