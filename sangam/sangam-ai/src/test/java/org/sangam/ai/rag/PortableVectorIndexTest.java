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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-129 equivalence test (H2): the portable vector index returns exactly what an uncapped brute-force ranking returns
 * (score descending, ties by chunk id), applies the authorization predicate before ranking, and no longer loses a better
 * chunk that sits after the former {@code maxCandidates} cap.
 */
class PortableVectorIndexTest {

    Transactions tx;

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            AiSchema.create(c, Dialect.POSTGRESQL);
        }
        tx = Transactions.of(ds);
    }

    record Chunk(String id, String doc, int seq, float[] vec) {
    }

    void version(String kb, int version, String state) {
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO AI_KB_VERSION(tenant_id,kb_key,version,state,model,created_at) VALUES (?,?,?,?,?,?)",
                "t", kb, version, state, "m", java.sql.Timestamp.valueOf("2026-11-12 00:00:00")));
    }

    void store(String kb, int version, Chunk ch) {
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO AI_KB_CHUNK(tenant_id,kb_key,version,id,document_id,doc_version,seq,text_value,embedding) VALUES (?,?,?,?,?,?,?,?,?)",
                "t", kb, version, ch.id(), ch.doc(), "1", ch.seq(), "text " + ch.id(), KnowledgeBaseService.encode(ch.vec())));
    }

    /** The reference: every chunk scored, sorted by score descending, ties in chunk-id order (stable sort). */
    static List<String> bruteForce(List<Chunk> chunks, float[] q, int k) {
        List<Chunk> byId = new ArrayList<>(chunks);
        byId.sort(Comparator.comparing(Chunk::id));
        return byId.stream().map(ch -> Map.entry(ch, KnowledgeBaseService.cosine(q, ch.vec())))
                .sorted(Map.Entry.<Chunk, Double>comparingByValue().reversed())
                .limit(k).map(e -> e.getKey().doc() + "#" + e.getKey().seq()).toList();
    }

    List<String> portable(String kb, SqlPredicate visible, float[] q, int k) {
        return tx.inTransaction(c -> VectorIndex.portable().rank(c, "t", kb, visible, q, k)).stream()
                .map(h -> h.documentId() + "#" + h.seq()).toList();
    }

    static SqlPredicate all() {
        return new SqlPredicate("1=1", List.of());
    }

    @Test
    void equalsTheUncappedBruteForceRankingIncludingTies() {
        Random r = new Random(129);
        version("kb", 1, "ACTIVE");
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 700; i++) {
            float[] v = new float[8];
            for (int d = 0; d < v.length; d++) {
                // a small integer grid makes equal scores (ties) likely, so the tie order is exercised too
                v[d] = r.nextInt(3) - 1;
            }
            Chunk ch = new Chunk("doc" + (i % 37) + "#" + i, "doc" + (i % 37), i, v);
            chunks.add(ch);
            store("kb", 1, ch);
        }
        for (int trial = 0; trial < 20; trial++) {
            float[] q = new float[8];
            for (int d = 0; d < q.length; d++) {
                q[d] = r.nextInt(5) - 2;
            }
            for (int k : new int[]{1, 5, 50, 700, 900}) {
                assertEquals(bruteForce(chunks, q, k), portable("kb", all(), q, k), "trial " + trial + " k " + k);
            }
        }
    }

    @Test
    void onlyTheActiveVersionAndTheVisibleChunksAreRanked() {
        version("kb", 1, "RETIRED");
        version("kb", 2, "ACTIVE");
        store("kb", 1, new Chunk("old#0", "old", 0, new float[]{1, 0}));
        store("kb", 2, new Chunk("a#0", "a", 0, new float[]{1, 0}));
        store("kb", 2, new Chunk("b#0", "b", 0, new float[]{0.9f, 0.1f}));
        float[] q = {1, 0};
        assertEquals(List.of("a#0", "b#0"), portable("kb", all(), q, 5));
        // authorization first: the hidden best chunk neither appears nor pushes a visible one out of the top k
        assertEquals(List.of("b#0"), portable("kb", new SqlPredicate("k.document_id <> ?", List.of("a")), q, 1));
        assertEquals(List.of(), portable("kb", SqlPredicate.alwaysFalse(), q, 5));
        assertEquals(List.of(), portable("none", all(), q, 5));
    }

    @Test
    void aStoredVectorOfAnotherDimensionScoresZero() {
        version("kb", 1, "ACTIVE");
        store("kb", 1, new Chunk("a#0", "a", 0, new float[]{1, 0, 0}));
        store("kb", 1, new Chunk("b#0", "b", 0, new float[]{0.1f, 1}));
        var hits = tx.inTransaction(c -> VectorIndex.portable().rank(c, "t", "kb", all(), new float[]{1, 0}, 2));
        assertEquals("b", hits.get(0).documentId());
        assertEquals(0.0, hits.get(1).score());
    }

    @Test
    void kBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> tx.inTransaction(c -> VectorIndex.portable().rank(c, "t", "kb", all(), new float[]{1}, 0)));
    }

    @Test
    void theFormerCandidateCapNoLongerDropsTheBestChunk() {
        // maxCandidates = 1 used to keep only the first chunk in id order ("a-doc"), so the exact match was never ranked
        KnowledgeBaseService kb = new KnowledgeBaseService(Dialect.POSTGRESQL, tx, new SimulatedLlm(), 200, 20, 1,
                (c, who) -> new SqlPredicate("1=1", List.of()));
        int v = kb.newVersion("t", "kb");
        kb.ingest("t", "kb", v, "a-doc", "1", "unrelated words only");
        kb.ingest("t", "kb", v, "m-doc", "1", "something else entirely");
        kb.ingest("t", "kb", v, "z-doc", "1", "budget reappropriation order");
        kb.activate("t", "kb", v);
        SecurityIdentity who = new SecurityIdentity("t", "alice", "alice", Set.of(), Set.of(), Set.of(), Set.of(), "OIDC",
                null, null, false, Map.of());
        var hits = kb.retrieve(who, "kb", "budget reappropriation order", 1);
        assertEquals(List.of("z-doc"), hits.stream().map(KnowledgeBaseService.Hit::documentId).toList());
        assertTrue(hits.get(0).score() > 0.99, "exact lexical match: " + hits.get(0).score());
        assertEquals(List.of(), kb.retrieve(who, "kb", "budget", 0), "k below one returns nothing");
    }

    @Test
    void theIndexIsRequiredAndTheLegacyCapMustBePositive() {
        assertThrows(NullPointerException.class, () -> new KnowledgeBaseService(Dialect.POSTGRESQL, tx, new SimulatedLlm(), 200, 20,
                null, null));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeBaseService(Dialect.POSTGRESQL, tx, new SimulatedLlm(), 200, 20, 0));
    }
}
