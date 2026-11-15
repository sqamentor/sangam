// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.s3;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.http.HttpRestConnector;

/**
 * M-105 S3 connector (R71) through the connector SPI: {@code put}, {@code get}, {@code head} and {@code delete} of one
 * object in the configured bucket, over {@link S3ObjectClient} (egress policy, SigV4 by secret reference, no SDK).
 *
 * <p><b>Capability class (a), idempotent with lookup.</b> Every operation is idempotent on the store: a repeated
 * {@code PUT} of the same key and bytes leaves the same object, a repeated {@code DELETE} answers 204, reads change
 * nothing. A {@code put} stores the operationId as user metadata {@code sangam-operation-id}; the lookup is a
 * {@code HEAD} of the key that reports the operation as done only when that metadata names this operation, so a lost
 * answer is settled without a second upload. S3 is last-writer-wins: two operations that write one key overwrite each
 * other, as on the store itself.
 *
 * <p><b>Input.</b> {@code key} (required; {@link #keyPrefix} is prepended), and for {@code put} either {@code content}
 * (text, sent as UTF-8) or {@code contentBase64} (bytes), optional {@code contentType} and {@code metadata} (map). For
 * {@code get}, {@code encoding} {@code base64} (default) or {@code utf8} chooses the output field.
 *
 * <p><b>Output.</b> {@code put}: {@code key}, {@code etag}, {@code size}. {@code get}: {@code key}, {@code etag},
 * {@code size}, {@code contentType}, {@code metadata} and {@code contentBase64} or {@code content}. {@code head}:
 * {@code exists} plus the same fields without content. {@code delete}: {@code key}, {@code deleted=true}.
 *
 * <p><b>Failures.</b> An invalid key, input or metadata is {@code INVALID_REQUEST} and nothing is sent; a credential that
 * cannot be read is a no-effect transient failure. {@code get} of a missing key is {@code NOT_FOUND}; other 4xx answers
 * are {@code S3_<Code>} business failures (for example {@code S3_AccessDenied}), except 408/429/503 (transient, with
 * {@code Retry-After}). A 5xx or a lost answer is retried with the same operationId (class (a)).
 */
public final class S3Connector implements SangamConnector {

    /** User metadata name that carries the operationId of a {@code put}. */
    public static final String OPERATION_META = "sangam-operation-id";

    /** Static configuration. {@code maxPutBytes} bounds one upload; reads are bounded by the transport's egress policy. */
    public record Config(String id, String keyPrefix, long maxPutBytes, ConnectorProfile profile) {
        public Config {
            Objects.requireNonNull(id, "id");
            keyPrefix = keyPrefix == null ? "" : keyPrefix;
            if (!keyPrefix.isEmpty()) {
                S3ObjectClient.checkKey(keyPrefix.endsWith("/") ? keyPrefix.substring(0, keyPrefix.length() - 1) : keyPrefix);
            }
            maxPutBytes = maxPutBytes <= 0 ? 32L * 1024 * 1024 : maxPutBytes;
        }
    }

    private final Config config;
    private final S3ObjectClient client;
    private final ConnectorDescriptor descriptor;

    public S3Connector(Config config, S3ObjectClient client) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        this.descriptor = new ConnectorDescriptor(config.id(), "s3", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of("put", "get", "head", "delete"), config.profile());
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    /** The configured key prefix (prepended to every input key). */
    public String keyPrefix() {
        return config.keyPrefix();
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        long deadline = r.deadline() == null ? 0 : r.deadline().toEpochMilli();
        try {
            String key = config.keyPrefix() + r.required("key");
            S3ObjectClient.checkKey(key);
            return switch (r.operation()) {
                case "put" -> put(r, key, deadline);
                case "get" -> get(r, key, deadline);
                case "head" -> head(r, key, deadline);
                case "delete" -> delete(r, key, deadline);
                default -> ConnectorResult.BusinessFailure.of("INVALID_REQUEST", "unknown S3 operation '" + r.operation()
                        + "' (put, get, head, delete)");
            };
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", e.getMessage());
        } catch (HttpRestConnector.AuthUnavailableException e) {
            return ConnectorResult.TransientFailure.noEffect("credential unavailable, request not sent: " + e.getMessage());
        }
    }

    /** Owner decision 320: {@code get} (an HTTP GET) and {@code head} (an HTTP HEAD) read; put and delete do not. */
    @Override
    public boolean readOnly(ConnectorRequest r) {
        return "get".equals(r.operation()) || "head".equals(r.operation());
    }

    private ConnectorResult put(ConnectorRequest r, String key, long deadline) {
        byte[] body = content(r);
        if (body.length > config.maxPutBytes()) {
            return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", "object of " + body.length
                    + " bytes is larger than the configured limit of " + config.maxPutBytes());
        }
        Map<String, String> meta = new LinkedHashMap<>();
        r.map("metadata").forEach((k, v) -> meta.put(k, v == null ? "" : String.valueOf(v)));
        if (meta.keySet().stream().anyMatch(OPERATION_META::equalsIgnoreCase)) {
            throw new IllegalArgumentException("metadata '" + OPERATION_META + "' is reserved for the connector");
        }
        meta.put(OPERATION_META, r.operationId().toString());
        HttpTransport.Outcome o = client.put(r.tenantId(), key, body, r.str("contentType"), meta, deadline);
        return map(o, resp -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key", key);
            resp.header("ETag").ifPresent(v -> out.put("etag", v));
            out.put("size", body.length);
            return ConnectorResult.Success.of(out, key);
        });
    }

    private ConnectorResult get(ConnectorRequest r, String key, long deadline) {
        String encoding = r.str("encoding", "base64");
        if (!encoding.equals("base64") && !encoding.equals("utf8")) {
            throw new IllegalArgumentException("encoding must be base64 or utf8");
        }
        HttpTransport.Outcome o = client.get(r.tenantId(), key, deadline);
        if (o instanceof HttpTransport.Outcome.Response resp && resp.status() == 404) {
            return ConnectorResult.BusinessFailure.of("NOT_FOUND", "no object " + key);
        }
        return map(o, resp -> {
            Map<String, Object> out = describe(key, resp, resp.body().length);
            if (encoding.equals("utf8")) {
                out.put("content", new String(resp.body(), StandardCharsets.UTF_8));
            } else {
                out.put("contentBase64", Base64.getEncoder().encodeToString(resp.body()));
            }
            return ConnectorResult.Success.of(out, key);
        });
    }

    private ConnectorResult head(ConnectorRequest r, String key, long deadline) {
        HttpTransport.Outcome o = client.head(r.tenantId(), key, deadline);
        if (o instanceof HttpTransport.Outcome.Response resp && resp.status() == 404) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key", key);
            out.put("exists", false);
            return ConnectorResult.Success.of(out, key);
        }
        return map(o, resp -> {
            Map<String, Object> out = describe(key, resp, contentLength(resp));
            out.put("exists", true);
            return ConnectorResult.Success.of(out, key);
        });
    }

    private ConnectorResult delete(ConnectorRequest r, String key, long deadline) {
        return map(client.delete(r.tenantId(), key, deadline), resp -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key", key);
            out.put("deleted", true);
            return ConnectorResult.Success.of(out, key);
        });
    }

    /**
     * Settles a lost {@code put} answer: a HEAD of the key whose {@value #OPERATION_META} metadata names this operation is
     * the effect; a missing key or another operation's object is not found (the put is repeated with the same
     * operationId). The other operations are idempotent and repeated as they are.
     */
    @Override
    public LookupResult lookup(ConnectorRequest r) {
        if (!"put".equals(r.operation())) {
            return new LookupResult.NotFound();
        }
        String key;
        try {
            key = config.keyPrefix() + r.required("key");
            S3ObjectClient.checkKey(key);
        } catch (IllegalArgumentException e) {
            return new LookupResult.Unavailable("INVALID_REQUEST: " + e.getMessage());
        }
        HttpTransport.Outcome o;
        try {
            o = client.head(r.tenantId(), key, r.deadline() == null ? 0 : r.deadline().toEpochMilli());
        } catch (HttpRestConnector.AuthUnavailableException e) {
            return new LookupResult.Unavailable("credential unavailable: " + e.getMessage());
        }
        return switch (o) {
            case HttpTransport.Outcome.Response resp when resp.status() == 404 -> new LookupResult.NotFound();
            case HttpTransport.Outcome.Response resp when resp.is2xx() -> {
                String op = S3ObjectClient.metadata(resp).get(OPERATION_META);
                if (!r.operationId().toString().equals(op)) {
                    yield new LookupResult.NotFound();
                }
                Map<String, Object> out = describe(key, resp, contentLength(resp));
                yield LookupResult.Found.success(out, key);
            }
            case HttpTransport.Outcome.Response resp -> new LookupResult.Unavailable("lookup HTTP " + resp.status());
            case HttpTransport.Outcome.NoEffect ne -> new LookupResult.Unavailable(ne.reason());
            case HttpTransport.Outcome.Unknown u -> new LookupResult.Unavailable(u.reason());
        };
    }

    private static byte[] content(ConnectorRequest r) {
        Object text = r.input().get("content");
        Object b64 = r.input().get("contentBase64");
        if ((text == null) == (b64 == null)) {
            throw new IllegalArgumentException("put needs exactly one of content and contentBase64");
        }
        if (text != null) {
            return String.valueOf(text).getBytes(StandardCharsets.UTF_8);
        }
        try {
            return Base64.getDecoder().decode(String.valueOf(b64));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("contentBase64 is not valid base64");
        }
    }

    /** Connector outputs are copied with Map.copyOf, which refuses null values: a header the store did not send is absent. */
    private static Map<String, Object> describe(String key, HttpTransport.Outcome.Response resp, long size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        resp.header("ETag").ifPresent(v -> out.put("etag", v));
        out.put("size", size);
        resp.header("Content-Type").ifPresent(v -> out.put("contentType", v));
        out.put("metadata", S3ObjectClient.metadata(resp));
        return out;
    }

    private static long contentLength(HttpTransport.Outcome.Response resp) {
        try {
            return Long.parseLong(resp.header("Content-Length").orElse("-1").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The common mapping of a store answer; every operation is idempotent, so a loss is retried (class (a)). */
    static ConnectorResult map(HttpTransport.Outcome o,
                               java.util.function.Function<HttpTransport.Outcome.Response, ConnectorResult> onSuccess) {
        return switch (o) {
            case HttpTransport.Outcome.NoEffect ne -> ConnectorResult.TransientFailure.noEffect(ne.reason());
            case HttpTransport.Outcome.Unknown u -> ConnectorResult.OutcomeUnknown.of(u.reason());
            case HttpTransport.Outcome.Response resp when resp.is2xx() -> onSuccess.apply(resp);
            case HttpTransport.Outcome.Response resp when resp.status() == 408 || resp.status() == 429
                    || resp.status() == 503 -> new ConnectorResult.TransientFailure("HTTP " + resp.status()
                    + codeSuffix(resp), true, resp.retryAfter().orElse(null));
            case HttpTransport.Outcome.Response resp when resp.status() >= 400 && resp.status() < 500 -> {
                String code = S3ObjectClient.errorCode(resp);
                yield ConnectorResult.BusinessFailure.of(code == null ? "HTTP_" + resp.status() : "S3_" + code,
                        "S3 answered HTTP " + resp.status() + codeSuffix(resp));
            }
            case HttpTransport.Outcome.Response resp -> new ConnectorResult.TransientFailure("HTTP " + resp.status()
                    + codeSuffix(resp), false, null);
        };
    }

    private static String codeSuffix(HttpTransport.Outcome.Response resp) {
        String code = S3ObjectClient.errorCode(resp);
        return code == null ? "" : " (" + code + ")";
    }
}
