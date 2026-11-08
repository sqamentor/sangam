// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.bulk;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.ops.bulk.BulkIngest.JobState;
import org.sangam.ops.bulk.BulkIngest.RowInput;
import org.sangam.ops.bulk.BulkIngest.RowState;
import org.sangam.ops.vars.ScopedVariables;
import org.sangam.persistence.SangamSchema;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SL-44 on PostgreSQL 17 with the product schema from the sangam-persistence Liquibase changelog (not the test DDL):
 * two nodes share one job through SKIP LOCKED claims, duplicate external ids are applied exactly once, a node that
 * dies mid-batch resumes after restart, and jobs are tenant-scoped. Run with {@code -Dsangam.db=postgres}.
 */
class BulkIngestPostgresIT {

    static PostgreSQLContainer<?> pg;
    static PGSimpleDataSource ds;
    static Transactions tx;
    static final Dialect D = Dialect.POSTGRESQL;
    static final AuditChain AUDIT = new AuditChain(D);

    @BeforeAll
    static void start() {
        String db = System.getProperty("sangam.db", "");
        assumeTrue(db.isBlank() || "postgres".equals(db), "PostgreSQL lane only (sangam.db=" + db + ")");
        pg = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17")).withDatabaseName("sangam").withUsername("sangam")
                .withPassword("sangam").withStartupTimeout(Duration.ofMinutes(5));
        pg.start();
        ds = new PGSimpleDataSource();
        ds.setUrl(pg.getJdbcUrl());
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        SangamSchema.apply(ds);
        tx = Transactions.of(ds);
    }

    @AfterAll
    static void stop() {
        if (pg != null) {
            pg.stop();
        }
    }

    static final class Crash extends Error {
        Crash() {
            super("simulated process death");
        }
    }

    /** The variable sink, dying on one external id while {@code crashOn} is set. */
    static BulkIngest node(String id, ScopedVariables vars, String[] crashOn) {
        BulkIngest.Sink real = BulkSinks.variables(vars, "ops.tenant.admin");
        return new BulkIngest(D, tx, AUDIT, BulkIngest.Limits.DEFAULTS, id).register(new BulkIngest.Sink() {
            public String objectType() {
                return real.objectType();
            }

            public String permission() {
                return real.permission();
            }

            public String apply(Connection c, String tenant, String actor, String ext, Map<String, Object> data, String existingRef) {
                if (ext.equals(crashOn[0])) {
                    throw new Crash();
                }
                return real.apply(c, tenant, actor, ext, data, existingRef);
            }

            public void committed(String tenant, String ext) {
                real.committed(tenant, ext);
            }
        });
    }

    static long version(String tenant, String name) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT version FROM OPS_VARIABLE WHERE scope_id = ? AND name = ?",
                rs -> rs.getLong(1), ScopedVariables.tenantScope(tenant), name)).orElse(0L);
    }

    @Test
    void twoNodesShareAJobAndApplyEveryExternalIdExactlyOnce() throws Exception {
        ScopedVariables vars = new ScopedVariables(D, tx, AUDIT, 0);
        String[] noCrash = {null};
        BulkIngest a = node("pg-node-a", vars, noCrash);
        BulkIngest b = node("pg-node-b", vars, noCrash);
        String tenant = "pg-t1";
        String job = a.create(tenant, BulkSinks.VARIABLE, 4, "loader").id();
        // 4 chunks x 500 rows; ids repeat across chunks (same payload) so concurrent first applies of one key race
        int unique = 0;
        for (int chunk = 0; chunk < 4; chunk++) {
            List<RowInput> rows = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                int n = chunk * 500 + i;
                int id = n % 1500; // ids 0..499 appear twice (chunks 0 and 3)
                rows.add(new RowInput("v" + id, Map.of("value", "val-" + id)));
            }
            assertFalse(b.upload(tenant, job, chunk, rows, "loader").replayed());
        }
        unique = 1500;
        a.close(tenant, job, "loader");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> f = new ArrayList<>();
            java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);
            for (BulkIngest node : List.of(a, b)) {
                f.add(pool.submit(() -> {
                    together.await(1, TimeUnit.MINUTES);
                    BulkIngestWorker w = new BulkIngestWorker(node, 25, 1000, 300_000);
                    int total = 0;
                    long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                    while (node.job(tenant, job).state() == JobState.PROCESSING && System.nanoTime() < until) {
                        total += w.tick();
                    }
                    return total;
                }));
            }
            int settledA = f.get(0).get(4, TimeUnit.MINUTES);
            int settledB = f.get(1).get(4, TimeUnit.MINUTES);
            assertEquals(2000, settledA + settledB, "every row settled once, by one of the nodes");
            assertTrue(settledA > 0 && settledB > 0, "both nodes worked the job (a=" + settledA + ", b=" + settledB + ")");
        } finally {
            pool.shutdownNow();
        }

        BulkIngest.Job done = a.job(tenant, job);
        assertEquals(JobState.COMPLETED, done.state());
        assertEquals((long) unique, done.rows().get(RowState.DONE));
        assertEquals(500L, done.rows().get(RowState.SKIPPED));
        assertEquals(0L, done.rows().get(RowState.FAILED));
        for (int id = 0; id < unique; id++) {
            assertEquals(1L, version(tenant, "v" + id), "v" + id + " written exactly once");
        }
        assertEquals("val-7", vars.get(tenant, "v7"));
    }

    @Test
    void aNodeThatDiesMidBatchResumesAfterRestart() {
        ScopedVariables vars = new ScopedVariables(D, tx, AUDIT, 0);
        String[] crashOn = {"r-30"};
        String tenant = "pg-t2";
        BulkIngest first = node("pg-node-c", vars, crashOn);
        String job = first.create(tenant, BulkSinks.VARIABLE, null, "loader").id();
        List<RowInput> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add(new RowInput("r-" + i, Map.of("value", i)));
        }
        first.upload(tenant, job, 0, rows, "loader");
        first.close(tenant, job, "loader");
        assertThrows(Crash.class, () -> first.runOnce(50));
        assertEquals(20L, first.job(tenant, job).rows().get(RowState.RUNNING), "r-30..r-49 claimed when the node died");

        crashOn[0] = null;
        BulkIngest restarted = node("pg-node-c", vars, crashOn);
        BulkIngestWorker w = new BulkIngestWorker(restarted, 50, 60_000, 3_600_000);
        w.start();
        try {
            w.tick();
        } finally {
            w.stop();
        }
        BulkIngest.Job done = restarted.job(tenant, job);
        assertEquals(JobState.COMPLETED, done.state());
        assertEquals(60L, done.rows().get(RowState.DONE));
        for (int i = 0; i < 60; i++) {
            assertEquals(1L, version(tenant, "r-" + i), "r-" + i + " written exactly once");
        }
    }

    @Test
    void jobsAreInvisibleToOtherTenants() {
        ScopedVariables vars = new ScopedVariables(D, tx, AUDIT, 0);
        BulkIngest n = node("pg-node-d", vars, new String[]{null});
        String job = n.create("pg-t3", BulkSinks.VARIABLE, null, "loader").id();
        n.upload("pg-t3", job, 0, List.of(new RowInput("k", Map.of("value", 1))), "loader");
        BulkIngest.BulkException e = assertThrows(BulkIngest.BulkException.class, () -> n.job("pg-t4", job));
        assertEquals(BulkIngest.BulkException.Kind.NOT_FOUND, e.kind());
        assertThrows(BulkIngest.BulkException.class, () -> n.upload("pg-t4", job, 1, List.of(new RowInput("k", Map.of("value", 2))), "x"));
        assertEquals(0, n.jobs("pg-t4", java.util.Set.of(BulkSinks.VARIABLE), null, 0, 10).total());
        assertEquals(1, n.jobs("pg-t3", java.util.Set.of(BulkSinks.VARIABLE), null, 0, 10).total());
    }
}
