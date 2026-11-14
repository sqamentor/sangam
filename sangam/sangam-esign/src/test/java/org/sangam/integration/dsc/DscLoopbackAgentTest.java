// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.Json;
import org.sangam.esign.EsignTestPki;

/**
 * Owner decision 165: the loopback agent answers only a configured origin on its loopback host, signs only bound
 * attributes, and only after the user's approval. A software key stands in for the token (SunPKCS11 itself needs a
 * hardware token and is not exercised here).
 */
class DscLoopbackAgentTest {

    private static final String UI = "https://sangam.example.org";

    private EsignTestPki.Party party;
    private DscToken.Entry entry;
    private final List<DscLoopbackAgent.SignRequest> asked = new ArrayList<>();
    private final AtomicInteger signed = new AtomicInteger();
    private volatile boolean approve = true;
    private DscLoopbackAgent agent;
    private byte[] digest;

    @BeforeEach
    void start() throws Exception {
        party = EsignTestPki.selfSigned("Asha Verma", false);
        entry = new DscToken.Entry("key-1", List.of(party.cert()), DscToken.sha256(party.cert()));
        DscToken token = new DscToken() {
            @Override
            public List<Entry> entries() {
                return List.of(entry);
            }

            @Override
            public byte[] sign(String sha, String algorithm, byte[] data) throws GeneralSecurityException {
                signed.incrementAndGet();
                Signature s = Signature.getInstance(algorithm);
                s.initSign(party.keys().getPrivate());
                s.update(data);
                return s.sign();
            }
        };
        agent = DscLoopbackAgent.start(new DscLoopbackAgent.Config(0, Set.of(UI)), token, r -> {
            asked.add(r);
            return approve;
        });
        digest = MessageDigest.getInstance("SHA-256").digest("prepared".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void stop() {
        agent.close();
    }

    private record Reply(int status, Map<String, String> headers, String body) {
        JsonNode json() {
            return Json.tree(body);
        }
    }

    /** A raw HTTP/1.1 exchange, so the Host and Origin headers are exactly what a browser or an attacker sends. */
    private Reply call(String method, String path, String host, String origin, String contentType, String body,
                       Map<String, String> extra) throws Exception {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), agent.port())) {
            byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder req = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\n"
                    + "Connection: close\r\nContent-Length: " + b.length + "\r\n");
            if (origin != null) {
                req.append("Origin: ").append(origin).append("\r\n");
            }
            if (contentType != null) {
                req.append("Content-Type: ").append(contentType).append("\r\n");
            }
            extra.forEach((k, v) -> req.append(k).append(": ").append(v).append("\r\n"));
            req.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(b);
            out.flush();
            InputStream in = s.getInputStream();
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int split = raw.indexOf("\r\n\r\n");
            String[] lines = raw.substring(0, split).split("\r\n");
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            return new Reply(Integer.parseInt(lines[0].split(" ")[1]), headers, raw.substring(split + 4));
        }
    }

    private String host() {
        return "127.0.0.1:" + agent.port();
    }

    private Reply get(String path, String origin) throws Exception {
        return call("GET", path, host(), origin, null, null, Map.of());
    }

    private static byte[] attributes(byte[] digest) {
        byte[] a = new byte[2 + 2 + 32];
        a[0] = 0x31;
        a[1] = 34;
        a[2] = 0x04;
        a[3] = 0x20;
        System.arraycopy(digest, 0, a, 4, 32);
        return a;
    }

    private String signBody(byte[] attrs, String algorithm, String certSha) {
        return Json.write(Map.of("certificateSha256", certSha, "algorithm", algorithm,
                "signedAttributes", Base64.getEncoder().encodeToString(attrs),
                "preparedDigestSha256", HexFormat.of().formatHex(digest), "documentName", "Sanction order 12"));
    }

    private Reply sign(byte[] attrs, String algorithm, String certSha) throws Exception {
        return call("POST", "/v1/sign", host(), UI, "application/json", signBody(attrs, algorithm, certSha), Map.of());
    }

    @Test
    void theConfiguredOriginGetsAnAnswerForItselfOnly() throws Exception {
        Reply r = get("/v1/status", UI);
        assertEquals(200, r.status());
        assertEquals(UI, r.headers().get("access-control-allow-origin"));
        assertEquals("no-store", r.headers().get("cache-control"));
        assertEquals("sangam-dsc", r.json().path("agent").asText());
    }

    @Test
    void otherOriginsAndNoOriginAreRefused() throws Exception {
        for (String origin : new String[]{"https://evil.example", "null", UI + ".evil.example", null}) {
            Reply r = get("/v1/certificates", origin);
            assertEquals(403, r.status(), String.valueOf(origin));
            assertEquals("ORIGIN_NOT_ALLOWED", r.json().path("error").asText());
            assertNull(r.headers().get("access-control-allow-origin"));
        }
    }

    @Test
    void aRebindingHostIsRefused() throws Exception {
        Reply r = call("GET", "/v1/certificates", "attacker.example:" + agent.port(), UI, null, null, Map.of());
        assertEquals(421, r.status());
        assertEquals("HOST_NOT_ALLOWED", r.json().path("error").asText());
        assertEquals(200, call("GET", "/v1/status", "localhost:" + agent.port(), UI, null, null, Map.of()).status());
    }

    @Test
    void preflightIsAnsweredForTheConfiguredOrigin() throws Exception {
        Reply r = call("OPTIONS", "/v1/sign", host(), UI, null, null, Map.of("Access-Control-Request-Method", "POST",
                "Access-Control-Request-Private-Network", "true"));
        assertEquals(204, r.status());
        assertEquals(UI, r.headers().get("access-control-allow-origin"));
        assertEquals("GET, POST", r.headers().get("access-control-allow-methods"));
        assertEquals("true", r.headers().get("access-control-allow-private-network"));
        assertEquals(403, call("OPTIONS", "/v1/sign", host(), "https://evil.example", null, null, Map.of()).status());
    }

    @Test
    void theCertificatesOnTheTokenAreListed() throws Exception {
        JsonNode certs = get("/v1/certificates", UI).json().path("certificates");
        assertEquals(1, certs.size());
        assertEquals(entry.certificateSha256(), certs.get(0).path("certificateSha256").asText());
        assertTrue(certs.get(0).path("subject").asText().contains("Asha Verma"));
        assertEquals(1, certs.get(0).path("certificateChain").size());
    }

    @Test
    void anApprovedSignatureIsOverExactlyTheAttributes() throws Exception {
        byte[] attrs = attributes(digest);
        Reply r = sign(attrs, "SHA256withRSA", entry.certificateSha256());
        assertEquals(200, r.status(), r.body());
        Signature check = Signature.getInstance("SHA256withRSA");
        check.initVerify(party.cert().getPublicKey());
        check.update(attrs);
        assertTrue(check.verify(Base64.getDecoder().decode(r.json().path("signature").asText())));
        assertEquals(1, asked.size());
        DscLoopbackAgent.SignRequest seen = asked.get(0);
        assertEquals(UI, seen.origin());
        assertEquals("Sanction order 12", seen.documentName());
        assertEquals(HexFormat.of().formatHex(digest), seen.preparedDigestSha256());
        assertTrue(seen.signerSubject().contains("Asha Verma"));
    }

    @Test
    void aDeclinedSignatureIsNotMade() throws Exception {
        approve = false;
        Reply r = sign(attributes(digest), "SHA256withRSA", entry.certificateSha256());
        assertEquals(403, r.status());
        assertEquals("DECLINED", r.json().path("error").asText());
        assertEquals(0, signed.get());
    }

    @Test
    void unboundOrMalformedRequestsAreRefusedBeforeTheUserIsAsked() throws Exception {
        byte[] otherDigest = MessageDigest.getInstance("SHA-256").digest("other".getBytes(StandardCharsets.UTF_8));
        assertEquals("ATTRIBUTES_NOT_BOUND", sign(attributes(otherDigest), "SHA256withRSA",
                entry.certificateSha256()).json().path("error").asText());
        byte[] notASet = attributes(digest);
        notASet[0] = 0x30;
        assertEquals("ATTRIBUTES_NOT_BOUND", sign(notASet, "SHA256withRSA", entry.certificateSha256()).json()
                .path("error").asText());
        assertEquals("ALGORITHM_NOT_ACCEPTED", sign(attributes(digest), "SHA1withRSA", entry.certificateSha256())
                .json().path("error").asText());
        assertEquals("ALGORITHM_NOT_ACCEPTED", sign(attributes(digest), "SHA256withECDSA", entry.certificateSha256())
                .json().path("error").asText());
        assertEquals(404, sign(attributes(digest), "SHA256withRSA", "ab".repeat(32)).status());
        assertEquals(415, call("POST", "/v1/sign", host(), UI, "text/plain",
                signBody(attributes(digest), "SHA256withRSA", entry.certificateSha256()), Map.of()).status());
        assertEquals(400, call("POST", "/v1/sign", host(), UI, "application/json", "not json", Map.of()).status());
        assertEquals(413, call("POST", "/v1/sign", host(), UI, "application/json",
                "x".repeat(DscLoopbackAgent.MAX_BODY + 1), Map.of()).status());
        assertTrue(asked.isEmpty(), "the user is never asked for a refused request");
        assertEquals(0, signed.get());
    }

    @Test
    void theDigestMustBeADerOctetStringInsideASet() {
        assertTrue(DscLoopbackAgent.carriesDigest(attributes(digest), digest));
        assertFalse(DscLoopbackAgent.carriesDigest(new byte[]{0x31}, digest));
        assertFalse(DscLoopbackAgent.carriesDigest(null, digest));
        assertFalse(DscLoopbackAgent.carriesDigest(new byte[DscLoopbackAgent.MAX_SIGNED_ATTRIBUTES + 1], digest));
    }

    @Test
    void theConfigurationNeedsExactOrigins() {
        assertThrows(IllegalArgumentException.class, () -> new DscLoopbackAgent.Config(0, Set.of()));
        for (String bad : List.of("*", "null", "https://ui.example/path", "ui.example")) {
            assertThrows(IllegalArgumentException.class, () -> new DscLoopbackAgent.Config(0, Set.of(bad)), bad);
        }
    }
}
