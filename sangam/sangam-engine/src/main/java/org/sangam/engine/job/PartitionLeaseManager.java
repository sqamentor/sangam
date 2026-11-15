// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

/**
 * M-056 finish (R39, SANGAM.md X.2.1.3 to X.2.1.6): the partition lease lifecycle of one node. The engine database is
 * the only ownership authority and every lease time is on the database clock (A-1); each step is its own short
 * transaction on rows, never a transaction held for the length of a lease (X.2.1.8 rule 2).
 *
 * <ul>
 *   <li><b>Bootstrap</b>: the first node of a cluster creates the {@code EXEC_MAP} row, partitions {@code 1..P}
 *       {@code UNOWNED} and every slot {@code 0..S-1} {@code STABLE} on a contiguous, balanced range (invariant I-1);
 *       an existing map is never changed here (a partition-count change is the X.2.1.7 administrative command).</li>
 *   <li><b>Heartbeat</b>: {@code EXEC_NODE} row per node with this process's incarnation.</li>
 *   <li><b>Renew</b> every {@code leaseTtl/3} with the X.2.1.4 compare-and-set; 0 rows means the lease is lost and
 *       the partition is dropped at once. <b>Self-fencing</b>: a partition whose last renewal is older than
 *       {@code leaseTtl - safetyMargin} on the monotonic clock is no longer reported as owned. Acquisition itself
 *       re-checks the lease in SQL on every poll ({@link PartitionedAcquireJobsCmd}), so correctness never rests on
 *       this node's memory.</li>
 *   <li><b>Claim</b> up to the fair share {@code ceil(P / live nodes)} of {@code UNOWNED} or expired partitions
 *       (X.2.1.4 claim, a new epoch every time), then <b>in-flight recovery</b> in batches (X.2.1.6): jobs stamped
 *       with an older epoch of the partition and still locked are unlocked with {@code REV_ + 1} (the engine's own
 *       compare-and-set, so a stale execution can no longer commit), their stamp cleared, {@code recoveries + 1};
 *       retries are not touched. A job over {@code maxRecoveries} is parked ({@code RETRIES_ = 0}) and reported for a
 *       {@code JOB_RECOVERY_LOOP} incident.</li>
 *   <li><b>Drain</b>: a {@code DRAINING} partition takes no new jobs (the acquisition filter needs {@code ACTIVE});
 *       its owner sets it {@code UNOWNED} once none of its jobs holds a live lock, or at {@code drain_deadline}.</li>
 *   <li><b>Coordinator</b> (X.2.1.6): one node holds the fenced {@code EXEC_MAP} coordinator lease; it moves
 *       partitions off nodes above the fair share (or draining nodes) by setting them {@code DRAINING}, every plan
 *       write carrying its coordinator epoch, and sweeps {@code EXEC_JOB_SLOT} orphans (rows whose job is gone, left
 *       by Operaton's bulk deletes; contract W2-JOB-SIDE-TABLES-CONTRACT.md).</li>
 * </ul>
 *
 * <p>OFF by default: nothing of this runs unless {@code sangam.engine.executor-partitioning} is on (T54 gate).
 */
public final class PartitionLeaseManager {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PartitionLeaseManager.class);

    /** Incident type of a job that killed its executor too often (X.2.1.6). */
    public static final String RECOVERY_LOOP = "JOB_RECOVERY_LOOP";

    /**
     * @param clusterId     the {@code EXEC_MAP} key
     * @param nodeId        stable node name (at most 64 characters); the incarnation tells restarts apart
     * @param incarnation   a fresh id per process start (X.2.1.3)
     * @param partitions    P for a new map, 1 to {@code slots}
     * @param slots         S, the fixed slot count
     */
    public record Config(String clusterId, String nodeId, String incarnation, int partitions, int slots,
                         Duration leaseTtl, Duration safetyMargin, Duration drainTimeout, int recoveryBatch,
                         int maxRecoveries) {
        public Config {
            require(clusterId != null && !clusterId.isBlank() && clusterId.length() <= 64, "clusterId 1..64 characters");
            require(nodeId != null && !nodeId.isBlank() && nodeId.length() <= 64, "nodeId 1..64 characters");
            require(incarnation != null && !incarnation.isBlank(), "incarnation");
            require(slots >= 1 && slots <= ExecutorSlots.MAX_SLOTS, "slots 1.." + ExecutorSlots.MAX_SLOTS);
            require(partitions >= 1 && partitions <= slots, "partitions 1..slots");
            require(leaseTtl != null && leaseTtl.toMillis() >= 3_000, "leaseTtl at least 3 s");
            require(safetyMargin != null && !safetyMargin.isNegative() && safetyMargin.compareTo(leaseTtl) < 0,
                    "safetyMargin 0..leaseTtl");
            require(drainTimeout != null && drainTimeout.toMillis() > 0, "drainTimeout positive");
            require(recoveryBatch >= 1 && recoveryBatch <= 10_000, "recoveryBatch 1..10000");
            require(maxRecoveries >= 0, "maxRecoveries >= 0");
        }

        /** X.2.1.6 orphan rule: 2 x leaseTtl. */
        public Duration orphanTimeout() {
            return leaseTtl.multipliedBy(2);
        }

        /** X.2.1.4: renew every leaseTtl / 3. */
        public Duration renewInterval() {
            return leaseTtl.dividedBy(3);
        }

        private static void require(boolean ok, String what) {
            if (!ok) {
                throw new IllegalArgumentException("M-056 executor partitioning: " + what);
            }
        }
    }

    /** Observations for metrics, audit (EXECUTOR_OWNERSHIP_CHANGED) and incidents; every call is guarded. */
    public interface Listener {
        Listener NONE = new Listener() {
        };

        default void ownerChanged(int partition, long epoch, String reason) {
        }

        default void recovered(int partition, int jobs) {
        }

        /** A job over {@code maxRecoveries}, already parked with retries 0; raise its incident. */
        default void recoveryLoop(String jobId, String executionId, int recoveries) {
        }

        /** Coordinator view: partitions with no live owner ({@code executor_orphaned_partitions} gauge). */
        default void orphanedPartitions(long count) {
        }
    }

    /**
     * C3-04 (X.2.1.8 rule 5): the {@code EXECUTOR_OWNERSHIP_CHANGED} audit of every ownership write this node makes,
     * called inside the same transaction as the write, so the audit row and the ownership change commit or roll back
     * together (an audit failure undoes the change).
     */
    @FunctionalInterface
    public interface OwnershipAudit {
        OwnershipAudit NONE = (conn, partition, epoch, reason, previousOwner) -> {
        };

        /**
         * @param reason        claimed, taken-over, drain-requested, drained or released
         * @param previousOwner the owner node before the write, or {@code null}
         */
        void record(Connection conn, int partition, long epoch, String reason, String previousOwner);
    }

    private record Owned(long epoch, long renewedAtNanos) {
    }

    private final DataSource ds;
    private final Dialect d;
    private final Config c;
    private final Supplier<Instant> engineClock;
    private final LongSupplier nanos;
    private final Listener listener;
    private final OwnershipAudit audit;
    private final PartitionResize resize;
    private final Map<Integer, Owned> owned = new ConcurrentHashMap<>();
    private volatile boolean draining;
    private volatile long coordinatorEpoch = -1;
    private boolean registered;

    public PartitionLeaseManager(DataSource ds, Dialect dialect, Config config, Supplier<Instant> engineClock,
                                 LongSupplier monotonicNanos, Listener listener) {
        this(ds, dialect, config, engineClock, monotonicNanos, listener, OwnershipAudit.NONE);
    }

    public PartitionLeaseManager(DataSource ds, Dialect dialect, Config config, Supplier<Instant> engineClock,
                                 LongSupplier monotonicNanos, Listener listener, OwnershipAudit audit) {
        this(ds, dialect, config, engineClock, monotonicNanos, listener, audit, null);
    }

    /** @param resize decision 146: the partition-count change the coordinator advances each cycle, or {@code null} */
    public PartitionLeaseManager(DataSource ds, Dialect dialect, Config config, Supplier<Instant> engineClock,
                                 LongSupplier monotonicNanos, Listener listener, OwnershipAudit audit,
                                 PartitionResize resize) {
        this.resize = resize;
        this.ds = ds;
        this.d = dialect;
        this.c = config;
        this.engineClock = engineClock;
        this.nanos = monotonicNanos;
        this.listener = listener == null ? Listener.NONE : listener;
        this.audit = audit == null ? OwnershipAudit.NONE : audit;
    }

    public Config config() {
        return c;
    }

    /** Decision 146: the resize service of this cluster, or {@code null} when none was wired. */
    public PartitionResize resize() {
        return resize;
    }

    /**
     * Decision 146: the live partition count of the committed map (UNOWNED, ACTIVE or DRAINING, never the virtual
     * partition 0); the configured count only seeds a new map. At least 1, so the fair share stays defined.
     */
    int livePartitions() {
        long n = tx(conn -> Jdbc.one(conn, "SELECT COUNT(*) FROM EXEC_PARTITION WHERE partition_id > 0"
                + " AND state IN ('UNOWNED', 'ACTIVE', 'DRAINING')", rs -> rs.getLong(1)).orElse(0L));
        return (int) Math.max(1L, n);
    }

    // ------------------------------------------------------------------ the cycle

    /** One cycle: heartbeat, renew, drains, coordinator duties, claims. Never throws; failures are logged. */
    public void tick() {
        step("bootstrap", this::bootstrap);
        step("heartbeat", this::heartbeat);
        step("renew", this::renew);
        step("drain", this::completeDrains);
        step("coordinate", this::coordinate);
        step("claim", this::claimFairShare);
    }

    private void step(String name, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            LOG.warn("M-056 partition lease step '{}' failed on node {}: {}", name, c.nodeId(), e.toString());
        }
    }

    /** The partitions this node may still treat as owned (renewed within {@code leaseTtl - safetyMargin}). */
    public Set<Integer> ownedLive() {
        long limit = c.leaseTtl().minus(c.safetyMargin()).toNanos();
        long now = nanos.getAsLong();
        Set<Integer> out = new TreeSet<>();
        owned.forEach((p, o) -> {
            if (now - o.renewedAtNanos() < limit) {
                out.add(p);
            }
        });
        return Collections.unmodifiableSet(out);
    }

    /** The epoch this node holds for {@code partition}, or -1. */
    public long epochOf(int partition) {
        Owned o = owned.get(partition);
        return o == null ? -1 : o.epoch();
    }

    /** Pre-stop: take no new partitions and let the coordinator move the owned ones away (planned handover). */
    public void startDraining() {
        draining = true;
        step("heartbeat", this::heartbeat);
        for (Map.Entry<Integer, Owned> e : owned.entrySet()) {
            int p = e.getKey();
            long epoch = e.getValue().epoch();
            step("drain-request", () -> tx(conn -> {
                int n = Jdbc.update(conn, "UPDATE EXEC_PARTITION SET state = 'DRAINING',"
                                + " drain_deadline = " + d.nowPlusMillisParam() + " WHERE partition_id = ? AND owner_incarnation = ?"
                                + " AND owner_epoch = ? AND state = 'ACTIVE'",
                        c.drainTimeout().toMillis(), p, c.incarnation(), epoch);
                if (n == 1) {
                    audit.record(conn, p, epoch, "drain-requested", c.nodeId());
                }
                return n;
            }));
        }
    }

    /**
     * Shutdown after the job executor stopped (its in-flight jobs are done): every partition of this incarnation
     * becomes {@code UNOWNED} and the node row is removed; the next claimer still runs recovery as a safety net.
     */
    public void releaseAll() {
        step("release", () -> tx(conn -> {
            List<long[]> held = Jdbc.query(conn, "SELECT partition_id, owner_epoch FROM EXEC_PARTITION"
                    + " WHERE owner_incarnation = ? AND state IN ('ACTIVE', 'DRAINING') ORDER BY partition_id",
                    rs -> new long[]{rs.getInt(1), rs.getLong(2)}, c.incarnation());
            for (long[] pe : held) { // per row, fenced by the epoch, so only what this write released is audited
                if (Jdbc.update(conn, "UPDATE EXEC_PARTITION SET state = 'UNOWNED', owner_node = NULL,"
                        + " owner_incarnation = NULL, lease_expires_at = NULL, drain_deadline = NULL WHERE partition_id = ?"
                        + " AND owner_incarnation = ? AND owner_epoch = ? AND state IN ('ACTIVE', 'DRAINING')",
                        (int) pe[0], c.incarnation(), pe[1]) == 1) {
                    audit.record(conn, (int) pe[0], pe[1], "released", c.nodeId());
                }
            }
            Jdbc.update(conn, "UPDATE EXEC_MAP SET coordinator_node = NULL, coordinator_expires_at = NULL"
                    + " WHERE cluster_id = ? AND coordinator_node = ? AND coordinator_epoch = ?",
                    c.clusterId(), coordinatorName(), coordinatorEpoch);
            return Jdbc.update(conn, "DELETE FROM EXEC_NODE WHERE node_id = ? AND incarnation = ?", c.nodeId(),
                    c.incarnation());
        }));
        owned.keySet().forEach(p -> listener.ownerChanged(p, -1, "released"));
        owned.clear();
        coordinatorEpoch = -1;
    }

    // ------------------------------------------------------------------ steps

    /** Creates the map, partitions and slots when the cluster has none (idempotent, race-safe on the map key). */
    void bootstrap() {
        tx(conn -> {
            if (Jdbc.one(conn, "SELECT current_map_epoch FROM EXEC_MAP WHERE cluster_id = ?", rs -> rs.getLong(1),
                    c.clusterId()).isPresent()) {
                return null;
            }
            if (!Jdbc.insertIfAbsent(conn, d, "INSERT INTO EXEC_MAP (cluster_id, current_map_epoch, state,"
                    + " coordinator_epoch) VALUES (?, 1, 'STABLE', 0)", c.clusterId())) {
                return null; // another node created it
            }
            List<Object[]> partitions = new ArrayList<>();
            for (int p = 1; p <= c.partitions(); p++) {
                partitions.add(new Object[]{p, 0L, "UNOWNED", 1L});
            }
            Jdbc.batch(conn, "INSERT INTO EXEC_PARTITION (partition_id, owner_epoch, state, map_epoch)"
                    + " VALUES (?, ?, ?, ?)", partitions);
            List<Object[]> slots = new ArrayList<>(c.slots());
            for (int s = 0; s < c.slots(); s++) {
                slots.add(new Object[]{s, partitionOfSlot(s, c.slots(), c.partitions()), "STABLE", 1L});
            }
            Jdbc.batch(conn, "INSERT INTO EXEC_SLOT (slot, partition_id, state, map_epoch) VALUES (?, ?, ?, ?)", slots);
            LOG.info("M-056: executor map '{}' created: {} partitions over {} slots", c.clusterId(), c.partitions(),
                    c.slots());
            return null;
        });
    }

    /** X.2.1.7 step 1: contiguous balanced ranges, partitions numbered from 1 (0 is the virtual SHARED partition). */
    static int partitionOfSlot(int slot, int slots, int partitions) {
        return 1 + (int) ((long) slot * partitions / slots);
    }

    void heartbeat() {
        int drainFlag = draining ? 1 : 0;
        tx(conn -> {
            int n = registered
                    ? Jdbc.update(conn, "UPDATE EXEC_NODE SET heartbeat_at = " + d.now() + ", draining = ?"
                    + " WHERE node_id = ? AND incarnation = ?", drainFlag, c.nodeId(), c.incarnation())
                    : Jdbc.update(conn, "UPDATE EXEC_NODE SET incarnation = ?, started_at = " + d.now()
                    + ", heartbeat_at = " + d.now() + ", draining = ? WHERE node_id = ?",
                    c.incarnation(), drainFlag, c.nodeId());
            if (n == 0) {
                Jdbc.insertIfAbsent(conn, d, "INSERT INTO EXEC_NODE (node_id, incarnation, started_at, heartbeat_at,"
                        + " draining) VALUES (?, ?, " + d.now() + ", " + d.now() + ", ?)", c.nodeId(), c.incarnation(),
                        drainFlag);
            }
            return null;
        });
        registered = true;
    }

    void renew() {
        for (Map.Entry<Integer, Owned> e : new ArrayList<>(owned.entrySet())) {
            int p = e.getKey();
            long epoch = e.getValue().epoch();
            int n = tx(conn -> Jdbc.update(conn, "UPDATE EXEC_PARTITION SET lease_expires_at = " + d.nowPlusMillisParam()
                            + " WHERE partition_id = ? AND owner_incarnation = ? AND owner_epoch = ?"
                            + " AND state IN ('ACTIVE', 'DRAINING') AND lease_expires_at >= " + d.now(),
                    c.leaseTtl().toMillis(), p, c.incarnation(), epoch));
            if (n == 1) {
                owned.put(p, new Owned(epoch, nanos.getAsLong()));
            } else {
                owned.remove(p); // X.2.1.4: the lease is lost; stop at once
                LOG.warn("M-056: node {} lost partition {} (epoch {})", c.nodeId(), p, epoch);
                listener.ownerChanged(p, epoch, "lost");
            }
        }
        long limit = c.leaseTtl().minus(c.safetyMargin()).toNanos();
        long now = nanos.getAsLong();
        owned.entrySet().removeIf(e -> {
            boolean stale = now - e.getValue().renewedAtNanos() >= limit; // self-fencing (liveness only)
            if (stale) {
                listener.ownerChanged(e.getKey(), e.getValue().epoch(), "self-fenced");
            }
            return stale;
        });
    }

    /** DRAINING partitions of this node become UNOWNED when no job of theirs holds a live lock, or at the deadline. */
    void completeDrains() {
        Timestamp jobNow = Timestamp.from(engineClock.get());
        for (Map.Entry<Integer, Owned> e : new ArrayList<>(owned.entrySet())) {
            int p = e.getKey();
            long epoch = e.getValue().epoch();
            boolean released = tx(conn -> {
                Integer overdue = Jdbc.one(conn, "SELECT CASE WHEN drain_deadline IS NULL OR drain_deadline < " + d.now()
                                + " THEN 1 ELSE 0 END FROM EXEC_PARTITION WHERE partition_id = ? AND owner_incarnation = ?"
                                + " AND owner_epoch = ? AND state = 'DRAINING'", rs -> rs.getInt(1), p, c.incarnation(),
                        epoch).orElse(null);
                if (overdue == null) {
                    return false; // not draining
                }
                long busy = Jdbc.one(conn, "SELECT COUNT(*) FROM EXEC_JOB_SLOT s, ACT_RU_JOB j WHERE s.owner_partition = ?"
                                + " AND s.owner_epoch = ? AND j.ID_ = s.job_id AND j.LOCK_OWNER_ IS NOT NULL"
                                + " AND j.LOCK_EXP_TIME_ > ?", rs -> rs.getLong(1), p, epoch, jobNow).orElse(0L);
                if (busy > 0 && overdue == 0) {
                    return false;
                }
                boolean done = Jdbc.update(conn, "UPDATE EXEC_PARTITION SET state = 'UNOWNED', owner_node = NULL,"
                        + " owner_incarnation = NULL, lease_expires_at = NULL, drain_deadline = NULL"
                        + " WHERE partition_id = ? AND owner_incarnation = ? AND owner_epoch = ? AND state = 'DRAINING'",
                        p, c.incarnation(), epoch) == 1;
                if (done) {
                    audit.record(conn, p, epoch, "drained", c.nodeId());
                }
                return done;
            });
            if (released) {
                owned.remove(p);
                listener.ownerChanged(p, epoch, "drained");
            }
        }
    }

    /** Claims UNOWNED or expired partitions up to the fair share, each followed by in-flight recovery. */
    void claimFairShare() {
        if (draining) {
            return;
        }
        long live = tx(conn -> Jdbc.one(conn, "SELECT COUNT(*) FROM EXEC_NODE WHERE draining = 0 AND heartbeat_at > "
                + d.nowPlusMillisParam(), rs -> rs.getLong(1), -c.orphanTimeout().toMillis()).orElse(1L));
        int fair = (int) Math.ceil(livePartitions() / (double) Math.max(1L, live));
        if (owned.size() >= fair) {
            return;
        }
        List<Integer> free = tx(conn -> Jdbc.query(conn, "SELECT partition_id FROM EXEC_PARTITION"
                + " WHERE state = 'UNOWNED' OR (state IN ('ACTIVE', 'DRAINING') AND (lease_expires_at IS NULL"
                + " OR lease_expires_at < " + d.now() + ")) ORDER BY partition_id", rs -> rs.getInt(1)));
        // start at a node-specific offset so concurrent claimers spread over the free partitions
        int offset = free.isEmpty() ? 0 : Math.floorMod(c.nodeId().hashCode(), free.size());
        for (int i = 0; i < free.size() && owned.size() < fair; i++) {
            int p = free.get((i + offset) % free.size());
            long epoch = claim(p);
            if (epoch > 0) {
                recover(p, epoch);
            }
        }
    }

    /** X.2.1.4 claim: a new epoch, or -1 when another node won. */
    long claim(int p) {
        long epoch = tx(conn -> {
            // owner_node is NULL while the partition is UNOWNED: a row without an owner, not a missing row
            String previous = Jdbc.one(conn, "SELECT owner_node FROM EXEC_PARTITION WHERE partition_id = ?",
                    rs -> java.util.Optional.ofNullable(rs.getString(1)), p).flatMap(owner -> owner).orElse(null);
            int n = Jdbc.update(conn, "UPDATE EXEC_PARTITION SET owner_node = ?, owner_incarnation = ?,"
                            + " owner_epoch = owner_epoch + 1, lease_expires_at = " + d.nowPlusMillisParam()
                            + ", state = 'ACTIVE', drain_deadline = NULL WHERE partition_id = ?"
                            + " AND state IN ('UNOWNED', 'ACTIVE', 'DRAINING') AND (state = 'UNOWNED'"
                            + " OR lease_expires_at IS NULL OR lease_expires_at < " + d.now() + ")",
                    c.nodeId(), c.incarnation(), c.leaseTtl().toMillis(), p);
            if (n != 1) {
                return -1L;
            }
            long won = Jdbc.one(conn, "SELECT owner_epoch FROM EXEC_PARTITION WHERE partition_id = ? AND owner_incarnation = ?",
                    rs -> rs.getLong(1), p, c.incarnation()).orElse(-1L);
            if (won > 0) {
                audit.record(conn, p, won, previous == null ? "claimed" : "taken-over", previous);
            }
            return won;
        });
        if (epoch > 0) {
            owned.put(p, new Owned(epoch, nanos.getAsLong()));
            LOG.info("M-056: node {} claimed partition {} at epoch {}", c.nodeId(), p, epoch);
            listener.ownerChanged(p, epoch, "claimed");
        }
        return epoch;
    }

    /**
     * X.2.1.6 in-flight recovery after a claim at {@code newEpoch}: locked jobs stamped with an older epoch of the
     * partition are unlocked (engine compare-and-set {@code REV_ + 1}), in batches, each its own transaction; rows a
     * stale execution is committing right now are skipped (a later pass or the lock expiry catches them).
     *
     * @return the number of jobs recovered
     */
    int recover(int p, long newEpoch) {
        int total = 0;
        while (true) {
            List<String[]> loops = new ArrayList<>();
            int n = tx(conn -> {
                List<String[]> jobs = Jdbc.query(conn, d.skipLockedSelect("j.ID_, j.EXECUTION_ID_", "ACT_RU_JOB", "j",
                                "j.LOCK_OWNER_ IS NOT NULL AND EXISTS (SELECT 1 FROM EXEC_JOB_SLOT s WHERE s.job_id = j.ID_"
                                        + " AND s.owner_partition = ? AND s.owner_epoch < ?)", "j.ID_", c.recoveryBatch()),
                        rs -> new String[]{rs.getString(1), rs.getString(2)}, p, newEpoch);
                for (String[] job : jobs) {
                    Jdbc.update(conn, "UPDATE EXEC_JOB_SLOT SET owner_partition = NULL, owner_epoch = NULL,"
                            + " recoveries = recoveries + 1 WHERE job_id = ?", job[0]);
                    int recoveries = Jdbc.one(conn, "SELECT recoveries FROM EXEC_JOB_SLOT WHERE job_id = ?",
                            rs -> rs.getInt(1), job[0]).orElse(0);
                    boolean loop = recoveries > c.maxRecoveries();
                    // retries are not decremented (node loss is not a job failure); a poison job is parked
                    Jdbc.update(conn, "UPDATE ACT_RU_JOB SET LOCK_OWNER_ = NULL, LOCK_EXP_TIME_ = NULL, REV_ = REV_ + 1"
                            + (loop ? ", RETRIES_ = 0" : "") + " WHERE ID_ = ? AND LOCK_OWNER_ IS NOT NULL", job[0]);
                    if (loop) {
                        loops.add(new String[]{job[0], job[1], Integer.toString(recoveries)});
                    }
                }
                return jobs.size();
            });
            total += n;
            for (String[] l : loops) {
                LOG.warn("M-056: job {} recovered {} times (> {}): parked, incident {}", l[0], l[2], c.maxRecoveries(),
                        RECOVERY_LOOP);
                guarded(() -> listener.recoveryLoop(l[0], l[1], Integer.parseInt(l[2])));
            }
            if (n < c.recoveryBatch()) {
                break;
            }
        }
        if (total > 0) {
            int recovered = total;
            guarded(() -> listener.recovered(p, recovered));
        }
        return total;
    }

    // ------------------------------------------------------------------ coordinator (X.2.1.6)

    String coordinatorName() {
        return c.nodeId() + "#" + c.incarnation();
    }

    /** Holds or takes the coordinator lease; as coordinator, plans moves and sweeps orphans. */
    void coordinate() {
        if (!holdCoordinator()) {
            return;
        }
        planMoves();
        sweepOrphans();
        if (resize != null) { // decision 146: fenced by this coordinator's epoch inside every write
            resize.advance(coordinatorName(), coordinatorEpoch, c.drainTimeout(), c.renewInterval(), engineClock);
        }
    }

    boolean holdCoordinator() {
        if (coordinatorEpoch > 0) {
            long e = coordinatorEpoch;
            int n = tx(conn -> Jdbc.update(conn, "UPDATE EXEC_MAP SET coordinator_expires_at = " + d.nowPlusMillisParam()
                            + " WHERE cluster_id = ? AND coordinator_node = ? AND coordinator_epoch = ?"
                            + " AND coordinator_expires_at >= " + d.now(),
                    c.leaseTtl().toMillis(), c.clusterId(), coordinatorName(), e));
            if (n == 1) {
                return true;
            }
            coordinatorEpoch = -1;
        }
        if (draining) {
            return false;
        }
        coordinatorEpoch = tx(conn -> {
            int n = Jdbc.update(conn, "UPDATE EXEC_MAP SET coordinator_node = ?, coordinator_epoch = coordinator_epoch + 1,"
                            + " coordinator_expires_at = " + d.nowPlusMillisParam() + " WHERE cluster_id = ?"
                            + " AND (coordinator_expires_at IS NULL OR coordinator_expires_at < " + d.now() + ")",
                    coordinatorName(), c.leaseTtl().toMillis(), c.clusterId());
            if (n != 1) {
                return -1L;
            }
            return Jdbc.one(conn, "SELECT coordinator_epoch FROM EXEC_MAP WHERE cluster_id = ? AND coordinator_node = ?",
                    rs -> rs.getLong(1), c.clusterId(), coordinatorName()).orElse(-1L);
        });
        return coordinatorEpoch > 0;
    }

    /**
     * Moves partitions off nodes above the fair share and off draining nodes (DRAINING with a deadline), only as far as
     * live nodes below the share can take them; every write is fenced by this coordinator's epoch.
     */
    void planMoves() {
        long e = coordinatorEpoch;
        Map<String, Boolean> nodes = new LinkedHashMap<>(); // node id -> draining
        tx(conn -> Jdbc.query(conn, "SELECT node_id, draining FROM EXEC_NODE WHERE heartbeat_at > " + d.nowPlusMillisParam()
                        + " ORDER BY node_id", rs -> nodes.put(rs.getString(1), rs.getInt(2) != 0),
                -c.orphanTimeout().toMillis()));
        long liveTakers = nodes.values().stream().filter(dr -> !dr).count();
        if (liveTakers == 0) {
            return;
        }
        int partitions = livePartitions();
        int fair = (int) Math.ceil(partitions / (double) liveTakers);
        Map<String, List<long[]>> byOwner = new HashMap<>(); // owner -> [partition, epoch]
        tx(conn -> Jdbc.query(conn, "SELECT owner_node, partition_id, owner_epoch FROM EXEC_PARTITION WHERE state = 'ACTIVE'"
                + " AND lease_expires_at > " + d.now() + " ORDER BY partition_id DESC", rs -> byOwner
                .computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new long[]{rs.getInt(2), rs.getLong(3)})));
        long capacity = 0;
        for (Map.Entry<String, Boolean> n : nodes.entrySet()) {
            if (!n.getValue()) {
                capacity += Math.max(0, fair - byOwner.getOrDefault(n.getKey(), List.of()).size());
            }
        }
        long unclaimed = partitions - byOwner.values().stream().mapToLong(List::size).sum();
        guarded(() -> listener.orphanedPartitions(Math.max(0, unclaimed)));
        capacity -= unclaimed; // free partitions are taken first
        for (Map.Entry<String, List<long[]>> o : byOwner.entrySet()) {
            boolean ownerDraining = nodes.getOrDefault(o.getKey(), Boolean.TRUE); // unknown or stale node: move away
            int surplus = ownerDraining ? o.getValue().size() : o.getValue().size() - fair;
            for (int i = 0; i < surplus && (ownerDraining || capacity > 0); i++) {
                long[] pe = o.getValue().get(i);
                String from = o.getKey();
                int n = tx(conn -> {
                    int moved = Jdbc.update(conn, "UPDATE EXEC_PARTITION SET state = 'DRAINING', drain_deadline = "
                                    + d.nowPlusMillisParam() + " WHERE partition_id = ? AND owner_epoch = ? AND state = 'ACTIVE'"
                                    + " AND EXISTS (SELECT 1 FROM EXEC_MAP m WHERE m.cluster_id = ? AND m.coordinator_node = ?"
                                    + " AND m.coordinator_epoch = ? AND m.coordinator_expires_at > " + d.now() + ")",
                            c.drainTimeout().toMillis(), pe[0], pe[1], c.clusterId(), coordinatorName(), e);
                    if (moved == 1) {
                        audit.record(conn, (int) pe[0], pe[1], "drain-requested", from);
                    }
                    return moved;
                });
                if (n == 1) {
                    capacity--;
                    LOG.info("M-056: coordinator {} moves partition {} off node {}", c.nodeId(), pe[0], o.getKey());
                }
            }
        }
    }

    /** Removes side rows whose job is gone (older than the orphan timeout), in bounded batches. */
    int sweepOrphans() {
        long before = engineClock.get().toEpochMilli() - c.orphanTimeout().toMillis();
        return tx(conn -> {
            List<String> ids = Jdbc.query(conn, d.skipLockedSelect("s.job_id", "EXEC_JOB_SLOT", "s",
                    "s.created_at_ms < ? AND NOT EXISTS (SELECT 1 FROM ACT_RU_JOB j WHERE j.ID_ = s.job_id)", "s.job_id",
                    c.recoveryBatch()), rs -> rs.getString(1), before);
            for (String id : ids) {
                Jdbc.update(conn, "DELETE FROM EXEC_JOB_SLOT WHERE job_id = ?", id);
            }
            return ids.size();
        });
    }

    // ------------------------------------------------------------------ plumbing

    private void guarded(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ex) {
            LOG.warn("M-056 partition lease listener failed: {}", ex.toString());
        }
    }

    private <T> T tx(Function<Connection, T> work) {
        try (Connection conn = ds.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                T r = work.apply(conn);
                conn.commit();
                return r;
            } catch (RuntimeException | Error ex) {
                try {
                    conn.rollback();
                } catch (SQLException rollback) {
                    ex.addSuppressed(rollback);
                }
                throw ex;
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("M-056 partition lease: " + ex.getMessage(), ex);
        }
    }
}
