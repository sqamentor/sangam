// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.content;

import org.sangam.core.ports.DocumentStore;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;
import java.util.TreeMap;

/**
 * S3-compatible document store (FLOWABLE-INTO-OPERATON FC-40; AWS S3, MinIO, Ceph RGW, OCI/GCS interop) with AWS Signature V4 over
 * {@link HttpClient}: no SDK dependency, path-style URLs ({@code endpoint/bucket/key}). {@code put} uses
 * {@code If-None-Match: *} so an existing key is never overwritten (stores that ignore the header still get unique
 * keys from the content service). Objects up to {@code maxBytes} are buffered to compute the payload hash; larger
 * uploads must use the file-system store or a multipart-capable gateway (documented limit).
 */
public class S3DocumentStore implements DocumentStore {

    private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final URI endpoint;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final long maxBytes;
    private final HttpClient http;

    public S3DocumentStore(URI endpoint, String bucket, String region, String accessKey, String secretKey, long maxBytes) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.maxBytes = maxBytes;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String type() {
        return "s3";
    }

    @Override
    public StoredObject put(String key, InputStream content, String contentType) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int n;
        long total = 0;
        while ((n = content.read(chunk)) > 0) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("object larger than the S3 store limit of " + maxBytes + " bytes");
            }
            buf.write(chunk, 0, n);
        }
        byte[] body = buf.toByteArray();
        String hash = HexFormat.of().formatHex(sha256(body));
        HttpRequest.Builder b = signed("PUT", key, hash, contentType == null ? "application/octet-stream" : contentType)
                .header("If-None-Match", "*")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<String> r = send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 412) {
            throw new java.nio.file.FileAlreadyExistsException(key);
        }
        if (r.statusCode() / 100 != 2) {
            throw new IOException("S3 PUT " + key + " failed: HTTP " + r.statusCode());
        }
        return new StoredObject(key, body.length, hash);
    }

    @Override
    public Optional<InputStream> get(String key) throws IOException {
        HttpResponse<InputStream> r = send(signed("GET", key, emptyHash(), null).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() == 404) {
            r.body().close();
            return Optional.empty();
        }
        if (r.statusCode() / 100 != 2) {
            r.body().close();
            throw new IOException("S3 GET " + key + " failed: HTTP " + r.statusCode());
        }
        return Optional.of(r.body());
    }

    @Override
    public boolean exists(String key) throws IOException {
        HttpResponse<Void> r = send(signed("HEAD", key, emptyHash(), null).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        return r.statusCode() / 100 == 2;
    }

    @Override
    public void delete(String key) throws IOException {
        HttpResponse<Void> r = send(signed("DELETE", key, emptyHash(), null).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() / 100 != 2 && r.statusCode() != 404) {
            throw new IOException("S3 DELETE " + key + " failed: HTTP " + r.statusCode());
        }
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> h) throws IOException {
        try {
            return http.send(req, h);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    HttpRequest.Builder signed(String method, String key, String payloadHash, String contentType) {
        if (key == null || key.isBlank() || key.contains("..")) {
            throw new IllegalArgumentException("invalid document key: " + key);
        }
        String path = "/" + bucket + "/" + encodePath(key);
        URI uri = endpoint.resolve(path);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String amzDate = AMZ.format(now);
        String day = DAY.format(now);
        String host = uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", host);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        if (contentType != null) {
            headers.put("content-type", contentType);
        }
        StringBuilder canonHeaders = new StringBuilder();
        headers.forEach((k, v) -> canonHeaders.append(k).append(':').append(v.trim()).append('\n'));
        String signedHeaders = String.join(";", headers.keySet());
        String canonical = method + "\n" + path + "\n\n" + canonHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = day + "/" + region + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + HexFormat.of().formatHex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, "s3");
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(kSigning, toSign));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders
                        + ", Signature=" + signature);
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        return b;
    }

    private static String encodePath(String key) {
        StringBuilder sb = new StringBuilder();
        for (String seg : key.split("/", -1)) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~"));
        }
        return sb.toString();
    }

    private static String emptyHash() {
        return HexFormat.of().formatHex(sha256(new byte[0]));
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
