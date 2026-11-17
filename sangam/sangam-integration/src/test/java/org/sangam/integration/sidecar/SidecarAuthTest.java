// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Owner decision 153, shared-token part: tokens come from a secret file, are long random visible ASCII, are compared by
 * digest, can be rotated and revoked without a restart, and nothing about them appears in a message. Every token here
 * is synthetic and made by the test.
 */
class SidecarAuthTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @TempDir
    Path dir;

    static String token() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Writes the file and moves its time stamp forward so a same-size rewrite is seen as a change. */
    static Path write(Path file, String... lines) throws IOException {
        FileTime before = Files.exists(file) ? Files.getLastModifiedTime(file) : null;
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.US_ASCII);
        Instant next = before == null ? Instant.now() : before.toInstant().plusSeconds(5);
        Files.setLastModifiedTime(file, FileTime.from(next));
        return file;
    }

    @Test
    void tokenRules() {
        assertNull(SidecarTokens.problem(token()));
        assertNotNull(SidecarTokens.problem(null));
        assertNotNull(SidecarTokens.problem(""));
        assertNotNull(SidecarTokens.problem("a".repeat(SidecarTokens.MIN_LENGTH - 1)));
        assertNull(SidecarTokens.problem("a".repeat(SidecarTokens.MIN_LENGTH)));
        assertNotNull(SidecarTokens.problem("a".repeat(SidecarTokens.MAX_LENGTH + 1)));
        assertNotNull(SidecarTokens.problem("a".repeat(40) + " b"));
        assertNotNull(SidecarTokens.problem("a".repeat(40) + "é"));
    }

    @Test
    void theRightTokenIsAcceptedAndEverythingElseRefused() throws IOException {
        String t = token();
        SidecarAuthenticator auth = SidecarAuthenticator.tokenFile(write(dir.resolve("tokens"), "# callers", "", t));
        assertNull(auth.notReadyReason());
        assertTrue(auth.authenticate("Bearer " + t));
        assertTrue(auth.authenticate("bearer " + t));
        assertFalse(auth.authenticate(null));
        assertFalse(auth.authenticate(""));
        assertFalse(auth.authenticate("Bearer"));
        assertFalse(auth.authenticate("Bearer "));
        assertFalse(auth.authenticate("Basic " + t));
        assertFalse(auth.authenticate(t));
        assertFalse(auth.authenticate("Bearer " + token()));
        assertFalse(auth.authenticate("Bearer " + t.substring(1)));
        assertFalse(auth.authenticate("Bearer " + t + "x"));
        assertFalse(auth.toString().contains(t));
    }

    @Test
    void rotationAcceptsOldAndNewThenRevokesTheOld() throws IOException {
        String old = token();
        String next = token();
        Path file = write(dir.resolve("tokens"), old);
        SidecarAuthenticator auth = SidecarAuthenticator.tokenFile(file);
        assertTrue(auth.authenticate("Bearer " + old));
        assertFalse(auth.authenticate("Bearer " + next));

        write(file, old, next);
        assertTrue(auth.authenticate("Bearer " + old));
        assertTrue(auth.authenticate("Bearer " + next));

        write(file, next);
        assertFalse(auth.authenticate("Bearer " + old), "a removed line is revoked without a restart");
        assertTrue(auth.authenticate("Bearer " + next));
    }

    /** CodeQL java/sensitive-log (alerts 790, 791): the not-ready reason is logged, so it never names the secret file. */
    @Test
    void theNotReadyReasonNeverNamesTheTokenFile() throws IOException {
        Path file = dir.resolve("secret-tokens-file");
        SidecarAuthenticator auth = SidecarAuthenticator.tokenFile(file);
        assertFalse(auth.notReadyReason().contains("secret-tokens-file"), auth.notReadyReason()); // missing
        write(file, "short-secret-value");
        assertFalse(auth.notReadyReason().contains("secret-tokens-file"), auth.notReadyReason()); // bad line
        write(file, "# only a comment");
        assertFalse(auth.notReadyReason().contains("secret-tokens-file"), auth.notReadyReason()); // empty
        Files.write(file, new byte[(int) SidecarTokens.MAX_FILE_BYTES + 1]);
        assertFalse(auth.notReadyReason().contains("secret-tokens-file"), auth.notReadyReason()); // too large
    }
    @Test
    void anUnusableTokenFileMeansNotReadyWithoutAFallbackAndWithoutTheContent() throws IOException {
        String good = token();
        Path file = write(dir.resolve("tokens"), good);
        SidecarAuthenticator auth = SidecarAuthenticator.tokenFile(file);
        assertTrue(auth.authenticate("Bearer " + good));

        String shortOne = "short-secret-value";
        write(file, good, shortOne);
        assertNotNull(auth.notReadyReason());
        assertFalse(auth.notReadyReason().contains(shortOne));
        assertFalse(auth.notReadyReason().contains(good));
        assertFalse(auth.authenticate("Bearer " + good), "a half-valid file is not a smaller token set");

        write(file, "# only a comment");
        assertNotNull(auth.notReadyReason());
        assertFalse(auth.authenticate("Bearer " + good));

        Files.delete(file);
        assertNotNull(auth.notReadyReason());
        assertFalse(auth.authenticate("Bearer " + good), "the previous token is not kept as a fallback");

        SidecarAuthenticator missing = SidecarAuthenticator.tokenFile(dir.resolve("absent"));
        assertNotNull(missing.notReadyReason());
        assertFalse(missing.authenticate("Bearer " + good));
    }

    @Test
    void noConfiguredCredentialsAreNeverReady() {
        SidecarAuthenticator none = SidecarAuthenticator.none("X_TOKEN_FILE");
        assertTrue(none.notReadyReason().contains("X_TOKEN_FILE"));
        assertFalse(none.authenticate("Bearer " + token()));
        SidecarCredentials c = SidecarCredentials.none("Y_TOKEN_FILE");
        assertTrue(c.notReadyReason().contains("Y_TOKEN_FILE"));
        assertThrows(IllegalStateException.class, c::headers);
    }

    @Test
    void theClientSendsTheFirstTokenAndFollowsARotation() throws IOException {
        String first = token();
        String second = token();
        Path file = write(dir.resolve("client-token"), first, second);
        SidecarCredentials c = SidecarCredentials.tokenFile(file);
        assertNull(c.notReadyReason());
        assertEquals(Map.of("Authorization", "Bearer " + first), c.headers());
        write(file, second);
        assertEquals(Map.of("Authorization", "Bearer " + second), c.headers());
        assertFalse(c.toString().contains(second));
        write(file, "too-short");
        assertNotNull(c.notReadyReason());
        assertThrows(IllegalStateException.class, c::headers);
    }

    @Test
    void transportIsTlsUnlessMeshIsChosen() {
        assertEquals(SidecarTransport.TLS, SidecarTransport.parse(null));
        assertEquals(SidecarTransport.TLS, SidecarTransport.parse(" "));
        assertEquals(SidecarTransport.TLS, SidecarTransport.parse("tls"));
        assertEquals(SidecarTransport.MESH, SidecarTransport.parse("MESH"));
        assertThrows(IllegalArgumentException.class, () -> SidecarTransport.parse("none"));
        assertThrows(IllegalArgumentException.class, () -> SidecarTransport.parse("http"));
    }
}
