// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.security.crypto.ReencryptionSource;
import org.sangam.security.crypto.ReencryptionSource.Row;

/**
 * M-085: the {@code ReencryptionSource} contract of the REST_DOCUMENT and FORM_DRAFT stores on H2 with the baseline
 * column types (CLOB body, BLOB draft data): ascending total key order and resume strictly after a key, envelope
 * detection of the stored formats (clear rows are rows without an envelope), the rewritten stored value, and the
 * compare-and-set (a changed value or a new revision is never overwritten; a rewrap keeps the revision). Other tenants
 * are never read. Written for the test phase (not run here).
 */
class RestStoreReencryptionSourcesTest {

    static final String T = "t1";
    static final String OLD = "sgmenc:v1:t1/k1:AAAA";
    static final String NEW = "sgmenc:v1:t1/k2:BBBB";

    Connection c;

    @BeforeEach
    void db() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:rsrs" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE REST_DOCUMENT (tenant_id VARCHAR(64) NOT NULL, kind VARCHAR(64) NOT NULL,"
                    + " doc_id VARCHAR(64) NOT NULL, revision BIGINT NOT NULL, body CHARACTER LARGE OBJECT NOT NULL,"
                    + " PRIMARY KEY (tenant_id, kind, doc_id))");
            s.execute("CREATE TABLE FORM_DRAFT (tenant_id VARCHAR(64) NOT NULL, draft_id VARCHAR(64) NOT NULL,"
                    + " revision BIGINT NOT NULL, data_ciphertext BINARY LARGE OBJECT, PRIMARY KEY (draft_id))");
        }
    }

    @AfterEach
    void close() throws Exception {
        c.close();
    }

    void doc(String tenant, String kind, String id, long revision, String body) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO REST_DOCUMENT VALUES (?,?,?,?,?)")) {
            ps.setString(1, tenant);
            ps.setString(2, kind);
            ps.setString(3, id);
            ps.setLong(4, revision);
            ps.setString(5, body);
            ps.executeUpdate();
        }
    }

    void draft(String tenant, String id, long revision, String data) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO FORM_DRAFT VALUES (?,?,?,?)")) {
            ps.setString(1, tenant);
            ps.setString(2, id);
            ps.setLong(3, revision);
            ps.setBytes(4, data == null ? null : data.getBytes(StandardCharsets.UTF_8));
            ps.executeUpdate();
        }
    }

    String one(String sql, String... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Object o = rs.getObject(1);
                return o instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(o);
            }
        }
    }

    static List<Row> all(ReencryptionSource s, Connection c, String tenant, int limit) {
        List<Row> out = new ArrayList<>();
        String after = null;
        while (true) {
            List<Row> page = s.page(c, tenant, after, limit);
            assertTrue(page.size() <= limit);
            if (page.isEmpty()) {
                return out;
            }
            out.addAll(page);
            after = page.get(page.size() - 1).key();
        }
    }

    @Test
    void documentKeysAreUnambiguousAndRoundTrip() {
        String k = RestStoreReencryptionSources.RestDocuments.key("a:b", "c");
        assertArrayEquals(new String[] {"a:b", "c"}, RestStoreReencryptionSources.RestDocuments.parse(k));
        // "ab"+"c" and "a"+"bc" are different keys
        assertFalse(RestStoreReencryptionSources.RestDocuments.key("ab", "c")
                .equals(RestStoreReencryptionSources.RestDocuments.key("a", "bc")));
    }

    @Test
    void documentsArePagedInKeyOrderAcrossKindsAndResumeStrictlyAfterTheCursor() throws Exception {
        var src = new RestStoreReencryptionSources.RestDocuments();
        doc(T, "b", "1", 1, "{\"_enc\":\"" + OLD + "\"}");
        doc(T, "a", "2", 1, "{\"x\":1}");
        doc(T, "a", "1", 1, "{\"_enc\":\"" + OLD + "\"}");
        doc(T, "a", "10", 1, "{\"_enc\":\"" + OLD + "\"}");
        doc("t2", "a", "0", 1, "{\"_enc\":\"" + OLD + "\"}");
        List<Row> rows = all(src, c, T, 2);
        assertEquals(List.of("a/1", "a/10", "a/2", "b/1"), rows.stream()
                .map(r -> String.join("/", RestStoreReencryptionSources.RestDocuments.parse(r.key()))).toList());
        assertEquals("rest-document:a:1", rows.get(0).context());
        assertEquals(OLD, rows.get(0).envelope());
        assertNull(rows.get(2).envelope(), "a clear JSON body has no envelope");
        // a body that only looks like an envelope object is not one
        assertNull(RestStoreReencryptionSources.RestDocuments.envelope("{\"_enc\":\"plain\"}"));
        assertNull(RestStoreReencryptionSources.RestDocuments.envelope("{\"_enc\":\"" + OLD + "\",\"x\":1}"));
    }

    @Test
    void documentReplaceIsACompareAndSetThatKeepsTheRevision() throws Exception {
        var src = new RestStoreReencryptionSources.RestDocuments();
        String stored = "{\"_enc\":\"" + OLD + "\"}";
        doc(T, "k", "1", 7, stored);
        String fresh = src.withEnvelope(stored, NEW);
        assertEquals("{\"_enc\":\"" + NEW + "\"}", fresh);
        String key = RestStoreReencryptionSources.RestDocuments.key("k", "1");
        assertFalse(src.replace(c, T, key, "{\"_enc\":\"other\"}", fresh), "value changed meanwhile");
        assertFalse(src.replace(c, "t2", key, stored, fresh), "another tenant");
        assertTrue(src.replace(c, T, key, stored, fresh));
        assertEquals(fresh, one("SELECT body FROM REST_DOCUMENT WHERE kind='k' AND doc_id='1'"));
        assertEquals("7", one("SELECT revision FROM REST_DOCUMENT WHERE kind='k' AND doc_id='1'"));
        assertFalse(src.replace(c, T, key, stored, fresh), "the old value is gone");
    }

    @Test
    void draftsArePagedInIdOrderAndOnlyEnvelopesAreRewritten() throws Exception {
        var src = new RestStoreReencryptionSources.FormDrafts();
        draft(T, "d2", 1, "sgm-enc1:" + OLD);
        draft(T, "d1", 1, "{\"clear\":true}");
        draft(T, "d3", 1, null);
        draft("t2", "d0", 1, "sgm-enc1:" + OLD);
        List<Row> rows = all(src, c, T, 1);
        assertEquals(List.of("d1", "d2", "d3"), rows.stream().map(Row::key).toList());
        assertNull(rows.get(0).envelope(), "legacy clear draft");
        assertEquals(OLD, rows.get(1).envelope());
        assertEquals("form-draft:d2", rows.get(1).context());
        assertNull(rows.get(2).envelope(), "empty draft");
    }

    @Test
    void draftReplaceIsACompareAndSetThatKeepsTheRevision() throws Exception {
        var src = new RestStoreReencryptionSources.FormDrafts();
        String stored = "sgm-enc1:" + OLD;
        draft(T, "d1", 3, stored);
        String fresh = src.withEnvelope(stored, NEW);
        assertEquals("sgm-enc1:" + NEW, fresh);
        assertFalse(src.replace(c, T, "d1", "sgm-enc1:other", fresh));
        assertFalse(src.replace(c, "t2", "d1", stored, fresh));
        assertTrue(src.replace(c, T, "d1", stored, fresh));
        assertEquals(fresh, one("SELECT data_ciphertext FROM FORM_DRAFT WHERE draft_id='d1'"));
        assertEquals("3", one("SELECT revision FROM FORM_DRAFT WHERE draft_id='d1'"));
    }

    @Test
    void sourceNamesFitTheProgressKey() {
        assertTrue(new RestStoreReencryptionSources.RestDocuments().name().length() <= 64);
        assertTrue(new RestStoreReencryptionSources.FormDrafts().name().length() <= 64);
    }
}
