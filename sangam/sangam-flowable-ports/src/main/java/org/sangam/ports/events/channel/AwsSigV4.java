// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 for any service (M-064: SQS, SNS, EventBridge over their public HTTPS APIs, no vendor SDK).
 * This is the one generic SigV4 signer of the product (also used by sangam-integration through {@code AwsRequestSigner}); {@code S3DocumentStore} keeps its S3-only variant (S3 does not
 * double-encode the path) until its owner moves it here. Algorithm: canonical request (method, path with every segment
 * URI-encoded once more, sorted canonical query, sorted lower-case signed headers including {@code host} and
 * {@code x-amz-date}, hex SHA-256 of the payload), string to sign, HMAC key chain {@code AWS4<secret> → date → region →
 * service → aws4_request}. The time is passed in (callers take it from an injected {@link java.time.Clock}).
 */
public final class AwsSigV4 {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /** Static credentials; the session token is set for temporary (STS) credentials only. */
    public record Credentials(String accessKey, String secretKey, String sessionTokenOrNull) {
        public Credentials {
            Objects.requireNonNull(accessKey, "accessKey");
            Objects.requireNonNull(secretKey, "secretKey");
        }

        @Override
        public String toString() {
            return "Credentials[" + accessKey + ", secret hidden]";
        }
    }

    private AwsSigV4() {
    }

    /**
     * Signs a request and returns the headers to add: {@code x-amz-date}, {@code x-amz-security-token} (temporary
     * credentials) and {@code Authorization}. {@code headers} are the request headers that are signed with it (for
     * example {@code content-type}, {@code x-amz-target}); {@code host} is taken from {@code uri}.
     */
    public static Map<String, String> sign(String method, URI uri, Map<String, String> headers, byte[] body, String region,
                                           String service, Credentials credentials, Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String day = DAY.format(now);
        TreeMap<String, String> canon = new TreeMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> canon.put(k.toLowerCase(Locale.ROOT).trim(), v == null ? "" : v.trim().replaceAll("\\s+", " ")));
        }
        canon.put("host", hostHeader(uri));
        canon.put("x-amz-date", amzDate);
        if (credentials.sessionTokenOrNull() != null) {
            canon.put("x-amz-security-token", credentials.sessionTokenOrNull());
        }
        StringBuilder canonHeaders = new StringBuilder();
        canon.forEach((k, v) -> canonHeaders.append(k).append(':').append(v).append('\n'));
        String signedHeaders = String.join(";", canon.keySet());
        String canonical = method.toUpperCase(Locale.ROOT) + "\n" + canonicalPath(uri.getRawPath()) + "\n"
                + canonicalQuery(uri.getRawQuery()) + "\n" + canonHeaders + "\n" + signedHeaders + "\n"
                + hex(sha256(body == null ? new byte[0] : body));
        String scope = day + "/" + region + "/" + service + "/aws4_request";
        String toSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] key = hmac(("AWS4" + credentials.secretKey()).getBytes(StandardCharsets.UTF_8), day);
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        String signature = hex(hmac(key, toSign));
        Map<String, String> out = new LinkedHashMap<>();
        out.put("x-amz-date", amzDate);
        if (credentials.sessionTokenOrNull() != null) {
            out.put("x-amz-security-token", credentials.sessionTokenOrNull());
        }
        out.put("Authorization", ALGORITHM + " Credential=" + credentials.accessKey() + "/" + scope + ", SignedHeaders="
                + signedHeaders + ", Signature=" + signature);
        return out;
    }

    static String hostHeader(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean dflt = port == -1 || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)
                || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80);
        return dflt ? host : host + ":" + port;
    }

    /** Every segment of the (already once-encoded) path is URI-encoded again; an empty path is {@code /}. */
    static String canonicalPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        List<String> segments = new ArrayList<>();
        for (String seg : rawPath.split("/", -1)) {
            segments.add(encode(seg));
        }
        return String.join("/", segments);
    }

    static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String[]> pairs = new ArrayList<>();
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            pairs.add(new String[]{encode(k), encode(v)});
        }
        pairs.sort((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]));
        StringBuilder sb = new StringBuilder();
        for (String[] p : pairs) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(p[0]).append('=').append(p[1]);
        }
        return sb.toString();
    }

    /** RFC 3986 encoding: unreserved characters stay, everything else is {@code %XX} in upper-case hex. */
    public static String encode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xf, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
