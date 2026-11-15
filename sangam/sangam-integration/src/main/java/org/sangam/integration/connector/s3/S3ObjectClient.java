// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.s3;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.http.AwsRequestSigner;
import org.sangam.integration.connector.http.HttpRestConnector;

/**
 * M-105 / M-057: object calls on an S3-compatible store (AWS S3, MinIO, Ceph RGW, any store that speaks the S3 REST API
 * with Signature Version 4) over the shared {@link HttpTransport}, so every call passes the {@link EgressPolicy} of its
 * tenant, the L2 lock rule, the bounded response and the no-effect/unknown classification. No vendor SDK.
 *
 * <ul>
 *   <li><b>Signing.</b> The product's one SigV4 signer ({@link AwsRequestSigner}, service {@code s3}); the access key,
 *       secret key and optional session token are secret references read on every call, so a rotated key needs no
 *       restart. A reference that cannot be read means the request is not sent
 *       ({@link HttpRestConnector.AuthUnavailableException}). The payload hash is sent as {@code x-amz-content-sha256}
 *       and signed, as S3 requires.</li>
 *   <li><b>Object keys.</b> Only the characters AWS lists as safe in object key names ({@code 0-9 a-z A-Z ! - _ . * ' ( )}
 *       and {@code /} as the separator), plus {@code =}, 1 to 1024 bytes, no empty, {@code .} or {@code ..} segment.
 *       {@code =} is what partitioned lake layouts are made of ({@code name=value/} segments, read by Spark, Trino,
 *       Athena and DuckDB; the M-057 history sink writes {@code tenant=…/date=…/}); it has no meaning in a path. They are
 *       all legal in a URI path (RFC 3986 {@code pchar}), so the key is sent as it is and the signer encodes each
 *       character once ({@code =} as {@code %3D}), which is exactly S3's canonical form (S3 does not double-encode the
 *       path, unlike the other AWS services). A key outside this set is refused before anything is sent
 *       ({@link IllegalArgumentException}), never silently rewritten.</li>
 *   <li><b>Addressing.</b> Path style ({@code {endpoint}/{bucket}/{key}}, MinIO and Ceph) or virtual-hosted style
 *       ({@code {bucket}.{endpoint host}/{key}}, AWS). Bucket names follow the S3 rules (3 to 63 of {@code a-z 0-9 . -}).</li>
 * </ul>
 */
public final class S3ObjectClient {

    /** Metadata header prefix of user metadata ({@code x-amz-meta-<name>}). */
    public static final String META_PREFIX = "x-amz-meta-";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9!\\-_.*'()=/]{1,1024}");
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern META_NAME = Pattern.compile("[a-z0-9-]{1,128}");
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /**
     * Where the bucket lives and how to sign for it.
     *
     * @param endpoint        {@code https://s3.<region>.amazonaws.com}, {@code https://minio.example:9000}, ...
     * @param pathStyle       true for {@code {endpoint}/{bucket}/{key}}, false for {@code {bucket}.{host}/{key}}
     * @param sessionTokenRef secret reference of an STS session token, or null for long-term keys
     */
    public record Settings(URI endpoint, String bucket, String region, boolean pathStyle, String accessKeyRef,
                           String secretKeyRef, String sessionTokenRef) {
        public Settings {
            Objects.requireNonNull(endpoint, "endpoint");
            String scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
            if (endpoint.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
                throw new IllegalArgumentException("S3 endpoint must be an absolute http(s) URL: " + endpoint);
            }
            if (endpoint.getUserInfo() != null) {
                throw new IllegalArgumentException("S3 endpoint must not carry credentials");
            }
            if (bucket == null || !BUCKET.matcher(bucket).matches() || bucket.contains("..")) {
                throw new IllegalArgumentException("not a valid S3 bucket name: " + bucket);
            }
            if (region == null || region.isBlank()) {
                throw new IllegalArgumentException("S3 region is required (the SigV4 scope; MinIO default us-east-1)");
            }
            if (blank(accessKeyRef) || blank(secretKeyRef)) {
                throw new IllegalArgumentException("S3 access-key-ref and secret-key-ref are required");
            }
            region = region.trim();
            sessionTokenRef = blank(sessionTokenRef) ? null : sessionTokenRef;
        }

        /** The origin every call of these settings goes to (the egress allow-list entry). */
        public String origin() {
            String host = pathStyle ? endpoint.getHost() : bucket + "." + endpoint.getHost();
            return endpoint.getScheme().toLowerCase(Locale.ROOT) + "://" + host
                    + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
        }
    }

    private final Settings settings;
    private final HttpTransport transport;
    private final HttpRestConnector.RequestSigner signer;

    public S3ObjectClient(Settings settings, HttpTransport transport, SecretResolver secrets, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.signer = AwsRequestSigner.signer(settings.region(), "s3", settings.accessKeyRef(), settings.secretKeyRef(),
                settings.sessionTokenRef(), Objects.requireNonNull(secrets, "secrets"), clock);
    }

    public Settings settings() {
        return settings;
    }

    /**
     * {@code PUT} an object. {@code metadata} names become {@code x-amz-meta-<name>} (lower case letters, digits and
     * {@code -}; values must be valid header values).
     *
     * @throws IllegalArgumentException an invalid key or metadata entry; nothing was sent
     * @throws HttpRestConnector.AuthUnavailableException a credential reference cannot be read; nothing was sent
     */
    public HttpTransport.Outcome put(String tenantId, String key, byte[] body, String contentType,
                                     Map<String, String> metadata, long deadlineMillis) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("content-type", contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
        if (metadata != null) {
            for (Map.Entry<String, String> m : metadata.entrySet()) {
                String name = m.getKey() == null ? "" : m.getKey().toLowerCase(Locale.ROOT);
                if (!META_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("metadata name '" + HttpTransport.printable(m.getKey())
                            + "' must be lower-case letters, digits or '-'");
                }
                if (!HttpTransport.isFieldValue(m.getValue())) {
                    throw new IllegalArgumentException("metadata '" + name + "' has a value that is not a valid header value");
                }
                h.put(META_PREFIX + name, m.getValue());
            }
        }
        return call(tenantId, "PUT", key, h, body == null ? new byte[0] : body, true, deadlineMillis);
    }

    /** {@code GET} an object; the body is bounded by the transport's egress policy. */
    public HttpTransport.Outcome get(String tenantId, String key, long deadlineMillis) {
        return call(tenantId, "GET", key, new LinkedHashMap<>(), new byte[0], true, deadlineMillis);
    }

    /** {@code HEAD} an object: status, size, ETag and user metadata, no body. */
    public HttpTransport.Outcome head(String tenantId, String key, long deadlineMillis) {
        return call(tenantId, "HEAD", key, new LinkedHashMap<>(), new byte[0], true, deadlineMillis);
    }

    /** {@code DELETE} an object (S3 answers 204 whether or not the key existed, so a repeat is harmless). */
    public HttpTransport.Outcome delete(String tenantId, String key, long deadlineMillis) {
        return call(tenantId, "DELETE", key, new LinkedHashMap<>(), new byte[0], true, deadlineMillis);
    }

    private HttpTransport.Outcome call(String tenantId, String method, String key, Map<String, String> headers, byte[] body,
                                       boolean idempotent, long deadlineMillis) {
        URI uri = objectUri(key);
        headers.put("x-amz-content-sha256", body.length == 0 ? EMPTY_SHA256 : sha256Hex(body));
        Map<String, String> signed = signer.sign(tenantId, method, uri, Map.copyOf(headers), body);
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.putAll(signed);
        return transport.execute(tenantId, new HttpTransport.Call(method, uri, all, body, null, idempotent), deadlineMillis);
    }

    /** The object's URI; {@link #checkKey} first. */
    public URI objectUri(String key) {
        checkKey(key);
        URI e = settings.endpoint();
        String scheme = e.getScheme().toLowerCase(Locale.ROOT);
        String port = e.getPort() == -1 ? "" : ":" + e.getPort();
        if (settings.pathStyle()) {
            String base = e.getRawPath() == null ? "" : e.getRawPath().replaceAll("/+$", "");
            return URI.create(scheme + "://" + e.getHost() + port + base + "/" + settings.bucket() + "/" + key);
        }
        return URI.create(scheme + "://" + settings.bucket() + "." + e.getHost() + port + "/" + key);
    }

    /**
     * Refuses a key outside the AWS safe-character set plus {@code =} (see the class comment), a leading or trailing
     * {@code /}, and an empty, {@code .} or {@code ..} segment.
     */
    public static void checkKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("object key must be 1-1024 of the S3 safe characters 0-9 a-z A-Z ! - _ . * ' ( )"
                    + " = and '/': " + HttpTransport.printable(key));
        }
        if (key.startsWith("/") || key.endsWith("/")) {
            throw new IllegalArgumentException("object key must not start or end with '/': " + key);
        }
        for (String seg : key.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException("object key has an empty, '.' or '..' segment: " + key);
            }
        }
    }

    /** True when {@code s} could be (part of) an object key; for callers that build keys from data. */
    public static boolean isKeySafe(String s) {
        return s != null && !s.isEmpty() && KEY.matcher(s).matches();
    }

    /** The user metadata of a HEAD or GET answer, names without the {@code x-amz-meta-} prefix, lower case. */
    public static Map<String, String> metadata(HttpTransport.Outcome.Response r) {
        Map<String, String> out = new LinkedHashMap<>();
        r.headers().forEach((k, v) -> {
            if (k != null && k.toLowerCase(Locale.ROOT).startsWith(META_PREFIX) && !v.isEmpty()) {
                out.put(k.substring(META_PREFIX.length()).toLowerCase(Locale.ROOT), v.get(0));
            }
        });
        return out;
    }

    /** The S3 error code of an XML error answer ({@code <Code>NoSuchKey</Code>}), or null. */
    public static String errorCode(HttpTransport.Outcome.Response r) {
        String b = r.body() == null ? "" : new String(r.body(), StandardCharsets.UTF_8);
        int i = b.indexOf("<Code>");
        int j = i < 0 ? -1 : b.indexOf("</Code>", i);
        return i < 0 || j < 0 ? null : b.substring(i + 6, j).trim();
    }

    static String sha256Hex(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
