// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.sangam.sdk.auth.TokenProvider;
import org.sangam.sdk.error.GoneException;
import org.sangam.sdk.error.Problem;
import org.sangam.sdk.error.SangamApiException;
import org.sangam.sdk.error.SangamException;
import org.sangam.sdk.error.StaleLeaseException;
import org.sangam.sdk.error.TransportException;
import org.sangam.sdk.log.SangamLogger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The single HTTP path of the SDK: base URL + {@code /api/v1}, bearer auth with one refresh on 401,
 * Idempotency-Key on every mutation (R41), problem+json decoding (RFC 9457), and jittered retries of safe calls.
 */
public final class HttpTransport {

    public static final String API_PREFIX = "/api/v1";
    public static final String SDK_VERSION = "0.1.0";

    private final HttpClient http;
    private final String base;
    private final TokenProvider tokens;
    private final ObjectMapper json;
    private final RetryPolicy retryPolicy;
    private final Duration requestTimeout;
    private final SangamLogger log;
    private final String userAgent;
    private final java.util.Map<String, String> defaultHeaders;

    public HttpTransport(HttpClient http, URI baseUrl, TokenProvider tokens, RetryPolicy retryPolicy,
                         Duration requestTimeout, SangamLogger log, String userAgent, java.util.Map<String, String> defaultHeaders) {
        this.http = Objects.requireNonNull(http);
        String b = baseUrl.toString();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith(API_PREFIX)) b = b.substring(0, b.length() - API_PREFIX.length());
        this.base = b;
        this.tokens = tokens == null ? TokenProvider.none() : tokens;
        this.retryPolicy = retryPolicy == null ? RetryPolicy.defaults() : retryPolicy;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        this.log = log == null ? SangamLogger.noop() : log;
        this.userAgent = userAgent == null ? "sangam-sdk-java/" + SDK_VERSION : userAgent;
        this.defaultHeaders = defaultHeaders == null ? java.util.Map.of() : java.util.Map.copyOf(defaultHeaders);
        this.json = defaultMapper();
    }

    public static ObjectMapper defaultMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public ObjectMapper json() {
        return json;
    }

    public SangamLogger logger() {
        return log;
    }

    public <T> ApiResponse<T> execute(ApiRequest req, Class<T> type) {
        return execute(req, type == null ? null : json.constructType(type));
    }

    public <T> ApiResponse<T> execute(ApiRequest req, JavaType type) {
        String idemKey = req.idempotencyKey;
        if (idemKey == null && req.isMutation()) {
            idemKey = UUID.randomUUID().toString();
        }
        boolean safe = !req.isMutation() || idemKey != null;
        int maxAttempts = req.retry && safe ? retryPolicy.maxAttempts() : 1;
        boolean refreshedAuth = false;
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                HttpResponse<byte[]> resp = sendOnce(req, idemKey);
                int status = resp.statusCode();
                if (status == 401 && !refreshedAuth) {
                    refreshedAuth = true;
                    tokens.invalidate();
                    attempt--; // an auth refresh does not consume a retry
                    continue;
                }
                if (status / 100 == 2) {
                    T body = decode(resp.body(), type);
                    return new ApiResponse<>(status, resp.headers(), body);
                }
                SangamApiException ex = toException(req, resp);
                if (ex.isRetryable() && attempt < maxAttempts) {
                    Duration d = retryAfter(resp).orElse(retryPolicy.delay(attempt));
                    log.debug("http.retry", "retrying after HTTP " + status,
                            Map.of("method", req.method, "path", req.path, "attempt", attempt, "delayMs", d.toMillis()));
                    sleep(d);
                    continue;
                }
                throw ex;
            } catch (IOException e) {
                if (attempt < maxAttempts) {
                    Duration d = retryPolicy.delay(attempt);
                    log.debug("http.retry", "retrying after transport error: " + e,
                            Map.of("method", req.method, "path", req.path, "attempt", attempt, "delayMs", d.toMillis()));
                    sleep(d);
                    continue;
                }
                throw new TransportException(req.method + " " + req.path + " failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SangamException(req.method + " " + req.path + " interrupted", e);
            }
        }
    }

    private HttpResponse<byte[]> sendOnce(ApiRequest req, String idemKey) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(req))
                .timeout(req.timeout != null ? req.timeout : requestTimeout)
                .header("Accept", "application/json, application/problem+json")
                .header("User-Agent", userAgent);
        String authorization = tokens.authorization();
        if (authorization != null) b.header("Authorization", authorization);
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        defaultHeaders.forEach((k, v) -> { if (!req.headers.containsKey(k)) b.header(k, v); });
        req.headers.forEach(b::header);
        HttpRequest.BodyPublisher publisher;
        if (req.rawBody != null) {
            b.header("Content-Type", req.rawContentType);
            publisher = HttpRequest.BodyPublishers.ofByteArray(req.rawBody);
        } else if (req.body != null) {
            b.header("Content-Type", "application/json");
            publisher = HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(req.body));
        } else if (req.isMutation()) {
            b.header("Content-Type", "application/json");
            publisher = HttpRequest.BodyPublishers.ofString("{}");
        } else {
            publisher = HttpRequest.BodyPublishers.noBody();
        }
        b.method(req.method, publisher);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(ApiRequest req) {
        StringBuilder sb = new StringBuilder(base).append(API_PREFIX);
        if (!req.path.startsWith("/")) sb.append('/');
        sb.append(req.path);
        char sep = '?';
        for (Map.Entry<String, Object> e : req.query.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Collection<?> c) {
                for (Object item : c) {
                    sb.append(sep).append(enc(e.getKey())).append('=').append(enc(String.valueOf(item)));
                    sep = '&';
                }
            } else {
                sb.append(sep).append(enc(e.getKey())).append('=').append(enc(String.valueOf(v)));
                sep = '&';
            }
        }
        return URI.create(sb.toString());
    }

    /** Percent-encodes one path segment (ids, keys, channel names). */
    public static String seg(String s) {
        return URLEncoder.encode(Objects.requireNonNull(s, "path parameter"), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private <T> T decode(byte[] body, JavaType type) throws IOException {
        if (type == null || body == null || body.length == 0) return null;
        if (type.getRawClass() == Void.class) return null;
        if (type.getRawClass() == byte[].class) {
            @SuppressWarnings("unchecked") T raw = (T) body;
            return raw;
        }
        return json.readValue(body, type);
    }

    private SangamApiException toException(ApiRequest req, HttpResponse<byte[]> resp) {
        int status = resp.statusCode();
        Problem p = null;
        byte[] body = resp.body();
        if (body != null && body.length > 0) {
            try {
                p = json.readValue(body, Problem.class);
            } catch (IOException ignored) {
                // not JSON; fall through to the synthetic problem
            }
        }
        if (p == null) {
            p = new Problem();
            p.status = status;
            p.title = "HTTP " + status;
            p.detail = body == null ? null : new String(body, StandardCharsets.UTF_8);
        }
        if (p.status == null) p.status = status;
        String msg = req.method + " " + req.path + " -> " + status
                + (p.title != null ? " " + p.title : "") + (p.detail != null ? ": " + p.detail : "");
        if (status == 409 && (p.is("stale-lease") || p.is("STALE_LEASE"))) return new StaleLeaseException(p, msg);
        if (status == 410) return new GoneException(p, msg);
        return new SangamApiException(status, p, msg);
    }

    private static java.util.Optional<Duration> retryAfter(HttpResponse<?> resp) {
        return resp.headers().firstValue("Retry-After").flatMap(v -> {
            try {
                return java.util.Optional.of(Duration.ofSeconds(Math.min(60, Long.parseLong(v.trim()))));
            } catch (NumberFormatException e) {
                return java.util.Optional.empty();
            }
        });
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SangamException("interrupted during retry back-off", e);
        }
    }

    /** Serialises a value with the SDK mapper (for payload hashing in callers). */
    public byte[] toJsonBytes(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new SangamException("cannot serialise value", e);
        }
    }
}
