// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.sangam.ai.AiSchema;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Decision 170 equivalence test on real PostgreSQL with pgvector: {@link VectorIndex#pgvector()} returns exactly what
 * {@link VectorIndex#portable()} returns. Status NOT_RUN until a database is provided: it runs only when
 * {@code SANGAM_AI_PGVECTOR_URL} (and {@code SANGAM_AI_PGVECTOR_USER}, {@code SANGAM_AI_PGVECTOR_PASSWORD}) are set,
 * for example against the pgvector image of decision 188. It works in a fresh schema that it drops afterwards. The
 * extension and column statements here are test setup; the shipped changeset belongs to the generator.
 */
class PgVectorIndexEquivalenceTest {

    PGSimpleDataSource ds;
    Transactions tx;
    String schema;

    @BeforeEach
    void db() throws Exception {
        String url = System.getenv("SANGAM_AI_PGVECTOR_URL");
        assumeTrue(url != null && !url.isBlank(), "NOT_RUN: SANGAM_AI_PGVECTOR_URL is not set");
        ds = new PGSimpleDataSource();
        ds.setURL(url);
        ds.setUser(System.getenv("SANGAM_AI_PGVECTOR_USER"));
        ds.setPassword(System.getenv("SANGAM_AI_PGVECTOR_PASSWORD"));
        schema = "w4k_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            s.execute("CREATE SCHEMA " + schema);
        }
        ds.setCurrentSchema(schema + ",public");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            AiSchema.create(c, Dialect.POSTGRESQL);
        }
        tx = Transactions.of(ds);
    }

    @AfterEach
    void drop() throws Exception {
        if (schema != null && ds != null) {
            ds.setCurrentSchema("public");
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    void addColumn() {
        tx.inTransaction(c -> Jdbc.update(c, "ALTER TABLE AI_KB_CHUNK ADD COLUMN embedding_vec vector"));
    }

    void version() {
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO AI_KB_VERSION(tenant_id,kb_key,version,state,model,created_at) VALUES (?,?,?,?,?,?)",
                "t", "kb", 1, "ACTIVE", "m", java.sql.Timestamp.valueOf("2026-11-13 00:00:00")));
    }

    void store(VectorIndex index, String id, String doc, int seq, float[] v, boolean nativeToo) {
        tx.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO AI_KB_CHUNK(tenant_id,kb_key,version,id,document_id,doc_version,seq,text_value,embedding) VALUES (?,?,?,?,?,?,?,?,?)",
                    "t", "kb", 1, id, doc, "1", seq, "text " + id, KnowledgeBaseService.encode(v));
            if (nativeToo) {
                index.stored(c, "t", "kb", 1, id, v);
            }
            return null;
        });
    }

    List<KnowledgeBaseService.Hit> rank(VectorIndex index, SqlPredicate visible, float[] q, int k) {
        return tx.inTransaction(c -> index.rank(c, "t", "kb", visible, q, k));
    }

    @Test
    void pgvectorEqualsThePortableIndex() {
        addColumn();
        version();
        VectorIndex pg = VectorIndex.pgvector();
        Random r = new Random(188);
        for (int i = 0; i < 400; i++) {
            float[] v = new float[32];
            for (int d = 0; d < v.length; d++) {
                v[d] = i % 2 == 0 ? r.nextInt(3) - 1 : (float) r.nextGaussian();
            }
            store(pg, "doc" + (i % 23) + "#" + i, "doc" + (i % 23), i, v, i % 9 != 0);
        }
        store(pg, "zero#0", "zero", 0, new float[32], true);
        store(pg, "odd#0", "odd", 0, new float[]{1, 0, 0}, true);
        SqlPredicate all = new SqlPredicate("1=1", List.of());
        SqlPredicate hidden = new SqlPredicate("k.document_id <> ?", List.of("doc4"));
        for (int trial = 0; trial < 10; trial++) {
            float[] q = new float[32];
            for (int d = 0; d < q.length; d++) {
                q[d] = trial % 2 == 0 ? r.nextInt(5) - 2 : (float) r.nextGaussian();
            }
            for (int k : new int[]{1, 3, 25, 399, 402, 500}) {
                assertEquals(rank(VectorIndex.portable(), all, q, k), rank(pg, all, q, k), "trial " + trial + " k " + k);
                assertEquals(rank(VectorIndex.portable(), hidden, q, k), rank(pg, hidden, q, k), "trial " + trial + " k " + k);
            }
        }
    }

    @Test
    void aMissingColumnFailsClosed() {
        version();
        store(VectorIndex.portable(), "a#0", "a", 0, new float[]{1, 0}, false);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> rank(VectorIndex.pgvector(), new SqlPredicate("1=1", List.of()), new float[]{1, 0}, 1));
        assertTrue(e.getMessage().contains("AI_KB_CHUNK.embedding_vec"), e.getMessage());
    }
}
