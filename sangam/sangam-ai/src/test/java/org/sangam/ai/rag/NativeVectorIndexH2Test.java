// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.rag;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.ai.AiSchema;
import org.sangam.ai.llm.SimulatedLlm;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision 170 equivalence test (H2): {@link NativeVectorIndex} returns exactly what the portable index returns, hits
 * and scores, although its native score differs from the portable one. The native score here is a single-precision
 * cosine (what a native vector type computes) plus a deterministic perturbation of a quarter of
 * {@link NativeVectorIndex#margin(int)}, so ties and near ties are ordered differently by the database than by the
 * portable code. A test-only nullable column stands in for the native column, so chunks without a native vector are
 * exercised too. The real pgvector run is {@code PgVectorIndexEquivalenceTest} (NOT_RUN without a database).
 */
public class NativeVectorIndexH2Test {

    Transactions tx;
    JdbcDataSource ds;

    @BeforeEach
    void db() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            Schema.create(c, Dialect.POSTGRESQL);
            AiSchema.create(c, Dialect.POSTGRESQL);
            // test-only stand-in for a native column; the real DDL belongs to the generator (W4-G)
            s.execute("ALTER TABLE AI_KB_CHUNK ADD COLUMN native_vec VARBINARY(65536)");
            s.execute("CREATE ALIAS NATIVE_SCORE FOR '" + NativeVectorIndexH2Test.class.getName() + ".nativeScore'");
        }
        tx = Transactions.of(ds);
    }

    /** H2 function: a float-precision cosine with a perturbation within the margin; 0 on a dimension mismatch or zero norm. */
    public static Double nativeScore(byte[] stored, byte[] query) {
        if (stored == null || query == null) {
            // SQL semantics: a null native vector has no score (the OR branch "IS NULL" keeps the chunk)
            return null;
        }
        float[] a = KnowledgeBaseService.decode(stored);
        float[] b = KnowledgeBaseService.decode(query);
        if (a.length != b.length) {
            return 0.0;
        }
        float dot = 0;
        float na = 0;
        float nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        double cos = dot / ((float) Math.sqrt(na) * (float) Math.sqrt(nb));
        int sign = (java.util.Arrays.hashCode(stored) & 1) == 0 ? 1 : -1;
        return cos + sign * NativeVectorIndex.margin(a.length) / 4;
    }

    /** The test strategy: the native column is {@code native_vec}, scored by {@code NATIVE_SCORE}. */
    static NativeVectorSql strategy(String column) {
        return new NativeVectorSql() {
            @Override
            public String name() {
                return "h2-test";
            }

            @Override
            public String column() {
                return column;
            }

            @Override
            public String scoreExpression() {
                return "NATIVE_SCORE(k." + column + ", ?)";
            }

            @Override
            public List<Object> scoreParams(Connection c, float[] query) {
                return List.of(KnowledgeBaseService.encode(query));
            }

            @Override
            public String writeStatement() {
                return "UPDATE AI_KB_CHUNK SET " + column + "=? WHERE tenant_id=? AND kb_key=? AND version=? AND id=?";
            }

            @Override
            public Object writeParam(Connection c, float[] vector) {
                return KnowledgeBaseService.encode(vector);
            }

            @Override
            public String missingHint() {
                return "test column " + column + " is required";
            }
        };
    }

    static SqlPredicate all() {
        return new SqlPredicate("1=1", List.of());
    }

    void version(String kb, int version, String state) {
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO AI_KB_VERSION(tenant_id,kb_key,version,state,model,created_at) VALUES (?,?,?,?,?,?)",
                "t", kb, version, state, "m", java.sql.Timestamp.valueOf("2026-11-13 00:00:00")));
    }

    /** Stores a chunk; {@code nativeToo} also writes the native column through the index (as ingest does). */
    void store(VectorIndex index, String kb, int version, String id, String doc, int seq, float[] v, boolean nativeToo) {
        tx.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO AI_KB_CHUNK(tenant_id,kb_key,version,id,document_id,doc_version,seq,text_value,embedding) VALUES (?,?,?,?,?,?,?,?,?)",
                    "t", kb, version, id, doc, "1", seq, "text " + id, KnowledgeBaseService.encode(v));
            if (nativeToo) {
                index.stored(c, "t", kb, version, id, v);
            }
            return null;
        });
    }

    List<KnowledgeBaseService.Hit> rank(VectorIndex index, SqlPredicate visible, float[] q, int k) {
        return tx.inTransaction(c -> index.rank(c, "t", "kb", visible, q, k));
    }

    @Test
    void equalsThePortableIndexWithTiesNearTiesAndChunksWithoutANativeVector() {
        VectorIndex nat = new NativeVectorIndex(strategy("native_vec"));
        VectorIndex portable = VectorIndex.portable();
        Random r = new Random(170);
        version("kb", 1, "ACTIVE");
        for (int i = 0; i < 600; i++) {
            float[] v = new float[16];
            for (int d = 0; d < v.length; d++) {
                // half on a small integer grid (exact ties), half continuous (near ties within the margin)
                v[d] = i % 2 == 0 ? r.nextInt(3) - 1 : (float) r.nextGaussian();
            }
            // every seventh chunk was stored before the native index was enabled
            store(nat, "kb", 1, "doc" + (i % 41) + "#" + i, "doc" + (i % 41), i, v, i % 7 != 0);
        }
        // one stored vector of another dimension (the embedding model changed): scores 0 on both
        store(nat, "kb", 1, "odd#0", "odd", 0, new float[]{1, 0, 0}, true);
        SqlPredicate hidden = new SqlPredicate("k.document_id NOT IN (?, ?)", List.of("doc3", "doc5"));
        for (int trial = 0; trial < 15; trial++) {
            float[] q = new float[16];
            for (int d = 0; d < q.length; d++) {
                q[d] = trial % 2 == 0 ? r.nextInt(5) - 2 : (float) r.nextGaussian();
            }
            for (int k : new int[]{1, 2, 7, 50, 599, 601, 900}) {
                assertEquals(rank(portable, all(), q, k), rank(nat, all(), q, k), "trial " + trial + " k " + k);
                assertEquals(rank(portable, hidden, q, k), rank(nat, hidden, q, k), "authorization first, trial " + trial + " k " + k);
            }
        }
        assertEquals(List.of(), rank(nat, SqlPredicate.alwaysFalse(), new float[16], 3));
    }

    @Test
    void onlyChunksWithoutANativeVectorStillRankExactly() {
        VectorIndex nat = new NativeVectorIndex(strategy("native_vec"));
        version("kb", 1, "ACTIVE");
        store(nat, "kb", 1, "a#0", "a", 0, new float[]{1, 0}, false);
        store(nat, "kb", 1, "b#0", "b", 0, new float[]{0.6f, 0.8f}, false);
        float[] q = {1, 0};
        assertEquals(rank(VectorIndex.portable(), all(), q, 1), rank(nat, all(), q, 1));
        assertEquals("a", rank(nat, all(), q, 1).get(0).documentId());
    }

    @Test
    void ingestStoresTheNativeVectorAndRetrievalMatchesThePortableIndex() {
        VectorIndex nat = new NativeVectorIndex(strategy("native_vec"));
        KnowledgeBaseService.Visibility everyone = (c, who) -> all();
        KnowledgeBaseService viaNative = new KnowledgeBaseService(Dialect.POSTGRESQL, tx, new SimulatedLlm(), 60, 10, everyone, nat);
        KnowledgeBaseService viaPortable = new KnowledgeBaseService(Dialect.POSTGRESQL, tx, new SimulatedLlm(), 60, 10, everyone, VectorIndex.portable());
        int v = viaNative.newVersion("t", "kb");
        viaNative.ingest("t", "kb", v, "d1", "1", "budget reappropriation order for the district treasury and the works division");
        viaNative.ingest("t", "kb", v, "d2", "1", "water supply scheme tender evaluation and the award of the contract");
        viaNative.activate("t", "kb", v);
        long missing = tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AI_KB_CHUNK WHERE native_vec IS NULL", rs -> rs.getLong(1)).orElse(-1L));
        assertEquals(0L, missing, "ingest wrote the native vector of every chunk");
        SecurityIdentity who = new SecurityIdentity("t", "alice", "alice", Set.of(), Set.of(), Set.of(), Set.of(), "OIDC", null, null, false, Map.of());
        for (int k = 1; k <= 6; k++) {
            assertEquals(viaPortable.retrieve(who, "kb", "treasury budget order", k), viaNative.retrieve(who, "kb", "treasury budget order", k));
        }
    }

    @Test
    void aMissingNativeColumnFailsClosedWithTheHint() {
        VectorIndex nat = new NativeVectorIndex(strategy("no_such_column"));
        version("kb", 1, "ACTIVE");
        store(VectorIndex.portable(), "kb", 1, "a#0", "a", 0, new float[]{1, 0}, false);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> rank(nat, all(), new float[]{1, 0}, 1));
        assertTrue(e.getMessage().contains("test column no_such_column is required"), e.getMessage());
        IllegalStateException w = assertThrows(IllegalStateException.class,
                () -> store(nat, "kb", 1, "b#0", "b", 0, new float[]{1, 0}, true));
        assertTrue(w.getMessage().contains("h2-test vector index unavailable"), w.getMessage());
    }

    @Test
    void theMarginGrowsWithTheDimensionAndCoversFloatRounding() {
        assertEquals(12 * 0x1p-24, NativeVectorIndex.margin(1));
        assertTrue(NativeVectorIndex.margin(1536) > NativeVectorIndex.margin(768));
        assertTrue(NativeVectorIndex.margin(3072) < 1e-3, "still a narrow band at the largest common dimension");
    }

    @Test
    void indexesAreChosenByNameAndThePortableOneIsTheDefault() {
        assertInstanceOf(PortableVectorIndex.class, VectorIndex.named(null));
        assertInstanceOf(PortableVectorIndex.class, VectorIndex.named(" "));
        assertInstanceOf(PortableVectorIndex.class, VectorIndex.named("portable"));
        assertInstanceOf(NativeVectorIndex.class, VectorIndex.named("pgvector"));
        assertInstanceOf(NativeVectorIndex.class, VectorIndex.named("oracle"), "decision 207");
        assertThrows(IllegalArgumentException.class, () -> VectorIndex.named("PGVECTOR"));
        assertThrows(IllegalArgumentException.class, () -> VectorIndex.named("ORACLE"));
        assertThrows(IllegalArgumentException.class, () -> VectorIndex.named("sqlserver"), "SQL Server VECTOR is not planned");
    }

    @Test
    void theVectorLiteralRoundTripsAndRefusesNonFiniteValues() {
        assertEquals("[1.0,-0.5,1.0E-10]", NativeVectorSql.literal(new float[]{1, -0.5f, 1e-10f}));
        assertEquals("[]", NativeVectorSql.literal(new float[0]));
        assertThrows(IllegalArgumentException.class, () -> NativeVectorSql.literal(new float[]{Float.NaN}));
        assertThrows(IllegalArgumentException.class, () -> NativeVectorSql.literal(new float[]{Float.POSITIVE_INFINITY}));
    }

    /** Decision 207: the Oracle text has no exponent and every component reads back as the same float. */
    @Test
    void thePlainLiteralHasNoExponentAndReadsBackAsTheSameFloats() {
        float[] v = {1, -0.5f, 1e-10f, 3.4028235e38f, Float.MIN_VALUE, -0f, 0.1f};
        String text = NativeVectorSql.plainLiteral(v);
        assertTrue(text.startsWith("[") && text.endsWith("]"), text);
        assertTrue(!text.contains("E") && !text.contains("e"), text);
        String[] parts = text.substring(1, text.length() - 1).split(",");
        assertEquals(v.length, parts.length);
        for (int i = 0; i < v.length; i++) {
            assertEquals(Float.floatToIntBits(v[i] == 0 ? 0f : v[i]), Float.floatToIntBits(Float.parseFloat(parts[i])), parts[i]);
        }
        assertEquals("[]", NativeVectorSql.plainLiteral(new float[0]));
        assertThrows(IllegalArgumentException.class, () -> NativeVectorSql.plainLiteral(new float[]{Float.NEGATIVE_INFINITY}));
    }

    /** Decision 207: the Oracle score uses only documented functions and guards the dimension and a zero norm. */
    @Test
    void theOracleScoreGuardsTheDimensionAndAZeroNorm() throws Exception {
        String s = NativeVectorSql.ORACLE.scoreExpression();
        assertTrue(s.startsWith("CASE WHEN VECTOR_DIMENSION_COUNT(k.embedding_vec) <> ? THEN"), s);
        assertTrue(s.contains("CASE WHEN VECTOR_DIMENSION_COUNT(k.embedding_vec) = ? THEN TO_VECTOR(?, *, FLOAT32) ELSE k.embedding_vec END"), s);
        assertTrue(s.contains(" DOT) / NULLIF(VECTOR_NORM(k.embedding_vec) * ?, 0)"), s);
        assertTrue(!s.contains("COSINE") && !s.contains("<=>"), "zero-vector cosine is undocumented: " + s);
        assertEquals(4, s.chars().filter(ch -> ch == '?').count());
        assertTrue(NativeVectorSql.ORACLE.writeStatement().startsWith("UPDATE AI_KB_CHUNK SET embedding_vec=TO_VECTOR(?, *, FLOAT32) WHERE"));
        List<Object> p;
        try (Connection c = ds.getConnection()) {
            p = NativeVectorSql.ORACLE.scoreParams(c, new float[]{3, 4});
            java.sql.Clob text = assertInstanceOf(java.sql.Clob.class, p.get(2));
            assertEquals("[3.0,4.0]", text.getSubString(1, (int) text.length()), "the CLOB text the statement binds");
            NativeVectorIndex.free(p);
        }
        assertEquals(2, p.get(0), "query dimension, outer guard");
        assertEquals(2, p.get(1), "query dimension, inner guard");
        assertEquals(5.0, p.get(3), "query norm");
    }
}
