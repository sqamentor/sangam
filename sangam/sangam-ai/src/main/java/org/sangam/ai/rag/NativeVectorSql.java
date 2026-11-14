// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.rag;

import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Decision 170: the SQL a database-native vector column needs, for {@link NativeVectorIndex}. One implementation per
 * database; each is off by default and chosen only by explicit configuration. The native column lives on
 * {@code AI_KB_CHUNK} next to the portable {@code embedding} blob (which stays the source of truth: final scores are
 * always computed from it), and may be null for chunks stored before the native index was enabled.
 */
public interface NativeVectorSql {

    /** Configuration name of this index, for example {@code pgvector}. */
    String name();

    /** The native column of {@code AI_KB_CHUNK} (alias {@code k}). */
    String column();

    /**
     * SQL expression over alias {@code k} that scores one chunk with a non-null native column: the cosine similarity
     * to the query, 0 when the stored dimension differs from the query's, and 0 instead of NaN for a zero vector. Its
     * parameters are {@link #scoreParams(Connection, float[])}, in order.
     */
    String scoreExpression();

    /**
     * The parameters of {@link #scoreExpression()} for {@code query}, on the connection that runs the statement. A
     * parameter that holds a database resource (a {@link Clob}) is freed by {@link NativeVectorIndex} after use.
     */
    List<Object> scoreParams(Connection c, float[] query) throws SQLException;

    /** Statement that sets the native column of one chunk; parameters: vector, tenant_id, kb_key, version, id. */
    String writeStatement();

    /** The vector parameter of {@link #writeStatement()}; a {@link Clob} is freed after use. */
    Object writeParam(Connection c, float[] vector) throws SQLException;

    /** What the operator must provide when the native column or extension is missing. */
    String missingHint();

    /** The vector as text {@code [x,y,...]}; {@link Float#toString} gives the shortest text that reads back as the same float. */
    static String literal(float[] v) {
        StringBuilder b = new StringBuilder(v.length * 12 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(Float.toString(finite(v, i)));
        }
        return b.append(']').toString();
    }

    /**
     * The vector as text {@code [x,y,...]} without exponent notation: the same decimal value as {@link #literal}
     * (so it reads back as the same float), written in the plain form of the Oracle {@code TO_VECTOR} text examples.
     */
    static String plainLiteral(float[] v) {
        StringBuilder b = new StringBuilder(v.length * 12 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(new BigDecimal(Float.toString(finite(v, i))).toPlainString());
        }
        return b.append(']').toString();
    }

    private static float finite(float[] v, int i) {
        if (!Float.isFinite(v[i])) {
            throw new IllegalArgumentException("vector component " + i + " is not finite: " + v[i]);
        }
        return v[i];
    }

    /** The Euclidean norm of {@code v}, accumulated in double. */
    static double norm(float[] v) {
        double s = 0;
        for (float x : v) {
            s += (double) x * x;
        }
        return Math.sqrt(s);
    }

    /**
     * PostgreSQL with the pgvector extension: column {@code embedding_vec} of type {@code vector} (no fixed dimension,
     * so knowledge bases with different embedding models can share the table). {@code <=>} is the cosine distance.
     */
    NativeVectorSql PGVECTOR = new NativeVectorSql() {
        @Override
        public String name() {
            return "pgvector";
        }

        @Override
        public String column() {
            return "embedding_vec";
        }

        @Override
        public String scoreExpression() {
            return "CASE WHEN vector_dims(k.embedding_vec) <> ? THEN 0"
                    + " ELSE COALESCE(NULLIF(1 - (k.embedding_vec <=> CAST(? AS vector)), CAST('NaN' AS double precision)), 0) END";
        }

        @Override
        public List<Object> scoreParams(Connection c, float[] query) {
            return List.of(query.length, literal(query));
        }

        @Override
        public String writeStatement() {
            return "UPDATE AI_KB_CHUNK SET embedding_vec=CAST(? AS vector) WHERE tenant_id=? AND kb_key=? AND version=? AND id=?";
        }

        @Override
        public Object writeParam(Connection c, float[] vector) {
            return literal(vector);
        }

        @Override
        public String missingHint() {
            return "the pgvector extension and the nullable column AI_KB_CHUNK.embedding_vec (type vector) are required"
                    + " (decision 170 changeset); otherwise configure the portable index";
        }
    };

    /**
     * Decision 207: Oracle AI Vector Search (Oracle AI Database 26ai documentation, read 2026-11-13; see
     * DOWNLOADS-WAVE4.md): column {@code embedding_vec} of type {@code VECTOR} (equivalent to {@code VECTOR(*, *)}:
     * any dimension count, so knowledge bases with different embedding models can share the table).
     *
     * <p>Only documented behaviour is used. The documentation does not say what {@code COSINE} returns for a zero
     * vector, and a distance between vectors of different dimension counts is an error. So the score is the dot product
     * ({@code VECTOR_DISTANCE(..., DOT)} is the negated dot product, defined for every vector) divided by both norms
     * ({@code VECTOR_NORM} for the stored vector, the query norm bound from Java), with {@code NULLIF} turning a zero
     * norm into 0. The distance always compares vectors of equal dimension: for a stored vector whose dimension
     * differs from the query's, the inner {@code CASE} compares it with itself, whatever the order in which the
     * database evaluates the branches, and the outer {@code CASE} scores it 0. Vector text is bound as a {@link Clob}
     * (the documented binding for vector text longer than a VARCHAR) and converted with {@code TO_VECTOR(?, *, FLOAT32)}.
     */
    NativeVectorSql ORACLE = new NativeVectorSql() {
        @Override
        public String name() {
            return "oracle";
        }

        @Override
        public String column() {
            return "embedding_vec";
        }

        @Override
        public String scoreExpression() {
            String zero = "CAST(0 AS BINARY_DOUBLE)";
            return "CASE WHEN VECTOR_DIMENSION_COUNT(k.embedding_vec) <> ? THEN " + zero
                    + " ELSE COALESCE(-VECTOR_DISTANCE(k.embedding_vec,"
                    + " CASE WHEN VECTOR_DIMENSION_COUNT(k.embedding_vec) = ? THEN TO_VECTOR(?, *, FLOAT32) ELSE k.embedding_vec END,"
                    + " DOT) / NULLIF(VECTOR_NORM(k.embedding_vec) * ?, 0), " + zero + ") END";
        }

        @Override
        public List<Object> scoreParams(Connection c, float[] query) throws SQLException {
            return List.of(query.length, query.length, clob(c, query), norm(query));
        }

        @Override
        public String writeStatement() {
            return "UPDATE AI_KB_CHUNK SET embedding_vec=TO_VECTOR(?, *, FLOAT32) WHERE tenant_id=? AND kb_key=? AND version=? AND id=?";
        }

        @Override
        public Object writeParam(Connection c, float[] vector) throws SQLException {
            return clob(c, vector);
        }

        @Override
        public String missingHint() {
            return "Oracle AI Database with AI Vector Search and the nullable column AI_KB_CHUNK.embedding_vec (type VECTOR)"
                    + " are required (decision 207 changeset); otherwise configure the portable index";
        }

        private Clob clob(Connection c, float[] v) throws SQLException {
            String text = plainLiteral(v);
            Clob clob = c.createClob();
            try {
                clob.setString(1L, text);
            } catch (SQLException | RuntimeException e) {
                clob.free();
                throw e;
            }
            return clob;
        }
    };
}
