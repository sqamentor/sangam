// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.rag;

import org.sangam.core.layer.SqlPredicate;

import java.sql.Connection;
import java.util.List;

/**
 * M-129 (NG-03): the vector-index port of {@link KnowledgeBaseService}. An index ranks the chunks of the
 * <b>active</b> version of one knowledge base by cosine similarity to a query vector and returns the best {@code k}.
 *
 * <p>Contract every implementation keeps, so that results do not depend on the database:
 * <ul>
 *   <li>Authorization first: only chunks that satisfy {@code visible} (a predicate over alias {@code k} of
 *       {@code AI_KB_CHUNK}) take part in the ranking, so a hidden document never changes a ranking or a count.</li>
 *   <li>No cap that drops chunks: every visible chunk of the active version is a candidate.</li>
 *   <li>Order: score descending; equal scores keep chunk id ascending (the order the brute-force ranking had).</li>
 *   <li>A stored vector whose dimension differs from the query scores 0 (the embedding model changed).</li>
 * </ul>
 * The portable implementation ({@link #portable()}) runs on every Tier-1 database and is the default. A database-native
 * index (decision 170: {@link #pgvector()} and, decision 207, {@link #oracle()}; off by default, chosen only by explicit configuration) is another
 * implementation of this port and returns exactly what the portable index returns (see {@code NativeVectorIndex}).
 */
public interface VectorIndex {

    /**
     * Ranks inside the caller's transaction.
     *
     * @param c        the connection of the retrieval transaction
     * @param tenantId the tenant of the knowledge base
     * @param kb       the knowledge base key
     * @param visible  the READ predicate over alias {@code k}, applied before ranking
     * @param query    the query embedding
     * @param k        how many hits to return (at least 1)
     */
    List<KnowledgeBaseService.Hit> rank(Connection c, String tenantId, String kb, SqlPredicate visible, float[] query, int k);

    /**
     * Called by {@link KnowledgeBaseService#ingest} in the ingest transaction after each chunk row is inserted, so a
     * native index can store its own representation. The portable index needs nothing (the {@code embedding} blob is
     * already stored). A failure rolls the ingest back.
     */
    default void stored(Connection c, String tenantId, String kb, int version, String chunkId, float[] vector) {
    }

    /** The portable index: streams the visible chunks and keeps the best {@code k} in memory (O(k)), with no cap. */
    static VectorIndex portable() {
        return new PortableVectorIndex();
    }

    /**
     * Decision 170: PostgreSQL with the pgvector extension and the nullable column {@code AI_KB_CHUNK.embedding_vec}.
     * Off by default. Fails closed (no fallback) when the extension or column is missing.
     */
    static VectorIndex pgvector() {
        return new NativeVectorIndex(NativeVectorSql.PGVECTOR);
    }

    /**
     * Decision 207: Oracle AI Vector Search with the nullable column {@code AI_KB_CHUNK.embedding_vec} of type
     * {@code VECTOR}. Off by default. Fails closed (no fallback) when the column or the vector functions are missing.
     */
    static VectorIndex oracle() {
        return new NativeVectorIndex(NativeVectorSql.ORACLE);
    }

    /**
     * An index by configuration name: {@code portable} (the default), {@code pgvector} or {@code oracle}; anything else
     * is refused.
     */
    static VectorIndex named(String name) {
        return switch (name == null || name.isBlank() ? "portable" : name.trim()) {
            case "portable" -> portable();
            case "pgvector" -> pgvector();
            case "oracle" -> oracle();
            default -> throw new IllegalArgumentException("unknown vector index '" + name + "'; supported: portable, pgvector, oracle");
        };
    }
}
