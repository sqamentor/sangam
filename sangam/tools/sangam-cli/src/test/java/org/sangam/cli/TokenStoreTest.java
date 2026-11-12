// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** M-093: token storage in the OS keychain (fake protector) with the owner-only file fallback. */
class TokenStoreTest {

    @TempDir
    Path dir;
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    /** An in-memory keychain; {@code broken} makes it refuse like a missing or locked store. */
    static final class FakeKeychain implements KeyProtector {
        final Map<String, byte[]> keys = new HashMap<>();
        boolean broken;
        int forgotten;

        @Override
        public String name() {
            return "fake-keychain";
        }

        @Override
        public String protect(String account, byte[] key) throws IOException {
            if (broken) {
                throw new IOException("locked");
            }
            keys.put(account, key.clone());
            return "k";
        }

        @Override
        public byte[] recover(String account, String handle) {
            return broken ? null : keys.get(account);
        }

        @Override
        public void forget(String account) {
            forgotten++;
            keys.remove(account);
        }
    }

    TokenStore store(KeyProtector p, TokenStore.Mode mode) {
        return new TokenStore(dir.resolve("credentials"), p, mode, new PrintStream(err));
    }

    static final String ACCOUNT = TokenStore.account("https://s.example", "c");

    @Test
    void keychainModeEncryptsTokensUnderAKeyHeldByTheKeychain() throws Exception {
        FakeKeychain k = new FakeKeychain();
        TokenStore s = store(k, TokenStore.Mode.AUTO);
        assertEquals("fake-keychain", s.save(ACCOUNT, new TokenStore.Credentials("ACCESS-xyz", "REFRESH-xyz", 42)));
        String file = Files.readString(s.file(ACCOUNT));
        assertFalse(file.contains("ACCESS-xyz") || file.contains("REFRESH-xyz"), "no token in clear text: " + file);
        assertTrue(file.contains("\"protection\":\"fake-keychain\""), file);
        TokenStore.Credentials back = s.load(ACCOUNT);
        assertEquals("ACCESS-xyz", back.accessToken());
        assertEquals("REFRESH-xyz", back.refreshToken());
        assertEquals(42, back.expiresAt());
        assertEquals(32, k.keys.get(ACCOUNT).length, "a 256-bit data key");
    }

    @Test
    void theDataKeyIsReusedAcrossSaves() throws Exception {
        FakeKeychain k = new FakeKeychain();
        TokenStore s = store(k, TokenStore.Mode.AUTO);
        s.save(ACCOUNT, new TokenStore.Credentials("A1", "R1", 0));
        byte[] first = k.keys.get(ACCOUNT);
        s.save(ACCOUNT, new TokenStore.Credentials("A2", "R2", 0));
        assertArrayEquals(first, k.keys.get(ACCOUNT));
        assertEquals("A2", s.load(ACCOUNT).accessToken());
    }

    @Test
    void aFileCopiedToAnotherAccountDoesNotDecrypt() throws Exception {
        FakeKeychain k = new FakeKeychain();
        TokenStore s = store(k, TokenStore.Mode.AUTO);
        s.save(ACCOUNT, new TokenStore.Credentials("A1", null, 0));
        String other = TokenStore.account("https://other.example", "c");
        k.keys.put(other, k.keys.get(ACCOUNT));
        Files.copy(s.file(ACCOUNT), s.file(other));
        assertNull(s.load(other), "the account is the GCM associated data");
        assertFalse(err.toString().contains("A1"));
    }

    @Test
    void lostKeychainKeyMeansLoginAgain() throws Exception {
        FakeKeychain k = new FakeKeychain();
        TokenStore s = store(k, TokenStore.Mode.AUTO);
        s.save(ACCOUNT, new TokenStore.Credentials("A1", null, 0));
        k.keys.clear();
        assertNull(s.load(ACCOUNT));
        assertTrue(err.toString().contains("sangam login"));
    }

    @Test
    void autoFallsBackToTheOwnerOnlyFileWhenTheKeychainRefuses() throws Exception {
        FakeKeychain k = new FakeKeychain();
        k.broken = true;
        TokenStore s = store(k, TokenStore.Mode.AUTO);
        assertEquals("file", s.save(ACCOUNT, new TokenStore.Credentials("A1", "R1", 0)));
        assertTrue(err.toString().contains("owner-only file"), err.toString());
        assertEquals("R1", s.load(ACCOUNT).refreshToken());
        PosixFileAttributeView v = Files.getFileAttributeView(s.file(ACCOUNT), PosixFileAttributeView.class);
        if (v != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), v.readAttributes().permissions(), "0600");
        }
    }

    @Test
    void keychainModeFailsInsteadOfFallingBack() {
        FakeKeychain k = new FakeKeychain();
        k.broken = true;
        TokenStore s = store(k, TokenStore.Mode.KEYCHAIN);
        assertThrows(IOException.class, () -> s.save(ACCOUNT, new TokenStore.Credentials("A1", null, 0)));
        assertFalse(Files.exists(s.file(ACCOUNT)));
    }

    @Test
    void fileModeNeverTouchesTheKeychainAndRemovesAStaleKey() throws Exception {
        FakeKeychain k = new FakeKeychain();
        store(k, TokenStore.Mode.AUTO).save(ACCOUNT, new TokenStore.Credentials("A1", null, 0));
        TokenStore f = store(k, TokenStore.Mode.FILE);
        assertEquals("file", f.save(ACCOUNT, new TokenStore.Credentials("A2", null, 0)));
        assertEquals(1, k.forgotten);
        assertFalse(k.keys.containsKey(ACCOUNT));
        assertEquals("A2", f.load(ACCOUNT).accessToken());
    }

    @Test
    void clearRemovesFileAndKey() throws Exception {
        FakeKeychain k = new FakeKeychain();
        TokenStore s = store(k, TokenStore.Mode.AUTO);
        s.save(ACCOUNT, new TokenStore.Credentials("A1", null, 0));
        assertTrue(s.clear(ACCOUNT));
        assertFalse(Files.exists(s.file(ACCOUNT)));
        assertFalse(k.keys.containsKey(ACCOUNT));
        assertFalse(s.clear(ACCOUNT));
    }

    @Test
    void credentialsNeverShowTheirValues() {
        assertFalse(new TokenStore.Credentials("SECRET-A", "SECRET-R", 1).toString().contains("SECRET"));
    }

    @Test
    void accountsAreHexAndModeParses() {
        assertTrue(ACCOUNT.matches("[0-9a-f]{32}"));
        assertNotEquals(ACCOUNT, TokenStore.account("https://s.example", "d"));
        assertEquals(TokenStore.Mode.AUTO, TokenStore.Mode.parse(null));
        assertEquals(TokenStore.Mode.FILE, TokenStore.Mode.parse(" file "));
        assertThrows(IllegalArgumentException.class, () -> TokenStore.Mode.parse("vault"));
        assertThrows(IllegalArgumentException.class, () -> store(null, TokenStore.Mode.FILE).file("../x"));
    }
}
