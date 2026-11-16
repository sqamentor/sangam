// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Owner decisions 387 (4) and 390 (4): a key custodian's signed statement that keys belong to one environment, in
 * place of another environment's fingerprint list. The custodian is the person or office that issues the keys of every
 * environment, so only the custodian can say that a key is used by no other environment.
 *
 * <p>The file ({@value KeyEnvironmentSeparation#ATTESTATION}) is JSON:
 * <pre>
 * {"format": "sangam-key-attestation/1", "statement": "&lt;base64 of the statement's UTF-8 JSON&gt;",
 *  "signature": "&lt;base64 Ed25519 signature over exactly those statement bytes&gt;"}
 * </pre>
 * and the statement is {@code {"environment": "<name>", "issued_at": "<ISO-8601>", "keys": [{"fingerprint":
 * "<64 lower-case hex>", "purpose": "<purpose>"}, ...]}}, the fingerprints being those this environment publishes
 * ({@value KeyEnvironmentSeparation#EXPORT}) and the purposes those of {@link KeyPurposeSeparation#SETTINGS}. The
 * signature is checked against the custodian's Ed25519 public key ({@value KeyEnvironmentSeparation#CUSTODIAN}, base64
 * X.509), a trusted key of someone else (no fingerprint, {@link KeyPurposeSeparation.Role#TRUST}). The signature covers
 * the statement's bytes as stored, so no canonical form is needed. Anything else (an unreadable file, another format, a
 * bad or missing signature, a malformed statement) is refused with {@value #INVALID}; a refusal never prints a key or a
 * fingerprint.
 */
public final class KeyAttestation {

    public static final String FORMAT = "sangam-key-attestation/1";
    public static final String INVALID = "KEY_ATTESTATION_INVALID";
    private static final ObjectMapper JSON = new ObjectMapper();

    private KeyAttestation() {
    }

    /** One attested key: a fingerprint this environment publishes and the purpose it serves. */
    public record Key(String fingerprint, String purpose) {
    }

    /** The verified statement. */
    public record Statement(String environment, String issuedAt, Set<Key> keys) {
    }

    /** The custodian's Ed25519 public key from its base64 X.509 form. */
    public static PublicKey custodian(String base64) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw refusal(KeyEnvironmentSeparation.CUSTODIAN + " is not a base64 X.509 Ed25519 public key");
        }
    }

    /** Reads and verifies the attestation file; the statement only when the custodian's signature verifies. */
    public static Statement verify(Path file, PublicKey custodian) {
        JsonNode outer;
        try {
            outer = JSON.readTree(Files.readAllBytes(file));
        } catch (IOException | RuntimeException e) {
            throw refusal(KeyEnvironmentSeparation.ATTESTATION + " cannot be read (" + e.getClass().getSimpleName() + ")");
        }
        if (outer == null || !outer.isObject() || !FORMAT.equals(text(outer, "format"))) {
            throw refusal(KeyEnvironmentSeparation.ATTESTATION + " is not a " + FORMAT + " document");
        }
        byte[] statement;
        byte[] signature;
        try {
            statement = Base64.getDecoder().decode(require(outer, "statement"));
            signature = Base64.getDecoder().decode(require(outer, "signature"));
        } catch (IllegalArgumentException e) {
            throw refusal(KeyEnvironmentSeparation.ATTESTATION + ": statement and signature must be base64");
        }
        boolean ok;
        try {
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(custodian);
            v.update(statement);
            ok = v.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            ok = false;
        }
        if (!ok) {
            throw refusal(KeyEnvironmentSeparation.ATTESTATION + ": the signature does not verify against "
                    + KeyEnvironmentSeparation.CUSTODIAN);
        }
        return statement(statement);
    }

    /** The statement's fields; only a signed statement reaches here, but its form is still checked (fail closed). */
    static Statement statement(byte[] bytes) {
        JsonNode s;
        try {
            s = JSON.readTree(bytes);
        } catch (IOException | RuntimeException e) {
            throw refusal(KeyEnvironmentSeparation.ATTESTATION + ": the statement is not JSON");
        }
        if (s == null || !s.isObject()) {
            throw refusal(KeyEnvironmentSeparation.ATTESTATION + ": the statement is not a JSON object");
        }
        String environment = require(s, "environment");
        if (!KeyEnvironmentSeparation.NAME.matcher(environment).matches()) {
            throw refusal(KeyEnvironmentSeparation.ATTESTATION + ": the statement's environment must match "
                    + KeyEnvironmentSeparation.NAME.pattern());
        }
        String issued = require(s, "issued_at");
        JsonNode keys = s.get("keys");
        if (keys == null || !keys.isArray() || keys.isEmpty()) {
            throw refusal(KeyEnvironmentSeparation.ATTESTATION + ": the statement attests no key");
        }
        Set<Key> out = new LinkedHashSet<>();
        for (int i = 0; i < keys.size(); i++) {
            JsonNode k = keys.get(i);
            String f = k != null && k.isObject() ? text(k, "fingerprint") : null;
            String p = k != null && k.isObject() ? text(k, "purpose") : null;
            if (f == null || !KeyEnvironmentSeparation.FINGERPRINT.matcher(f).matches() || p == null || p.isBlank()) {
                throw refusal(KeyEnvironmentSeparation.ATTESTATION + ": key " + (i + 1)
                        + " is not {fingerprint: 64 lower-case hex digits, purpose}");
            }
            out.add(new Key(f, p));
        }
        return new Statement(environment, issued, Set.copyOf(out));
    }

    /**
     * Writes an attestation as a custodian would (the format above), for the custodian's own tooling and for tests:
     * the statement bytes are signed exactly as written.
     */
    public static String sign(String environment, String issuedAt, List<Key> keys, PrivateKey custodian) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("environment", environment);
        statement.put("issued_at", issuedAt);
        List<Map<String, String>> ks = new ArrayList<>();
        for (Key k : keys) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("fingerprint", k.fingerprint());
            m.put("purpose", k.purpose());
            ks.add(m);
        }
        statement.put("keys", ks);
        try {
            byte[] bytes = JSON.writeValueAsBytes(statement);
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(custodian);
            s.update(bytes);
            Map<String, String> outer = new LinkedHashMap<>();
            outer.put("format", FORMAT);
            outer.put("statement", Base64.getEncoder().encodeToString(bytes));
            outer.put("signature", Base64.getEncoder().encodeToString(s.sign()));
            return new String(JSON.writeValueAsBytes(outer), StandardCharsets.UTF_8);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign the key attestation", e);
        }
    }

    private static String require(JsonNode n, String field) {
        String v = text(n, field);
        if (v == null || v.isBlank()) {
            throw refusal(KeyEnvironmentSeparation.ATTESTATION + ": " + field + " is missing");
        }
        return v;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    static IllegalStateException refusal(String what) {
        return new IllegalStateException(INVALID + " (owner decisions 37, 387 (4) and 390: a custodian-signed key"
                + " attestation): " + what);
    }
}
