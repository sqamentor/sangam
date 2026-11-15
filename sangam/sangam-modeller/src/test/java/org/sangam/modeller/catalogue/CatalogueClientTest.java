// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.catalogue;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.core.layer.Hashes;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.modeller.catalogue.ExtensionCatalogue.CatalogueException;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M-128 / NG-17, owner decision 380: the consumer side of the platform-wide catalogue (P1, P2 (a), P3). */
class CatalogueClientTest {

    static final byte[] BUNDLE = "{\"format\":1,\"packageHash\":\"h\"}".getBytes(StandardCharsets.UTF_8);
    static final byte[] JAR = "PK-code".getBytes(StandardCharsets.UTF_8);

    static KeyPair keys() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static String sign(byte[] data, KeyPair kp) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(data);
        return Base64.getEncoder().encodeToString(s.sign());
    }

    static String entry(String id, String kind, String licence, byte[] artefact, String path) {
        return "{\"id\":\"" + id + "\",\"version\":\"1.0.0\",\"kind\":\"" + kind + "\",\"publisher\":\"acme\",\"licence\":\""
                + licence + "\",\"sha256\":\"" + Hashes.hex(Hashes.sha256(artefact)) + "\",\"minPlatform\":\"0.1.0\",\"artifact\":\"" + path + "\"}";
    }

    static byte[] index(String... entries) {
        return ("{\"entries\":[" + String.join(",", entries) + "]}").getBytes(StandardCharsets.UTF_8);
    }

    /** A mirrored catalogue in {@code dir}: index.json, index.json.sig, packs/pay.json, code/conn.jar. */
    static CatalogueSource.Offline mirror(Path dir, KeyPair signer, String... entries) throws Exception {
        byte[] idx = index(entries);
        Files.write(dir.resolve("index.json"), idx);
        Files.writeString(dir.resolve("index.json.sig"), sign(idx, signer));
        Files.createDirectories(dir.resolve("packs"));
        Files.createDirectories(dir.resolve("code"));
        Files.write(dir.resolve("packs/pay.json"), BUNDLE);
        Files.write(dir.resolve("code/conn.jar"), JAR);
        return new CatalogueSource.Offline(dir.resolve("index.json"), dir.resolve("index.json.sig"));
    }

    static CatalogueClient client(CatalogueSource s, KeyPair... trusted) {
        List<java.security.PublicKey> keys = new ArrayList<>();
        for (KeyPair k : trusted) {
            keys.add(k.getPublic());
        }
        return new CatalogueClient(s, keys, Set.of("Apache-2.0"), "0.1.0");
    }

    @Test
    void offlinePackageEntryPassesEveryCheckAndReturnsTheArtefact(@TempDir Path dir) throws Exception {
        KeyPair kp = keys();
        CatalogueClient c = client(mirror(dir, kp, entry("pay", "package", "Apache-2.0", BUNDLE, "packs/pay.json")), kp);
        assertEquals(1, c.entries().size());
        CatalogueClient.Verified v = c.forInstall("pay", "1.0.0");
        assertEquals("pay", v.entry().id());
        assertArrayEquals(BUNDLE, v.artifact());
    }

    /** P2 (a): a code kind is listed but never handed out for install, even with a valid signature and checksum. */
    @Test
    void codeKindIsRefusedForInstall(@TempDir Path dir) throws Exception {
        KeyPair kp = keys();
        CatalogueClient c = client(mirror(dir, kp, entry("conn", "connector", "Apache-2.0", JAR, "code/conn.jar")), kp);
        assertEquals("connector", c.entries().get(0).kind());
        assertEquals("CATALOGUE_KIND_NOT_INSTALLABLE", assertThrows(CatalogueException.class, () -> c.forInstall("conn", "1.0.0")).code());
    }

    /** P3: the index verifies with any one of the trusted keys (key rotation); an untrusted signer is refused. */
    @Test
    void signatureMustVerifyWithOneOfTheTrustedKeys(@TempDir Path dir) throws Exception {
        KeyPair signer = keys();
        KeyPair other = keys();
        CatalogueSource s = mirror(dir, signer, entry("pay", "package", "Apache-2.0", BUNDLE, "packs/pay.json"));
        assertEquals(1, client(s, other, signer).entries().size());
        assertEquals("CATALOGUE_SIGNATURE_INVALID", assertThrows(CatalogueException.class, () -> client(s, other).entries()).code());
        assertEquals("CATALOGUE_KEYS_MISSING", assertThrows(CatalogueException.class, () -> client(s)).code());
    }

    @Test
    void licenceChecksumAndUnknownEntryAreRefused(@TempDir Path dir) throws Exception {
        KeyPair kp = keys();
        CatalogueClient c = client(mirror(dir, kp, entry("gpl", "package", "GPL-3.0", BUNDLE, "packs/pay.json"),
                entry("bad", "package", "Apache-2.0", JAR, "packs/pay.json")), kp);
        CatalogueException lic = assertThrows(CatalogueException.class, () -> c.forInstall("gpl", "1.0.0"));
        assertEquals("CATALOGUE_CHECK_FAILED", lic.code());
        assertTrue(lic.getMessage().contains("licence not allowed: GPL-3.0"), lic.getMessage());
        CatalogueException sum = assertThrows(CatalogueException.class, () -> c.forInstall("bad", "1.0.0"));
        assertTrue(sum.getMessage().contains("checksum mismatch"), sum.getMessage());
        assertEquals("CATALOGUE_ENTRY_NOT_FOUND", assertThrows(CatalogueException.class, () -> c.forInstall("pay", "9")).code());
    }

    /** An artefact path that leaves the index's directory is refused before any read. */
    @Test
    void offlineArtefactPathMayNotLeaveTheMirror(@TempDir Path dir) throws Exception {
        Path mirrorDir = Files.createDirectories(dir.resolve("mirror"));
        Files.write(dir.resolve("secret.json"), BUNDLE);
        KeyPair kp = keys();
        CatalogueSource.Offline s = mirror(mirrorDir, kp);
        for (String p : List.of("../secret.json", "packs/../../secret.json", "/etc/passwd", "C:/x.json", "packs\\pay.json", "")) {
            assertEquals("CATALOGUE_ARTIFACT_PATH_INVALID", assertThrows(CatalogueException.class, () -> s.artifact(p)).code(), p);
        }
        assertEquals("CATALOGUE_ARTIFACT_MISSING", assertThrows(CatalogueException.class, () -> s.artifact("packs/none.json")).code());
    }

    /** P1: the hosted index is https only, the signature is at the index URL plus .sig, artefacts stay under the index. */
    @Test
    void hostedSourceReadsIndexSignatureAndArtefactsRelativeToTheIndex() throws Exception {
        KeyPair kp = keys();
        byte[] idx = index(entry("pay", "package", "Apache-2.0", BUNDLE, "packs/pay.json"));
        Map<String, byte[]> served = new HashMap<>();
        served.put("https://cat.example.org/v1/index.json", idx);
        served.put("https://cat.example.org/v1/index.json.sig", sign(idx, kp).getBytes(StandardCharsets.UTF_8));
        served.put("https://cat.example.org/v1/packs/pay.json", BUNDLE);
        List<String> asked = new ArrayList<>();
        CatalogueSource.Fetch fetch = uri -> {
            asked.add(uri.toString());
            byte[] b = served.get(uri.toString());
            if (b == null) {
                throw new CatalogueException("CATALOGUE_UNREACHABLE", "HTTP 404");
            }
            return b;
        };
        CatalogueSource.Hosted s = new CatalogueSource.Hosted(URI.create("https://cat.example.org/v1/index.json"), fetch);
        assertArrayEquals(BUNDLE, client(s, kp).forInstall("pay", "1.0.0").artifact());
        assertEquals(List.of("https://cat.example.org/v1/index.json", "https://cat.example.org/v1/index.json.sig",
                "https://cat.example.org/v1/packs/pay.json"), asked);
        for (String p : List.of("../index.json", "https://evil.example/x.json", "//evil.example/x.json", "/v1/packs/pay.json")) {
            assertEquals("CATALOGUE_ARTIFACT_PATH_INVALID", assertThrows(CatalogueException.class, () -> s.artifact(p)).code(), p);
        }
        assertEquals("CATALOGUE_URL_NOT_HTTPS", assertThrows(CatalogueException.class,
                () -> new CatalogueSource.Hosted(URI.create("http://cat.example.org/index.json"), fetch)).code());
        assertEquals("CATALOGUE_URL_INVALID", assertThrows(CatalogueException.class,
                () -> new CatalogueSource.Hosted(URI.create("https://u:p@cat.example.org/index.json"), fetch)).code());
        assertEquals("CATALOGUE_URL_INVALID", assertThrows(CatalogueException.class,
                () -> new CatalogueSource.Hosted(URI.create("https://cat.example.org/index.json?k=1"), fetch)).code());
    }

    /** P1: without an allow-list entry the transport sends nothing and the fetch is CATALOGUE_EGRESS_DENIED. */
    @Test
    void fetchWithoutAllowListEntryIsEgressDenied() {
        HttpTransport t = new HttpTransport(CatalogueSource.egress(List.of()), null, Duration.ofSeconds(2), Duration.ofSeconds(5), null);
        CatalogueException e = assertThrows(CatalogueException.class,
                () -> CatalogueSource.over(t).get(URI.create("https://203.0.113.10/index.json")));
        assertEquals("CATALOGUE_EGRESS_DENIED", e.code());
        HttpTransport other = new HttpTransport(CatalogueSource.egress(List.of("https://cat.example.org/v1/")), null,
                Duration.ofSeconds(2), Duration.ofSeconds(5), null);
        assertEquals("CATALOGUE_EGRESS_DENIED", assertThrows(CatalogueException.class,
                () -> CatalogueSource.over(other).get(URI.create("https://203.0.113.10/index.json"))).code());
    }

    /** The fetch goes through the real transport: an allowed destination answers its bytes, a 404 is unreachable. */
    @Test
    void fetchThroughTheTransportReturnsTheBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/cat/index.json", x -> {
            x.sendResponseHeaders(200, BUNDLE.length);
            x.getResponseBody().write(BUNDLE);
            x.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/cat/";
            EgressPolicy policy = EgressPolicy.of(List.of(EgressPolicy.Rule.parse(CatalogueSource.PLATFORM, base, true)), 3, 1 << 20);
            HttpTransport t = new HttpTransport(policy, null, Duration.ofSeconds(2), Duration.ofSeconds(5), null);
            assertArrayEquals(BUNDLE, CatalogueSource.over(t).get(URI.create(base + "index.json")));
            assertEquals("CATALOGUE_UNREACHABLE", assertThrows(CatalogueException.class,
                    () -> CatalogueSource.over(t).get(URI.create(base + "missing.json"))).code());
        } finally {
            server.stop(0);
        }
    }

    /** P3: keys are secret references; a literal, an unknown reference and a value that is not a key are refused. */
    @Test
    void trustedKeysAreSecretReferences() throws Exception {
        KeyPair kp = keys();
        String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        SecretResolver secrets = SecretResolver.of(Map.of("secret://catalogue/key-a", pub, "secret://catalogue/junk", "not-a-key"));
        assertEquals(kp.getPublic(), CatalogueKeys.trusted(List.of("secret://catalogue/key-a"), secrets).get(0));
        assertEquals("CATALOGUE_KEY_NOT_A_REFERENCE", assertThrows(CatalogueException.class,
                () -> CatalogueKeys.trusted(List.of(pub), secrets)).code());
        assertEquals("CATALOGUE_KEY_NOT_FOUND", assertThrows(CatalogueException.class,
                () -> CatalogueKeys.trusted(List.of("secret://catalogue/none"), secrets)).code());
        assertEquals("CATALOGUE_KEY_INVALID", assertThrows(CatalogueException.class,
                () -> CatalogueKeys.trusted(List.of("secret://catalogue/junk"), secrets)).code());
        assertEquals("CATALOGUE_KEYS_MISSING", assertThrows(CatalogueException.class,
                () -> CatalogueKeys.trusted(List.of(" "), secrets)).code());
    }
}
