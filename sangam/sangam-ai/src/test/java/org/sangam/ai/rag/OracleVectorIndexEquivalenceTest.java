// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.ai.AiSchema;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.SqlPredicate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Decision 207 equivalence test on Oracle AI Database with AI Vector Search: {@link VectorIndex#oracle()} returns
 * exactly what {@link VectorIndex#portable()} returns, including chunks without a native vector, a zero vector, a
 * stored vector of another dimension, a zero query and a hidden document. Status NOT_RUN until a database is provided:
 * it runs only when {@code SANGAM_AI_ORACLE_URL} (and {@code SANGAM_AI_ORACLE_USER}, {@code SANGAM_AI_ORACLE_PASSWORD},
 * an account that may create and drop users) are set and the Oracle JDBC driver is on the test class path (Maven
 * profile {@code oracle-it}, test scope only, LIC-2). It works in a fresh schema that it drops afterwards. The column
 * statement here is test setup; the shipped changeset belongs to the generator (W4-G).
 */
class OracleVectorIndexEquivalenceTest {

    String url;
    String schema;

    @BeforeEach
    void db() throws Exception {
        url = System.getenv("SANGAM_AI_ORACLE_URL");
        assumeTrue(url != null && !url.isBlank(), "NOT_RUN: SANGAM_AI_ORACLE_URL is not set");
        schema = "W4K_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(java.util.Locale.ROOT);
        try (Connection c = connect(false); Statement s = c.createStatement()) {
            s.execute("CREATE USER " + schema + " NO AUTHENTICATION DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS");
        }
        try (Connection c = connect(true)) {
            Schema.create(c, Dialect.ORACLE);
            AiSchema.create(c, Dialect.ORACLE);
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (schema != null && url != null && !url.isBlank()) {
            try (Connection c = connect(false); Statement s = c.createStatement()) {
                s.execute("DROP USER " + schema + " CASCADE");
            }
        }
    }

    Connection connect(boolean inSchema) throws SQLException {
        Connection c = DriverManager.getConnection(url, System.getenv("SANGAM_AI_ORACLE_USER"), System.getenv("SANGAM_AI_ORACLE_PASSWORD"));
        if (inSchema) {
            try (Statement s = c.createStatement()) {
                s.execute("ALTER SESSION SET CURRENT_SCHEMA=" + schema);
            }
        }
        return c;
    }

    <T> T inTransaction(Function<Connection, T> work) {
        try (Connection c = connect(true)) {
            c.setAutoCommit(false);
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    void addColumn() {
        inTransaction(c -> Jdbc.update(c, "ALTER TABLE AI_KB_CHUNK ADD (embedding_vec VECTOR)"));
    }

    void version() {
        inTransaction(c -> Jdbc.update(c, "INSERT INTO AI_KB_VERSION(tenant_id,kb_key,version,state,model,created_at) VALUES (?,?,?,?,?,?)",
                "t", "kb", 1, "ACTIVE", "m", java.sql.Timestamp.valueOf("2026-11-13 00:00:00")));
    }

    void store(VectorIndex index, String id, String doc, int seq, float[] v, boolean nativeToo) {
        inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO AI_KB_CHUNK(tenant_id,kb_key,version,id,document_id,doc_version,seq,text_value,embedding) VALUES (?,?,?,?,?,?,?,?,?)",
                    "t", "kb", 1, id, doc, "1", seq, "text " + id, KnowledgeBaseService.encode(v));
            if (nativeToo) {
                index.stored(c, "t", "kb", 1, id, v);
            }
            return null;
        });
    }

    List<KnowledgeBaseService.Hit> rank(VectorIndex index, SqlPredicate visible, float[] q, int k) {
        return inTransaction(c -> index.rank(c, "t", "kb", visible, q, k));
    }

    @Test
    void oracleEqualsThePortableIndex() {
        addColumn();
        version();
        VectorIndex oracle = VectorIndex.oracle();
        Random r = new Random(207);
        for (int i = 0; i < 400; i++) {
            float[] v = new float[32];
            for (int d = 0; d < v.length; d++) {
                v[d] = i % 2 == 0 ? r.nextInt(3) - 1 : (float) r.nextGaussian();
            }
            store(oracle, "doc" + (i % 23) + "#" + i, "doc" + (i % 23), i, v, i % 9 != 0);
        }
        store(oracle, "zero#0", "zero", 0, new float[32], true);
        store(oracle, "odd#0", "odd", 0, new float[]{1, 0, 0}, true);
        store(oracle, "tiny#0", "tiny", 0, tiny(32), true);
        SqlPredicate all = new SqlPredicate("1=1", List.of());
        SqlPredicate hidden = new SqlPredicate("k.document_id <> ?", List.of("doc4"));
        for (int trial = 0; trial < 10; trial++) {
            float[] q = new float[32];
            for (int d = 0; d < q.length; d++) {
                q[d] = trial % 2 == 0 ? r.nextInt(5) - 2 : (float) r.nextGaussian();
            }
            for (int k : new int[]{1, 3, 25, 399, 403, 500}) {
                assertEquals(rank(VectorIndex.portable(), all, q, k), rank(oracle, all, q, k), "trial " + trial + " k " + k);
                assertEquals(rank(VectorIndex.portable(), hidden, q, k), rank(oracle, hidden, q, k), "trial " + trial + " k " + k);
            }
        }
        float[] zeroQuery = new float[32];
        assertEquals(rank(VectorIndex.portable(), all, zeroQuery, 10), rank(oracle, all, zeroQuery, 10), "zero query scores 0 everywhere");
    }

    /**
     * A vector of small components whose text needs leading zeros (no exponent is sent); their squares stay well above
     * the float underflow range, so the float-rounding margin still applies.
     */
    static float[] tiny(int n) {
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            v[i] = (i % 2 == 0 ? 1 : -1) * 1e-7f * (i + 1);
        }
        return v;
    }

    @Test
    void aMissingColumnFailsClosed() {
        version();
        store(VectorIndex.portable(), "a#0", "a", 0, new float[]{1, 0}, false);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> rank(VectorIndex.oracle(), new SqlPredicate("1=1", List.of()), new float[]{1, 0}, 1));
        assertTrue(e.getMessage().contains("AI_KB_CHUNK.embedding_vec"), e.getMessage());
    }
}
