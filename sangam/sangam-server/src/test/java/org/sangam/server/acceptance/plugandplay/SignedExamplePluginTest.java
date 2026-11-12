// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.plugandplay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

/**
 * AT-6 automation (no server): the fixture builds the example plugin from its sources into a jar with the plugin
 * descriptor, the connector service, the pack and the UI bundle, and signs it the way the plugin README prescribes,
 * so the server's own verification (detached Ed25519 signature over the jar bytes, key from {@code trusted-keys/})
 * accepts it. A tampered jar does not verify.
 */
class SignedExamplePluginTest {

    @Test
    void theExamplePluginIsBuiltAndSignedForTheRunsTrustedKey() throws Exception {
        Path folder = Path.of(SignedExamplePlugin.folder());
        Path jar = folder.resolve(SignedExamplePlugin.JAR);
        try (JarFile j = new JarFile(jar.toFile())) {
            assertThat(j.getEntry("META-INF/sangam/plugin.json")).isNotNull();
            assertThat(j.getEntry("META-INF/services/org.sangam.core.integration.SangamConnector")).isNotNull();
            assertThat(j.getEntry("com/example/sangam/EchoLedgerConnector.class")).isNotNull();
            assertThat(j.getEntry("packs/example-echo/pack.json")).isNotNull();
            assertThat(j.getEntry("ui/manifest.json")).isNotNull();
        }
        String pem = Files.readString(folder.resolve("trusted-keys").resolve(SignedExamplePlugin.KEY), StandardCharsets.US_ASCII);
        byte[] der = Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        byte[] sig = Base64.getMimeDecoder().decode(Files.readString(folder.resolve(SignedExamplePlugin.JAR + ".sig")).trim());
        byte[] bytes = Files.readAllBytes(jar);

        Signature v = Signature.getInstance("Ed25519");
        v.initVerify(key);
        v.update(bytes);
        assertThat(v.verify(sig)).as("signature over the jar bytes").isTrue();

        bytes[bytes.length / 2] ^= 1;
        Signature tampered = Signature.getInstance("Ed25519");
        tampered.initVerify(key);
        tampered.update(bytes);
        assertThat(tampered.verify(sig)).as("a changed jar must not verify").isFalse();
        assertThat(SignedExamplePlugin.folder()).as("built once per JVM").isEqualTo(folder.toString());
    }
}
