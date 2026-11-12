// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * WeChat Official Account encrypted message mode (security mode), from the public message encryption document (NG-14).
 *
 * <ul>
 *   <li>The AES key is the Base64 decoding of the 43-character {@code EncodingAESKey} plus {@code "="} (32 bytes);
 *       AES-256-CBC with the first 16 key bytes as IV and PKCS#7 padding to a 32-byte block.</li>
 *   <li>The plain text is 16 random bytes, the 4-byte big-endian message length, the message, then the app id.</li>
 *   <li>{@code msg_signature} is the hex SHA-1 of the lexically sorted, concatenated token, timestamp, nonce and
 *       ciphertext.</li>
 * </ul>
 * A failed check means: drop. Every comparison is constant time, and a wrong app id is refused, so a message encrypted for
 * another account is never accepted.
 */
public final class WeChatCrypto {

    private static final int BLOCK = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String token;
    private final byte[] aesKey;
    private final String appId;

    public WeChatCrypto(String token, String encodingAesKey, String appId) {
        if (token == null || token.isEmpty() || appId == null || appId.isEmpty()) {
            throw new IllegalArgumentException("WeChat encrypted mode needs a token and an app id");
        }
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new IllegalArgumentException("EncodingAESKey must have 43 characters");
        }
        this.aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        if (aesKey.length != 32) {
            throw new IllegalArgumentException("EncodingAESKey must decode to 32 bytes");
        }
        this.token = token;
        this.appId = appId;
    }

    /** {@code msg_signature} of a ciphertext. */
    public static String signature(String token, String timestamp, String nonce, String encrypted) {
        String[] parts = {token, timestamp, nonce, encrypted};
        Arrays.sort(parts);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(String.join("", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean verify(String timestamp, String nonce, String encrypted, String msgSignature) {
        if (timestamp == null || nonce == null || encrypted == null || msgSignature == null) {
            return false;
        }
        return MessageDigest.isEqual(signature(token, timestamp, nonce, encrypted).getBytes(StandardCharsets.US_ASCII),
                msgSignature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Verifies and decrypts an inbound POST body ({@code <xml><Encrypt>..</Encrypt></xml>}). Returns the plain message XML,
     * ready for {@link InboundMessageParsers#weChat}, or empty when the signature, padding, length or app id is wrong.
     */
    public Optional<byte[]> decryptInbound(byte[] xmlBody, String timestamp, String nonce, String msgSignature) {
        String encrypted;
        try {
            encrypted = InboundMessageParsers.weChatEncrypted(xmlBody);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (encrypted == null || !verify(timestamp, nonce, encrypted, msgSignature)) {
            return Optional.empty();
        }
        return decrypt(encrypted).map(s -> s.getBytes(StandardCharsets.UTF_8));
    }

    /** Decrypts one ciphertext; empty when it is malformed or was made for another app id. */
    public Optional<String> decrypt(String encrypted) {
        try {
            byte[] cipherText = Base64.getDecoder().decode(encrypted);
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(aesKey, 0, 16));
            byte[] plain = c.doFinal(cipherText);
            int pad = plain.length == 0 ? 0 : plain[plain.length - 1] & 0xff;
            if (pad < 1 || pad > BLOCK || pad > plain.length) {
                return Optional.empty();
            }
            for (int i = plain.length - pad; i < plain.length; i++) {
                if ((plain[i] & 0xff) != pad) {
                    return Optional.empty();
                }
            }
            int end = plain.length - pad;
            if (end < 20) {
                return Optional.empty();
            }
            int len = ByteBuffer.wrap(plain, 16, 4).getInt();
            if (len < 0 || 20 + len > end) {
                return Optional.empty();
            }
            byte[] from = Arrays.copyOfRange(plain, 20 + len, end);
            if (!MessageDigest.isEqual(from, appId.getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty();
            }
            return Optional.of(new String(plain, 20, len, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Encrypts with a fresh random prefix. */
    public String encrypt(String plainText) {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        return encrypt(plainText, random);
    }

    /** Encrypts with the given 16-byte random prefix (tests pass a fixed one). */
    public String encrypt(String plainText, byte[] random16) {
        if (random16 == null || random16.length != 16) {
            throw new IllegalArgumentException("the random prefix has 16 bytes");
        }
        byte[] msg = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] app = appId.getBytes(StandardCharsets.UTF_8);
        int raw = 20 + msg.length + app.length;
        int pad = BLOCK - (raw % BLOCK);
        ByteBuffer b = ByteBuffer.allocate(raw + pad);
        b.put(random16).putInt(msg.length).put(msg).put(app);
        for (int i = 0; i < pad; i++) {
            b.put((byte) pad);
        }
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(aesKey, 0, 16));
            return Base64.getEncoder().encodeToString(c.doFinal(b.array()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A passive reply in encrypted mode: {@code Encrypt}, {@code MsgSignature}, {@code TimeStamp} and {@code Nonce}. */
    public String encryptReply(String replyXml, String timestamp, String nonce) {
        String enc = encrypt(replyXml);
        return "<xml><Encrypt><![CDATA[" + enc + "]]></Encrypt><MsgSignature><![CDATA[" + signature(token, timestamp, nonce, enc)
                + "]]></MsgSignature><TimeStamp>" + digits(timestamp) + "</TimeStamp><Nonce><![CDATA[" + cdata(nonce) + "]]></Nonce></xml>";
    }

    private static String digits(String s) {
        if (s == null || !s.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException("timestamp must be digits");
        }
        return s;
    }

    private static String cdata(String s) {
        if (s == null || s.contains("]]>")) {
            throw new IllegalArgumentException("nonce cannot contain ]]>");
        }
        return s;
    }
}
