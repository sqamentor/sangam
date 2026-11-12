// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.core.security.FieldEncryptor;
import org.sangam.security.crypto.ReencryptionSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M-085: the {@link ReencryptionSource}s of the two sangam-rest stores that hold {@code FieldEncryptor} envelopes.
 * They live in the host because sangam-rest does not depend on sangam-security; the stored formats are those of
 * sangam-rest {@code DocumentStore} and {@code OperatonFormAdapter}:
 * <ul>
 *   <li>{@code REST_DOCUMENT.body} of an encrypted kind is the JSON object {@code {"_enc":"<envelope>"}}, context
 *       {@code rest-document:<kind>:<id>}. Every kind is scanned, not only the kinds configured as encrypted today,
 *       so a document of a kind that is no longer configured is still moved off a rotated-out key. Key order is
 *       {@code (kind, doc_id)} inside the tenant (the primary key);</li>
 *   <li>{@code FORM_DRAFT.data_ciphertext} is UTF-8 {@code sgm-enc1:<envelope>}, context {@code form-draft:<draftId>}.
 *       Key order is {@code draft_id}.</li>
 * </ul>
 * The rewrap is the job's: decrypt and encrypt the same text under the same context, so the canonical JSON inside is
 * unchanged and the stores' {@code decryptJson} reads it as before.
 *
 * <p>Compare-and-set: both stores bump {@code revision} on every write (the documents' and drafts' own optimistic
 * lock). {@code replace} reads the row's value and revision in the job's transaction, writes only while the value
 * equals {@code expectedStored}, and conditions the update on the revision it read, so a concurrent application write
 * (new revision) makes it match nothing. The rewrap leaves {@code revision} and {@code updated_at} unchanged: the
 * plaintext is the same, so a client holding the current revision gets no conflict from a background key move.
 *
 * <p>Documents stored under the global tenant {@code "*"} are re-encrypted when {@code "*"} is in the job's tenant list
 * ({@code sangam.security.reencryption.tenants}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.security.reencryption.enabled", havingValue = "true")
public class RestStoreReencryptionSources {

    @Bean
    public ReencryptionSource sangamRestDocumentReencryptionSource() {
        return new RestDocuments();
    }

    @Bean
    public ReencryptionSource sangamFormDraftReencryptionSource() {
        return new FormDrafts();
    }

    /** {@code REST_DOCUMENT} bodies of the form {@code {"_enc":"<envelope>"}}. */
    static final class RestDocuments implements ReencryptionSource {

        static final String NAME = "rest-document";
        static final String ENC = "_enc";
        private static final ObjectMapper JSON = new ObjectMapper();

        @Override
        public String name() {
            return NAME;
        }

        /** {@code <length of kind>:<kind><doc_id>}: unambiguous for any kind and id, at most 131 characters. */
        static String key(String kind, String docId) {
            return kind.length() + ":" + kind + docId;
        }

        /** {@code [kind, docId]} of a key written by {@link #key}. */
        static String[] parse(String key) {
            int colon = key.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("not a rest-document re-encryption key: " + key);
            }
            int len = Integer.parseInt(key.substring(0, colon));
            String rest = key.substring(colon + 1);
            if (len > rest.length()) {
                throw new IllegalArgumentException("not a rest-document re-encryption key: " + key);
            }
            return new String[] {rest.substring(0, len), rest.substring(len)};
        }

        static String context(String kind, String docId) {
            return "rest-document:" + kind + ":" + docId;
        }

        /** The envelope of an encrypted body, or null for a clear JSON body. */
        static String envelope(String body) {
            if (body == null || !body.startsWith("{\"" + ENC + "\"")) {
                return null;
            }
            try {
                Map<?, ?> m = JSON.readValue(body, Map.class);
                return m.size() == 1 && m.get(ENC) instanceof String s && FieldEncryptor.isEncrypted(s) ? s : null;
            } catch (JsonProcessingException e) {
                return null;
            }
        }

        @Override
        public List<Row> page(Connection c, String tenantId, String afterKey, int limit) {
            String sql = "SELECT kind, doc_id, body FROM REST_DOCUMENT WHERE tenant_id=?"
                    + (afterKey == null ? "" : " AND (kind>? OR (kind=? AND doc_id>?))") + " ORDER BY kind, doc_id";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setMaxRows(limit);
                ps.setString(1, tenantId);
                if (afterKey != null) {
                    String[] k = parse(afterKey);
                    ps.setString(2, k[0]);
                    ps.setString(3, k[0]);
                    ps.setString(4, k[1]);
                }
                List<Row> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && out.size() < limit) {
                        String kind = rs.getString(1);
                        String id = rs.getString(2);
                        String body = rs.getString(3);
                        out.add(new Row(key(kind, id), context(kind, id), body, envelope(body)));
                    }
                }
                return out;
            } catch (SQLException e) {
                throw new IllegalStateException("REST_DOCUMENT re-encryption page failed: " + e.getMessage(), e);
            }
        }

        @Override
        public String withEnvelope(String stored, String newEnvelope) {
            try {
                return JSON.writeValueAsString(Map.of(ENC, newEnvelope));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean replace(Connection c, String tenantId, String key, String expectedStored, String newStored) {
            String[] k = parse(key);
            try {
                long revision;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT body, revision FROM REST_DOCUMENT WHERE tenant_id=? AND kind=? AND doc_id=?")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, k[0]);
                    ps.setString(3, k[1]);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !expectedStored.equals(rs.getString(1))) {
                            return false;
                        }
                        revision = rs.getLong(2);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE REST_DOCUMENT SET body=? WHERE tenant_id=? AND kind=? AND doc_id=? AND revision=?")) {
                    ps.setString(1, newStored);
                    ps.setString(2, tenantId);
                    ps.setString(3, k[0]);
                    ps.setString(4, k[1]);
                    ps.setLong(5, revision);
                    return ps.executeUpdate() == 1;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("REST_DOCUMENT re-encryption write failed: " + e.getMessage(), e);
            }
        }
    }

    /** {@code FORM_DRAFT.data_ciphertext} holding UTF-8 {@code sgm-enc1:<envelope>}. */
    static final class FormDrafts implements ReencryptionSource {

        static final String NAME = "form-draft";
        static final String ENC_PREFIX = "sgm-enc1:";

        @Override
        public String name() {
            return NAME;
        }

        static String context(String draftId) {
            return "form-draft:" + draftId;
        }

        /** The envelope of an encrypted draft, or null for a clear (legacy) or empty one. */
        static String envelope(String stored) {
            if (stored == null || !stored.startsWith(ENC_PREFIX)) {
                return null;
            }
            String e = stored.substring(ENC_PREFIX.length());
            return FieldEncryptor.isEncrypted(e) ? e : null;
        }

        private static String text(byte[] b) {
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }

        @Override
        public List<Row> page(Connection c, String tenantId, String afterKey, int limit) {
            String sql = "SELECT draft_id, data_ciphertext FROM FORM_DRAFT WHERE tenant_id=?"
                    + (afterKey == null ? "" : " AND draft_id>?") + " ORDER BY draft_id";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setMaxRows(limit);
                ps.setString(1, tenantId);
                if (afterKey != null) {
                    ps.setString(2, afterKey);
                }
                List<Row> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && out.size() < limit) {
                        String id = rs.getString(1);
                        String stored = text(rs.getBytes(2));
                        out.add(new Row(id, context(id), stored, envelope(stored)));
                    }
                }
                return out;
            } catch (SQLException e) {
                throw new IllegalStateException("FORM_DRAFT re-encryption page failed: " + e.getMessage(), e);
            }
        }

        @Override
        public String withEnvelope(String stored, String newEnvelope) {
            return ENC_PREFIX + newEnvelope;
        }

        @Override
        public boolean replace(Connection c, String tenantId, String key, String expectedStored, String newStored) {
            try {
                long revision;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT data_ciphertext, revision FROM FORM_DRAFT WHERE tenant_id=? AND draft_id=?")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !expectedStored.equals(text(rs.getBytes(1)))) {
                            return false;
                        }
                        revision = rs.getLong(2);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE FORM_DRAFT SET data_ciphertext=? WHERE tenant_id=? AND draft_id=? AND revision=?")) {
                    ps.setBytes(1, newStored.getBytes(StandardCharsets.UTF_8));
                    ps.setString(2, tenantId);
                    ps.setString(3, key);
                    ps.setLong(4, revision);
                    return ps.executeUpdate() == 1;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("FORM_DRAFT re-encryption write failed: " + e.getMessage(), e);
            }
        }
    }
}
