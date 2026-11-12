// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.crypto;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.FieldEncryptor;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.support.Sql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Scheduled, resumable, tenant-scoped re-encryption of stored field values after a tenant key rotation (M-085,
 * X.2.3.8). Before it, values moved to the new key only when the application rewrote them
 * ({@link FieldEncryptor#reencrypt}), so a rotated-out key stayed needed for as long as any old row lived.
 *
 * <p>Per tenant and {@link ReencryptionSource}, one batch is one transaction:
 * <ol>
 *   <li>the progress row {@code SEC_REENCRYPT_PROGRESS(tenant, source)} is locked. Its {@code target_key_id} is the key
 *       the run moves values to; when the tenant's active key is another one (a newer rotation), the run restarts from
 *       the first row for the new key. A {@code DONE} row for the active key ends the pass at once;</li>
 *   <li>the next page after the stored cursor {@code last_key} is read. A row already under the active key, or holding
 *       no envelope, is skipped. Any other row is decrypted with its own key and encrypted under the active key with the
 *       same context, and written with the source's compare-and-set, so a row the application changed meanwhile is left
 *       as the application wrote it. A row whose key was destroyed (410) or that does not authenticate (403) counts as
 *       failed and is left unchanged; nothing about its value is logged;</li>
 *   <li>the cursor and the counters are written in the same transaction, so a crash or a stop resumes after the last
 *       committed batch, and a row is never rewritten twice for one target key (idempotent per row in any case: a
 *       rewritten row is under the active key and is skipped);</li>
 *   <li>a page shorter than the batch size ends the run: status {@code DONE} and one
 *       {@code FIELD_REENCRYPTION_COMPLETED} event on the tenant security chain with the counts.</li>
 * </ol>
 * Two nodes running the job serialise on the progress row lock. {@linkplain #dryRun Dry run} reads every page from
 * the start and reports how many rows are not under the active key, writing nothing. The {@link Metrics} callback
 * receives every row outcome ({@code rewritten}, {@code skipped}, {@code failed}, {@code pending} in a dry run).
 *
 * <p>Owner decision 30: the stored idempotency responses are not swept (they expire), so no source is registered for
 * them. The key store calls of the encryptor run on its own leaf pool (SL-161), never on {@code tx}.
 */
public final class FieldReencryptionJob {

    private static final Logger LOG = LoggerFactory.getLogger(FieldReencryptionJob.class);
    public static final String ACTOR = "system:field-reencryption";
    public static final int DEFAULT_BATCH = 200;

    /** Row outcomes (the metric callback, M-085). */
    public interface Metrics {
        Metrics NONE = (tenant, source, outcome, rows) -> { };

        /** {@code outcome}: {@code rewritten}, {@code skipped}, {@code failed} or {@code pending} (dry run). */
        void rows(String tenantId, String source, String outcome, long rows);
    }

    /** What one pass over one source did; {@code done} when every row was visited for {@code targetKeyId}. */
    public record Result(String tenantId, String source, String targetKeyId, long scanned, long rewritten, long failed,
                         long pending, boolean done, boolean dryRun) {
    }

    private record Progress(String targetKeyId, String status, String lastKey, long scanned, long rewritten, long failed) {
    }

    private final SecurityDialect dialect;
    private final Transactions tx;
    private final AesGcmFieldEncryptor encryptor;
    private final SecurityAuditService audit;
    private final Metrics metrics;
    private final int batchSize;

    public FieldReencryptionJob(SecurityDialect dialect, Transactions tx, AesGcmFieldEncryptor encryptor,
                                SecurityAuditService audit, Metrics metrics, int batchSize) {
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("batchSize must be 1..10000");
        }
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.encryptor = Objects.requireNonNull(encryptor, "encryptor");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.metrics = metrics == null ? Metrics.NONE : metrics;
        this.batchSize = batchSize;
    }

    /** One pass for every source of the tenant: at most {@code maxBatches} batches per source. */
    public List<Result> run(String tenantId, List<? extends ReencryptionSource> sources, int maxBatches) {
        List<Result> out = new ArrayList<>();
        for (ReencryptionSource s : sources) {
            out.add(run(tenantId, s, maxBatches));
        }
        return out;
    }

    /** One pass over one source: at most {@code maxBatches} batches, each in its own transaction. */
    public Result run(String tenantId, ReencryptionSource source, int maxBatches) {
        requireName(source);
        String target = encryptor.activeKeyId(tenantId);
        long scanned = 0;
        long rewritten = 0;
        long failed = 0;
        boolean done = false;
        for (int b = 0; b < maxBatches && !done; b++) {
            long[] batch = tx.inTransaction(c -> batch(c, tenantId, source, target));
            scanned += batch[0];
            rewritten += batch[1];
            failed += batch[2];
            done = batch[3] == 1;
        }
        return new Result(tenantId, source.name(), target, scanned, rewritten, failed, 0, done, false);
    }

    /** Dry run: counts the rows not under the active key; writes nothing (no rows, no progress, no audit). */
    public Result dryRun(String tenantId, ReencryptionSource source) {
        requireName(source);
        String target = encryptor.activeKeyId(tenantId);
        long[] counts = tx.inTransaction(c -> {
            long scanned = 0;
            long pending = 0;
            String after = null;
            while (true) {
                List<ReencryptionSource.Row> page = source.page(c, tenantId, after, batchSize);
                for (ReencryptionSource.Row r : page) {
                    scanned++;
                    if (r.envelope() != null && !target.equals(FieldEncryptor.keyIdOf(r.envelope()))) {
                        pending++;
                    }
                }
                if (page.size() < batchSize) {
                    return new long[]{scanned, pending};
                }
                after = page.get(page.size() - 1).key();
            }
        });
        metrics.rows(tenantId, source.name(), "pending", counts[1]);
        return new Result(tenantId, source.name(), target, counts[0], 0, 0, counts[1], true, true);
    }

    /** {scanned, rewritten, failed, done 0/1} of one batch, in the caller's transaction. */
    private long[] batch(Connection c, String tenantId, ReencryptionSource source, String target) {
        Progress p = lockProgress(c, tenantId, source.name(), target);
        if ("DONE".equals(p.status())) {
            return new long[]{0, 0, 0, 1};
        }
        List<ReencryptionSource.Row> page = source.page(c, tenantId, p.lastKey(), batchSize);
        long rewritten = 0;
        long failed = 0;
        long skipped = 0;
        for (ReencryptionSource.Row r : page) {
            if (r.envelope() == null || target.equals(FieldEncryptor.keyIdOf(r.envelope()))) {
                skipped++;
                continue;
            }
            String fresh;
            try {
                fresh = encryptor.encrypt(tenantId, r.context(), encryptor.decrypt(tenantId, r.context(), r.envelope()));
            } catch (SangamSecurityException e) {
                failed++;   // destroyed key (410) or not authentic (403): left as it is, value never logged
                LOG.warn("field re-encryption: {} row {} of tenant {} not moved ({})", source.name(), r.key(), tenantId, e.code());
                continue;
            }
            if (source.replace(c, tenantId, r.key(), r.stored(), source.withEnvelope(r.stored(), fresh))) {
                rewritten++;
            } else {
                skipped++;   // changed meanwhile: the application wrote it under the active key
            }
        }
        boolean done = page.size() < batchSize;
        String lastKey = page.isEmpty() ? p.lastKey() : page.get(page.size() - 1).key();
        Instant now = Sql.dbNow(c, dialect);
        long scanned = p.scanned() + page.size();
        long totalRewritten = p.rewritten() + rewritten;
        long totalFailed = p.failed() + failed;
        Jdbc.update(c, "UPDATE SEC_REENCRYPT_PROGRESS SET status=?, last_key=?, scanned=?, rewritten=?, failed=?, updated_at=?,"
                        + " finished_at=? WHERE tenant_id=? AND source=?",
                done ? "DONE" : "RUNNING", lastKey, scanned, totalRewritten, totalFailed, now, done ? now : null,
                tenantId, source.name());
        if (done) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", source.name());
            payload.put("target_key_id", target);
            payload.put("scanned", scanned);
            payload.put("rewritten", totalRewritten);
            payload.put("failed", totalFailed);
            audit.security(c, SecurityIdentity.system(tenantId, ACTOR), SecurityEventType.FIELD_REENCRYPTION_COMPLETED, payload);
        }
        metrics.rows(tenantId, source.name(), "rewritten", rewritten);
        metrics.rows(tenantId, source.name(), "skipped", skipped);
        metrics.rows(tenantId, source.name(), "failed", failed);
        return new long[]{page.size(), rewritten, failed, done ? 1 : 0};
    }

    /** Locks (creating when absent) the progress row; restarts it when the active key is not its target. */
    private Progress lockProgress(Connection c, String tenantId, String source, String target) {
        Instant now = Sql.dbNow(c, dialect);
        Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_REENCRYPT_PROGRESS(tenant_id, source, target_key_id, status, last_key,"
                + " scanned, rewritten, failed, started_at, updated_at, finished_at) VALUES (?,?,?,'RUNNING',NULL,0,0,0,?,?,NULL)",
                tenantId, source, target, now, now);
        Optional<Progress> row = Jdbc.one(c, dialect.lockingSelect("target_key_id, status, last_key, scanned, rewritten, failed",
                        "SEC_REENCRYPT_PROGRESS", "tenant_id=? AND source=?"),
                rs -> new Progress(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6)), tenantId, source);
        Progress p = row.orElseThrow();
        if (target.equals(p.targetKeyId())) {
            return p;
        }
        Jdbc.update(c, "UPDATE SEC_REENCRYPT_PROGRESS SET target_key_id=?, status='RUNNING', last_key=NULL, scanned=0, rewritten=0,"
                + " failed=0, started_at=?, updated_at=?, finished_at=NULL WHERE tenant_id=? AND source=?",
                target, now, now, tenantId, source);
        return new Progress(target, "RUNNING", null, 0, 0, 0);
    }

    private static void requireName(ReencryptionSource source) {
        String n = source.name();
        if (n == null || n.isBlank() || n.length() > 64) {
            throw new IllegalArgumentException("a re-encryption source needs a name of 1..64 characters");
        }
    }
}
