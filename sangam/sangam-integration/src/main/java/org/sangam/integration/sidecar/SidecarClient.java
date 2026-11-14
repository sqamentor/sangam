// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * The HTTP side shared by the server's sidecar clients (PDF validator, OCR; owner decisions 114, 153, 167). It checks
 * the configuration once (URL, transport, timeout, credentials) and reports a reason instead of guessing; it sends the
 * credentials on every call; it never follows a redirect; it reads at most a bounded answer. Any failure to obtain an
 * answer is a {@link CallFailed}: the callers turn it into their own "not available" error and never into a success.
 * TLS uses the JVM trust store ({@code javax.net.ssl.trustStore}) so the operator installs the sidecar's CA there.
 */
public final class SidecarClient {

    /** Settings as configured (raw strings are validated by {@link #SidecarClient}). */
    public record Settings(String url, String timeoutMs, String transport, SidecarCredentials credentials) {
    }

    /** No answer that can be used: not ready, unreachable, timed out, refused credentials, or bad status. */
    public static final class CallFailed extends Exception {
        private final int status;

        CallFailed(String message, int status) {
            super(message);
            this.status = status;
        }

        /** The HTTP status when the sidecar answered, otherwise 0. */
        public int status() {
            return status;
        }
    }

    /** A 2xx answer. */
    public record Answer(int status, byte[] body) {
    }

    private final String what;
    private final String notReady;
    private final String endpoint;
    private final Duration timeout;
    private final SidecarCredentials credentials;
    private final HttpClient http;

    /**
     * @param what           display name, for example {@code PDF validator}
     * @param urlSetting     the setting names of the URL, for messages
     * @param defaultTimeout used when {@code settings.timeoutMs()} is blank
     */
    public SidecarClient(String what, String urlSetting, Settings settings, Duration defaultTimeout) {
        this(what, urlSetting, settings, defaultTimeout, null);
    }

    /**
     * @param trust the TLS context that trusts the sidecar's certificate, or {@code null} for the JVM default trust
     *              store
     */
    public SidecarClient(String what, String urlSetting, Settings settings, Duration defaultTimeout,
                         javax.net.ssl.SSLContext trust) {
        this.what = what;
        String reason = null;
        String shown = null;
        long ms = defaultTimeout.toMillis();
        String url = settings.url();
        SidecarTransport transport = SidecarTransport.TLS;
        try {
            transport = SidecarTransport.parse(settings.transport());
        } catch (IllegalArgumentException e) {
            reason = "the " + what + " transport must be tls or mesh";
        }
        if (reason == null && (url == null || url.isBlank())) {
            reason = "no " + what + " service is configured (" + urlSetting + ")";
        } else if (reason == null) {
            try {
                URI base = new URI(url.strip());
                String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    reason = urlSetting + " must be an http or https URL";
                } else if (scheme.equals("http") && transport != SidecarTransport.MESH) {
                    reason = urlSetting + " must be https (owner decision 153); plain http only with transport mesh";
                } else if (base.getHost() == null || base.getHost().isBlank()) {
                    reason = urlSetting + " has no host";
                } else if (base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null) {
                    reason = urlSetting + " must not carry credentials, a query or a fragment";
                } else {
                    String path = base.getRawPath() == null ? "" : base.getRawPath();
                    while (path.endsWith("/")) {
                        path = path.substring(0, path.length() - 1);
                    }
                    shown = scheme + "://" + base.getRawAuthority() + path;
                }
            } catch (URISyntaxException e) {
                reason = urlSetting + " is not a valid URL";
            }
        }
        if (reason == null && settings.timeoutMs() != null && !settings.timeoutMs().isBlank()) {
            try {
                ms = Long.parseLong(settings.timeoutMs().strip());
            } catch (NumberFormatException e) {
                ms = -1;
            }
            if (ms <= 0) {
                reason = "the " + what + " timeout must be a positive number of milliseconds";
            }
        }
        this.notReady = reason;
        this.endpoint = reason == null ? shown : null;
        this.timeout = Duration.ofMillis(ms > 0 ? ms : defaultTimeout.toMillis());
        this.credentials = settings.credentials() == null ? SidecarCredentials.none(urlSetting) : settings.credentials();
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (trust != null) {
            builder.sslContext(trust);
        }
        this.http = reason == null ? builder.build() : null;
    }

    /** Why calls cannot be made now (configuration or credentials), or {@code null}. Never contains a secret. */
    public String notReadyReason() {
        if (notReady != null) {
            return notReady;
        }
        String c = credentials.notReadyReason();
        return c == null ? null : "the " + what + " credentials are not usable: " + c;
    }

    /** The configured base URL, or {@code null}. */
    public String endpoint() {
        return endpoint;
    }

    public Duration timeout() {
        return timeout;
    }

    /**
     * POSTs {@code body} to {@code path}{@code ?query} and returns a 2xx answer of at most {@code maxAnswerBytes};
     * anything else is {@link CallFailed}.
     */
    public Answer post(String path, String query, String contentType, String accept, byte[] body, int maxAnswerBytes)
            throws CallFailed {
        String reason = notReadyReason();
        if (reason != null) {
            throw new CallFailed("the " + what + " is not ready: " + reason, 0);
        }
        Map<String, String> auth;
        try {
            auth = credentials.headers();
        } catch (RuntimeException e) {
            throw new CallFailed("the " + what + " credentials are not usable", 0);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint + path + (query == null ? "" : "?" + query)))
                .timeout(timeout)
                .header("Content-Type", contentType)
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        auth.forEach(b::header);
        HttpResponse<InputStream> response;
        byte[] answer;
        try {
            response = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                answer = in.readNBytes(maxAnswerBytes + 1);
            }
        } catch (HttpTimeoutException e) {
            throw failed("did not answer within " + timeout.toMillis() + " ms", 0);
        } catch (IOException e) {
            throw failed("cannot be reached (" + e.getClass().getSimpleName() + ")", 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed("was interrupted while waiting for the answer", 0);
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw failed("refused the service credentials (HTTP " + status + ")", status);
        }
        if (status < 200 || status > 299) {
            throw failed("answered HTTP " + status, status);
        }
        if (answer.length > maxAnswerBytes) {
            throw failed("answered more than " + maxAnswerBytes + " bytes", status);
        }
        return new Answer(status, answer);
    }

    /** A failure about the answer itself (the sidecar answered something this client cannot use). */
    public CallFailed unusable(String problem) {
        return failed(problem, 0);
    }

    private CallFailed failed(String how, int status) {
        return new CallFailed("the " + what + " service at " + endpoint + " " + how, status);
    }
}
