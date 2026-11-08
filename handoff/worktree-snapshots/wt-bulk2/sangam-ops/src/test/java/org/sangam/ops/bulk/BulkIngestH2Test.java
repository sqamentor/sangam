// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.bulk;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.core.quota.QuotaLimit;
import org.sangam.core.quota.TenantQuotaExceededException;
import org.sangam.core.quota.TenantQuotas;
import org.sangam.ops.OpsSchema;
import org.sangam.ops.bulk.BulkIngest.BulkException;
import org.sangam.ops.bulk.BulkIngest.JobState;
import org.sangam.ops.bulk.BulkIngest.RowInput;
import org.sangam.ops.bulk.BulkIngest.RowState;
import org.sangam.ops.vars.ScopedVariables;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** SL-44 bulk ingest on H2 (MODE=PostgreSQL): lifecycle, idempotency, limits, quota, tenant scope, restart resume. */
class BulkIngestH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TYPE = "test.item";

    Transactions tx;
    AuditChain audit = new AuditChain(D);
    /** Sink calls per tenant|externalId that committed (a rolled-back call is not counted: it is in the transaction). */
    final Map<String, AtomicInteger> applied = new ConcurrentHashMap<>();
    /** Fails the row's first {@code n} attempts transiently while the predicate matches. */
    volatile Predicate<String> transientFor = x -> false;
    final Map<String, AtomicInteger> transientSeen = new ConcurrentHashMap<>();
    volatile int transientTimes = 0;
    /** Throws an Error (a dying process) when applying this external id. */
    volatile String crashOn;

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            OpsSchema.create(c, D);
            c.createStatement().execute("CREATE TABLE BULK_TEST_TARGET (tenant_id VARCHAR(64), ext VARCHAR(255), val VARCHAR(1000),"
                    + " writes INT, PRIMARY KEY (tenant_id, ext))");
        }
        tx = Transactions.of(ds);
    }

    static final class Crash extends Error {
        Crash() {
            super("simulated process death");
        }
    }

    BulkIngest.Sink sink() {
        return new BulkIngest.Sink() {
            public String objectType() {
                return TYPE;
            }

            public String permission() {
                return "test.load";
            }

            public String apply(Connection c, String tenant, String actor, String ext, Map<String, Object> data, String existingRef) {
                if (ext.equals(crashOn)) {
                    throw new Crash();
                }
                if (transientFor.test(ext) && transientSeen.computeIfAbsent(ext, k -> new AtomicInteger()).incrementAndGet() <= transientTimes) {
                    throw new IllegalStateException("downstream unavailable");
                }
                if ("reject".equals(data.get("v"))) {
                    throw new BulkIngest.RowRejectedException("BAD_VALUE", "v must not be reject");
                }
                if (existingRef == null) {
                    Jdbc.update(c, "INSERT INTO BULK_TEST_TARGET VALUES (?,?,?,1)", tenant, ext, String.valueOf(data.get("v")));
                } else {
                    assertEquals("item:" + tenant + ":" + ext, existingRef, "an update gets the reference of the first apply");
                    Jdbc.update(c, "UPDATE BULK_TEST_TARGET SET val = ?, writes = writes + 1 WHERE tenant_id = ? AND ext = ?",
                            String.valueOf(data.get("v")), tenant, ext);
                }
                return "item:" + tenant + ":" + ext;
            }

            public void committed(String tenant, String ext) {
                applied.computeIfAbsent(tenant + "|" + ext, k -> new AtomicInteger()).incrementAndGet();
            }
        };
    }

    BulkIngest ingest(String node, BulkIngest.Limits limits) {
        return new BulkIngest(D, tx, audit, limits, node).register(sink());
    }

    BulkIngest ingest() {
        return ingest("node-1", null);
    }

    static List<RowInput> rows(int from, int to, String value) {
        List<RowInput> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            out.add(new RowInput("ext-" + i, Map.of("v", value + i)));
        }
        return out;
    }

    static RowInput row(String ext, String v) {
        return new RowInput(ext, Map.of("v", v));
    }

    String target(String tenant, String ext) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT val FROM BULK_TEST_TARGET WHERE tenant_id = ? AND ext = ?", rs -> rs.getString(1), tenant, ext)).orElse(null);
    }

    int writes(String tenant, String ext) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT writes FROM BULK_TEST_TARGET WHERE tenant_id = ? AND ext = ?", rs -> rs.getInt(1), tenant, ext)).orElse(0);
    }

    static void drain(BulkIngest b) {
        new BulkIngestWorker(b, 7, 1000, 60_000).tick();
    }

    @Test
    void chunkedJobIsAppliedAsynchronouslyAndCompletes() {
        BulkIngest b = ingest();
        BulkIngest.Job job = b.create("t1", TYPE, 2, "alice");
        assertEquals(JobState.OPEN, job.state());
        assertEquals(new BulkIngest.ChunkReceipt(0, 10, 0, false), b.upload("t1", job.id(), 0, rows(0, 10, "a"), "alice"));
        assertEquals(new BulkIngest.ChunkReceipt(1, 5, 0, false), b.upload("t1", job.id(), 1, rows(10, 15, "a"), "alice"));
        assertEquals(0, b.runOnce(100), "an OPEN job is not processed: rows wait for close");
        assertEquals(JobState.PROCESSING, b.close("t1", job.id(), "alice").state());
        drain(b);

        BulkIngest.Job done = b.job("t1", job.id());
        assertEquals(JobState.COMPLETED, done.state());
        assertEquals(15L, done.totalRows());
        assertEquals(15L, done.rows().get(RowState.DONE));
        assertNotNull(done.completedAt());
        assertEquals("a7", target("t1", "ext-7"));
        BulkIngest.Slice<BulkIngest.RowStatus> page = b.rows("t1", job.id(), null, 10, 3);
        assertEquals(15, page.total());
        assertEquals(List.of(1, 1, 1), page.items().stream().map(BulkIngest.RowStatus::chunkSeq).toList());
        assertEquals("item:t1:ext-10", page.items().get(0).resultRef());
        assertEquals(0, b.errors("t1", job.id(), 0, 50).total());
    }

    @Test
    void perRowIdempotencyAcrossJobsSkipsUnchangedAndUpdatesChanged() {
        BulkIngest b = ingest();
        String j1 = b.create("t1", TYPE, null, "alice").id();
        b.upload("t1", j1, 0, rows(0, 4, "a"), "alice");
        b.close("t1", j1, "alice");
        drain(b);

        // the same four rows again, plus one changed and one new
        String j2 = b.create("t1", TYPE, null, "alice").id();
        List<RowInput> again = new ArrayList<>(rows(0, 4, "a"));
        again.set(2, row("ext-2", "changed"));
        again.add(row("ext-9", "new"));
        b.upload("t1", j2, 0, again, "alice");
        b.close("t1", j2, "alice");
        drain(b);

        BulkIngest.Job job = b.job("t1", j2);
        assertEquals(JobState.COMPLETED, job.state());
        assertEquals(3L, job.rows().get(RowState.SKIPPED));
        assertEquals(2L, job.rows().get(RowState.DONE));
        assertEquals(List.of("DUPLICATE"), b.rows("t1", j2, RowState.SKIPPED, 0, 50).items().stream().map(BulkIngest.RowStatus::errorCode).distinct().toList());
        assertEquals(1, writes("t1", "ext-0"), "an unchanged row is never applied twice");
        assertEquals(1, applied.get("t1|ext-0").get());
        assertEquals(2, writes("t1", "ext-2"), "a changed row updates the record it created");
        assertEquals("changed", target("t1", "ext-2"));
        assertEquals("item:t1:ext-0", b.rows("t1", j2, RowState.SKIPPED, 0, 1).items().get(0).resultRef(), "a skipped row reports the existing record");
    }

    @Test
    void laterRowOfTheSameJobWinsEvenWhenAnEarlierOneIsRetriedAfterIt() {
        BulkIngest b = ingest();
        transientFor = "dup"::equals;
        transientTimes = 1; // the first row for "dup" fails once and is retried after the second
        String j = b.create("t1", TYPE, null, "alice").id();
        b.upload("t1", j, 0, List.of(row("dup", "first"), row("dup", "second")), "alice");
        b.close("t1", j, "alice");
        drain(b);

        assertEquals("second", target("t1", "dup"));
        List<BulkIngest.RowStatus> rs = b.rows("t1", j, null, 0, 10).items();
        assertEquals(RowState.SKIPPED, rs.get(0).state());
        assertEquals("SUPERSEDED", rs.get(0).errorCode());
        assertEquals(2, rs.get(0).attempts());
        assertEquals(RowState.DONE, rs.get(1).state());
    }

    @Test
    void chunkUploadIsIdempotentPerSequenceAndRejectsDifferentContent() {
        BulkIngest b = ingest();
        String j = b.create("t1", TYPE, null, "alice").id();
        b.upload("t1", j, 0, rows(0, 3, "a"), "alice");
        BulkIngest.ChunkReceipt replay = b.upload("t1", j, 0, rows(0, 3, "a"), "alice");
        assertTrue(replay.replayed());
        assertEquals(3L, b.job("t1", j).totalRows(), "a replayed chunk stores nothing twice");
        assertEquals(1, b.job("t1", j).chunks());
        BulkException e = assertThrows(BulkException.class, () -> b.upload("t1", j, 0, rows(0, 3, "b"), "alice"));
        assertEquals(BulkException.Kind.CONFLICT, e.kind());
        assertEquals("CHUNK_CONFLICT", e.code());
    }

    @Test
    void invalidAndRejectedRowsAreReportedAndNotRetried() {
        BulkIngest b = ingest();
        String j = b.create("t1", TYPE, null, "alice").id();
        List<RowInput> in = new ArrayList<>();
        in.add(row("ok-1", "x"));
        in.add(new RowInput(" ", Map.of("v", "x")));
        in.add(new RowInput("no-data", null));
        in.add(new RowInput("x".repeat(BulkIngest.MAX_EXTERNAL_ID + 1), Map.of("v", "x")));
        in.add(row("bad", "reject"));
        BulkIngest.ChunkReceipt r = b.upload("t1", j, 0, in, "alice");
        assertEquals(3, r.invalidRows());
        b.close("t1", j, "alice");
        drain(b);

        BulkIngest.Job job = b.job("t1", j);
        assertEquals(JobState.COMPLETED_WITH_ERRORS, job.state());
        assertEquals(1L, job.rows().get(RowState.DONE));
        assertEquals(4L, job.rows().get(RowState.FAILED));
        List<BulkIngest.RowStatus> errors = b.errors("t1", j, 0, 50).items();
        assertEquals(List.of("INVALID_ROW", "INVALID_ROW", "INVALID_ROW", "BAD_VALUE"), errors.stream().map(BulkIngest.RowStatus::errorCode).toList());
        assertEquals("externalId is required", errors.get(0).errorText());
        assertEquals("data is required", errors.get(1).errorText());
        assertEquals(0, errors.get(0).attempts(), "an invalid row is never handed to the sink");
        assertEquals(1, errors.get(3).attempts(), "a rejected row is not retried");
        assertNull(target("t1", "bad"));
    }

    @Test
    void transientFailuresAreRetriedUpToTheAttemptLimit() {
        BulkIngest b = ingest("node-1", new BulkIngest.Limits(100, 1 << 20, 10, 1000, 5, 3));
        transientFor = ext -> ext.startsWith("flaky") || ext.startsWith("dead");
        transientTimes = 2;
        String j = b.create("t1", TYPE, null, "alice").id();
        b.upload("t1", j, 0, List.of(row("flaky", "v")), "alice");
        b.close("t1", j, "alice");
        drain(b); // attempt 1 fails and is re-queued
        drain(b); // attempt 2 fails and is re-queued
        drain(b); // attempt 3 succeeds
        assertEquals(RowState.DONE, b.rows("t1", j, null, 0, 1).items().get(0).state());
        assertEquals(3, b.rows("t1", j, null, 0, 1).items().get(0).attempts());

        transientTimes = 99;
        String k = b.create("t1", TYPE, null, "alice").id();
        b.upload("t1", k, 0, List.of(row("dead", "v")), "alice");
        b.close("t1", k, "alice");
        for (int i = 0; i < 5; i++) {
            drain(b);
        }
        BulkIngest.RowStatus s = b.rows("t1", k, null, 0, 1).items().get(0);
        assertEquals(RowState.FAILED, s.state());
        assertEquals("APPLY_FAILED", s.errorCode());
        assertEquals(3, s.attempts());
        assertTrue(s.errorText().contains("downstream unavailable"));
        assertEquals(JobState.COMPLETED_WITH_ERRORS, b.job("t1", k).state());
    }

    @Test
    void everyJobOperationIsScopedToTheCallerTenant() {
        BulkIngest b = ingest();
        String j = b.create("t1", TYPE, null, "alice").id();
        b.upload("t1", j, 0, rows(0, 2, "a"), "alice");
        for (Runnable r : List.<Runnable>of(() -> b.job("t2", j), () -> b.upload("t2", j, 1, rows(5, 6, "x"), "eve"),
                () -> b.close("t2", j, "eve"), () -> b.abort("t2", j, "eve"), () -> b.rows("t2", j, null, 0, 10),
                () -> b.errors("t2", j, 0, 10))) {
            BulkException e = assertThrows(BulkException.class, r::run);
            assertEquals(BulkException.Kind.NOT_FOUND, e.kind());
        }
        assertEquals(0, b.jobs("t2", Set.of(TYPE), null, 0, 50).total());
        assertEquals(1, b.jobs("t1", Set.of(TYPE), null, 0, 50).total());
        assertEquals(0, b.jobs("t1", Set.of("other.type"), null, 0, 50).total(), "only the object types the caller may see");
        assertThrows(BulkException.class, () -> b.create(" ", TYPE, null, "x"));

        // the same external id in two tenants is two records with two keys
        String j2 = b.create("t2", TYPE, null, "bob").id();
        b.upload("t2", j2, 0, rows(0, 2, "b"), "bob");
        b.close("t1", j, "alice");
        b.close("t2", j2, "bob");
        drain(b);
        assertEquals("a0", target("t1", "ext-0"));
        assertEquals("b0", target("t2", "ext-0"));
        assertEquals(2L, b.job("t2", j2).rows().get(RowState.DONE));
    }

    @Test
    void limitsAreEnforcedBeforeAnythingIsStored() {
        BulkIngest b = ingest("node-1", new BulkIngest.Limits(3, 200, 2, 5, 1, 3));
        String j = b.create("t1", TYPE, null, "alice").id();
        assertEquals(BulkException.Kind.LIMIT, assertThrows(BulkException.class, () -> b.upload("t1", j, 0, rows(0, 4, "a"), "alice")).kind());
        assertEquals(BulkException.Kind.LIMIT, assertThrows(BulkException.class,
                () -> b.upload("t1", j, 0, List.of(row("big", "x".repeat(300))), "alice")).kind(), "bytes per chunk");
        assertEquals(BulkException.Kind.LIMIT, assertThrows(BulkException.class, () -> b.upload("t1", j, 2, rows(0, 1, "a"), "alice")).kind(), "chunk sequence");
        assertEquals(BulkException.Kind.INVALID, assertThrows(BulkException.class, () -> b.upload("t1", j, 0, List.of(), "alice")).kind());
        b.upload("t1", j, 0, rows(0, 3, "a"), "alice");
        assertEquals(BulkException.Kind.LIMIT, assertThrows(BulkException.class, () -> b.upload("t1", j, 1, rows(3, 6, "a"), "alice")).kind(), "rows per job");
        assertEquals(3L, b.job("t1", j).totalRows());
        assertEquals("BULK_ACTIVE_JOBS", assertThrows(BulkException.class, () -> b.create("t1", TYPE, null, "alice")).code());
        assertEquals("UNKNOWN_OBJECT_TYPE", assertThrows(BulkException.class, () -> b.create("t2", "nope", null, "alice")).code());

        BulkIngest c = ingest("node-1", null);
        String e = c.create("t3", TYPE, 2, "alice").id();
        assertEquals("BULK_JOB_EMPTY", assertThrows(BulkException.class, () -> c.close("t3", e, "alice")).code());
        c.upload("t3", e, 0, rows(0, 1, "a"), "alice");
        assertEquals("BULK_CHUNKS_MISSING", assertThrows(BulkException.class, () -> c.close("t3", e, "alice")).code());
        c.upload("t3", e, 1, rows(1, 2, "a"), "alice");
        assertEquals("BULK_CHUNKS_COMPLETE", assertThrows(BulkException.class, () -> c.upload("t3", e, 2, rows(2, 3, "a"), "alice")).code());
        assertEquals(JobState.PROCESSING, c.close("t3", e, "alice").state());
        assertEquals("BULK_JOB_STATE", assertThrows(BulkException.class, () -> c.upload("t3", e, 5, rows(5, 6, "z"), "alice")).code());
    }

    /** Test double of the SL-43 port: a batch-size limit per tenant, recording the questions. */
    static final class SizeQuota implements TenantQuotas {
        final Map<String, Long> max = new ConcurrentHashMap<>();
        final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();

        public void acquire(String tenantId, QuotaLimit limit, long permits) {
        }

        public void checkSize(String tenantId, QuotaLimit limit, long amount) {
            asked.add(tenantId + ":" + limit + ":" + amount);
            long m = limit(tenantId, limit);
            if (!TenantQuotas.within(m, amount)) {
                throw new TenantQuotaExceededException(tenantId, limit, m, amount, null);
            }
        }

        public long limit(String tenantId, QuotaLimit limit) {
            return limit == QuotaLimit.BATCH_SIZE ? max.getOrDefault(tenantId, QuotaLimit.UNLIMITED) : QuotaLimit.UNLIMITED;
        }
    }

    @Test
    void tenantBatchSizeQuotaIsCheckedPerChunkBeforeAnythingIsStored() {
        SizeQuota quota = new SizeQuota();
        quota.max.put("t1", 2L);
        TenantQuotas.install(quota);
        try {
            BulkIngest b = ingest();
            String j = b.create("t1", TYPE, null, "alice").id();
            TenantQuotaExceededException e = assertThrows(TenantQuotaExceededException.class, () -> b.upload("t1", j, 0, rows(0, 3, "a"), "alice"));
            assertEquals(QuotaLimit.BATCH_SIZE, e.limit());
            assertEquals(413, e.httpStatus());
            assertEquals(0L, b.job("t1", j).totalRows(), "a refused chunk stores nothing");
            assertEquals(List.of("t1:BATCH_SIZE:3"), quota.asked);
            b.upload("t1", j, 0, rows(0, 2, "a"), "alice");
            b.upload("t1", j, 0, rows(0, 2, "a"), "alice"); // replay: answered before the quota is asked
            assertEquals(List.of("t1:BATCH_SIZE:3", "t1:BATCH_SIZE:2"), quota.asked);
            assertEquals(2L, b.job("t1", j).totalRows());
            String k = b.create("t2", TYPE, null, "bob").id();
            assertEquals(3, b.upload("t2", k, 0, rows(0, 3, "a"), "bob").rows(), "another tenant has its own limit");
        } finally {
            TenantQuotas.install(null);
        }
    }

    @Test
    void aRestartedNodeResumesTheRowsItHeldAndAppliesEachExactlyOnce() {
        BulkIngest first = ingest("node-1", null);
        String j = first.create("t1", TYPE, null, "alice").id();
        first.upload("t1", j, 0, rows(0, 20, "a"), "alice");
        first.close("t1", j, "alice");
        crashOn = "ext-12";
        assertThrows(Crash.class, () -> first.runOnce(15), "the process dies in the middle of a claimed batch");
        BulkIngest.Job mid = first.job("t1", j);
        assertEquals(12L, mid.rows().get(RowState.DONE));
        assertEquals(3L, mid.rows().get(RowState.RUNNING), "ext-12..14 were claimed by node-1 when it died");

        crashOn = null;
        BulkIngest restarted = ingest("node-1", null);
        BulkIngestWorker worker = new BulkIngestWorker(restarted, 5, 60_000, 3_600_000);
        worker.start(); // gives back the rows node-1 held
        try {
            assertEquals(0L, restarted.job("t1", j).rows().get(RowState.RUNNING));
            worker.tick();
        } finally {
            worker.stop();
        }
        BulkIngest.Job done = restarted.job("t1", j);
        assertEquals(JobState.COMPLETED, done.state());
        assertEquals(20L, done.rows().get(RowState.DONE));
        for (int i = 0; i < 20; i++) {
            assertEquals(1, writes("t1", "ext-" + i), "ext-" + i + " applied exactly once");
        }
    }

    @Test
    void rowsOfAVanishedNodeAreRecoveredByAnotherAfterTheStaleTime() throws Exception {
        BulkIngest gone = ingest("node-2", null);
        String j = gone.create("t1", TYPE, null, "alice").id();
        gone.upload("t1", j, 0, rows(0, 4, "a"), "alice");
        gone.close("t1", j, "alice");
        crashOn = "ext-0";
        assertThrows(Crash.class, () -> gone.runOnce(4));
        crashOn = null;

        BulkIngest other = ingest("node-3", null);
        assertEquals(0, other.recoverNode(), "node-3 never held a row");
        assertEquals(0, other.recoverStale(3_600_000), "not stale yet");
        assertEquals(0, other.runOnce(10), "rows held by node-2 are not taken over early");
        Thread.sleep(30);
        assertEquals(4, other.recoverStale(10));
        drain(other);
        assertEquals(JobState.COMPLETED, other.job("t1", j).state());
        assertEquals(1, writes("t1", "ext-3"));
    }

    @Test
    void abortCancelsRowsNotYetApplied() {
        BulkIngest b = ingest();
        String j = b.create("t1", TYPE, null, "alice").id();
        b.upload("t1", j, 0, rows(0, 10, "a"), "alice");
        b.close("t1", j, "alice");
        b.runOnce(4);
        BulkIngest.Job aborted = b.abort("t1", j, "alice");
        assertEquals(JobState.ABORTED, aborted.state());
        assertEquals(4L, aborted.rows().get(RowState.DONE));
        assertEquals(6L, aborted.rows().get(RowState.CANCELLED));
        assertEquals(0, b.runOnce(10));
        assertEquals("BULK_JOB_STATE", assertThrows(BulkException.class, () -> b.abort("t1", j, "alice")).code());
        assertEquals(1, b.jobs("t1", Set.of(TYPE), JobState.ABORTED, 0, 10).total());
    }

    @Test
    void variableSinkLoadsTenantVariablesInTheRowTransaction() {
        ScopedVariables vars = new ScopedVariables(D, tx, audit, 60_000);
        BulkIngest b = new BulkIngest(D, tx, audit, null, "node-1").register(BulkSinks.variables(vars, "ops.tenant.admin"));
        assertEquals(Set.of(BulkSinks.VARIABLE), b.objectTypes());
        assertNull(vars.get("t1", "limit"));
        String j = b.create("t1", BulkSinks.VARIABLE, null, "alice").id();
        b.upload("t1", j, 0, List.of(new RowInput("limit", Map.of("value", 5, "reason", "initial load")),
                new RowInput("apiToken", Map.of("value", "x")), new RowInput("empty", Map.of())), "alice");
        b.close("t1", j, "alice");
        drain(b);
        assertEquals(5, vars.get("t1", "limit"), "the after-commit hook evicted the cached scope");
        assertEquals(List.of("INVALID_VARIABLE", "VALUE_REQUIRED"), b.errors("t1", j, 0, 10).items().stream().map(BulkIngest.RowStatus::errorCode).toList());
        assertEquals("alice", vars.entries(ScopedVariables.tenantScope("t1")).get(0).updatedBy());
        assertNull(vars.get("t2", "limit"));
    }

    @Test
    void sinkRegistrationIsValidated() {
        BulkIngest b = ingest();
        assertThrows(IllegalArgumentException.class, () -> b.register(sink()), "duplicate object type");
        assertThrows(IllegalArgumentException.class, () -> new BulkIngest(D, tx, audit, null, " "));
        assertThrows(IllegalArgumentException.class, () -> new BulkIngest.Limits(0, 1, 1, 1, 1, 1));
    }
}
