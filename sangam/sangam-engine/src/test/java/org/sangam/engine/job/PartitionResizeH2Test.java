// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.persistence.SangamSchema;

/**
 * Decision 146 (M-056 resize, X.2.1.7) on H2: request validation and concurrency control, scale-out 8 to 12, scale-in,
 * off and on again, a busy moving slot that waits and is then recovered (retries kept, REV_ + 1), a deposed coordinator
 * that changes nothing, and the commit audit. Written, NOT_RUN; Oracle and SQL Server runs NOT_RUN (the T52 load test
 * with 100,000 jobs is a Testcontainers acceptance item, not this test).
 */
class PartitionResizeH2Test {

    /** The EXEC_RESIZE columns of W4-G changeset 0045-05-exec_resize (decision 146); keep equal to the changeset. */
    /** The ACT_RU_JOB columns the slot recovery touches (Operaton's table has more; these are the ones used). */
    static final String JOB_DDL = "CREATE TABLE ACT_RU_JOB (ID_ VARCHAR(64) NOT NULL PRIMARY KEY, REV_ INTEGER NOT NULL,"
            + " EXECUTION_ID_ VARCHAR(64), LOCK_OWNER_ VARCHAR(255), LOCK_EXP_TIME_ TIMESTAMP, RETRIES_ INTEGER NOT NULL)";

    private static final int SLOTS = 64;

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final List<String> audit = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> payloads = new CopyOnWriteArrayList<>();

    private final AuditSink sink = (c, tenant, root, type, payload) -> {
        audit.add(type + "@" + tenant + "@" + root);
        payloads.add(payload);
    };

    private JdbcDataSource h2() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rs" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds); // the product changelog: the executor tables and EXEC_RESIZE
        try (Connection c = ds.getConnection()) {
            Jdbc.update(c, JOB_DDL);
        }
        return ds;
    }

    private PartitionResize resize(JdbcDataSource ds) {
        return new PartitionResize(ds, Dialect.POSTGRESQL, "c1", SLOTS, sink, "system", 7);
    }

    private PartitionLeaseManager node(JdbcDataSource ds, String name, int partitions, PartitionResize r) {
        return new PartitionLeaseManager(ds, Dialect.POSTGRESQL, new PartitionLeaseManager.Config("c1", name,
                UUID.randomUUID().toString(), partitions, SLOTS, Duration.ofSeconds(15), Duration.ofSeconds(3),
                Duration.ofSeconds(30), 5, 3), Instant::now, nanos::get, null, null, r);
    }

    private static long count(JdbcDataSource ds, String q, Object... p) throws Exception {
        try (Connection c = ds.getConnection()) {
            return Jdbc.one(c, q, rs -> rs.getLong(1), p).orElse(0L);
        }
    }

    private static void exec(JdbcDataSource ds, String q, Object... p) throws Exception {
        try (Connection c = ds.getConnection()) {
            Jdbc.update(c, q, p);
        }
    }

    /** Becomes coordinator; returns its epoch. */
    private static long coordinator(JdbcDataSource ds, PartitionLeaseManager n) throws Exception {
        n.bootstrap();
        n.heartbeat();
        assertTrue(n.holdCoordinator());
        return count(ds, "SELECT coordinator_epoch FROM EXEC_MAP WHERE cluster_id = 'c1'");
    }

    private static Optional<PartitionResize.State> step(PartitionResize r, PartitionLeaseManager n, long epoch,
                                                          Duration drain) {
        return r.advance(n.coordinatorName(), epoch, drain, Duration.ZERO, Instant::now);
    }

    private static void assertEverySlotMappedOnce(JdbcDataSource ds, long mapEpoch) throws Exception {
        assertEquals(SLOTS, count(ds, "SELECT COUNT(*) FROM EXEC_SLOT WHERE state = 'STABLE' AND map_epoch = ?", mapEpoch),
                "I-1: every slot STABLE in exactly one row of the committed map");
    }

    @Test
    void requestIsValidatedIdempotentAndConcurrencyControlled() throws Exception {
        JdbcDataSource ds = h2();
        PartitionResize r = resize(ds);
        assertEquals(PartitionResize.Code.NOT_INITIALISED,
                assertThrows(PartitionResize.ResizeException.class, () -> r.request("r0", "ops", "x", 4, null)).code());
        node(ds, "a", 8, r).bootstrap();
        assertEquals(PartitionResize.Code.VALIDATION_FAILED,
                assertThrows(PartitionResize.ResizeException.class, () -> r.request("r1", "ops", "x", 65, null)).code());
        assertEquals(PartitionResize.Code.VALIDATION_FAILED,
                assertThrows(PartitionResize.ResizeException.class, () -> r.request("r1", "ops", " ", 12, null)).code());
        assertEquals(PartitionResize.Code.VALIDATION_FAILED,
                assertThrows(PartitionResize.ResizeException.class, () -> r.request("r1", " ", "x", 12, null)).code());
        assertEquals(PartitionResize.Code.REVISION_CONFLICT,
                assertThrows(PartitionResize.ResizeException.class, () -> r.request("r1", "ops", "x", 12, 7L)).code());
        assertEquals(PartitionResize.Code.INVALID_TRANSITION,
                assertThrows(PartitionResize.ResizeException.class, () -> r.request("r1", "ops", "x", 8, null)).code(),
                "the current count");
        assertEquals(0, audit.size(), "a refused request writes nothing");

        PartitionResize.Resize first = r.request("r1", "ops", "scale out", 12, 1L);
        assertEquals(PartitionResize.State.REQUESTED, first.state());
        assertEquals(8, first.fromCount());
        assertEquals(1, first.fromMapEpoch());
        assertEquals(2, first.toMapEpoch());
        assertEquals(PartitionAllocation.ALGORITHM_VERSION, first.algorithmVersion());
        assertEquals(1, count(ds, "SELECT COUNT(*) FROM EXEC_MAP WHERE state = 'TRANSITION' AND target_map_epoch = 2"));
        assertEquals(List.of(PartitionResize.RESIZE_REQUESTED + "@system@executor:c1"), audit);
        assertEquals("ops", payloads.get(0).get("actor"));

        assertEquals(first.id(), r.request("r1", "ops", "scale out", 12, 1L).id(), "same id and count: the stored resize");
        assertEquals(1, audit.size(), "an idempotent repeat audits nothing");
        assertEquals(PartitionResize.Code.INVALID_TRANSITION,
                assertThrows(PartitionResize.ResizeException.class, () -> r.request("r1", "ops", "x", 4, null)).code(),
                "a reused id with another count");
        assertEquals(PartitionResize.Code.INVALID_TRANSITION,
                assertThrows(PartitionResize.ResizeException.class, () -> r.request("r2", "ops", "x", 4, null)).code(),
                "a second resize while one is open");
    }

    @Test
    void scaleOutEightToTwelveCommitsAMinimalMoveAndAuditsIt() throws Exception {
        JdbcDataSource ds = h2();
        PartitionResize r = resize(ds);
        PartitionLeaseManager a = node(ds, "a", 8, r);
        long epoch = coordinator(ds, a);
        int[] before = r.committedAssignment();
        r.request("r1", "ops", "scale out", 12, 1L);

        assertEquals(Optional.of(PartitionResize.State.COMPLETED), step(r, a, epoch, Duration.ofHours(1)),
                "no busy jobs: one step drains, settles and commits");
        assertEverySlotMappedOnce(ds, 2);
        assertEquals(1, count(ds, "SELECT COUNT(*) FROM EXEC_MAP WHERE state = 'STABLE' AND current_map_epoch = 2"
                + " AND target_map_epoch IS NULL"));
        assertEquals(12, count(ds, "SELECT COUNT(*) FROM EXEC_PARTITION WHERE partition_id > 0 AND state <> 'RETIRED'"));
        assertEquals(12, a.livePartitions(), "the fair share follows the committed map, not the configured count");
        int[] after = r.committedAssignment();
        PartitionAllocation.Plan expected = PartitionAllocation.plan(before, 12);
        assertEquals(java.util.Arrays.toString(expected.target()), java.util.Arrays.toString(after));
        PartitionResize.Resize done = r.get("r1").orElseThrow();
        assertEquals(PartitionResize.State.COMPLETED, done.state());
        assertEquals(expected.moved().length, done.movedSlotCount());
        assertEquals(expected.ranges(before), done.movedSlots());
        assertNotNull(done.startedAt());
        assertNotNull(done.completedAt());
        assertNotNull(done.handoverPauseMs(), "the pause is measured, never assumed zero");
        assertEquals(PartitionResize.COUNT_CHANGED + "@system@executor:c1", audit.get(audit.size() - 1));
        Map<String, Object> p = payloads.get(payloads.size() - 1);
        assertEquals(8, p.get("fromPartitionCount"));
        assertEquals(12, p.get("toPartitionCount"));
        assertEquals(2L, p.get("toMapEpoch"));
        assertEquals("COMPLETED", p.get("outcome"));
        assertEquals(List.of(), r.list(PartitionResize.State.IN_PROGRESS));
        assertEquals(List.of(), r.list(null).get(0).movedSlots(), "the list view omits movedSlots");
        assertEquals(Optional.empty(), step(r, a, epoch, Duration.ofHours(1)), "nothing open afterwards");
    }

    @Test
    void scaleInThenOffThenOnKeepsEverySlotMapped() throws Exception {
        JdbcDataSource ds = h2();
        PartitionResize r = resize(ds);
        PartitionLeaseManager a = node(ds, "a", 8, r);
        long epoch = coordinator(ds, a);
        r.request("in", "ops", "scale in", 4, 1L);
        assertEquals(Optional.of(PartitionResize.State.COMPLETED), step(r, a, epoch, Duration.ofHours(1)));
        assertEverySlotMappedOnce(ds, 2);
        assertEquals(4, count(ds, "SELECT COUNT(*) FROM EXEC_PARTITION WHERE state = 'RETIRED'"), "5..8 retired");

        r.request("off", "ops", "partitioning off", 0, 2L);
        assertEquals(Optional.of(PartitionResize.State.COMPLETED), step(r, a, epoch, Duration.ofHours(1)));
        assertEverySlotMappedOnce(ds, 3);
        assertEquals(SLOTS, count(ds, "SELECT COUNT(*) FROM EXEC_SLOT WHERE partition_id = 0"));
        assertEquals(1, count(ds, "SELECT COUNT(*) FROM EXEC_PARTITION WHERE partition_id = 0 AND state = 'SHARED'"));
        assertEquals(0, count(ds, "SELECT COUNT(*) FROM EXEC_PARTITION WHERE partition_id > 0 AND state <> 'RETIRED'"));

        r.request("on", "ops", "partitioning on", 8, 3L);
        assertEquals(Optional.of(PartitionResize.State.COMPLETED), step(r, a, epoch, Duration.ofHours(1)));
        assertEverySlotMappedOnce(ds, 4);
        assertEquals(1, count(ds, "SELECT COUNT(*) FROM EXEC_PARTITION WHERE partition_id = 0 AND state = 'RETIRED'"));
        assertEquals(8, count(ds, "SELECT COUNT(*) FROM EXEC_PARTITION WHERE partition_id > 0 AND state = 'UNOWNED'"),
                "the re-created partitions wait to be claimed");
    }

    @Test
    void aBusyMovingSlotWaitsThenItsJobIsRecoveredWithRetriesKept() throws Exception {
        JdbcDataSource ds = h2();
        PartitionResize r = resize(ds);
        PartitionLeaseManager a = node(ds, "a", 2, r);
        long epoch = coordinator(ds, a);
        int[] before = r.committedAssignment();
        PartitionAllocation.Plan plan = PartitionAllocation.plan(before, 3);
        int slot = plan.moved()[0];
        int from = before[slot];
        exec(ds, "INSERT INTO ACT_RU_JOB (ID_, REV_, LOCK_OWNER_, LOCK_EXP_TIME_, RETRIES_) VALUES ('j1', 4, 'old-owner',"
                + " DATEADD('HOUR', 1, CURRENT_TIMESTAMP), 3)");
        exec(ds, "INSERT INTO EXEC_JOB_SLOT (job_id, tenant_id, root_id, slot, owner_partition, owner_epoch, recoveries,"
                + " created_at_ms) VALUES ('j1', 't1', 'root1', ?, ?, 1, 0, 0)", slot, from);
        r.request("r1", "ops", "scale out", 3, 1L);

        assertEquals(Optional.of(PartitionResize.State.IN_PROGRESS), step(r, a, epoch, Duration.ofHours(1)));
        assertEquals(1, count(ds, "SELECT COUNT(*) FROM EXEC_SLOT WHERE slot = ? AND state = 'MOVING'", slot),
                "the source still holds a live lock in the slot: it stays MOVING");
        assertEquals(1, count(ds, "SELECT COUNT(*) FROM EXEC_MAP WHERE current_map_epoch = 1 AND state = 'TRANSITION'"),
                "the old map stays committed meanwhile");

        assertEquals(Optional.of(PartitionResize.State.COMPLETED), step(r, a, epoch, Duration.ZERO),
                "after the drain timeout the job is recovered and the slot settles");
        assertEquals(0, count(ds, "SELECT COUNT(*) FROM ACT_RU_JOB WHERE ID_ = 'j1' AND LOCK_OWNER_ IS NOT NULL"));
        assertEquals(5, count(ds, "SELECT REV_ FROM ACT_RU_JOB WHERE ID_ = 'j1'"), "REV_ + 1: the stale run cannot commit");
        assertEquals(3, count(ds, "SELECT RETRIES_ FROM ACT_RU_JOB WHERE ID_ = 'j1'"), "retries kept");
        assertEquals(1, count(ds, "SELECT recoveries FROM EXEC_JOB_SLOT WHERE job_id = 'j1'"));
        assertEquals(0, count(ds, "SELECT COUNT(*) FROM EXEC_JOB_SLOT WHERE job_id = 'j1' AND owner_partition IS NOT NULL"));
        assertEverySlotMappedOnce(ds, 2);
    }

    /** Every slot in exactly one range of exactly one partition view; returns slot -> partition. */
    private static int[] slotsOf(PartitionResize.PartitionMap m) {
        int[] out = new int[SLOTS];
        java.util.Arrays.fill(out, -1);
        for (PartitionResize.PartitionView p : m.partitions()) {
            int prev = -2;
            for (PartitionResize.SlotRange sr : p.slots()) {
                assertTrue(sr.first() <= sr.last() && sr.first() > prev + 1, "sorted, non-adjacent ranges: " + p);
                prev = sr.last();
                for (int s = sr.first(); s <= sr.last(); s++) {
                    assertEquals(-1, out[s], "slot " + s + " shown twice");
                    out[s] = p.partitionId();
                }
            }
        }
        for (int s = 0; s < SLOTS; s++) {
            assertTrue(out[s] >= 0, "slot " + s + " missing from the map view");
        }
        return out;
    }

    @Test
    void mapViewShowsTheCommittedMapBeforeDuringAndAfterAResize() throws Exception {
        JdbcDataSource ds = h2();
        PartitionResize r = resize(ds);
        assertEquals(PartitionResize.Code.NOT_INITIALISED,
                assertThrows(PartitionResize.ResizeException.class, r::map).code(), "no executor map: 409 in the contract");
        PartitionLeaseManager a = node(ds, "a", 2, r);
        long epoch = coordinator(ds, a);

        PartitionResize.PartitionMap boot = r.map();
        assertEquals("c1", boot.clusterId());
        assertEquals("STABLE", boot.state());
        assertEquals(1L, boot.currentMapEpoch());
        assertEquals(null, boot.targetMapEpoch());
        assertEquals(2, boot.partitionCount());
        assertEquals(SLOTS, boot.slotCount());
        assertEquals(PartitionResize.BOOTSTRAP_ALGORITHM, boot.algorithmVersion(), "a map never resized");
        assertEquals(null, boot.activeResize());
        assertEquals(List.of(1, 2), boot.partitions().stream().map(PartitionResize.PartitionView::partitionId).toList());
        int[] before = r.committedAssignment();
        assertEquals(java.util.Arrays.toString(before), java.util.Arrays.toString(slotsOf(boot)));
        assertEquals(List.of(new PartitionResize.SlotRange(0, SLOTS / 2 - 1)), boot.partitions().get(0).slots(),
                "bootstrap: contiguous balanced ranges");

        // in progress: a busy moving slot keeps the old map committed; the view shows every slot under its M owner
        PartitionAllocation.Plan plan = PartitionAllocation.plan(before, 3);
        int slot = plan.moved()[0];
        exec(ds, "INSERT INTO ACT_RU_JOB (ID_, REV_, LOCK_OWNER_, LOCK_EXP_TIME_, RETRIES_) VALUES ('j1', 1, 'old-owner',"
                + " DATEADD('HOUR', 1, CURRENT_TIMESTAMP), 3)");
        exec(ds, "INSERT INTO EXEC_JOB_SLOT (job_id, tenant_id, root_id, slot, owner_partition, owner_epoch, recoveries,"
                + " created_at_ms) VALUES ('j1', 't1', 'root1', ?, ?, 1, 0, 0)", slot, before[slot]);
        r.request("r1", "ops", "scale out", 3, 1L);
        PartitionResize.PartitionMap requested = r.map();
        assertEquals("TRANSITION", requested.state());
        assertEquals(2L, requested.targetMapEpoch());
        assertEquals("r1", requested.activeResize().id());
        assertEquals(PartitionResize.State.REQUESTED, requested.activeResize().state());
        assertEquals(java.util.Arrays.toString(before), java.util.Arrays.toString(slotsOf(requested)));

        assertEquals(Optional.of(PartitionResize.State.IN_PROGRESS), step(r, a, epoch, Duration.ofHours(1)));
        PartitionResize.PartitionMap moving = r.map();
        assertEquals(PartitionResize.State.IN_PROGRESS, moving.activeResize().state());
        assertEquals(plan.ranges(before), moving.activeResize().movedSlots(), "the stored plan is in the view");
        assertEquals(1L, moving.currentMapEpoch());
        assertEquals(2, moving.partitionCount(), "the committed count until M+1 commits");
        assertEquals(java.util.Arrays.toString(before), java.util.Arrays.toString(slotsOf(moving)),
                "moved and settled slots stay under their committed owner while the resize runs");
        PartitionResize.PartitionView target = moving.partitions().stream().filter(p -> p.partitionId() == 3)
                .findFirst().orElseThrow();
        assertEquals("UNOWNED", target.state());
        assertEquals(List.of(), target.slots(), "a target partition owns no committed slot yet");
        assertEquals(null, target.ownerNode());
        assertEquals(null, target.leaseExpiresAt());

        assertEquals(Optional.of(PartitionResize.State.COMPLETED), step(r, a, epoch, Duration.ZERO));
        PartitionResize.PartitionMap after = r.map();
        assertEquals("STABLE", after.state());
        assertEquals(2L, after.currentMapEpoch());
        assertEquals(null, after.targetMapEpoch());
        assertEquals(null, after.activeResize());
        assertEquals(3, after.partitionCount());
        assertEquals(PartitionAllocation.ALGORITHM_VERSION, after.algorithmVersion(), "the allocation of the last resize");
        assertEquals(java.util.Arrays.toString(plan.target()), java.util.Arrays.toString(slotsOf(after)));
        assertEquals(java.util.Arrays.toString(r.committedAssignment()), java.util.Arrays.toString(slotsOf(after)));
    }

    @Test
    void aDeposedCoordinatorChangesNothingAndASecondCoordinatorContinues() throws Exception {
        JdbcDataSource ds = h2();
        PartitionResize r = resize(ds);
        PartitionLeaseManager a = node(ds, "a", 8, r);
        long epoch = coordinator(ds, a);
        r.request("r1", "ops", "scale out", 12, 1L);
        assertEquals(Optional.of(PartitionResize.State.REQUESTED), step(r, a, epoch + 1, Duration.ofHours(1)),
                "wrong coordinator epoch: nothing moves");
        assertEquals(0, count(ds, "SELECT COUNT(*) FROM EXEC_SLOT WHERE state = 'MOVING' OR map_epoch = 2"));
        assertEquals(PartitionResize.State.REQUESTED, r.get("r1").orElseThrow().state());

        // the coordinator lease expires and node b takes over; it continues the same stored plan
        exec(ds, "UPDATE EXEC_MAP SET coordinator_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)");
        PartitionLeaseManager b = node(ds, "b", 8, r);
        long epochB = coordinator(ds, b);
        assertTrue(epochB > epoch);
        assertEquals(Optional.of(PartitionResize.State.REQUESTED), step(r, a, epoch, Duration.ofHours(1)),
                "the deposed coordinator still changes nothing");
        assertEquals(Optional.of(PartitionResize.State.COMPLETED), step(r, b, epochB, Duration.ofHours(1)));
        assertEverySlotMappedOnce(ds, 2);
    }
}
