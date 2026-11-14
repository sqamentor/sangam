// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.rag;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decision 170: a {@link VectorIndex} that lets the database score chunks with its native vector type and still
 * returns exactly what {@link VectorIndex#portable()} returns.
 *
 * <p>Why two steps: a native cosine accumulates in single precision and in its own order, so its score can differ
 * from the portable score (double accumulation over float products) and a plain native {@code ORDER BY ... FETCH FIRST k}
 * could swap near-equal chunks or break ties differently. Both scores are within {@link #margin(int)} of the exact
 * cosine (error bound of a float dot product and norms, at most {@code (n + 2)} units in the last place of a float per
 * term, with a safety factor). So:
 * <ol>
 *   <li>the database returns the k-th best native score {@code t} among the visible chunks that have a native vector;</li>
 *   <li>it then returns every visible chunk whose native score is at least {@code t - 2 * margin}, plus every visible
 *       chunk without a native vector (stored before the index was enabled);</li>
 *   <li>these candidates are ranked by the portable code ({@code PortableVectorIndex.rankRows}) from the
 *       {@code embedding} blob, so scores and tie order (chunk id ascending) are the portable ones.</li>
 * </ol>
 * A chunk of the exact top k has a portable score of at least the k-th portable score of the scored chunks, which is
 * at least {@code t - margin}; its native score is therefore at least {@code t - 2 * margin}, so step 2 keeps it. If the
 * database answers step 1 from an approximate index, its k-th score can only be lower, which widens step 2 and keeps
 * the result exact. Authorization ({@code visible}) applies in both steps, before any scoring.
 *
 * <p>Fail closed: a missing extension or column fails the retrieval with {@link NativeVectorSql#missingHint()}; there
 * is no silent fallback to another index.
 */
final class NativeVectorIndex implements VectorIndex {

    private final NativeVectorSql sql;

    NativeVectorIndex(NativeVectorSql sql) {
        this.sql = Objects.requireNonNull(sql, "sql");
    }

    /** Bound on |native score - portable score| for vectors of dimension {@code n}: {@code (4n + 8) * 2^-24}. */
    static double margin(int n) {
        return (4.0 * n + 8.0) * 0x1p-24;
    }

    @Override
    public List<KnowledgeBaseService.Hit> rank(Connection c, String tenantId, String kb, SqlPredicate visible, float[] query, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be at least 1, was " + k);
        }
        String col = "k." + sql.column();
        String scope = " FROM AI_KB_CHUNK k WHERE k.tenant_id=? AND k.kb_key=? AND k.version="
                + "(SELECT v.version FROM AI_KB_VERSION v WHERE v.tenant_id=? AND v.kb_key=? AND v.state='ACTIVE') AND (" + visible.sql() + ")";
        List<Object> scopeParams = new ArrayList<>(List.of(tenantId, kb, tenantId, kb));
        scopeParams.addAll(visible.params());

        String kth = "SELECT " + sql.scoreExpression() + " AS s" + scope + " AND " + col + " IS NOT NULL"
                + " ORDER BY s DESC OFFSET ? ROWS FETCH FIRST 1 ROWS ONLY";
        List<Object> kthParams = new ArrayList<>(scoreParams(c, query));
        kthParams.addAll(scopeParams);
        kthParams.add(k - 1);
        Double threshold;
        try {
            threshold = kthScore(c, kth, kthParams);
        } finally {
            free(kthParams);
        }

        String candidates = "SELECT k.document_id,k.doc_version,k.seq,k.text_value,k.embedding" + scope;
        List<Object> params = new ArrayList<>(scopeParams);
        if (threshold != null) {
            candidates += " AND (" + col + " IS NULL OR " + sql.scoreExpression() + " >= ?)";
            params.addAll(scoreParams(c, query));
            params.add(threshold - 2 * margin(query.length));
        }
        candidates += " ORDER BY k.id";
        try {
            return PortableVectorIndex.rankRows(c, candidates, params, query, k);
        } catch (Jdbc.LayerSqlException e) {
            throw unavailable(e);
        } finally {
            free(params);
        }
    }

    private List<Object> scoreParams(Connection c, float[] query) {
        try {
            return sql.scoreParams(c, query);
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    /** Frees the database resources among {@code params} (the CLOBs a strategy binds), so none outlives its statement. */
    static void free(List<Object> params) {
        RuntimeException failure = null;
        for (Object p : params) {
            if (p instanceof Clob clob) {
                try {
                    clob.free();
                } catch (SQLException | RuntimeException e) {
                    if (failure == null) {
                        failure = new IllegalStateException("vector index: a bound CLOB could not be freed", e);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** The k-th best native score, or null when fewer than k visible chunks have a native vector. */
    private Double kthScore(Connection c, String statement, List<Object> params) {
        try (PreparedStatement ps = c.prepareStatement(statement)) {
            Jdbc.bind(ps, statement, 1, params.toArray());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                double t = rs.getDouble(1);
                if (rs.wasNull() || Double.isNaN(t)) {
                    throw new IllegalStateException(sql.name() + " index returned no score for a stored vector");
                }
                return t;
            }
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    @Override
    public void stored(Connection c, String tenantId, String kb, int version, String chunkId, float[] vector) {
        String statement = sql.writeStatement();
        if (statement == null) {
            return;
        }
        Object param = null;
        try (PreparedStatement ps = c.prepareStatement(statement)) {
            param = sql.writeParam(c, vector);
            Jdbc.bind(ps, statement, 1, param, tenantId, kb, version, chunkId);
            int n = ps.executeUpdate();
            if (n != 1) {
                throw new IllegalStateException(sql.name() + " index: chunk " + chunkId + " not found to store its vector (" + n + " rows)");
            }
        } catch (SQLException e) {
            throw unavailable(e);
        } finally {
            if (param != null) {
                free(java.util.Collections.singletonList(param));
            }
        }
    }

    private IllegalStateException unavailable(Exception cause) {
        return new IllegalStateException(sql.name() + " vector index unavailable: " + sql.missingHint() + " (" + cause.getMessage() + ")", cause);
    }
}
