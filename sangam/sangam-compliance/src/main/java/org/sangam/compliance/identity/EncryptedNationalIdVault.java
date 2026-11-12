// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.identity;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.ports.CompliancePorts.NationalIdVault;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.FieldEncryptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The durable national-identifier data vault (M-078, R50; table {@code CMP_NATIONAL_ID_VAULT}). The number is stored
 * only here, encrypted with the tenant's data key ({@link FieldEncryptor}, authenticated context
 * {@code national-id:<type>:<referenceKey>}, so a ciphertext copied to another row or tenant does not decrypt); the
 * rest of Sangam holds the random reference key. Lookup of an existing number goes through a keyed HMAC-SHA256
 * ({@code lookup_hash}, pepper from configuration), so the table never holds a plain or unkeyed hash of a number from
 * a small, guessable space. The same number of the same type gets the same reference key (one-to-one mapping), and
 * every issue and every reveal is audited on the tenant's compliance chain with the masked number only.
 *
 * <p>IN pack: this is the Aadhaar Data Vault of the UIDAI circulars on storage of Aadhaar numbers (reference key in
 * place of the number, encryption, access logging). No biometric data is ever accepted: the port carries a number
 * only. Keys at rest are the tenant data keys of sangam-security; an HSM-backed key store is configured there.
 */
public final class EncryptedNationalIdVault implements NationalIdVault {

    private static final int MIN_PEPPER_BYTES = 16;

    private final Dialect dialect;
    private final Transactions tx;
    private final FieldEncryptor encryptor;
    private final byte[] pepper;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;

    public EncryptedNationalIdVault(Dialect dialect, Transactions tx, FieldEncryptor encryptor, byte[] pepper,
                                    ComplianceAudit audit, ComplianceConfig.Provider config) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.encryptor = Objects.requireNonNull(encryptor, "encryptor");
        if (pepper == null || pepper.length < MIN_PEPPER_BYTES) {
            throw new IllegalArgumentException("the national-id vault pepper must be at least " + MIN_PEPPER_BYTES + " bytes");
        }
        this.pepper = pepper.clone();
        this.audit = Objects.requireNonNull(audit, "audit");
        this.config = config;
    }

    @Override
    public String referenceKey(String tenantId, String identifierType, String number) {
        Sql.requireText(tenantId, "tenantId");
        Sql.requireText(identifierType, "identifierType");
        String value = Sql.requireText(number, "number").strip();
        String lookup = lookupHash(tenantId, identifierType, value);
        return tx.inTransaction(c -> {
            Optional<String> existing = find(c, tenantId, identifierType, lookup);
            if (existing.isPresent()) {
                return existing.get();
            }
            String ref = Sql.newId();
            String masked = mask(tenantId, identifierType, value);
            Instant now = Sql.now(c, dialect);
            boolean inserted = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CMP_NATIONAL_ID_VAULT(tenant_id, reference_key, id_type,"
                            + " lookup_hash, number_enc, masked, created_by, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    tenantId, ref, identifierType, lookup, encryptor.encrypt(tenantId, context(identifierType, ref), value),
                    masked, "system:national-id-vault", now);
            if (!inserted) {
                // a concurrent issue of the same number won the unique (tenant, type, lookup) index: use its key
                return find(c, tenantId, identifierType, lookup).orElseThrow(() ->
                        new IllegalStateException("national-id vault insert lost and no row found"));
            }
            audit.organisation(c, tenantId, ComplianceAudit.NATIONAL_ID_VAULTED,
                    Sql.kv("referenceKey", ref, "type", identifierType, "masked", masked));
            return ref;
        });
    }

    @Override
    public Optional<String> reveal(String tenantId, String referenceKey, String purpose, String actor) {
        Sql.requireText(tenantId, "tenantId");
        Sql.requireText(referenceKey, "referenceKey");
        Sql.requireText(purpose, "purpose");
        Sql.requireText(actor, "actor");
        return tx.inTransaction(c -> {
            Optional<String[]> row = Jdbc.one(c, "SELECT id_type, number_enc, masked FROM CMP_NATIONAL_ID_VAULT"
                            + " WHERE tenant_id=? AND reference_key=?",
                    rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, tenantId, referenceKey);
            if (row.isEmpty()) {
                return Optional.<String>empty();
            }
            String number = encryptor.decrypt(tenantId, context(row.get()[0], referenceKey), row.get()[1]);
            audit.organisation(c, tenantId, ComplianceAudit.NATIONAL_ID_REVEALED,
                    Sql.kv("referenceKey", referenceKey, "type", row.get()[0], "masked", row.get()[2], "purpose", purpose,
                            "actor", actor));
            return Optional.of(number);
        });
    }

    private Optional<String> find(java.sql.Connection c, String tenantId, String type, String lookup) {
        return Jdbc.one(c, "SELECT reference_key FROM CMP_NATIONAL_ID_VAULT WHERE tenant_id=? AND id_type=? AND lookup_hash=?",
                rs -> rs.getString(1), tenantId, type, lookup);
    }

    static String context(String type, String referenceKey) {
        return "national-id:" + type + ":" + referenceKey;
    }

    /** HMAC-SHA256 over {@code tenant | type | number} (64 hex characters). */
    String lookupHash(String tenantId, String type, String number) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return Hashes.hex(mac.doFinal((tenantId + "|" + type + "|" + number).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    /** The pack's mask for the type (keep-last rule), or every character masked for a type the pack does not list. */
    String mask(String tenantId, String type, String number) {
        if (config != null) {
            try {
                for (JurisdictionPack.NationalIdentifier id : config.forTenant(tenantId).pack.nationalIdentifiers()) {
                    if (id.code().equals(type)) {
                        return NationalIdGuard.mask(id, number);
                    }
                }
            } catch (ComplianceException | IllegalStateException e) {
                // no effective jurisdiction for the tenant: fall through to the full mask
            }
        }
        return "X".repeat(number.length());
    }
}
