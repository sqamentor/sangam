// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.sangam.integration.connector.Json;

/**
 * Owner decision 165 (M-079): the loopback desktop agent for DSC signing. It runs on the signer's computer, listens on
 * the loopback interface only, and signs on the user's token (through {@link DscToken}, normally the JDK SunPKCS11
 * provider of {@link Pkcs11Tokens}) the CMS signed attributes the server built for a prepared PDF. It never sees the
 * PDF, never holds a key, and proves nothing: the server checks the signature, assembles the CMS and verifies the
 * signed PDF under decision 140.
 *
 * <p>Protections, all fail-closed:
 * <ul>
 *   <li>bound to 127.0.0.1 only (IPv4 loopback, never {@code ::1}); the caller must connect from 127.0.0.1 and the
 *       {@code Host} header must be {@code 127.0.0.1:<port>} or {@code localhost:<port>} (DNS rebinding);
 *       {@code [::1]} and IPv4-mapped forms are refused with {@code 421 HOST_NOT_ALLOWED};</li>
 *   <li>the {@code Origin} must be one of the configured Sangam UI origins (exact match), for every request including
 *       pre-flight; the CORS answer names that origin only;</li>
 *   <li>{@code POST} needs {@code Content-Type: application/json} (a cross-site form cannot send it without a
 *       pre-flight) and a small body;</li>
 *   <li>the signed attributes must be a DER SET carrying the announced SHA-256 document digest as an OCTET STRING, so
 *       the approval shows what is actually signed;</li>
 *   <li>every signature needs the user's approval in a local dialog ({@link Approval}); the token asks its PIN itself;
 *       one signature at a time.</li>
 * </ul>
 *
 * <p>API (JSON): {@code GET /v1/status}; {@code GET /v1/certificates} (the signing certificates on the token);
 * {@code POST /v1/sign} with {@code certificateSha256}, {@code algorithm} ({@code SHA256withRSA} or
 * {@code SHA256withECDSA}), {@code signedAttributes} (Base64 DER), {@code preparedDigestSha256} and
 * {@code documentName}; answer {@code signature} (Base64), {@code algorithm} and {@code certificateChain} (Base64 DER,
 * signer first).
 */
public final class DscLoopbackAgent implements AutoCloseable {

    /** The JCA signature algorithms the agent performs. */
    public static final List<String> ALGORITHMS = List.of("SHA256withRSA", "SHA256withECDSA");
    static final int MAX_BODY = 64 * 1024;
    static final int MAX_SIGNED_ATTRIBUTES = 8 * 1024;

    /**
     * The IPv4 loopback address 127.0.0.1, named explicitly: {@link InetAddress#getLoopbackAddress()} answers
     * {@code ::1} when the JVM prefers IPv6, and the UI accepts the agent only at 127.0.0.1 or localhost.
     */
    static final InetAddress BIND_ADDRESS;

    static {
        try {
            BIND_ADDRESS = InetAddress.getByAddress("127.0.0.1", new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** What the user is asked to approve. */
    public record SignRequest(String origin, String documentName, String preparedDigestSha256, String signerSubject,
                              String certificateSha256) {
    }

    /** The user's decision for one signature, asked locally on the signer's computer. */
    @FunctionalInterface
    public interface Approval {
        boolean approve(SignRequest request);
    }

    /**
     * @param port           loopback port (0 = any free port, for tests)
     * @param allowedOrigins the Sangam UI origins allowed to call the agent, for example {@code https://sangam.example.org}
     */
    public record Config(int port, Set<String> allowedOrigins) {
        public Config {
            allowedOrigins = Set.copyOf(allowedOrigins);
            if (allowedOrigins.isEmpty()) {
                throw new IllegalArgumentException("at least one allowed origin is required");
            }
            for (String o : allowedOrigins) {
                if (!o.matches("https?://[A-Za-z0-9.\\-]+(:\\d{1,5})?") || o.equals("null")) {
                    throw new IllegalArgumentException("not an origin (scheme://host[:port]): " + o);
                }
            }
        }
    }

    private final Config config;
    private final DscToken token;
    private final Approval approval;
    private final HttpServer server;
    private final Object signing = new Object();

    private DscLoopbackAgent(Config config, DscToken token, Approval approval) throws IOException {
        this.config = config;
        this.token = token;
        this.approval = approval;
        this.server = HttpServer.create(new InetSocketAddress(BIND_ADDRESS, config.port()), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
    }

    /** Starts the agent on the loopback interface. */
    public static DscLoopbackAgent start(Config config, DscToken token, Approval approval) throws IOException {
        DscLoopbackAgent a = new DscLoopbackAgent(Objects.requireNonNull(config, "config"),
                Objects.requireNonNull(token, "token"), Objects.requireNonNull(approval, "approval"));
        a.server.start();
        return a;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private record Answer(int status, Object body) {
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            String origin = x.getRequestHeaders().getFirst("Origin");
            Answer a;
            boolean allowed = origin != null && config.allowedOrigins().contains(origin);
            if (!peerIsLoopback(x.getRemoteAddress())
                    || !hostIsLoopback(x.getRequestHeaders().getFirst("Host"), port())) {
                a = error(421, "HOST_NOT_ALLOWED", "the agent answers only on its loopback address");
            } else if (!allowed) {
                a = error(403, "ORIGIN_NOT_ALLOWED", "this origin may not use the signing agent");
            } else if ("OPTIONS".equals(x.getRequestMethod())) {
                x.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST");
                x.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                x.getResponseHeaders().add("Access-Control-Max-Age", "600");
                if ("true".equals(x.getRequestHeaders().getFirst("Access-Control-Request-Private-Network"))) {
                    x.getResponseHeaders().add("Access-Control-Allow-Private-Network", "true");
                }
                a = new Answer(204, null);
            } else {
                a = route(x, origin);
            }
            send(x, a, allowed ? origin : null);
        } catch (RuntimeException e) {
            send(x, error(500, "AGENT_ERROR", e.getClass().getSimpleName()), null);
        } finally {
            x.close();
        }
    }

    private Answer route(HttpExchange x, String origin) throws IOException {
        String path = x.getRequestURI().getPath();
        String method = x.getRequestMethod();
        if ("GET".equals(method) && "/v1/status".equals(path)) {
            return new Answer(200, Map.of("agent", "sangam-dsc", "api", 1, "algorithms", ALGORITHMS));
        }
        if ("GET".equals(method) && "/v1/certificates".equals(path)) {
            return certificates();
        }
        if ("POST".equals(method) && "/v1/sign".equals(path)) {
            String ct = x.getRequestHeaders().getFirst("Content-Type");
            if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                return error(415, "CONTENT_TYPE", "application/json is required");
            }
            byte[] body = readLimited(x.getRequestBody());
            if (body == null) {
                return error(413, "TOO_LARGE", "the request body is too large");
            }
            return sign(Json.tree(body), origin);
        }
        return error(404, "NOT_FOUND", "no such endpoint");
    }

    private Answer certificates() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (DscToken.Entry e : token.entries()) {
                X509Certificate c = e.signer();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("certificateSha256", e.certificateSha256());
                m.put("subject", c.getSubjectX500Principal().getName());
                m.put("issuer", c.getIssuerX500Principal().getName());
                m.put("notAfter", c.getNotAfter().toInstant().toString());
                m.put("certificateChain", chain(e.chain()));
                out.add(m);
            }
        } catch (GeneralSecurityException ex) {
            return error(503, "TOKEN_UNAVAILABLE", "the token cannot be read: " + ex.getMessage());
        }
        return new Answer(200, Map.of("certificates", out));
    }

    private Answer sign(JsonNode req, String origin) {
        if (req == null || !req.isObject()) {
            return error(400, "INVALID_REQUEST", "a JSON object is required");
        }
        String certSha = text(req, "certificateSha256").toLowerCase(Locale.ROOT);
        String algorithm = text(req, "algorithm");
        String digest = text(req, "preparedDigestSha256").toLowerCase(Locale.ROOT);
        String documentName = text(req, "documentName");
        byte[] attrs;
        try {
            attrs = Base64.getDecoder().decode(text(req, "signedAttributes"));
        } catch (IllegalArgumentException e) {
            return error(400, "INVALID_REQUEST", "signedAttributes must be Base64");
        }
        if (!certSha.matches("[0-9a-f]{64}") || !digest.matches("[0-9a-f]{64}")) {
            return error(400, "INVALID_REQUEST", "certificateSha256 and preparedDigestSha256 must be SHA-256 hex");
        }
        if (!ALGORITHMS.contains(algorithm)) {
            return error(400, "ALGORITHM_NOT_ACCEPTED", "algorithm must be one of " + ALGORITHMS);
        }
        if (documentName.isBlank() || documentName.length() > 200) {
            return error(400, "INVALID_REQUEST", "documentName is required (at most 200 characters)");
        }
        if (!carriesDigest(attrs, HexFormat.of().parseHex(digest))) {
            return error(400, "ATTRIBUTES_NOT_BOUND", "signedAttributes must be a DER SET carrying the document digest");
        }
        synchronized (signing) {
            DscToken.Entry entry;
            try {
                entry = token.entries().stream().filter(e -> e.certificateSha256().equals(certSha)).findFirst()
                        .orElse(null);
            } catch (GeneralSecurityException e) {
                return error(503, "TOKEN_UNAVAILABLE", "the token cannot be read: " + e.getMessage());
            }
            if (entry == null) {
                return error(404, "CERTIFICATE_NOT_ON_TOKEN", "the chosen certificate is not on the token");
            }
            boolean rsa = entry.signer().getPublicKey() instanceof RSAPublicKey;
            boolean ec = entry.signer().getPublicKey() instanceof ECPublicKey;
            if (!(rsa && algorithm.endsWith("withRSA")) && !(ec && algorithm.endsWith("withECDSA"))) {
                return error(400, "ALGORITHM_NOT_ACCEPTED", "the algorithm does not match the key of the certificate");
            }
            SignRequest ask = new SignRequest(origin, documentName, digest,
                    entry.signer().getSubjectX500Principal().getName(), certSha);
            if (!approval.approve(ask)) {
                return error(403, "DECLINED", "the user declined to sign");
            }
            byte[] signature;
            try {
                signature = token.sign(certSha, algorithm, attrs);
            } catch (GeneralSecurityException e) {
                return error(502, "TOKEN_SIGNING_FAILED", "the token did not sign: " + e.getMessage());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("signature", Base64.getEncoder().encodeToString(signature));
            out.put("algorithm", algorithm);
            out.put("certificateChain", chain(entry.chain()));
            return new Answer(200, out);
        }
    }

    /** A DER SET (tag 0x31) whose content contains the OCTET STRING {@code 04 20 <digest>}. */
    static boolean carriesDigest(byte[] attrs, byte[] digest) {
        if (attrs == null || attrs.length < 2 || attrs.length > MAX_SIGNED_ATTRIBUTES || attrs[0] != 0x31
                || digest.length != 32) {
            return false;
        }
        outer:
        for (int i = 1; i + 34 <= attrs.length; i++) {
            if (attrs[i] != 0x04 || attrs[i + 1] != 0x20) {
                continue;
            }
            for (int j = 0; j < 32; j++) {
                if (attrs[i + 2 + j] != digest[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Only {@code 127.0.0.1:<port>} or {@code localhost:<port>} (localhost resolves to 127.0.0.1). {@code [::1]}, an
     * IPv4-mapped form such as {@code [::ffff:127.0.0.1]}, any other name and a missing port are refused (DNS rebinding,
     * coordinator ruling: the agent is IPv4 loopback only).
     */
    static boolean hostIsLoopback(String host, int port) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port);
    }

    /** The caller's socket address must be exactly 127.0.0.1 (an IPv6 peer, mapped or not, is refused). */
    static boolean peerIsLoopback(InetSocketAddress peer) {
        return peer != null && peer.getAddress() instanceof Inet4Address
                && BIND_ADDRESS.equals(peer.getAddress());
    }

    private static List<String> chain(List<X509Certificate> chain) {
        List<String> out = new ArrayList<>();
        for (X509Certificate c : chain) {
            try {
                out.add(Base64.getEncoder().encodeToString(c.getEncoded()));
            } catch (CertificateEncodingException e) {
                throw new IllegalStateException("certificate cannot be encoded", e);
            }
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText().trim() : "";
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        byte[] b = in.readNBytes(MAX_BODY + 1);
        return b.length > MAX_BODY ? null : b;
    }

    private static Answer error(int status, String code, String message) {
        return new Answer(status, Map.of("error", code, "message", message));
    }

    private static void send(HttpExchange x, Answer a, String origin) throws IOException {
        var h = x.getResponseHeaders();
        h.add("Cache-Control", "no-store");
        h.add("X-Content-Type-Options", "nosniff");
        h.add("Vary", "Origin");
        if (origin != null) {
            h.add("Access-Control-Allow-Origin", origin);
        }
        if (a.body() == null) {
            x.sendResponseHeaders(a.status(), -1);
            return;
        }
        byte[] out = Json.write(a.body()).getBytes(StandardCharsets.UTF_8);
        h.add("Content-Type", "application/json");
        x.sendResponseHeaders(a.status(), out.length);
        x.getResponseBody().write(out);
    }

    /**
     * Command line: {@code --pkcs11-config <file> --origin <https://ui-origin> [--origin ...] [--port <n>]}. The PIN and
     * every approval are asked in local dialogs ({@link DesktopPrompts}); without a desktop the agent does not start.
     */
    public static void main(String[] args) throws Exception {
        Path config = null;
        int port = 0;
        List<String> origins = new ArrayList<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--pkcs11-config" -> config = Path.of(args[i + 1]);
                case "--origin" -> origins.add(args[i + 1]);
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (config == null || origins.isEmpty() || port <= 0) {
            throw new IllegalArgumentException("usage: --pkcs11-config <file> --origin <origin> --port <port>");
        }
        DesktopPrompts prompts = DesktopPrompts.require();
        DscLoopbackAgent agent = start(new Config(port, Set.copyOf(origins)), Pkcs11Tokens.open(config, prompts), prompts);
        System.out.println("Sangam DSC agent listening on 127.0.0.1:" + agent.port());
    }
}
