// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.persistence.SangamSchema;

/**
 * M-056 finish (C3-04, X.2.1.4 to X.2.1.6) on H2: bootstrap, claim with a new epoch, renew and loss, self-fencing,
 * coordinator rebalance through DRAINING, drain completion, in-flight recovery with the engine CAS, the recovery loop,
 * and the side-row orphan sweep.
 */
class PartitionLeaseManagerH2Test {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    /** The ACT_RU_JOB columns the in-flight recovery touches (Operaton's table has more; these are the ones used). */
    static final String JOB_DDL = "CREATE TABLE ACT_RU_JOB (ID_ VARCHAR(64) NOT NULL PRIMARY KEY, REV_ INTEGER NOT NULL,"
            + " EXECUTION_ID_ VARCHAR(64), LOCK_OWNER_ VARCHAR(255), LOCK_EXP_TIME_ TIMESTAMP, RETRIES_ INTEGER NOT NULL)";

    /** A database with the product changelog applied: the tables the executor runs on. */
    private static JdbcDataSource h2() throws Exception {
        JdbcDataSource ds = empty();
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            Jdbc.update(c, JOB_DDL); // without an engine: the job columns the in-flight recovery of a claim reads
        }
        return ds;
    }

    /** An empty database, for a test that builds an engine on it (the engine applies the product changelog). */
    private static JdbcDataSource empty() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:c304" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return ds;
    }

    private PartitionLeaseManager node(JdbcDataSource ds, String name, int partitions, int maxRecoveries,
                                       PartitionLeaseManager.Listener l) {
        return new PartitionLeaseManager(ds, Dialect.POSTGRESQL, new PartitionLeaseManager.Config("c1", name,
                UUID.randomUUID().toString(), partitions, 64, Duration.ofSeconds(15), Duration.ofSeconds(3),
                Duration.ofSeconds(30), 2, maxRecoveries), Instant::now, nanos::get, l);
    }

    private static <T> T sql(JdbcDataSource ds, java.util.function.Function<Connection, T> f) throws Exception {
        try (Connection c = ds.getConnection()) {
            return f.apply(c);
        }
    }

    private static long count(JdbcDataSource ds, String q, Object... p) throws Exception {
        return sql(ds, c -> Jdbc.one(c, q, rs -> rs.getLong(1), p).orElse(0L));
    }

    @Test
    void slotsAreSplitIntoContiguousBalancedRanges() {
        assertEquals(1, PartitionLeaseManager.partitionOfSlot(0, 64, 4));
        assertEquals(1, PartitionLeaseManager.partitionOfSlot(15, 64, 4));
        assertEquals(2, PartitionLeaseManager.partitionOfSlot(16, 64, 4));
        assertEquals(4, PartitionLeaseManager.partitionOfSlot(63, 64, 4));
        assertThrows(IllegalArgumentException.class, () -> new PartitionLeaseManager.Config("c", "n", "i", 65, 64,
                Duration.ofSeconds(15), Duration.ofSeconds(3), Duration.ofSeconds(30), 10, 3));
        assertThrows(IllegalArgumentException.class, () -> new PartitionLeaseManager.Config("c", "n", "i", 4, 64,
                Duration.ofSeconds(1), Duration.ZERO, Duration.ofSeconds(30), 10, 3));
    }

    @Test
    void firstNodeBootstrapsAndClaimsEverythingWithANewEpoch() throws Exception {
        JdbcDataSource ds = h2();
        PartitionLeaseManager a = node(ds, "a", 4, 3, null);
        a.tick();
        assertEquals(64, count(ds, "SELECT COUNT(*) FROM EXEC_SLOT WHERE state = 'STABLE'"), "I-1: every slot mapped");
        assertEquals(Set.of(1, 2, 3, 4), a.ownedLive());
        assertEquals(1, a.epochOf(1));
        assertEquals(4, count(ds, "SELECT COUNT(*) FROM EXEC_PARTITION WHERE state = 'ACTIVE' AND owner_epoch = 1"));
        node(ds, "b", 9, 3, null).bootstrap();
        assertEquals(4, count(ds, "SELECT COUNT(*) FROM EXEC_PARTITION"), "an existing map is never changed");
        assertEquals(1, count(ds, "SELECT COUNT(*) FROM EXEC_MAP WHERE coordinator_node IS NOT NULL"));
    }

    @Test
    void aSecondNodeGetsItsFairShareThroughDrainAndANewEpoch() throws Exception {
        JdbcDataSource ds = h2();
        PartitionLeaseManager a = node(ds, "a", 4, 3, null);
        PartitionLeaseManager b = node(ds, "b", 4, 3, null);
        a.tick();
        b.tick(); // b registers; nothing free yet
        a.tick(); // a is coordinator: moves 2 partitions to DRAINING, then completes the drains (no busy jobs)
        a.tick();
        b.tick();
        assertEquals(2, a.ownedLive().size());
        assertEquals(2, b.ownedLive().size());
        for (int p : b.ownedLive()) {
            assertEquals(2, b.epochOf(p), "a moved partition is claimed with epoch + 1");
        }
    }

    @Test
    void everyOwnershipWriteIsAuditedInItsTransactionAndFeedsTheMetrics() throws Exception {
        JdbcDataSource ds = h2();
        List<String> audit = new CopyOnWriteArrayList<>();
        List<String> metrics = new CopyOnWriteArrayList<>();
        org.sangam.core.engine.EngineMetrics m = new org.sangam.core.engine.EngineMetrics() {
            @Override
            public void executorOwnerChanged(String reason) {
                metrics.add("owner:" + reason);
            }

            @Override
            public void executorOrphanedPartitions(long count) {
                metrics.add("orphaned:" + count);
            }
        };
        PartitionLeaseManager.Config cfg = new PartitionLeaseManager.Config("c1", "a", UUID.randomUUID().toString(), 2, 64,
                Duration.ofSeconds(15), Duration.ofSeconds(3), Duration.ofSeconds(30), 2, 3);
        org.sangam.core.engine.AuditSink sink = (conn, tenant, root, type, payload) -> {
            assertTrue(conn != null, "on the ownership write's own connection");
            audit.add(tenant + "|" + root + "|" + type + "|" + payload.get("partition") + "|" + payload.get("reason"));
        };
        PartitionLeaseManager a = new PartitionLeaseManager(ds, Dialect.POSTGRESQL, cfg, Instant::now, nanos::get,
                listenerOf(m), JobExecutorSetup.ownershipAudit(sink, "t1", cfg, cfg.incarnation()));
        a.tick();
        assertEquals(List.of("t1|executor:c1|EXECUTOR_OWNERSHIP_CHANGED|1|claimed",
                "t1|executor:c1|EXECUTOR_OWNERSHIP_CHANGED|2|claimed"), audit.stream().sorted().toList(),
                "one event per claim; the claim order starts at a node-specific offset");
        assertTrue(metrics.contains("owner:claimed"));
        assertTrue(metrics.contains("orphaned:2"), "the coordinator saw both partitions unowned before the claims");
        audit.clear();
        a.releaseAll();
        assertEquals(2, audit.stream().filter(s -> s.endsWith("|released")).count());

        PartitionLeaseManager failing = new PartitionLeaseManager(ds, Dialect.POSTGRESQL, new PartitionLeaseManager.Config(
                "c1", "b", UUID.randomUUID().toString(), 2, 64, Duration.ofSeconds(15), Duration.ofSeconds(3),
                Duration.ofSeconds(30), 2, 3), Instant::now, nanos::get, null, (conn, p, e, r, prev) -> {
                    throw new IllegalStateException("audit down");
                });
        assertThrows(IllegalStateException.class, () -> failing.claim(1));
        assertEquals(0, count(ds, "SELECT COUNT(*) FROM EXEC_PARTITION WHERE state = 'ACTIVE'"),
                "an ownership change whose audit fails is rolled back");
    }

    /** The listener JobExecutorSetup builds, reduced to the metric calls. */
    private static PartitionLeaseManager.Listener listenerOf(org.sangam.core.engine.EngineMetrics m) {
        return new PartitionLeaseManager.Listener() {
            @Override
            public void ownerChanged(int partition, long epoch, String reason) {
                m.executorOwnerChanged(reason);
            }

            @Override
            public void orphanedPartitions(long count) {
                m.executorOrphanedPartitions(count);
            }
        };
    }

    @Test
    void aLostLeaseIsDroppedAtOnceAndSelfFencingStopsAStalledNode() throws Exception {
        JdbcDataSource ds = h2();
        PartitionLeaseManager a = node(ds, "a", 2, 3, null);
        a.tick();
        sql(ds, c -> Jdbc.update(c, "UPDATE EXEC_PARTITION SET lease_expires_at = DATEADD('SECOND', -60, CURRENT_TIMESTAMP)"
                + " WHERE partition_id = 1"));
        PartitionLeaseManager b = node(ds, "b", 2, 3, null);
        assertTrue(b.claim(1) > 1, "an expired lease is taken over with a new epoch");
        a.renew();
        assertEquals(Set.of(2), a.ownedLive(), "renew of the taken partition fails: dropped");

        nanos.addAndGet(Duration.ofSeconds(13).toNanos()); // leaseTtl - safetyMargin passed without a renewal
        assertTrue(a.ownedLive().isEmpty(), "self-fencing: no longer reported as owned");
    }

    @Test
    void forcedTakeoverRecoversInFlightJobsWithTheEngineCasAndParksAPoisonJob() throws Exception {
        JdbcDataSource ds = empty();
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setExecutorPartitioning(true);
        String model = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="c304" isExecutable="true">
                    <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="w"/>
                    <serviceTask id="w" operaton:asyncBefore="true" operaton:expression="${true}"/>
                    <sequenceFlow id="f2" sourceRef="w" targetRef="e"/><endEvent id="e"/>
                  </process>
                </definitions>
                """;
        try (OperatonEngineAdapter engine = SangamEngineFactory.build(ds, s, "h2")) {
            engine.deploy("t1", "c304-" + UUID.randomUUID(), Map.of("c304.bpmn", model.getBytes(StandardCharsets.UTF_8)));
            engine.startProcess("t1", "c304", "B-" + UUID.randomUUID(), Map.of());
            String job = sql(ds, c -> Jdbc.one(c, "SELECT ID_ FROM ACT_RU_JOB", rs -> rs.getString(1)).orElseThrow());
            int retries = (int) count(ds, "SELECT RETRIES_ FROM ACT_RU_JOB WHERE ID_ = ?", job);

            List<String> loops = new CopyOnWriteArrayList<>();
            PartitionLeaseManager.Listener l = new PartitionLeaseManager.Listener() {
                @Override
                public void recoveryLoop(String jobId, String executionId, int recoveries) {
                    loops.add(jobId);
                }
            };
            PartitionLeaseManager b = node(ds, "b", 1, 1, l);
            b.bootstrap();
            for (int round = 1; round <= 2; round++) {
                long rev = count(ds, "SELECT REV_ FROM ACT_RU_JOB WHERE ID_ = ?", job);
                long stale = count(ds, "SELECT owner_epoch FROM EXEC_PARTITION WHERE partition_id = 1");
                // a dead owner holds the job under the current epoch, then its lease expires
                sql(ds, c -> Jdbc.update(c, "UPDATE ACT_RU_JOB SET LOCK_OWNER_ = 'dead', LOCK_EXP_TIME_ ="
                        + " DATEADD('HOUR', 1, CURRENT_TIMESTAMP) WHERE ID_ = ?", job));
                sql(ds, c -> Jdbc.update(c, "UPDATE EXEC_JOB_SLOT SET owner_partition = 1, owner_epoch = ? WHERE job_id = ?",
                        stale, job));
                sql(ds, c -> Jdbc.update(c, "UPDATE EXEC_PARTITION SET state = 'ACTIVE', lease_expires_at ="
                        + " DATEADD('SECOND', -60, CURRENT_TIMESTAMP) WHERE partition_id = 1"));
                long epoch = b.claim(1);
                assertEquals(stale + 1, epoch);
                assertEquals(1, b.recover(1, epoch));
                assertEquals(0, count(ds, "SELECT COUNT(*) FROM ACT_RU_JOB WHERE ID_ = ? AND LOCK_OWNER_ IS NOT NULL", job));
                assertEquals(rev + 1, count(ds, "SELECT REV_ FROM ACT_RU_JOB WHERE ID_ = ?", job),
                        "the engine's own compare-and-set: a stale execution can no longer commit");
                assertEquals(round, count(ds, "SELECT recoveries FROM EXEC_JOB_SLOT WHERE job_id = ?", job));
            }
            assertEquals(0, count(ds, "SELECT RETRIES_ FROM ACT_RU_JOB WHERE ID_ = ?", job),
                    "over maxRecoveries (1): parked");
            assertEquals(List.of(job), loops);
            assertTrue(retries > 0, "the first recovery kept the retries");
        }
    }

    @Test
    void orphanSideRowsAreSweptByTheCoordinator() throws Exception {
        JdbcDataSource ds = empty();
        try (OperatonEngineAdapter engine = SangamEngineFactory.build(ds, engineSettings(), "h2")) {
            sql(ds, c -> Jdbc.update(c, "INSERT INTO EXEC_JOB_SLOT (job_id, tenant_id, root_id, slot, recoveries,"
                    + " created_at_ms) VALUES ('gone', 't1', 'r', 3, 0, 0)"));
            PartitionLeaseManager a = node(ds, "a", 1, 3, null);
            a.tick();
            assertEquals(0, count(ds, "SELECT COUNT(*) FROM EXEC_JOB_SLOT WHERE job_id = 'gone'"));
        }
    }

    private static SangamEngineSettings engineSettings() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        return s;
    }
}
