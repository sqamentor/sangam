// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy.external;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.engine.tx.LockScope;

/**
 * {@link ExternalPolicyCheck} over HTTP with the JDK client (owner decision 166: a remote check, no in-process policy
 * library, no new dependency).
 *
 * <p><b>Request.</b> {@code POST <endpoint>} with {@code Content-Type: application/json} and, when a credential is
 * configured, {@code Authorization: Bearer <token>}. The body uses OPA's {@code input} envelope, so an OPA data API
 * path ({@code /v1/data/<package>/<rule>}) can be the endpoint as it is:
 * <pre>{@code
 * {"input": {"contract": "sangam.external-policy-request/1", "tenant": "...",
 *            "actor": {"id": "...", "auth_method": "OIDC", "groups": ["..."]},
 *            "action": "task.complete",
 *            "resource": {"method": "POST", "path_template": "/tasks/{taskId}/complete",
 *                         "path_params": {"taskId": "..."}}}}
 * }</pre>
 * The request body of the command itself is never sent.
 *
 * <p><b>Answer.</b> Status 200 with a JSON object; an OPA {@code result} member is unwrapped first. The decision is
 * either {@code "decision": "ALLOW" | "ALLOW_CONTINUE" | "DENY"} (case-insensitive, so a Cedar agent's
 * {@code "Allow"}/{@code "Deny"} is read too), or {@code "allow": true | false}, or a bare boolean result. Optional
 * {@code "reason_code"} and {@code "policy_id"} are kept only as safe tokens ({@link ExternalPolicyDecision#safeToken}).
 * Anything else (another status, not JSON, more than {@value #MAX_RESPONSE_BYTES} bytes, no decision, an unknown
 * decision value, an undefined OPA result) is {@link ExternalPolicyUnavailable}, never an allow.
 *
 * <p><b>Limits.</b> Redirects are not followed; the connect timeout and an overall deadline per call bound the wait;
 * no credential, request or response body is ever put in an exception message or log line. The call asserts that no
 * L2 lock is held (decision 86) like every outbound call.
 */
public final class HttpExternalPolicyCheck implements ExternalPolicyCheck {

    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param endpoint       the policy service URL (https; http only with {@code allowPlainHttp})
     * @param connectTimeout TCP and TLS connect timeout
     * @param timeout        overall deadline of one check (send, wait and read)
     * @param allowPlainHttp accept an http endpoint (for a loopback or service-mesh sidecar; the host refuses it in
     *                       production)
     */
    public record Settings(URI endpoint, Duration connectTimeout, Duration timeout, boolean allowPlainHttp) {
        public Settings {
            if (endpoint == null || endpoint.getScheme() == null || endpoint.getHost() == null) {
                throw new IllegalArgumentException("the external policy endpoint must be an absolute http(s) URL");
            }
            String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !(scheme.equals("http") && allowPlainHttp)) {
                throw new IllegalArgumentException("the external policy endpoint must use https (plain http needs"
                        + " allow-plain-http, which production refuses)");
            }
            if (endpoint.getRawUserInfo() != null) {
                throw new IllegalArgumentException("the external policy endpoint may not carry credentials in the URL;"
                        + " use the token file");
            }
            connectTimeout = positive(connectTimeout, "connect-timeout");
            timeout = positive(timeout, "timeout");
        }

        private static Duration positive(Duration d, String name) {
            if (d == null || d.isNegative() || d.isZero() || d.compareTo(MAX_TIMEOUT) > 0) {
                throw new IllegalArgumentException("external policy " + name + " must be above zero and at most "
                        + MAX_TIMEOUT.toSeconds() + " s");
            }
            return d;
        }

        /** Scheme, host and port only, for log lines and lock-scope messages (no path, query or credential). */
        public String origin() {
            return endpoint.getScheme() + "://" + endpoint.getHost() + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort());
        }
    }

    private final Settings settings;
    private final Supplier<String> bearerToken;
    private final HttpClient client;

    /**
     * @param bearerToken the credential, read at each call (so a rotated token file is picked up), or null when the
     *                    service needs none; a blank value at call time is {@link ExternalPolicyUnavailable.Reason#NOT_CONFIGURED}
     */
    public HttpExternalPolicyCheck(Settings settings, Supplier<String> bearerToken) {
        this(settings, bearerToken, HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    HttpExternalPolicyCheck(Settings settings, Supplier<String> bearerToken, HttpClient client) {
        if (settings == null || client == null) {
            throw new IllegalArgumentException("settings and an HTTP client are required");
        }
        this.settings = settings;
        this.bearerToken = bearerToken;
        this.client = client;
    }

    public Settings settings() {
        return settings;
    }

    @Override
    public ExternalPolicyDecision check(ExternalPolicyRequest request) throws ExternalPolicyUnavailable {
        LockScope.assertNoLockHeld("HTTP POST " + settings.origin() + " (external policy)");
        HttpRequest.Builder b = HttpRequest.newBuilder(settings.endpoint())
                .timeout(settings.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body(request)));
        if (bearerToken != null) {
            String token = token();
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<byte[]> response = send(b.build());
        if (response.statusCode() != 200) {
            throw new ExternalPolicyUnavailable(ExternalPolicyUnavailable.Reason.BAD_STATUS,
                    "HTTP " + response.statusCode() + " from " + settings.origin());
        }
        byte[] raw = response.body();
        if (raw == null || raw.length == 0 || raw.length > MAX_RESPONSE_BYTES) {
            throw new ExternalPolicyUnavailable(ExternalPolicyUnavailable.Reason.BAD_RESPONSE,
                    "empty or oversized answer from " + settings.origin());
        }
        return parse(raw, settings.origin());
    }

    private String token() throws ExternalPolicyUnavailable {
        String token;
        try {
            token = bearerToken.get();
        } catch (RuntimeException e) {
            // the exception may name the credential file; its message is not carried further
            throw new ExternalPolicyUnavailable(ExternalPolicyUnavailable.Reason.NOT_CONFIGURED,
                    "the policy service credential cannot be read");
        }
        if (token == null || token.isBlank() || token.chars().anyMatch(ch -> ch < 0x21 || ch > 0x7e)) {
            throw new ExternalPolicyUnavailable(ExternalPolicyUnavailable.Reason.NOT_CONFIGURED,
                    "the policy service credential is empty or not a single printable token");
        }
        return token;
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws ExternalPolicyUnavailable {
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info -> {
            long length = info.headers().firstValueAsLong("Content-Length").orElse(-1L);
            return length > MAX_RESPONSE_BYTES
                    ? HttpResponse.BodySubscribers.replacing(new byte[0])
                    : HttpResponse.BodySubscribers.ofByteArray();
        });
        try {
            return future.get(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ExternalPolicyUnavailable(ExternalPolicyUnavailable.Reason.TIMEOUT,
                    "no answer from " + settings.origin() + " within " + settings.timeout().toMillis() + " ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ExternalPolicyUnavailable(ExternalPolicyUnavailable.Reason.TRANSPORT, "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                throw new ExternalPolicyUnavailable(ExternalPolicyUnavailable.Reason.TIMEOUT,
                        "no answer from " + settings.origin() + " in time");
            }
            throw new ExternalPolicyUnavailable(ExternalPolicyUnavailable.Reason.TRANSPORT,
                    (cause instanceof IOException ? "I/O failure" : "call failure") + " towards " + settings.origin()
                            + " (" + (cause == null ? "unknown" : cause.getClass().getSimpleName()) + ")");
        }
    }

    static byte[] body(ExternalPolicyRequest r) {
        Map<String, Object> actor = new TreeMap<>();
        actor.put("id", r.actorId());
        if (r.authMethod() != null) {
            actor.put("auth_method", r.authMethod());
        }
        actor.put("groups", new TreeSet<>(r.groups()));
        Map<String, Object> resource = new TreeMap<>();
        if (r.method() != null) {
            resource.put("method", r.method());
        }
        if (r.pathTemplate() != null) {
            resource.put("path_template", r.pathTemplate());
        }
        resource.put("path_params", new TreeMap<>(r.pathParams()));
        Map<String, Object> input = new TreeMap<>();
        input.put("contract", ExternalPolicyRequest.CONTRACT);
        input.put("tenant", r.tenantId());
        input.put("actor", actor);
        input.put("action", r.commandType());
        input.put("resource", resource);
        try {
            return JSON.writeValueAsBytes(Map.of("input", input));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the external policy request", e);
        }
    }

    static ExternalPolicyDecision parse(byte[] raw, String origin) throws ExternalPolicyUnavailable {
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (IOException e) {
            throw bad(origin, "not JSON");
        }
        if (root == null || !root.isObject()) {
            throw bad(origin, "not a JSON object");
        }
        JsonNode node = root;
        if (root.has("result")) {
            node = root.get("result");
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? ExternalPolicyDecision.allowContinue(null) : ExternalPolicyDecision.deny(null, null);
        }
        if (!node.isObject()) {
            throw bad(origin, "no decision object");
        }
        String reason = text(node, "reason_code");
        String policyId = text(node, "policy_id");
        JsonNode decision = node.get("decision");
        if (decision != null && decision.isTextual()) {
            return switch (decision.textValue().strip().toUpperCase(Locale.ROOT)) {
                case "ALLOW", "ALLOW_CONTINUE" -> ExternalPolicyDecision.allowContinue(policyId);
                case "DENY" -> ExternalPolicyDecision.deny(reason, policyId);
                default -> throw bad(origin, "unknown decision value");
            };
        }
        JsonNode allow = node.get("allow");
        if (allow != null && allow.isBoolean()) {
            return allow.booleanValue() ? ExternalPolicyDecision.allowContinue(policyId)
                    : ExternalPolicyDecision.deny(reason, policyId);
        }
        throw bad(origin, "no decision member");
    }

    private static String text(JsonNode node, String name) {
        JsonNode v = node.get(name);
        return v != null && v.isTextual() ? v.textValue() : null;
    }

    private static ExternalPolicyUnavailable bad(String origin, String what) {
        return new ExternalPolicyUnavailable(ExternalPolicyUnavailable.Reason.BAD_RESPONSE, what + " from " + origin);
    }
}
