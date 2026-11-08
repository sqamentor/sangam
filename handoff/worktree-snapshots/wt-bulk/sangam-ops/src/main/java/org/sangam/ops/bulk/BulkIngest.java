// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.bulk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bulk data ingest (SL-44): an asynchronous job per object type, filled by chunked uploads and applied row by row by a
 * worker. Tables {@code OPS_BULK_JOB}, {@code OPS_BULK_CHUNK}, {@code OPS_BULK_ROW}, {@code OPS_BULK_KEY}.
 *
 * <p><b>Lifecycle.</b> {@code OPEN} (accepts chunks) → {@link #close} → {@code PROCESSING} → {@code COMPLETED} or
 * {@code COMPLETED_WITH_ERRORS}; {@code OPEN | PROCESSING} → {@link #abort} → {@code ABORTED}. Rows are
 * {@code PENDING → RUNNING → DONE | SKIPPED | FAILED}, or {@code CANCELLED} by an abort.
 *
 * <p><b>Tenant scope (VI.4 rule 6).</b> The tenant is always the caller's, passed in by the REST layer from the
 * authenticated identity; every statement on a job carries {@code tenant_id = ?}, so a job of another tenant is
 * "not found", never readable or writable. Sinks receive the job's tenant, never one taken from row data.
 *
 * <p><b>Per-row idempotency.</b> {@code OPS_BULK_KEY} holds one row per (tenant, object type, external id) with the
 * hash of the last applied payload and the sink's result reference. A row whose payload hash equals the stored one
 * is {@code SKIPPED} ({@code DUPLICATE}) and the sink is not called again, across jobs and re-uploads. A changed
 * payload is applied with the previous result reference, so a sink updates instead of creating twice. Within one
 * job a later row for the same key wins: an earlier row processed after it is {@code SKIPPED} ({@code SUPERSEDED}).
 * The sink, the key row and the row status commit in one transaction on the connection given to the sink.
 *
 * <p><b>Uploads.</b> A chunk is identified by its sequence number and is idempotent: the same content again is a
 * replay (nothing stored twice), different content for a stored sequence is a conflict. Rows that fail validation
 * are stored as {@code FAILED / INVALID_ROW} so they appear in the error report. Limits ({@link Limits}) and the
 * {@link BulkQuota} port are checked before anything is stored.
 *
 * <p><b>Worker.</b> {@link #runOnce} claims pending rows of processing jobs with {@code SKIP LOCKED} (several nodes
 * may work one job) and records the claiming node. All state is in the database, so a restarted node resumes:
 * {@link #recoverNode} gives back the rows its previous incarnation held, {@link #recoverStale} those of a node that
 * did not come back. Sinks must be idempotent per (tenant, external id) for effects outside the given connection
 * (R36), because a row may be retried after a crash or a transient failure.
 */
public final class BulkIngest {

    public enum JobState { OPEN, PROCESSING, COMPLETED, COMPLETED_WITH_ERRORS, ABORTED }

    public enum RowState { PENDING, RUNNING, DONE, SKIPPED, FAILED, CANCELLED }

    /** Applies the rows of one object type. Registered by object type; the REST layer requires {@link #permission()}. */
    public interface Sink {

        String objectType();

        /** Permission the caller needs to create or fill a job of this type. */
        String permission();

        /**
         * Applies one row in the given transaction and returns a short reference to what it created or updated.
         *
         * @param actor       the principal who created the job (for the sink's own audit)
         * @param existingRef the reference this sink returned for the same external id before, or {@code null}
         * @throws RowRejectedException for a row that can never succeed (it is not retried)
         */
        String apply(Connection c, String tenant, String actor, String externalId, Map<String, Object> data, String existingRef);

        /** Called after the row's transaction committed (cache eviction and similar); must not throw. */
        default void committed(String tenant, String externalId) {
        }
    }

    /** A row the sink refuses for good (validation); recorded as {@code FAILED} with {@code code}, never retried. */
    public static final class RowRejectedException extends RuntimeException {
        private final String code;

        public RowRejectedException(String code, String message) {
            super(message);
            this.code = code == null || code.isBlank() ? "ROW_REJECTED" : code;
        }

        public String code() {
            return code;
        }
    }

    /** Refusal of a request; the REST layer maps the kind to a status (404, 409, 400, 413, 429). */
    public static final class BulkException extends RuntimeException {
        public enum Kind { NOT_FOUND, CONFLICT, INVALID, LIMIT, QUOTA }

        private final Kind kind;
        private final String code;
        private final int retryAfterSeconds;

        public BulkException(Kind kind, String code, String message, int retryAfterSeconds) {
            super(message);
            this.kind = kind;
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        BulkException(Kind kind, String code, String message) {
            this(kind, code, message, 0);
        }

        public Kind kind() {
            return kind;
        }

        public String code() {
            return code;
        }

        public int retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** Service limits; every value must be positive. */
    public record Limits(int maxRowsPerChunk, int maxChunkBytes, int maxChunksPerJob, long maxRowsPerJob,
                         int maxActiveJobsPerTenant, int maxAttempts) {

        public static final Limits DEFAULTS = new Limits(1000, 4 * 1024 * 1024, 10_000, 1_000_000L, 10, 5);

        public Limits {
            if (maxRowsPerChunk < 1 || maxChunkBytes < 1 || maxChunksPerJob < 1 || maxRowsPerJob < 1
                    || maxActiveJobsPerTenant < 1 || maxAttempts < 1) {
                throw new IllegalArgumentException("bulk limits must be positive");
            }
        }
    }

    /** One uploaded row: the caller's stable id of the record and its fields. */
    public record RowInput(String externalId, Map<String, Object> data) {
    }

    public record Job(String id, String tenantId, String objectType, JobState state, Integer expectedChunks, int chunks,
                      long totalRows, Map<RowState, Long> rows, String createdBy, Instant createdAt, Instant closedAt,
                      Instant completedAt) {
    }

    public record ChunkReceipt(int chunkSeq, int rows, int invalidRows, boolean replayed) {
    }

    public record RowStatus(int chunkSeq, int rowSeq, String externalId, RowState state, int attempts, String errorCode,
                            String errorText, String resultRef) {
    }

    /** One page of a list and the total number of matching entries. */
    public record Slice<T>(List<T> items, long total) {
    }

    public static final int MAX_EXTERNAL_ID = 255;
    public static final int MAX_OBJECT_TYPE = 64;
    private static final int MAX_ERROR_TEXT = 1000;
    private static final ObjectMapper CANONICAL = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final Dialect dialect;
    private final Transactions tx;
    private final AuditChain audit;
    private final BulkQuota quota;
    private final Limits limits;
    private final String node;
    private final Map<String, Sink> sinks = new ConcurrentHashMap<>();

    public BulkIngest(Dialect dialect, Transactions tx, AuditChain audit, BulkQuota quota, Limits limits, String node) {
        this.dialect = Objects.requireNonNull(dialect);
        this.tx = Objects.requireNonNull(tx);
        this.audit = audit;
        this.quota = quota == null ? BulkQuota.UNLIMITED : quota;
        this.limits = limits == null ? Limits.DEFAULTS : limits;
        if (node == null || node.isBlank() || node.length() > 64) {
            throw new IllegalArgumentException("bulk ingest needs a node id of 1..64 characters");
        }
        this.node = node;
    }

    public BulkIngest register(Sink sink) {
        if (sink.objectType() == null || !sink.objectType().matches("[A-Za-z][A-Za-z0-9_.-]{0," + (MAX_OBJECT_TYPE - 1) + "}")) {
            throw new IllegalArgumentException("invalid bulk object type " + sink.objectType());
        }
        if (sinks.putIfAbsent(sink.objectType(), sink) != null) {
            throw new IllegalArgumentException("duplicate bulk sink for " + sink.objectType());
        }
        return this;
    }

    public Optional<Sink> sink(String objectType) {
        return Optional.ofNullable(objectType == null ? null : sinks.get(objectType));
    }

    public Set<String> objectTypes() {
        return Set.copyOf(sinks.keySet());
    }

    public Limits limits() {
        return limits;
    }

    public String node() {
        return node;
    }

    // ------------------------------------------------------------------ jobs

    public Job create(String tenant, String objectType, Integer expectedChunks, String actor) {
        requireTenant(tenant);
        if (sink(objectType).isEmpty()) {
            throw new BulkException(BulkException.Kind.INVALID, "UNKNOWN_OBJECT_TYPE", "no bulk sink for object type " + objectType);
        }
        if (expectedChunks != null && (expectedChunks < 1 || expectedChunks > limits.maxChunksPerJob())) {
            throw new BulkException(BulkException.Kind.INVALID, "INVALID_EXPECTED_CHUNKS", "expectedChunks must be 1.." + limits.maxChunksPerJob());
        }
        String id = UUID.randomUUID().toString();
        tx.inTransaction(c -> {
            long active = Jdbc.one(c, "SELECT COUNT(*) FROM OPS_BULK_JOB WHERE tenant_id = ? AND state IN ('OPEN','PROCESSING')",
                    rs -> rs.getLong(1), tenant).orElse(0L);
            if (active >= limits.maxActiveJobsPerTenant()) {
                throw new BulkException(BulkException.Kind.CONFLICT, "BULK_ACTIVE_JOBS",
                        "tenant has " + active + " open or processing bulk jobs (limit " + limits.maxActiveJobsPerTenant() + ")");
            }
            Jdbc.update(c, "INSERT INTO OPS_BULK_JOB(id, tenant_id, object_type, state, expected_chunks, chunk_count, total_rows,"
                            + " created_by, created_at, updated_at, closed_at, completed_at) VALUES (?,?,?,'OPEN',?,0,0,?,"
                            + dialect.now() + "," + dialect.now() + ",NULL,NULL)",
                    id, tenant, objectType, expectedChunks, actor);
            audit(c, tenant, id, "BULK_JOB_CREATED", Map.of("objectType", objectType, "actor", String.valueOf(actor)));
            return null;
        });
        return job(tenant, id);
    }

    /** Stores one chunk of an {@code OPEN} job; a replay of the same chunk content stores nothing. */
    public ChunkReceipt upload(String tenant, String jobId, int chunkSeq, List<RowInput> rows, String actor) {
        requireTenant(tenant);
        if (chunkSeq < 0 || chunkSeq >= limits.maxChunksPerJob()) {
            throw new BulkException(BulkException.Kind.LIMIT, "BULK_LIMIT", "chunk sequence must be 0.." + (limits.maxChunksPerJob() - 1));
        }
        if (rows == null || rows.isEmpty()) {
            throw new BulkException(BulkException.Kind.INVALID, "EMPTY_CHUNK", "a chunk needs at least one row");
        }
        if (rows.size() > limits.maxRowsPerChunk()) {
            throw new BulkException(BulkException.Kind.LIMIT, "BULK_LIMIT", "a chunk holds at most " + limits.maxRowsPerChunk() + " rows");
        }
        List<String> payloads = new ArrayList<>(rows.size());
        long bytes = 0;
        for (RowInput r : rows) {
            String p = canonical(r == null || r.data() == null ? Map.of() : r.data());
            payloads.add(p);
            bytes += p.getBytes(StandardCharsets.UTF_8).length
                    + (r == null || r.externalId() == null ? 0 : r.externalId().getBytes(StandardCharsets.UTF_8).length);
        }
        final long chunkBytes = bytes;
        if (chunkBytes > limits.maxChunkBytes()) {
            throw new BulkException(BulkException.Kind.LIMIT, "BULK_LIMIT", "a chunk holds at most " + limits.maxChunkBytes() + " bytes of row data");
        }
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            RowInput r = rows.get(i);
            all.append(r == null ? "" : String.valueOf(r.externalId())).append('\u0000').append(payloads.get(i)).append('\u0001');
        }
        String chunkHash = sha256(all.toString());

        // Read-only pre-check, so a replay or a refused chunk never consumes quota.
        Object[] pre = tx.inTransaction(c -> {
            Object[] j = jobRow(c, tenant, jobId);
            Optional<ChunkReceipt> r = existingChunk(c, jobId, chunkSeq, chunkHash);
            if (r.isPresent()) {
                return new Object[]{r.get(), j[1]};
            }
            checkAccepting(j, jobId, rows.size());
            return new Object[]{null, j[1]};
        });
        if (pre[0] != null) {
            return (ChunkReceipt) pre[0];
        }
        BulkQuota.Decision d = quota.acquire(tenant, (String) pre[1], rows.size());
        if (d == null || !d.allowed()) {
            throw new BulkException(BulkException.Kind.QUOTA, "QUOTA_EXCEEDED",
                    d == null || d.reason() == null ? "bulk row quota exceeded" : d.reason(), d == null ? 60 : d.retryAfterSeconds());
        }
        try {
            return tx.inTransaction(c -> {
                Jdbc.one(c, dialect.lockingSelect("id", "OPS_BULK_JOB", "id = ? AND tenant_id = ?"), rs -> rs.getString(1), jobId, tenant)
                        .orElseThrow(() -> notFound(jobId));
                Object[] j = jobRow(c, tenant, jobId);
                Optional<ChunkReceipt> r = existingChunk(c, jobId, chunkSeq, chunkHash);
                if (r.isPresent()) {
                    return r.get();
                }
                checkAccepting(j, jobId, rows.size());
                int invalid = 0;
                for (int i = 0; i < rows.size(); i++) {
                    RowInput in = rows.get(i);
                    String ext = in == null ? null : in.externalId();
                    String problem = validate(in);
                    if (problem != null) {
                        invalid++;
                    }
                    Jdbc.update(c, "INSERT INTO OPS_BULK_ROW(job_id, chunk_seq, row_seq, external_id, payload, payload_hash, state, attempts,"
                                    + " error_code, error_text, result_ref, claimed_by, updated_at) VALUES (?,?,?,?,?,?,?,0,?,?,NULL,NULL," + dialect.now() + ")",
                            jobId, chunkSeq, i, problem == null ? ext : truncate(ext == null ? "" : ext, MAX_EXTERNAL_ID),
                            payloads.get(i), sha256(payloads.get(i)), problem == null ? RowState.PENDING.name() : RowState.FAILED.name(),
                            problem == null ? null : "INVALID_ROW", problem);
                }
                Jdbc.update(c, "INSERT INTO OPS_BULK_CHUNK(job_id, chunk_seq, row_count, invalid_count, byte_count, chunk_hash, uploaded_by, uploaded_at)"
                        + " VALUES (?,?,?,?,?,?,?," + dialect.now() + ")", jobId, chunkSeq, rows.size(), invalid, chunkBytes, chunkHash, actor);
                Jdbc.update(c, "UPDATE OPS_BULK_JOB SET chunk_count = chunk_count + 1, total_rows = total_rows + ?, updated_at = " + dialect.now()
                        + " WHERE id = ? AND tenant_id = ?", rows.size(), jobId, tenant);
                return new ChunkReceipt(chunkSeq, rows.size(), invalid, false);
            });
        } catch (Jdbc.LayerSqlException e) {
            // two uploads of the same sequence raced past the pre-check: the loser re-reads the stored chunk
            if (e.getCause() instanceof SQLException s && dialect.isUniqueViolation(s)) {
                return tx.inTransaction(c -> existingChunk(c, jobId, chunkSeq, chunkHash)).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    /** {@code OPEN → PROCESSING}: no more chunks; the worker starts on the rows. */
    public Job close(String tenant, String jobId, String actor) {
        requireTenant(tenant);
        tx.inTransaction(c -> {
            Jdbc.one(c, dialect.lockingSelect("id", "OPS_BULK_JOB", "id = ? AND tenant_id = ?"), rs -> rs.getString(1), jobId, tenant)
                    .orElseThrow(() -> notFound(jobId));
            Object[] j = jobRow(c, tenant, jobId);
            if (!JobState.OPEN.name().equals(j[0])) {
                throw new BulkException(BulkException.Kind.CONFLICT, "BULK_JOB_STATE", "bulk job " + jobId + " is " + j[0] + ", not OPEN");
            }
            int chunks = (Integer) j[3];
            Integer expected = (Integer) j[2];
            if (chunks == 0) {
                throw new BulkException(BulkException.Kind.CONFLICT, "BULK_JOB_EMPTY", "bulk job " + jobId + " has no chunks");
            }
            if (expected != null && chunks != expected) {
                throw new BulkException(BulkException.Kind.CONFLICT, "BULK_CHUNKS_MISSING",
                        "bulk job " + jobId + " has " + chunks + " of " + expected + " expected chunks");
            }
            Jdbc.update(c, "UPDATE OPS_BULK_JOB SET state = 'PROCESSING', closed_at = " + dialect.now() + ", updated_at = " + dialect.now()
                    + " WHERE id = ? AND tenant_id = ? AND state = 'OPEN'", jobId, tenant);
            audit(c, tenant, jobId, "BULK_JOB_CLOSED", Map.of("chunks", chunks, "actor", String.valueOf(actor)));
            return null;
        });
        finishIfDone(jobId); // every row may already be invalid
        return job(tenant, jobId);
    }

    /** {@code OPEN | PROCESSING → ABORTED}: rows not yet applied are cancelled; applied rows stay applied. */
    public Job abort(String tenant, String jobId, String actor) {
        requireTenant(tenant);
        tx.inTransaction(c -> {
            Jdbc.one(c, dialect.lockingSelect("id", "OPS_BULK_JOB", "id = ? AND tenant_id = ?"), rs -> rs.getString(1), jobId, tenant)
                    .orElseThrow(() -> notFound(jobId));
            Object[] j = jobRow(c, tenant, jobId);
            if (!JobState.OPEN.name().equals(j[0]) && !JobState.PROCESSING.name().equals(j[0])) {
                throw new BulkException(BulkException.Kind.CONFLICT, "BULK_JOB_STATE", "bulk job " + jobId + " is " + j[0] + " and cannot be aborted");
            }
            Jdbc.update(c, "UPDATE OPS_BULK_JOB SET state = 'ABORTED', completed_at = " + dialect.now() + ", updated_at = " + dialect.now()
                    + " WHERE id = ? AND tenant_id = ?", jobId, tenant);
            Jdbc.update(c, "UPDATE OPS_BULK_ROW SET state = 'CANCELLED', updated_at = " + dialect.now() + " WHERE job_id = ? AND state = 'PENDING'", jobId);
            audit(c, tenant, jobId, "BULK_JOB_ABORTED", Map.of("actor", String.valueOf(actor)));
            return null;
        });
        return job(tenant, jobId);
    }

    // ------------------------------------------------------------------ worker

    /** Claims and applies up to {@code limit} pending rows of processing jobs. Returns the number of rows settled. */
    public int runOnce(int limit) {
        List<Object[]> claimed = tx.inTransaction(c -> {
            List<Object[]> rows = Jdbc.query(c, dialect.skipLockedSelect("r.job_id, r.chunk_seq, r.row_seq", "OPS_BULK_ROW", "r",
                    "r.state = 'PENDING' AND r.job_id IN (SELECT j.id FROM OPS_BULK_JOB j WHERE j.state = 'PROCESSING')",
                    "r.job_id, r.chunk_seq, r.row_seq", limit), rs -> new Object[]{rs.getString(1), rs.getInt(2), rs.getInt(3)});
            for (Object[] r : rows) {
                Jdbc.update(c, "UPDATE OPS_BULK_ROW SET state = 'RUNNING', attempts = attempts + 1, claimed_by = ?, updated_at = " + dialect.now()
                        + " WHERE job_id = ? AND chunk_seq = ? AND row_seq = ? AND state = 'PENDING'", node, r[0], r[1], r[2]);
            }
            return rows;
        });
        int settled = 0;
        Set<String> jobs = new LinkedHashSet<>();
        for (Object[] r : claimed) {
            jobs.add((String) r[0]);
            if (processRow((String) r[0], (Integer) r[1], (Integer) r[2])) {
                settled++;
            }
        }
        jobs.forEach(this::finishIfDone);
        return settled;
    }

    /** Puts back to {@code PENDING} the rows this node held when it stopped (call once at start). */
    public int recoverNode() {
        return tx.inTransaction(c -> Jdbc.update(c, "UPDATE OPS_BULK_ROW SET state = 'PENDING', claimed_by = NULL, updated_at = " + dialect.now()
                + " WHERE state = 'RUNNING' AND claimed_by = ?", node));
    }

    /** Puts back to {@code PENDING} rows of any node stuck in {@code RUNNING} for longer than {@code staleMillis}. */
    public int recoverStale(long staleMillis) {
        return tx.inTransaction(c -> Jdbc.update(c, "UPDATE OPS_BULK_ROW SET state = 'PENDING', claimed_by = NULL, updated_at = " + dialect.now()
                + " WHERE state = 'RUNNING' AND updated_at < " + dialect.nowPlusMillisParam(), -staleMillis));
    }

    /** Processing jobs whose rows are all settled become COMPLETED or COMPLETED_WITH_ERRORS; returns how many. */
    public int finishSettledJobs() {
        List<String> ids = tx.inTransaction(c -> Jdbc.query(c, "SELECT j.id FROM OPS_BULK_JOB j WHERE j.state = 'PROCESSING' AND NOT EXISTS"
                + " (SELECT 1 FROM OPS_BULK_ROW r WHERE r.job_id = j.id AND r.state IN ('PENDING','RUNNING'))", rs -> rs.getString(1)));
        int n = 0;
        for (String id : ids) {
            if (finishIfDone(id)) {
                n++;
            }
        }
        return n;
    }

    private boolean processRow(String jobId, int chunkSeq, int rowSeq) {
        Object[] ctx = new Object[3]; // tenant, external id, sink: for the after-commit call
        try {
            boolean settled = tx.inTransaction(c -> {
                Optional<Object[]> row = Jdbc.one(c, dialect.lockingSelect("state, claimed_by, external_id, payload, payload_hash, attempts",
                                "OPS_BULK_ROW", "job_id = ? AND chunk_seq = ? AND row_seq = ?"),
                        rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)},
                        jobId, chunkSeq, rowSeq);
                if (row.isEmpty() || !RowState.RUNNING.name().equals(row.get()[0]) || !node.equals(row.get()[1])) {
                    return false; // recovered and re-claimed elsewhere meanwhile
                }
                Object[] job = Jdbc.one(c, "SELECT tenant_id, object_type, state, created_by FROM OPS_BULK_JOB WHERE id = ?",
                        rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, jobId).orElseThrow();
                String tenant = (String) job[0];
                String type = (String) job[1];
                String ext = (String) row.get()[2];
                if (!JobState.PROCESSING.name().equals(job[2])) {
                    settle(c, jobId, chunkSeq, rowSeq, RowState.CANCELLED, null, null, null);
                    return true;
                }
                Sink sink = sinks.get(type);
                if (sink == null) {
                    settle(c, jobId, chunkSeq, rowSeq, RowState.FAILED, "UNKNOWN_OBJECT_TYPE", "no bulk sink for object type " + type, null);
                    return true;
                }
                String hash = (String) row.get()[4];
                Optional<Object[]> key = Jdbc.one(c, dialect.lockingSelect("payload_hash, result_ref, job_id, chunk_seq, row_seq", "OPS_BULK_KEY",
                                "tenant_id = ? AND object_type = ? AND external_id = ?"),
                        rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)}, tenant, type, ext);
                if (key.isPresent()) {
                    Object[] k = key.get();
                    boolean laterInSameJob = jobId.equals(k[2])
                            && ((Integer) k[3] > chunkSeq || ((Integer) k[3] == chunkSeq && (Integer) k[4] > rowSeq));
                    if (laterInSameJob) {
                        settle(c, jobId, chunkSeq, rowSeq, RowState.SKIPPED, "SUPERSEDED", "a later row of this job for the same external id was applied", (String) k[1]);
                        return true;
                    }
                    if (hash.equals(k[0])) {
                        settle(c, jobId, chunkSeq, rowSeq, RowState.SKIPPED, "DUPLICATE", "same payload already applied", (String) k[1]);
                        return true;
                    }
                }
                String ref = sink.apply(c, tenant, (String) job[3], ext, readMap((String) row.get()[3]), key.map(k -> (String) k[1]).orElse(null));
                if (key.isPresent()) {
                    Jdbc.update(c, "UPDATE OPS_BULK_KEY SET payload_hash = ?, result_ref = ?, job_id = ?, chunk_seq = ?, row_seq = ?, updated_at = "
                                    + dialect.now() + " WHERE tenant_id = ? AND object_type = ? AND external_id = ?",
                            hash, truncate(ref, 255), jobId, chunkSeq, rowSeq, tenant, type, ext);
                } else {
                    Jdbc.update(c, "INSERT INTO OPS_BULK_KEY(tenant_id, object_type, external_id, payload_hash, result_ref, job_id, chunk_seq, row_seq, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?," + dialect.now() + ")", tenant, type, ext, hash, truncate(ref, 255), jobId, chunkSeq, rowSeq);
                }
                settle(c, jobId, chunkSeq, rowSeq, RowState.DONE, null, null, truncate(ref, 255));
                ctx[0] = tenant;
                ctx[1] = ext;
                ctx[2] = sink;
                return true;
            });
            if (ctx[2] != null) {
                try {
                    ((Sink) ctx[2]).committed((String) ctx[0], (String) ctx[1]);
                } catch (RuntimeException ignored) {
                    // the row is committed; an after-commit hook cannot undo it
                }
            }
            return settled;
        } catch (RowRejectedException e) {
            return fail(jobId, chunkSeq, rowSeq, e.code(), e.getMessage(), true);
        } catch (RuntimeException e) {
            // transient (lock conflict, a concurrent first insert of the same key, a sink outage): retried up to maxAttempts
            String code = isUniqueViolation(e) ? "KEY_RACE" : "APPLY_FAILED";
            return fail(jobId, chunkSeq, rowSeq, code, e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        }
    }

    private boolean fail(String jobId, int chunkSeq, int rowSeq, String code, String text, boolean permanent) {
        return tx.inTransaction(c -> {
            Optional<Object[]> row = Jdbc.one(c, dialect.lockingSelect("state, claimed_by, attempts", "OPS_BULK_ROW",
                    "job_id = ? AND chunk_seq = ? AND row_seq = ?"), rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3)}, jobId, chunkSeq, rowSeq);
            if (row.isEmpty() || !RowState.RUNNING.name().equals(row.get()[0]) || !node.equals(row.get()[1])) {
                return false;
            }
            if (!permanent && (Integer) row.get()[2] < limits.maxAttempts()) {
                Jdbc.update(c, "UPDATE OPS_BULK_ROW SET state = 'PENDING', claimed_by = NULL, error_code = ?, error_text = ?, updated_at = " + dialect.now()
                        + " WHERE job_id = ? AND chunk_seq = ? AND row_seq = ?", code, truncate(text, MAX_ERROR_TEXT), jobId, chunkSeq, rowSeq);
                return false;
            }
            settle(c, jobId, chunkSeq, rowSeq, RowState.FAILED, code, text, null);
            return true;
        });
    }

    private void settle(Connection c, String jobId, int chunkSeq, int rowSeq, RowState state, String code, String text, String ref) {
        Jdbc.update(c, "UPDATE OPS_BULK_ROW SET state = ?, error_code = ?, error_text = ?, result_ref = ?, claimed_by = NULL, updated_at = "
                        + dialect.now() + " WHERE job_id = ? AND chunk_seq = ? AND row_seq = ?",
                state.name(), code, truncate(text, MAX_ERROR_TEXT), ref, jobId, chunkSeq, rowSeq);
    }

    private boolean finishIfDone(String jobId) {
        return tx.inTransaction(c -> {
            Optional<String> state = Jdbc.one(c, dialect.lockingSelect("state", "OPS_BULK_JOB", "id = ?"), rs -> rs.getString(1), jobId);
            if (state.isEmpty() || !JobState.PROCESSING.name().equals(state.get())) {
                return false;
            }
            long open = Jdbc.one(c, "SELECT COUNT(*) FROM OPS_BULK_ROW WHERE job_id = ? AND state IN ('PENDING','RUNNING')", rs -> rs.getLong(1), jobId).orElse(0L);
            if (open > 0) {
                return false;
            }
            long failed = Jdbc.one(c, "SELECT COUNT(*) FROM OPS_BULK_ROW WHERE job_id = ? AND state = 'FAILED'", rs -> rs.getLong(1), jobId).orElse(0L);
            Jdbc.update(c, "UPDATE OPS_BULK_JOB SET state = ?, completed_at = " + dialect.now() + ", updated_at = " + dialect.now()
                    + " WHERE id = ? AND state = 'PROCESSING'", failed > 0 ? JobState.COMPLETED_WITH_ERRORS.name() : JobState.COMPLETED.name(), jobId);
            return true;
        });
    }

    // ------------------------------------------------------------------ queries (always tenant-scoped)

    public Job job(String tenant, String jobId) {
        requireTenant(tenant);
        return tx.inTransaction(c -> {
            Job j = Jdbc.one(c, "SELECT id, tenant_id, object_type, state, expected_chunks, chunk_count, total_rows, created_by, created_at,"
                            + " closed_at, completed_at FROM OPS_BULK_JOB WHERE id = ? AND tenant_id = ?",
                    rs -> new Job(rs.getString(1), rs.getString(2), rs.getString(3), JobState.valueOf(rs.getString(4)),
                            rs.getObject(5) == null ? null : rs.getInt(5), rs.getInt(6), rs.getLong(7), Map.of(), rs.getString(8),
                            Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "closed_at"), Jdbc.instant(rs, "completed_at")),
                    jobId, tenant).orElseThrow(() -> notFound(jobId));
            Map<RowState, Long> counts = new LinkedHashMap<>();
            for (RowState s : RowState.values()) {
                counts.put(s, 0L);
            }
            Jdbc.query(c, "SELECT state, COUNT(*) FROM OPS_BULK_ROW WHERE job_id = ? GROUP BY state",
                    rs -> counts.put(RowState.valueOf(rs.getString(1)), rs.getLong(2)), jobId);
            return new Job(j.id(), j.tenantId(), j.objectType(), j.state(), j.expectedChunks(), j.chunks(), j.totalRows(), Map.copyOf(counts),
                    j.createdBy(), j.createdAt(), j.closedAt(), j.completedAt());
        });
    }

    /** Jobs of the tenant for the given object types (those the caller may see), newest first, optionally by state. */
    public Slice<Job> jobs(String tenant, Set<String> objectTypes, JobState stateOrNull, int offset, int limit) {
        requireTenant(tenant);
        if (objectTypes == null || objectTypes.isEmpty()) {
            return new Slice<>(List.of(), 0);
        }
        List<String> types = List.copyOf(objectTypes);
        String where = "tenant_id = ? AND object_type IN (" + String.join(",", java.util.Collections.nCopies(types.size(), "?")) + ")"
                + (stateOrNull == null ? "" : " AND state = ?");
        List<Object> p = new ArrayList<>();
        p.add(tenant);
        p.addAll(types);
        if (stateOrNull != null) {
            p.add(stateOrNull.name());
        }
        Object[] params = p.toArray();
        List<String> ids = new ArrayList<>();
        long total = tx.inTransaction(c -> {
            ids.addAll(Jdbc.query(c, "SELECT id FROM OPS_BULK_JOB WHERE " + where + " ORDER BY created_at DESC, id" + window(offset, limit),
                    rs -> rs.getString(1), params));
            return Jdbc.one(c, "SELECT COUNT(*) FROM OPS_BULK_JOB WHERE " + where, rs -> rs.getLong(1), params).orElse(0L);
        });
        return new Slice<>(ids.stream().map(id -> job(tenant, id)).toList(), total);
    }

    /** Per-row status of a job of the tenant, in upload order, optionally by state. */
    public Slice<RowStatus> rows(String tenant, String jobId, RowState stateOrNull, int offset, int limit) {
        requireTenant(tenant);
        return tx.inTransaction(c -> {
            jobRow(c, tenant, jobId);
            String where = "job_id = ?" + (stateOrNull == null ? "" : " AND state = ?");
            Object[] params = stateOrNull == null ? new Object[]{jobId} : new Object[]{jobId, stateOrNull.name()};
            List<RowStatus> items = Jdbc.query(c, "SELECT chunk_seq, row_seq, external_id, state, attempts, error_code, error_text, result_ref"
                            + " FROM OPS_BULK_ROW WHERE " + where + " ORDER BY chunk_seq, row_seq" + window(offset, limit),
                    rs -> new RowStatus(rs.getInt(1), rs.getInt(2), rs.getString(3), RowState.valueOf(rs.getString(4)), rs.getInt(5),
                            rs.getString(6), rs.getString(7), rs.getString(8)), params);
            long total = Jdbc.one(c, "SELECT COUNT(*) FROM OPS_BULK_ROW WHERE " + where, rs -> rs.getLong(1), params).orElse(0L);
            return new Slice<>(items, total);
        });
    }

    /** The error report: failed rows with code and text. */
    public Slice<RowStatus> errors(String tenant, String jobId, int offset, int limit) {
        return rows(tenant, jobId, RowState.FAILED, offset, limit);
    }

    // ------------------------------------------------------------------ helpers

    /** {state, objectType, expectedChunks, chunkCount, totalRows} of a job of the tenant, or NOT_FOUND. */
    private Object[] jobRow(Connection c, String tenant, String jobId) {
        return Jdbc.one(c, "SELECT state, object_type, expected_chunks, chunk_count, total_rows FROM OPS_BULK_JOB WHERE id = ? AND tenant_id = ?",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getObject(3) == null ? null : rs.getInt(3), rs.getInt(4), rs.getLong(5)},
                jobId, tenant).orElseThrow(() -> notFound(jobId));
    }

    private Optional<ChunkReceipt> existingChunk(Connection c, String jobId, int chunkSeq, String chunkHash) {
        Optional<Object[]> stored = Jdbc.one(c, "SELECT chunk_hash, row_count, invalid_count FROM OPS_BULK_CHUNK WHERE job_id = ? AND chunk_seq = ?",
                rs -> new Object[]{rs.getString(1), rs.getInt(2), rs.getInt(3)}, jobId, chunkSeq);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        if (!chunkHash.equals(stored.get()[0])) {
            throw new BulkException(BulkException.Kind.CONFLICT, "CHUNK_CONFLICT",
                    "chunk " + chunkSeq + " of bulk job " + jobId + " was already uploaded with different content");
        }
        return Optional.of(new ChunkReceipt(chunkSeq, (Integer) stored.get()[1], (Integer) stored.get()[2], true));
    }

    private void checkAccepting(Object[] job, String jobId, int newRows) {
        if (!JobState.OPEN.name().equals(job[0])) {
            throw new BulkException(BulkException.Kind.CONFLICT, "BULK_JOB_STATE", "bulk job " + jobId + " is " + job[0] + ", not OPEN");
        }
        Integer expected = (Integer) job[2];
        int chunks = (Integer) job[3];
        if (chunks >= (expected == null ? limits.maxChunksPerJob() : expected)) {
            throw new BulkException(BulkException.Kind.CONFLICT, "BULK_CHUNKS_COMPLETE", "bulk job " + jobId + " already has all its chunks");
        }
        if ((Long) job[4] + newRows > limits.maxRowsPerJob()) {
            throw new BulkException(BulkException.Kind.LIMIT, "BULK_LIMIT", "a bulk job holds at most " + limits.maxRowsPerJob() + " rows");
        }
    }

    /** Why a row is invalid, or {@code null}. */
    private static String validate(RowInput in) {
        if (in == null) {
            return "row is null";
        }
        String ext = in.externalId();
        if (ext == null || ext.isBlank()) {
            return "externalId is required";
        }
        if (ext.length() > MAX_EXTERNAL_ID) {
            return "externalId is longer than " + MAX_EXTERNAL_ID + " characters";
        }
        for (int i = 0; i < ext.length(); i++) {
            if (Character.isISOControl(ext.charAt(i))) {
                return "externalId contains control characters";
            }
        }
        if (in.data() == null) {
            return "data is required";
        }
        return null;
    }

    private String window(int offset, int limit) {
        int o = Math.max(0, offset);
        int l = Math.min(Math.max(1, limit), 1000);
        return switch (dialect) {
            case ORACLE, SQLSERVER -> " OFFSET " + o + " ROWS FETCH NEXT " + l + " ROWS ONLY";
            default -> " LIMIT " + l + " OFFSET " + o;
        };
    }

    private void audit(Connection c, String tenant, String jobId, String type, Map<String, ?> payload) {
        if (audit != null) {
            audit.append(c, tenant, "bulk:" + jobId, type, payload);
        }
    }

    private boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && dialect.isUniqueViolation(s)) {
                return true;
            }
        }
        return false;
    }

    private static void requireTenant(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            throw new BulkException(BulkException.Kind.INVALID, "TENANT_REQUIRED", "bulk ingest needs the caller's tenant");
        }
    }

    private static BulkException notFound(String jobId) {
        return new BulkException(BulkException.Kind.NOT_FOUND, "NOT_FOUND", "bulk job " + jobId + " not found");
    }

    static String canonical(Map<String, Object> data) {
        try {
            return CANONICAL.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new BulkException(BulkException.Kind.INVALID, "INVALID_ROW", "row data is not JSON-serialisable: " + e.getOriginalMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(String json) {
        try {
            return CANONICAL.readValue(json, LinkedHashMap.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored bulk row is not JSON", e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
