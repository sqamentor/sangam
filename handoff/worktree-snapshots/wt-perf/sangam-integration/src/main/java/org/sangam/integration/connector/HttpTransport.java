// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import javax.net.ssl.SSLHandshakeException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * HTTP transport shared by the REST, SOAP, webhook and government connectors.
 *
 * <ul>
 *   <li>Connect and request timeouts; the request timeout never exceeds the caller's deadline.</li>
 *   <li>Optional mTLS ({@link TlsConfig}).</li>
 *   <li>Egress control on every hop (R27): redirects are followed manually, each hop re-checked, at most
 *       {@link EgressPolicy#maxRedirects()}; only GET/HEAD follow 301/302/303/307/308, a POST follows only 307/308.</li>
 *   <li>Bounded response bodies ({@link EgressPolicy#maxResponseBytes()}).</li>
 *   <li>Failure classification for A-2: a failure before the request reached the server (egress refusal, DNS,
 *       connection refused, TLS handshake, connect timeout) is {@link Outcome.NoEffect}; anything after the request
 *       may have been sent (request timeout, reset, truncated response) is {@link Outcome.Unknown}.</li>
 *   <li>Retries ({@link RetryPolicy}) only for no-effect failures and 429/503 answers, or for every failure when the
 *       caller declares the request idempotent (class a, operationId as Idempotency-Key).</li>
 * </ul>
 */
public final class HttpTransport {

    /** One HTTP exchange to perform. */
    public record Call(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout,
                       boolean idempotent) {
        public Call {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? new byte[0] : body;
        }

        public static Call get(URI uri, Map<String, String> headers) {
            return new Call("GET", uri, headers, null, null, true);
        }

        public static Call post(URI uri, Map<String, String> headers, byte[] body, boolean idempotent) {
            return new Call("POST", uri, headers, body, null, idempotent);
        }
    }

    /** Result of a call. */
    public sealed interface Outcome permits Outcome.Response, Outcome.NoEffect, Outcome.Unknown {

        /** The server answered. */
        record Response(int status, Map<String, List<String>> headers, byte[] body, int attempts) implements Outcome {
            public String bodyString() {
                return new String(body, StandardCharsets.UTF_8);
            }

            public Optional<String> header(String name) {
                for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                        return Optional.of(e.getValue().get(0));
                    }
                }
                return Optional.empty();
            }

            public boolean is2xx() {
                return status >= 200 && status < 300;
            }

            /** Retry-After in seconds, if present and numeric. */
            public Optional<Duration> retryAfter() {
                return header("Retry-After").flatMap(v -> {
                    try {
                        return Optional.of(Duration.ofSeconds(Long.parseLong(v.trim())));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                });
            }
        }

        /** The request provably did not reach the server; nothing happened downstream. */
        record NoEffect(String reason, int attempts) implements Outcome {
        }

        /** The request may have been processed; the answer was lost. */
        record Unknown(String reason, int attempts) implements Outcome {
        }
    }

    private static final Set<String> RESTRICTED = Set.of("host", "connection", "content-length", "expect", "upgrade");

    private final HttpClient client;
    private final EgressPolicy egress;
    private final RetryPolicy retry;
    private final Duration defaultTimeout;

    public HttpTransport(EgressPolicy egress, TlsConfig tls, Duration connectTimeout, Duration defaultTimeout,
                         RetryPolicy retry) {
        this.egress = egress;
        this.retry = retry == null ? RetryPolicy.defaults() : retry;
        this.defaultTimeout = defaultTimeout == null ? Duration.ofSeconds(30) : defaultTimeout;
        HttpClient.Builder b = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout)
                .version(HttpClient.Version.HTTP_1_1);
        if (tls != null) {
            b.sslContext(tls.sslContext()).sslParameters(tls.sslParameters());
        }
        this.client = b.build();
    }

    /** Transport for the simulator profile: loopback only, short timeouts, no TLS. */
    public static HttpTransport forSimulator() {
        return new HttpTransport(EgressPolicy.simulatorLoopback(), null, Duration.ofSeconds(5), Duration.ofSeconds(30),
                new RetryPolicy(2, Duration.ofMillis(100), Duration.ofSeconds(1), 0.5));
    }

    public EgressPolicy egress() {
        return egress;
    }

    /**
     * Performs the call with egress checks, redirects, bounded body and retries.
     *
     * @param tenantId       tenant whose allow-list applies
     * @param deadlineMillis epoch ms by which the call must end; 0 = none
     */
    public Outcome execute(String tenantId, Call call, long deadlineMillis) {
        int attempt = 1;
        while (true) {
            Outcome o = once(tenantId, call, deadlineMillis, attempt);
            boolean retryable = switch (o) {
                case Outcome.NoEffect ne -> !ne.reason().startsWith("EGRESS_DENIED");
                case Outcome.Unknown u -> call.idempotent();
                case Outcome.Response r -> r.status() == 429 || r.status() == 503
                        || (call.idempotent() && (r.status() == 502 || r.status() == 504));
            };
            if (!retryable || attempt >= retry.maxAttempts() || !retry.sleepBefore(attempt + 1, deadlineMillis)) {
                return o;
            }
            attempt++;
        }
    }

    private Outcome once(String tenantId, Call call, long deadlineMillis, int attempt) {
        URI uri = call.uri();
        String method = call.method().toUpperCase(Locale.ROOT);
        byte[] body = call.body();
        for (int hop = 0; ; hop++) {
            try {
                egress.check(tenantId, uri);
            } catch (EgressPolicy.EgressDeniedException e) {
                return new Outcome.NoEffect(e.getMessage(), attempt);
            }
            Duration timeout = call.timeout() == null ? defaultTimeout : call.timeout();
            if (deadlineMillis > 0) {
                long left = deadlineMillis - System.currentTimeMillis();
                if (left <= 0) {
                    return hop == 0 ? new Outcome.NoEffect("deadline passed before send", attempt)
                            : new Outcome.Unknown("deadline passed during redirects", attempt);
                }
                timeout = timeout.compareTo(Duration.ofMillis(left)) > 0 ? Duration.ofMillis(left) : timeout;
            }
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(timeout);
            for (Map.Entry<String, String> h : call.headers().entrySet()) {
                if (!RESTRICTED.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                    rb.header(h.getKey(), h.getValue());
                }
            }
            HttpRequest.BodyPublisher pub = body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body);
            rb.method(method, pub);
            HttpResponse<InputStream> resp;
            byte[] respBody;
            try {
                resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
                respBody = readBounded(resp.body(), egress.maxResponseBytes());
            } catch (HttpConnectTimeoutException e) {
                return new Outcome.NoEffect("connect timeout: " + e.getMessage(), attempt);
            } catch (HttpTimeoutException e) {
                return new Outcome.Unknown("request timeout after send: " + e.getMessage(), attempt);
            } catch (ResponseTooLargeException e) {
                return new Outcome.Unknown(e.getMessage(), attempt);
            } catch (IOException e) {
                return beforeSend(e) ? new Outcome.NoEffect("not sent: " + describe(e), attempt)
                        : new Outcome.Unknown("I/O after send: " + describe(e), attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome.Unknown("interrupted", attempt);
            }
            int status = resp.statusCode();
            if (isRedirect(status)) {
                Optional<String> loc = resp.headers().firstValue("Location");
                boolean followable = loc.isPresent() && (method.equals("GET") || method.equals("HEAD")
                        || status == 307 || status == 308);
                if (followable) {
                    if (hop >= egress.maxRedirects()) {
                        return new Outcome.Unknown("too many redirects (" + hop + ")", attempt);
                    }
                    uri = uri.resolve(loc.get());
                    if (status == 303) {
                        method = "GET";
                        body = new byte[0];
                    }
                    continue;
                }
            }
            return new Outcome.Response(status, new LinkedHashMap<>(resp.headers().map()), respBody, attempt);
        }
    }

    private static boolean isRedirect(int s) {
        return s == 301 || s == 302 || s == 303 || s == 307 || s == 308;
    }

    private static boolean beforeSend(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof NoRouteToHostException
                    || t instanceof SSLHandshakeException || t instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException(long max) {
            super("response larger than " + max + " bytes");
        }
    }

    private static byte[] readBounded(InputStream in, long max) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) {
                    throw new ResponseTooLargeException(max);
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
