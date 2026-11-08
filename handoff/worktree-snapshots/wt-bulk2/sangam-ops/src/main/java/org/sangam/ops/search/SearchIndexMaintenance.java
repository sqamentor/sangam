// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.search;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch maintenance of the relational search index {@code OPS_SEARCH_DOC}. A rebuild writes a new generation beside the live one with
 * recorded progress. Only a complete rebuild whose count matches moves the tenant's alias, in one transaction, and then the old
 * generation is deleted, so readers never see a partial index. {@link #drift} compares source counts with the live generation. The
 * index is a table for this module; no external search engine is involved.
 */
public final class SearchIndexMaintenance {

    public record Doc(String id, String title, String body) {
    }

    /** Reads one document type from its source of truth by keyset. */
    public interface Reader {
        String type();

        long count(String tenantId);

        List<Doc> read(String tenantId, String afterIdOrNull, int limit);
    }

    public record Job(long generation, String state, long indexed, long expected, String message, String reason) {
    }

    public record Drift(String type, long source, long indexed) {
        public boolean drifted() {
            return source != indexed;
        }
    }

    private final Dialect dialect;
    private final Transactions tx;

    public SearchIndexMaintenance(Dialect dialect, Transactions tx) {
        this.dialect = dialect;
        this.tx = tx;
    }

    public Job rebuild(String tenantId, List<Reader> readers, int batchSize) {
        return rebuild(tenantId, readers, batchSize, null);
    }

    /** As {@link #rebuild(String, List, int)}, storing why the rebuild was started. */
    public Job rebuild(String tenantId, List<Reader> readers, int batchSize, String reason) {
        long expected = readers.stream().mapToLong(r -> r.count(tenantId)).sum();
        long gen = tx.inTransaction(c -> {
            long g = Jdbc.one(c, "SELECT COALESCE(MAX(generation),0)+1 FROM OPS_INDEX_JOB", rs -> rs.getLong(1)).orElse(1L);
            Jdbc.update(c, "INSERT INTO OPS_INDEX_JOB(generation,tenant_id,state,indexed,expected,updated_at,reason) VALUES (?,?,?,0,?,?,?)", g, tenantId, "RUNNING", expected, Jdbc.dbNow(c, dialect), reason);
            return g;
        });
        long done = 0;
        try {
            for (Reader r : readers) {
                String after = null;
                while (true) {
                    List<Doc> docs = r.read(tenantId, after, batchSize);
                    if (docs.isEmpty()) {
                        break;
                    }
                    done += docs.size();
                    long progress = done;
                    tx.inTransaction(c -> {
                        for (Doc d : docs) {
                            Jdbc.update(c, "INSERT INTO OPS_SEARCH_DOC(generation,tenant_id,doc_type,id,title,body) VALUES (?,?,?,?,?,?)", gen, tenantId, r.type(), d.id(), d.title(), d.body());
                        }
                        Jdbc.update(c, "UPDATE OPS_INDEX_JOB SET indexed=?, updated_at=? WHERE generation=?", progress, Jdbc.dbNow(c, dialect), gen);
                        return null;
                    });
                    after = docs.get(docs.size() - 1).id();
                }
            }
        } catch (RuntimeException e) {
            finish(gen, "FAILED", e.toString());
            return job(gen);
        }
        long actual = readers.stream().mapToLong(r -> r.count(tenantId)).sum();
        if (actual != done) {
            finish(gen, "NOT_SWITCHED", "source changed during rebuild: indexed " + done + ", source now " + actual + "; rebuild again");
            tx.inTransaction(c -> Jdbc.update(c, "DELETE FROM OPS_SEARCH_DOC WHERE generation=? AND tenant_id=?", gen, tenantId));
            return job(gen);
        }
        tx.inTransaction(c -> {
            Instant now = Jdbc.dbNow(c, dialect);
            Long old = Jdbc.one(c, "SELECT generation FROM OPS_SEARCH_ALIAS WHERE tenant_id=?", rs -> rs.getLong(1), tenantId).orElse(null);
            if (old == null) {
                Jdbc.update(c, "INSERT INTO OPS_SEARCH_ALIAS(tenant_id,generation,switched_at) VALUES (?,?,?)", tenantId, gen, now);
            } else {
                Jdbc.update(c, "UPDATE OPS_SEARCH_ALIAS SET generation=?, switched_at=? WHERE tenant_id=?", gen, now, tenantId);
                Jdbc.update(c, "DELETE FROM OPS_SEARCH_DOC WHERE generation=? AND tenant_id=?", old, tenantId);
            }
            return null;
        });
        finish(gen, "ACTIVE", null);
        return job(gen);
    }

    private void finish(long gen, String state, String message) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE OPS_INDEX_JOB SET state=?, message=?, updated_at=? WHERE generation=?", state, message, Jdbc.dbNow(c, dialect), gen));
    }

    public Job job(long generation) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT generation,state,indexed,expected,message,reason FROM OPS_INDEX_JOB WHERE generation=?",
                rs -> new Job(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6)), generation).orElseThrow());
    }

    public List<Drift> drift(String tenantId, List<Reader> readers) {
        Map<String, Long> indexed = new LinkedHashMap<>();
        tx.inTransaction(c -> Jdbc.query(c, "SELECT d.doc_type, COUNT(*) FROM OPS_SEARCH_DOC d JOIN OPS_SEARCH_ALIAS a ON a.tenant_id=d.tenant_id AND a.generation=d.generation "
                + "WHERE d.tenant_id=? GROUP BY d.doc_type", rs -> indexed.put(rs.getString(1), rs.getLong(2)), tenantId));
        return readers.stream().map(r -> new Drift(r.type(), r.count(tenantId), indexed.getOrDefault(r.type(), 0L))).toList();
    }

    /** A tenant's rebuild jobs, newest first, with the time of their last update. */
    public record JobRow(Job job, java.time.Instant updatedAt) {
    }

    public List<JobRow> jobs(String tenantId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT generation,state,indexed,expected,message,updated_at,reason FROM OPS_INDEX_JOB WHERE tenant_id=? ORDER BY generation DESC",
                rs -> new JobRow(new Job(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(7)), rs.getTimestamp(6).toInstant()), tenantId));
    }
}
