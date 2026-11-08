// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.secrets;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.FieldEncryptor;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Secret management back end: values are stored only as {@link FieldEncryptor} envelopes. Listing returns metadata, never values; there
 * is no read-value operation for people, only {@link #resolver} for connectors. Bulk import and an encrypted bundle (PBKDF2 and
 * AES-256-GCM with an operator passphrase) move secrets between clusters. The audit trail records names only.
 */
public final class SecretAdmin {

    public record SecretInfo(String ref, int version, String updatedBy, Instant updatedAt, String reason) {
    }

    public record ImportResult(int created, int updated) {
    }

    private static final int ITERATIONS = 600_000;
    private static final String CONTEXT = "ops-secret";

    private final Dialect dialect;
    private final Transactions tx;
    private final AuditChain audit;
    private final FieldEncryptor encryptor;

    public SecretAdmin(Dialect dialect, Transactions tx, AuditChain audit, FieldEncryptor encryptor) {
        this.dialect = dialect;
        this.tx = tx;
        this.audit = audit;
        this.encryptor = encryptor;
    }

    public void put(String tenantId, String ref, String value, String actor) {
        importAll(tenantId, Map.of(ref, value), actor);
    }

    public ImportResult importAll(String tenantId, Map<String, String> values, String actor) {
        return importAll(tenantId, values, actor, null);
    }

    /** Writes values; {@code reason} is stored on every row written and in the audit event. */
    public ImportResult importAll(String tenantId, Map<String, String> values, String actor, String reason) {
        values.keySet().forEach(SecretAdmin::checkRef);
        return tx.inTransaction(c -> {
            int created = 0;
            int updated = 0;
            Instant now = Jdbc.dbNow(c, dialect);
            for (Map.Entry<String, String> e : values.entrySet()) {
                String env = encryptor.encrypt(tenantId, CONTEXT, e.getValue());
                if (Jdbc.update(c, "UPDATE OPS_SECRET SET envelope=?, version=version+1, updated_by=?, updated_at=?, reason=? WHERE tenant_id=? AND ref_name=?", env, actor, now, reason, tenantId, e.getKey()) > 0) {
                    updated++;
                } else {
                    Jdbc.update(c, "INSERT INTO OPS_SECRET(tenant_id,ref_name,envelope,version,updated_by,updated_at,reason) VALUES (?,?,?,1,?,?,?)", tenantId, e.getKey(), env, actor, now, reason);
                    created++;
                }
            }
            audit.append(c, tenantId, "secrets:" + tenantId, "SECRETS_WRITTEN", Map.of("refs", List.copyOf(values.keySet()), "actor", actor, "reason", String.valueOf(reason)));
            return new ImportResult(created, updated);
        });
    }

    public List<SecretInfo> list(String tenantId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT ref_name,version,updated_by,updated_at,reason FROM OPS_SECRET WHERE tenant_id=? ORDER BY ref_name",
                rs -> new SecretInfo(rs.getString(1), rs.getInt(2), rs.getString(3), Jdbc.instant(rs, "updated_at"), rs.getString(5)), tenantId));
    }

    public void delete(String tenantId, String ref, String actor) {
        tx.inTransaction(c -> {
            if (Jdbc.update(c, "DELETE FROM OPS_SECRET WHERE tenant_id=? AND ref_name=?", tenantId, ref) > 0) {
                audit.append(c, tenantId, "secrets:" + tenantId, "SECRET_DELETED", Map.of("ref", ref, "actor", actor));
            }
            return null;
        });
    }

    /** Binding for connectors: {@code secret://name} to the current value of this tenant. */
    public SecretResolver resolver(String tenantId) {
        return reference -> {
            String name = reference.replaceFirst("^secret://", "");
            Optional<String> env = tx.inTransaction(c -> Jdbc.one(c, "SELECT envelope FROM OPS_SECRET WHERE tenant_id=? AND ref_name=?", rs -> rs.getString(1), tenantId, name));
            return env.map(e -> encryptor.decrypt(tenantId, CONTEXT, e));
        };
    }

    /** Cluster-to-cluster export: an encrypted, self-describing bundle. Requires a reason, which is audited with the names. */
    public String exportBundle(String tenantId, char[] passphrase, String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required");
        }
        Map<String, String> plain = new LinkedHashMap<>();
        List<String[]> rows = tx.inTransaction(c -> Jdbc.query(c, "SELECT ref_name,envelope FROM OPS_SECRET WHERE tenant_id=?", rs -> new String[]{rs.getString(1), rs.getString(2)}, tenantId));
        rows.forEach(r -> plain.put(r[0], encryptor.decrypt(tenantId, CONTEXT, r[1])));
        tx.inTransaction(c -> audit.append(c, tenantId, "secrets:" + tenantId, "SECRETS_EXPORTED", Map.of("refs", List.copyOf(plain.keySet()), "actor", actor, "reason", reason)));
        return seal(Json.bytes(plain), passphrase);
    }

    public ImportResult importBundle(String tenantId, String bundle, char[] passphrase, String actor) {
        Map<String, Object> m = Json.readMap(new String(open(bundle, passphrase), StandardCharsets.UTF_8));
        Map<String, String> values = new LinkedHashMap<>();
        m.forEach((k, v) -> values.put(k, String.valueOf(v)));
        return importAll(tenantId, values, actor, "bundle import");
    }

    static String seal(byte[] plain, char[] passphrase) {
        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[16];
            byte[] iv = new byte[12];
            random.nextBytes(salt);
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), new GCMParameterSpec(128, iv));
            Base64.Encoder b = Base64.getEncoder();
            return "sangam-secrets-v1." + b.encodeToString(salt) + "." + b.encodeToString(iv) + "." + b.encodeToString(c.doFinal(plain));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] open(String bundle, char[] passphrase) {
        String[] p = bundle.split("\\.");
        if (p.length != 4 || !p[0].equals("sangam-secrets-v1")) {
            throw new IllegalArgumentException("not a secret bundle");
        }
        try {
            Base64.Decoder d = Base64.getDecoder();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(passphrase, d.decode(p[1])), new GCMParameterSpec(128, d.decode(p[2])));
            return c.doFinal(d.decode(p[3]));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("wrong passphrase or damaged bundle");
        }
    }

    private static SecretKeySpec key(char[] passphrase, byte[] salt) throws GeneralSecurityException {
        byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(passphrase, salt, ITERATIONS, 256)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    private static void checkRef(String ref) {
        if (!ref.matches("[A-Za-z0-9][A-Za-z0-9_./-]{0,200}")) {
            throw new IllegalArgumentException("invalid secret name: " + ref);
        }
    }
}
