// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.server.SangamServerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Owner decisions 387 (4) and 390 (4), end to end, as the first hosted environment runs it: a real production start
 * (the settings of {@link ProductionStartupFailFastTest}) on an H2 file database that persists across starts.
 * <ol>
 *   <li>start 1 with {@code sangam.keys.bootstrap=true} succeeds and writes the fingerprint export;</li>
 *   <li>the custodian tool ({@code sangam/tools/keys/sangam-key-attestation.sh}, POSIX sh + OpenSSL 3) makes a key and
 *       signs the attestation from that export;</li>
 *   <li>start 2 with bootstrap off and the attestation succeeds;</li>
 * </ol>
 * and the refusals: a second bootstrap (refused before the web server serves anything), a tampered statement, another
 * environment and a missing purpose.
 */
class KeyAttestationEndToEndTest {

    private static final String ENV = "demo";
    private static final Path TOOL = Path.of("..", "tools", "keys", "sangam-key-attestation.sh").toAbsolutePath()
            .normalize();

    @TempDir
    Path dir;

    private final String dbPassword = java.util.UUID.randomUUID().toString();

    /** The baseline production settings on a file database in {@link #dir}, without a foreign list. */
    private Map<String, String> settings() {
        Map<String, String> m = ProductionStartupFailFastTest.baseline("unused");
        m.put("spring.datasource.url", "jdbc:h2:file:" + slash(dir.resolve("db")) + ";LOCK_TIMEOUT=10000;MODE=PostgreSQL");
        m.put("SANGAM_DB_PASSWORD", dbPassword); // the file database keeps the password of its first start
        m.remove("sangam.keys.foreign-fingerprints");
        m.put("sangam.keys.environment", ENV);
        m.put("sangam.keys.fingerprint-export", dir.resolve("key-fingerprints.txt").toString());
        return m;
    }

    private Map<String, String> bootstrap() {
        Map<String, String> m = settings();
        m.put("sangam.keys.bootstrap", "true");
        return m;
    }

    private Map<String, String> attested(Path attestation, String custodianPublicKey) {
        Map<String, String> m = settings();
        m.put("sangam.keys.bootstrap", "false");
        m.put("sangam.keys.attestation", attestation.toString());
        m.put("sangam.keys.custodian-public-key", custodianPublicKey);
        return m;
    }

    /** Starts the server; counts the web servers that started serving (main and management). */
    private static ConfigurableApplicationContext start(Map<String, String> settings, AtomicInteger serving) {
        String[] args = settings.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(SangamServerApplication.class).profiles("h2")
                .listeners((ApplicationListener<WebServerInitializedEvent>) e -> serving.incrementAndGet()).run(args);
    }

    private static String refusal(Map<String, String> settings, AtomicInteger serving) {
        try (ConfigurableApplicationContext ctx = start(settings, serving)) {
            fail("start-up succeeded, expected a refusal");
            return null;
        } catch (RuntimeException e) {
            StringBuilder chain = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
            }
            return chain.toString();
        }
    }

    @Test
    void bootstrapThenCustodianAttestationThenAttestedStarts() throws Exception {
        Path export = dir.resolve("key-fingerprints.txt");

        // start 1: the one-time bootstrap
        AtomicInteger serving = new AtomicInteger();
        try (ConfigurableApplicationContext ctx = start(bootstrap(), serving)) {
            assertTrue(ctx.isActive());
        }
        assertTrue(serving.get() > 0, "start 1 serves");
        List<String> lines = Files.readAllLines(export, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        assertFalse(lines.isEmpty(), "the export names this environment's keys");
        for (String l : lines) {
            String[] t = l.split(" ", 4);
            assertEquals(4, t.length, "an export line names environment, fingerprint, setting and purpose: " + l);
            assertEquals(ENV, t[0]);
            assertTrue(t[1].matches("[0-9a-f]{64}"), l);
        }
        assertTrue(lines.stream().anyMatch(l -> l.endsWith(" key-encryption key")), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(l -> l.endsWith(" audit checkpoint signing")), String.join("\n", lines));

        // the custodian, on the host
        Path key = dir.resolve("custodian").resolve("custodian-ed25519.pem");
        Files.createDirectories(key.getParent());
        Run gen = tool("generate-custodian-key", tp(key));
        assertEquals(0, gen.code, gen.err);
        String publicKey = gen.out.strip();
        assertTrue(publicKey.matches("[A-Za-z0-9+/=]{40,}"), "prints only the base64 public key");
        assertFalse(gen.out.contains("PRIVATE") || gen.err.contains("PRIVATE"), "never prints the private key");
        String pem = Files.readString(key, StandardCharsets.US_ASCII);
        String privateBody = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        assertFalse(gen.out.contains(privateBody), "never prints the private key");
        assertEquals(1, tool("generate-custodian-key", tp(key)).code, "an existing custodian key is never overwritten");

        Path attestation = dir.resolve("key-attestation.json");
        Run sign = tool("sign", tp(key), ENV, tp(export), tp(attestation));
        assertEquals(0, sign.code, sign.err);
        assertFalse((sign.out + sign.err).contains(privateBody));
        Run verify = tool("verify", publicKey, tp(attestation));
        assertEquals(0, verify.code, verify.err);
        assertTrue(verify.out.contains("verified: environment " + ENV), verify.out);

        // start 2: bootstrap off, the attestation instead
        serving.set(0);
        try (ConfigurableApplicationContext ctx = start(attested(attestation, publicKey), serving)) {
            assertTrue(ctx.isActive());
        }
        assertTrue(serving.get() > 0, "start 2 serves");

        // a tampered statement: same signature, statement changed
        String doc = Files.readString(attestation, StandardCharsets.UTF_8);
        String stmt = between(doc, "\"statement\":\"", "\"");
        String changed = new String(Base64.getDecoder().decode(stmt), StandardCharsets.UTF_8)
                .replaceFirst("\"issued_at\":\"[^\"]*\"", "\"issued_at\":\"2099-01-01T00:00:00Z\"");
        Path tampered = dir.resolve("tampered.json");
        Files.writeString(tampered, doc.replace(stmt,
                Base64.getEncoder().encodeToString(changed.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8);
        assertEquals(1, tool("verify", publicKey, tp(tampered)).code, "the tool refuses it too");
        String m = refusal(attested(tampered, publicKey), new AtomicInteger());
        assertTrue(m.contains("KEY_ATTESTATION_INVALID") && m.contains("does not verify"), m);

        // another environment: the custodian signed the same keys for staging
        Path staging = dir.resolve("staging-export.txt");
        Files.writeString(staging, Files.readString(export, StandardCharsets.UTF_8).replace(ENV + " ", "staging "),
                StandardCharsets.UTF_8);
        Path other = dir.resolve("staging.json");
        assertEquals(0, tool("sign", tp(key), "staging", tp(staging), tp(other)).code);
        m = refusal(attested(other, publicKey), new AtomicInteger());
        assertTrue(m.contains("KEY_ATTESTATION_INVALID") && m.contains("is for environment staging, not demo"), m);

        // a missing purpose: every key of one purpose left out of the export the custodian signs
        Path partial = dir.resolve("partial-export.txt");
        Files.write(partial, Files.readAllLines(export, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.endsWith(" key-encryption key")).toList(), StandardCharsets.UTF_8);
        Path missing = dir.resolve("missing.json");
        assertEquals(0, tool("sign", tp(key), ENV, tp(partial), tp(missing)).code);
        m = refusal(attested(missing, publicKey), new AtomicInteger());
        assertTrue(m.contains("KEY_ATTESTATION_INVALID") && m.contains("(key-encryption key) is not attested"), m);

        // the attestation still starts afterwards: the refusals left nothing behind
        try (ConfigurableApplicationContext ctx = start(attested(attestation, publicKey), new AtomicInteger())) {
            assertTrue(ctx.isActive());
        }
    }

    @Test
    void aSecondBootstrapIsRefusedBeforeAnythingIsServed() {
        try (ConfigurableApplicationContext ctx = start(bootstrap(), new AtomicInteger())) {
            assertTrue(ctx.isActive());
        }
        AtomicInteger serving = new AtomicInteger();
        String m = refusal(bootstrap(), serving);
        assertTrue(m.contains("KEY_ENVIRONMENT_LIST_REQUIRED") && m.contains("already started once"), m);
        assertEquals(0, serving.get(), "the refused start never served a request (no web server was started)");
    }

    @Test
    void theToolRefusesAnExportWithoutPurposes() throws Exception {
        Path key = dir.resolve("k.pem");
        assertEquals(0, tool("generate-custodian-key", tp(key)).code);
        Path old = dir.resolve("old-export.txt");
        Files.writeString(old, "# old\n" + ENV + " " + "ab".repeat(32) + " sangam.security.kek.keys\n",
                StandardCharsets.UTF_8);
        Run r = tool("sign", tp(key), ENV, tp(old), tp(dir.resolve("x.json")));
        assertEquals(1, r.code);
        assertTrue(r.err.contains("has no purpose"), r.err);
        assertFalse(Files.exists(dir.resolve("x.json")));
    }

    // --- the custodian tool, run as on the host -------------------------------------------------------------------

    private record Run(int code, String out, String err) {
    }

    private static Run tool(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        if (WINDOWS) {
            // a Windows workstation runs the tool where it runs in production: on Linux (WSL), with its own openssl
            cmd.addAll(List.of("wsl.exe", "-d", System.getProperty("sangam.test.wsl-distro", "Ubuntu"), "-e"));
        }
        cmd.add("sh");
        cmd.add(tp(TOOL));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.PIPE).start();
        p.getOutputStream().close();
        byte[] out = p.getInputStream().readAllBytes();
        byte[] err = p.getErrorStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return new Run(p.exitValue(), new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    /** A path as the tool's host sees it: itself on Linux, /mnt/<drive>/... under WSL. */
    private static String tp(Path p) {
        String s = slash(p);
        if (WINDOWS && s.length() > 2 && s.charAt(1) == ':') {
            return "/mnt/" + Character.toLowerCase(s.charAt(0)) + s.substring(2);
        }
        return s;
    }

    private static String slash(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String between(String s, String from, String to) {
        int a = s.indexOf(from) + from.length();
        return s.substring(a, s.indexOf(to, a));
    }
}
