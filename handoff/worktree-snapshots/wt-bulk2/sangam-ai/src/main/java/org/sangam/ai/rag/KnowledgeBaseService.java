// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.rag;

import org.sangam.ai.llm.LlmPort;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;

import java.sql.Connection;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Versioned knowledge bases: chunk, embed, store, retrieve. Authorization is a SQL predicate applied <b>before</b> ranking,
 * on the chunk's source document id (policy resource type {@code document}), so a hidden document cannot affect a ranking
 * or a count. The predicate comes from a {@link Visibility} binding (sangam-server: the sangam-security {@code PolicyCompiler},
 * whose denies win at every scope, A-13). Without a binding every retrieval is empty (fail closed). Ranking is brute-force cosine over at most {@code maxCandidates} authorized chunks of the active version in
 * the JVM, on all four databases. A native vector index such as pgvector is not implemented.
 */
public final class KnowledgeBaseService {

    public record Hit(String documentId, String docVersion, int seq, String text, double score) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final LlmPort llm;
    private final int chunkChars;
    private final int overlapChars;
    private final int maxCandidates;
    private final Visibility visibility;

    /** READ predicate over {@code AI_KB_CHUNK} alias {@code k}, computed in the query's own transaction. */
    @FunctionalInterface
    public interface Visibility {
        SqlPredicate visible(Connection c, SecurityIdentity who);
    }

    /** No binding: fail closed, every retrieval is empty. */
    public KnowledgeBaseService(Dialect dialect, Transactions tx, LlmPort llm, int chunkChars, int overlapChars, int maxCandidates) {
        this(dialect, tx, llm, chunkChars, overlapChars, maxCandidates, null);
    }

    /** {@code visibility} yields a predicate over alias {@code k}; null means fail closed. */
    public KnowledgeBaseService(Dialect dialect, Transactions tx, LlmPort llm, int chunkChars, int overlapChars, int maxCandidates,
                                Visibility visibility) {
        if (overlapChars >= chunkChars) {
            throw new IllegalArgumentException("overlap must be smaller than the chunk");
        }
        this.dialect = dialect;
        this.tx = tx;
        this.llm = llm;
        this.chunkChars = chunkChars;
        this.overlapChars = overlapChars;
        this.maxCandidates = maxCandidates;
        this.visibility = visibility;
    }

    /** Opens the next version in state BUILDING. */
    public int newVersion(String tenantId, String kb) {
        return newVersion(tenantId, kb, null);
    }

    /** Opens the next version in state BUILDING with descriptive metadata (title, owner, source), stored as JSON. */
    public int newVersion(String tenantId, String kb, java.util.Map<String, Object> metadataOrNull) {
        String meta = metadataOrNull == null ? null : org.sangam.integration.connector.Json.write(metadataOrNull);
        return tx.inTransaction(c -> {
            int v = Jdbc.one(c, "SELECT COALESCE(MAX(version),0)+1 FROM AI_KB_VERSION WHERE tenant_id=? AND kb_key=?", rs -> rs.getInt(1), tenantId, kb).orElse(1);
            Jdbc.update(c, "INSERT INTO AI_KB_VERSION(tenant_id,kb_key,version,state,model,created_at,metadata_json) VALUES (?,?,?,?,?,?,?)",
                    tenantId, kb, v, "BUILDING", llm.modelId(), Jdbc.dbNow(c, dialect), meta);
            return v;
        });
    }

    /** Embeds outside the transaction, then stores the chunks. Re-ingesting the same document version replaces its chunks. */
    public int ingest(String tenantId, String kb, int version, String documentId, String docVersion, String text) {
        List<String> chunks = chunk(text, chunkChars, overlapChars);
        List<byte[]> vectors = new ArrayList<>(chunks.size());
        for (String ch : chunks) {
            vectors.add(encode(llm.embed(tenantId, ch)));
        }
        return tx.inTransaction(c -> {
            String state = Jdbc.one(c, "SELECT state FROM AI_KB_VERSION WHERE tenant_id=? AND kb_key=? AND version=?", rs -> rs.getString(1), tenantId, kb, version)
                    .orElseThrow(() -> new IllegalArgumentException("no knowledge base version " + kb + " v" + version));
            if (!"BUILDING".equals(state)) {
                throw new IllegalStateException("version " + version + " is " + state + "; only a BUILDING version accepts documents");
            }
            Jdbc.update(c, "DELETE FROM AI_KB_CHUNK WHERE tenant_id=? AND kb_key=? AND version=? AND document_id=?", tenantId, kb, version, documentId);
            for (int i = 0; i < chunks.size(); i++) {
                Jdbc.update(c, "INSERT INTO AI_KB_CHUNK(tenant_id,kb_key,version,id,document_id,doc_version,seq,text_value,embedding) VALUES (?,?,?,?,?,?,?,?,?)",
                        tenantId, kb, version, documentId + "#" + i, documentId, docVersion, i, chunks.get(i), vectors.get(i));
            }
            return chunks.size();
        });
    }

    /** Makes a version the one retrieval uses; the previous active version is retired. */
    public void activate(String tenantId, String kb, int version) {
        tx.inTransaction(c -> {
            Jdbc.update(c, "UPDATE AI_KB_VERSION SET state='RETIRED', retire_reason=?, retired_at=? WHERE tenant_id=? AND kb_key=? AND state='ACTIVE'",
                    "superseded by version " + version, Jdbc.dbNow(c, dialect), tenantId, kb);
            if (Jdbc.update(c, "UPDATE AI_KB_VERSION SET state='ACTIVE' WHERE tenant_id=? AND kb_key=? AND version=?", tenantId, kb, version) == 0) {
                throw new IllegalArgumentException("no knowledge base version " + kb + " v" + version);
            }
            return null;
        });
    }

    public List<Hit> retrieve(SecurityIdentity who, String kb, String query, int k) {
        if (visibility == null) {
            return List.of();
        }
        float[] q = llm.embed(who.tenantId(), query);
        record Row(String doc, String ver, int seq, String text, byte[] vec) {
        }
        List<Row> rows = tx.inTransaction(c -> {
            SqlPredicate visible = visibility.visible(c, who);
            List<Object> params = new ArrayList<>(List.of(who.tenantId(), kb, who.tenantId(), kb));
            params.addAll(visible.params());
            String sql = "SELECT k.document_id,k.doc_version,k.seq,k.text_value,k.embedding FROM AI_KB_CHUNK k WHERE k.tenant_id=? AND k.kb_key=? AND k.version="
                    + "(SELECT v.version FROM AI_KB_VERSION v WHERE v.tenant_id=? AND v.kb_key=? AND v.state='ACTIVE') AND (" + visible.sql() + ") ORDER BY k.id";
            return Jdbc.query(c, sql, rs -> new Row(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getBytes(5)), params.toArray());
        });
        return rows.stream().limit(maxCandidates).map(r -> new Hit(r.doc, r.ver, r.seq, r.text, cosine(q, decode(r.vec))))
                .sorted(Comparator.comparingDouble(Hit::score).reversed()).limit(k).toList();
    }

    static List<String> chunk(String text, int size, int overlap) {
        List<String> out = new ArrayList<>();
        String t = text.strip();
        int at = 0;
        while (at < t.length()) {
            int end = Math.min(t.length(), at + size);
            if (end < t.length()) {
                int space = t.lastIndexOf(' ', end);
                if (space > at + overlap) {
                    end = space;
                }
            }
            out.add(t.substring(at, end).strip());
            if (end >= t.length()) {
                break;
            }
            at = end - overlap;
        }
        return out;
    }

    static byte[] encode(float[] v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * Float.BYTES);
        for (float f : v) {
            b.putFloat(f);
        }
        return b.array();
    }

    static float[] decode(byte[] raw) {
        ByteBuffer b = ByteBuffer.wrap(raw);
        float[] v = new float[raw.length / Float.BYTES];
        for (int i = 0; i < v.length; i++) {
            v[i] = b.getFloat();
        }
        return v;
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** The active version of a knowledge base, else its newest. */
    public record KbInfo(String key, int version, String status, long chunks, java.time.Instant updatedAt, java.util.Map<String, Object> metadata,
                         String retireReason, String retiredBy) {
    }

    public List<KbInfo> list(String tenantId) {
        List<KbInfo> all = tx.inTransaction(c -> Jdbc.query(c, "SELECT v.kb_key,v.version,v.state,v.created_at,(SELECT COUNT(*) FROM AI_KB_CHUNK k "
                + "WHERE k.tenant_id=v.tenant_id AND k.kb_key=v.kb_key AND k.version=v.version),v.metadata_json,v.retire_reason,v.retired_by"
                + " FROM AI_KB_VERSION v WHERE v.tenant_id=? ORDER BY v.kb_key, v.version DESC",
                rs -> new KbInfo(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getLong(5), rs.getTimestamp(4).toInstant(),
                        rs.getString(6) == null ? java.util.Map.of() : org.sangam.integration.connector.Json.readMap(rs.getString(6)), rs.getString(7), rs.getString(8)), tenantId));
        java.util.Map<String, KbInfo> out = new java.util.LinkedHashMap<>();
        for (KbInfo k : all) {
            KbInfo seen = out.get(k.key());
            if (seen == null || ("ACTIVE".equals(k.status()) && !"ACTIVE".equals(seen.status()))) {
                out.put(k.key(), k);
            }
        }
        return List.copyOf(out.values());
    }

    /** Stops retrieval from a knowledge base. Returns false when it had no active version. */
    public boolean retire(String tenantId, String kb) {
        return retire(tenantId, kb, null, null);
    }

    /** As {@link #retire(String, String)}, recording who retired it and why. */
    public boolean retire(String tenantId, String kb, String actor, String reason) {
        return tx.inTransaction(c -> Jdbc.update(c, "UPDATE AI_KB_VERSION SET state='RETIRED', retire_reason=?, retired_by=?, retired_at=? WHERE tenant_id=? AND kb_key=? AND state='ACTIVE'",
                reason, actor, Jdbc.dbNow(c, dialect), tenantId, kb)) > 0;
    }
}
