// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Where the CLI keeps its tokens, one file per account under {@code ~/.sangam/credentials/}, always owner-only
 * ({@link OwnerOnlyFiles}).
 *
 * <ul>
 *   <li><b>keychain</b> (default when the platform store works): the tokens are encrypted with AES-256-GCM under a
 *       random data key that lives in the OS secret store ({@link KeyProtector}); the account is the associated data, so a
 *       file copied to another account does not decrypt.</li>
 *   <li><b>file</b> (fallback, or chosen with {@code SANGAM_TOKEN_STORE=file}): the tokens in clear text in the
 *       owner-only file, for hosts without a desktop keychain (CI runners, servers).</li>
 * </ul>
 * Token values are never written to stdout or stderr.
 */
final class TokenStore {

    /** Access token, refresh token (may be null) and the access token's expiry in epoch seconds (0 when unknown). */
    record Credentials(String accessToken, String refreshToken, long expiresAt) {
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    /** {@code auto}: keychain when it works, else the file. {@code keychain}: keychain or fail. {@code file}: file only. */
    enum Mode {
        AUTO, KEYCHAIN, FILE;

        static Mode parse(String s) {
            if (s == null || s.isBlank()) {
                return AUTO;
            }
            try {
                return valueOf(s.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("token store must be auto, keychain or file");
            }
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NONE = "none";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path dir;
    private final KeyProtector protector;
    private final Mode mode;
    private final PrintStream err;
    private final SecureRandom random = new SecureRandom();

    TokenStore(Path dir, KeyProtector protector, Mode mode, PrintStream err) {
        this.dir = dir;
        this.protector = protector;
        this.mode = mode;
        this.err = err;
    }

    /** A stable, non-secret account id for a server and OIDC client (hex, safe in every keychain tool). */
    static String account(String server, String clientId) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((server + "\n" + (clientId == null ? "" : clientId)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    Path file(String account) {
        KeyProtector.checkAccount(account);
        return dir.resolve(account + ".json");
    }

    /** Saves the tokens and returns where they went ({@code keychain name} or {@code file}). */
    String save(String account, Credentials c) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode().put("accessToken", c.accessToken()).put("expiresAt", c.expiresAt());
        if (c.refreshToken() != null) {
            body.put("refreshToken", c.refreshToken());
        }
        ObjectNode doc = JSON.createObjectNode().put("v", 1);
        String where;
        byte[] key = mode == Mode.FILE || protector == null ? null : keyFor(account, doc);
        if (key != null) {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            doc.put("iv", b64(iv)).put("data", b64(crypt(Cipher.ENCRYPT_MODE, key, iv, account, JSON.writeValueAsBytes(body))));
            where = protector.name();
        } else {
            doc.put("protection", NONE).set("tokens", body);
            where = "file";
            forgetStaleKey(account);
        }
        OwnerOnlyFiles.Protection p = OwnerOnlyFiles.write(file(account), JSON.writeValueAsString(doc));
        if (p == OwnerOnlyFiles.Protection.PROFILE_ONLY) {
            err.println("note: this file system has no owner-only permissions; the credentials file relies on the user profile's permissions");
        }
        return where;
    }

    /** The data key: the one already in the platform store for this account, or a new one stored there. */
    private byte[] keyFor(String account, ObjectNode doc) throws IOException, InterruptedException {
        JsonNode old = read(account);
        try {
            if (old != null && protector.name().equals(old.path("protection").asText()) && old.hasNonNull("handle")) {
                byte[] k = protector.recover(account, old.get("handle").asText());
                if (k != null && k.length == 32) {
                    doc.put("protection", protector.name()).put("handle", old.get("handle").asText());
                    return k;
                }
            }
            byte[] k = new byte[32];
            random.nextBytes(k);
            String handle = protector.protect(account, k);
            doc.put("protection", protector.name()).put("handle", handle);
            return k;
        } catch (IOException e) {
            if (mode == Mode.KEYCHAIN) {
                throw new IOException("the OS keychain is not available: " + e.getMessage());
            }
            err.println("note: no usable OS keychain (" + e.getMessage() + "); tokens are kept in an owner-only file");
            return null;
        }
    }

    /** The saved tokens, or {@code null} when there are none or they can no longer be decrypted. */
    Credentials load(String account) throws IOException, InterruptedException {
        JsonNode doc = read(account);
        if (doc == null) {
            return null;
        }
        String protection = doc.path("protection").asText(NONE);
        JsonNode body;
        if (NONE.equals(protection)) {
            body = doc.path("tokens");
        } else {
            if (protector == null || !protector.name().equals(protection)) {
                err.println("note: the saved tokens were protected by " + protection + ", which is not available here; run sangam login");
                return null;
            }
            byte[] key = protector.recover(account, doc.path("handle").asText(null));
            if (key == null) {
                err.println("note: the OS keychain no longer holds the key for the saved tokens; run sangam login");
                return null;
            }
            try {
                body = JSON.readTree(crypt(Cipher.DECRYPT_MODE, key, unb64(doc.path("iv").asText()), account, unb64(doc.path("data").asText())));
            } catch (IOException e) {
                err.println("note: the saved tokens do not decrypt; run sangam login");
                return null;
            }
        }
        if (!body.hasNonNull("accessToken")) {
            return null;
        }
        return new Credentials(body.get("accessToken").asText(), body.hasNonNull("refreshToken") ? body.get("refreshToken").asText() : null,
                body.path("expiresAt").asLong(0));
    }

    /** A key left in the OS store by an earlier keychain save of this account is removed when the tokens move to the file. */
    private void forgetStaleKey(String account) throws IOException, InterruptedException {
        JsonNode doc = read(account);
        if (doc != null && protector != null && protector.name().equals(doc.path("protection").asText())) {
            try {
                protector.forget(account);
            } catch (IOException e) {
                err.println("note: could not remove the old key from " + protector.name());
            }
        }
    }

    /** Deletes the tokens and the key; returns whether anything was there. */
    boolean clear(String account) throws IOException, InterruptedException {
        forgetStaleKey(account);
        Path f = file(account);
        boolean had = Files.exists(f);
        OwnerOnlyFiles.delete(f);
        return had;
    }

    private JsonNode read(String account) throws IOException {
        Path f = file(account);
        if (!Files.exists(f)) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(f.toFile());
            return n != null && n.isObject() ? n : null;
        } catch (IOException e) {
            return null; // a damaged file is replaced at the next login
        }
    }

    private static byte[] crypt(int op, byte[] key, byte[] iv, String account, byte[] in) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(op, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(("sangam-cli:v1:" + account).getBytes(StandardCharsets.UTF_8));
            return c.doFinal(in);
        } catch (GeneralSecurityException e) {
            throw new IOException("credentials cipher failed");
        }
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] unb64(String s) throws IOException {
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new IOException("malformed credentials file");
        }
    }
}
