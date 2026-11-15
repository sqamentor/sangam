// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.sangam.core.security.FieldEncryptor;
import org.sangam.ops.secrets.SecretAdmin;
import org.sangam.security.crypto.ReencryptionSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SL-181: the {@link ReencryptionSource} of the secret store ({@link SecretAdmin}, table {@code OPS_SECRET}), so a
 * tenant key rotation moves stored secrets to the active key and the rotated-out key is no longer needed to resolve
 * them. It lives in the host because sangam-ops depends on sangam-security for tests only. Collected by
 * {@link FieldReencryptionWiring} with every other source.
 * <ul>
 *   <li>{@code OPS_SECRET.envelope} is the bare {@code FieldEncryptor} envelope, context {@link SecretAdmin#CONTEXT}
 *       (the one {@code SecretAdmin.resolver} decrypts with);</li>
 *   <li>key order is {@code ref_name} inside the tenant (the primary key is {@code (tenant_id, ref_name)}).</li>
 * </ul>
 *
 * <p>Compare-and-set: every {@code SecretAdmin} write sets a new envelope and bumps {@code version}. {@code replace}
 * reads the row's envelope and version in the job's transaction, writes only while the envelope equals
 * {@code expectedStored}, and conditions the update on the version it read (a CLOB is not compared in SQL, which
 * Oracle refuses), so a concurrent import (new version) makes it match nothing. The rewrap keeps {@code version},
 * {@code updated_by}, {@code updated_at} and {@code reason}: the secret value is the same, so the listed version is
 * not a new secret version.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.security.reencryption.enabled", havingValue = "true")
public class SecretStoreReencryptionSource {

    @Bean
    public ReencryptionSource sangamSecretStoreReencryptionSource() {
        return new Secrets();
    }

    /** {@code OPS_SECRET} rows of one tenant. */
    static final class Secrets implements ReencryptionSource {

        static final String NAME = "ops-secret";

        @Override
        public String name() {
            return NAME;
        }

        /** The first page of a tenant: constant SQL, every value bound. */
        static final String PAGE_FIRST_SQL =
                "SELECT ref_name, envelope FROM OPS_SECRET WHERE tenant_id=? ORDER BY ref_name";

        /** A later page, after {@code ref_name}: constant SQL, every value bound. */
        static final String PAGE_AFTER_SQL =
                "SELECT ref_name, envelope FROM OPS_SECRET WHERE tenant_id=? AND ref_name>? ORDER BY ref_name";

        @Override
        public List<Row> page(Connection c, String tenantId, String afterKey, int limit) {
            String sql = afterKey == null ? PAGE_FIRST_SQL : PAGE_AFTER_SQL;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setMaxRows(limit);
                ps.setString(1, tenantId);
                if (afterKey != null) {
                    ps.setString(2, afterKey);
                }
                List<Row> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && out.size() < limit) {
                        String stored = rs.getString(2);
                        String envelope = stored != null && FieldEncryptor.isEncrypted(stored) ? stored : null;
                        out.add(new Row(rs.getString(1), SecretAdmin.CONTEXT, stored, envelope));
                    }
                }
                return out;
            } catch (SQLException e) {
                throw new IllegalStateException("OPS_SECRET re-encryption page failed: " + e.getMessage(), e);
            }
        }

        @Override
        public String withEnvelope(String stored, String newEnvelope) {
            return newEnvelope;
        }

        @Override
        public boolean replace(Connection c, String tenantId, String key, String expectedStored, String newStored) {
            try {
                int version;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT envelope, version FROM OPS_SECRET WHERE tenant_id=? AND ref_name=?")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !expectedStored.equals(rs.getString(1))) {
                            return false;
                        }
                        version = rs.getInt(2);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE OPS_SECRET SET envelope=? WHERE tenant_id=? AND ref_name=? AND version=?")) {
                    ps.setString(1, newStored);
                    ps.setString(2, tenantId);
                    ps.setString(3, key);
                    ps.setInt(4, version);
                    return ps.executeUpdate() == 1;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("OPS_SECRET re-encryption write failed: " + e.getMessage(), e);
            }
        }
    }
}
