// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.rag;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * M-129: the portable {@link VectorIndex}, for every Tier-1 database. It streams the visible chunks of the active
 * version in chunk-id order and keeps the best {@code k} in a bounded heap, so memory is O(k) whatever the size of the
 * knowledge base and no chunk is dropped (the former {@code maxCandidates} cap cut the candidate list in chunk-id
 * order before ranking, so a better chunk after the cap was never seen).
 */
final class PortableVectorIndex implements VectorIndex {

    /** Rows fetched per round trip while streaming. */
    static final int FETCH_SIZE = 256;

    /** A ranked candidate; {@code arrival} is its position in chunk-id order, the tie-breaker. */
    private record Scored(KnowledgeBaseService.Hit hit, long arrival) {
    }

    /** Best first: score descending, then chunk id ascending (arrival order). */
    private static final Comparator<Scored> BEST_FIRST = Comparator.comparingDouble((Scored s) -> s.hit().score()).reversed()
            .thenComparingLong(Scored::arrival);

    @Override
    public List<KnowledgeBaseService.Hit> rank(Connection c, String tenantId, String kb, SqlPredicate visible, float[] query, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be at least 1, was " + k);
        }
        String sql = "SELECT k.document_id,k.doc_version,k.seq,k.text_value,k.embedding FROM AI_KB_CHUNK k WHERE k.tenant_id=? AND k.kb_key=? AND k.version="
                + "(SELECT v.version FROM AI_KB_VERSION v WHERE v.tenant_id=? AND v.kb_key=? AND v.state='ACTIVE') AND (" + visible.sql() + ") ORDER BY k.id";
        List<Object> params = new ArrayList<>(List.of(tenantId, kb, tenantId, kb));
        params.addAll(visible.params());
        return rankRows(c, sql, params, query, k);
    }

    /**
     * Runs {@code sql}, which must select {@code document_id, doc_version, seq, text_value, embedding} in this order and
     * {@code ORDER BY k.id}, and keeps the best {@code k} by the portable cosine. Every implementation of the port ranks
     * its final candidates here, so scores and tie order are those of the portable index.
     */
    static List<KnowledgeBaseService.Hit> rankRows(Connection c, String sql, List<Object> params, float[] query, int k) {
        // the heap's head is the worst kept candidate, so a better one replaces it
        PriorityQueue<Scored> best = new PriorityQueue<>(k + 1, BEST_FIRST.reversed());
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(FETCH_SIZE);
            Jdbc.bind(ps, sql, 1, params.toArray());
            try (ResultSet rs = ps.executeQuery()) {
                long arrival = 0;
                while (rs.next()) {
                    double score = KnowledgeBaseService.cosine(query, KnowledgeBaseService.decode(rs.getBytes(5)));
                    offer(best, new Scored(new KnowledgeBaseService.Hit(rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getString(4), score), arrival++), k);
                }
            }
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException(sql, e);
        }
        List<Scored> out = new ArrayList<>(best);
        out.sort(BEST_FIRST);
        return out.stream().map(Scored::hit).toList();
    }

    private static void offer(PriorityQueue<Scored> best, Scored s, int k) {
        if (best.size() < k) {
            best.add(s);
        } else if (BEST_FIRST.compare(s, best.peek()) < 0) {
            best.poll();
            best.add(s);
        }
    }
}
