// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import javax.sql.DataSource;

import org.sangam.core.engine.AuditSink;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

/**
 * Decision 146 (M-056, SANGAM.md X.2.1.7): a partition-count change as a controlled ownership transition from map
 * epoch {@code M} to {@code M+1}, bound to the W4-M contract {@code routes/executor.py}.
 *
 * <ol>
 *   <li>{@link #request}: validated (count 0..S, reason, platform actor, optional {@code expectedMapEpoch}); one
 *       compare-and-set on {@code EXEC_MAP} ({@code STABLE -> TRANSITION}, {@code target_map_epoch = M+1}) refuses a
 *       concurrent resize; an {@code EXEC_RESIZE} row in state {@code REQUESTED}. Nothing moves yet.</li>
 *   <li>{@link #advance}, run by the fenced coordinator each cycle (every write re-checks the coordinator epoch, so a
 *       deposed coordinator changes nothing): the plan ({@link PartitionAllocation}, stored in the row, so a restarted
 *       or new coordinator continues the same plan), new partitions {@code UNOWNED}, moving slots {@code MOVING} in
 *       batches. A {@code MOVING} slot takes no new acquisitions (the acquisition filter needs {@code STABLE} for an
 *       owner and a not-live target for the default path); it becomes {@code STABLE} under the target when no job of
 *       the slot holds a live lock stamped by the source partition, or after {@code drainTimeout}, when those jobs are
 *       recovered (lock cleared, {@code REV_ + 1} so the stale execution cannot commit, retries, due dates and tenant
 *       context untouched).</li>
 *   <li>When every slot is {@code STABLE} at {@code M+1}: {@code current_map_epoch = M+1}, partitions without slots
 *       {@code RETIRED} (their owner's next renewal fails and it stops at once), the row {@code COMPLETED} and
 *       {@code EXECUTOR_PARTITION_COUNT_CHANGED} audited, all in one transaction.</li>
 * </ol>
 * Partition ownership is never exactly-once: external effects keep their {@code operationId} (X.2.1.8 rule 3). The
 * handover pause (longest time a moved slot had no acquiring owner) is measured and stored, never assumed zero.
 */
public final class PartitionResize {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PartitionResize.class);

    public static final String COUNT_CHANGED = "EXECUTOR_PARTITION_COUNT_CHANGED";
    public static final String RESIZE_REQUESTED = "EXECUTOR_PARTITION_RESIZE_REQUESTED";
    public static final String RESIZE_FAILED = "EXECUTOR_PARTITION_RESIZE_FAILED";

    public enum State { REQUESTED, IN_PROGRESS, COMPLETED, FAILED }

    /** Refusal codes, as the REST contract maps them. */
    public enum Code { VALIDATION_FAILED, INVALID_TRANSITION, REVISION_CONFLICT, NOT_INITIALISED, NOT_FOUND }

    public static final class ResizeException extends RuntimeException {
        private final Code code;

        public ResizeException(Code code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    public record Resize(String id, State state, int fromCount, int toCount, long fromMapEpoch, long toMapEpoch,
                         String algorithmVersion, Integer movedSlotCount, List<PartitionAllocation.Move> movedSlots,
                         String actor, String reason, Instant requestedAt, Instant startedAt, Instant completedAt,
                         String failure, Long handoverPauseMs) {
    }

    private final DataSource ds;
    private final Dialect d;
    private final String clusterId;
    private final int slots;
    private final AuditSink audit;
    private final String auditScope;
    private final int batch;

    /**
     * @param auditScope the tenant (system audit scope) the class-B audit rows are written under
     * @param batch      slots per transaction while moving, 1..10000
     */
    public PartitionResize(DataSource ds, Dialect dialect, String clusterId, int slots, AuditSink audit,
                           String auditScope, int batch) {
        if (slots < 1 || slots > ExecutorSlots.MAX_SLOTS) {
            throw new IllegalArgumentException("decision 146: slots 1.." + ExecutorSlots.MAX_SLOTS);
        }
        if (batch < 1 || batch > 10_000) {
            throw new IllegalArgumentException("decision 146: batch 1..10000");
        }
        this.ds = ds;
        this.d = dialect;
        this.clusterId = clusterId;
        this.slots = slots;
        this.audit = audit == null ? AuditSink.NONE : audit;
        this.auditScope = auditScope;
        this.batch = batch;
    }

    // ------------------------------------------------------------------ request

    /** As {@link #request(Connection, String, String, String, int, Long)} in its own transaction. */
    public Resize request(String resizeId, String actor, String reason, int partitionCount, Long expectedMapEpoch) {
        return tx(c -> request(c, resizeId, actor, reason, partitionCount, expectedMapEpoch));
    }

    /**
     * Accepts a resize on the caller's connection (so an idempotency record of the REST host commits with it). The
     * caller has already checked the platform permission {@code executor.admin}; {@code actor} is the server-side
     * principal. A repeat of the same {@code resizeId} with the same count returns the stored resize.
     */
    public Resize request(Connection c, String resizeId, String actor, String reason, int partitionCount,
                          Long expectedMapEpoch) {
        if (resizeId == null || resizeId.isBlank() || resizeId.length() > 64) {
            throw new ResizeException(Code.VALIDATION_FAILED, "resize id 1..64 characters");
        }
        if (actor == null || actor.isBlank()) {
            throw new ResizeException(Code.VALIDATION_FAILED, "an authenticated platform actor is required");
        }
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new ResizeException(Code.VALIDATION_FAILED, "reason 1..1000 characters");
        }
        if (partitionCount < 0 || partitionCount > slots) {
            throw new ResizeException(Code.VALIDATION_FAILED, "partitionCount must be 0.." + slots + ": " + partitionCount);
        }
        Optional<Resize> existing = find(c, resizeId);
        if (existing.isPresent()) {
            if (existing.get().toCount() != partitionCount) {
                throw new ResizeException(Code.INVALID_TRANSITION, "resize id " + resizeId + " was used for "
                        + existing.get().toCount() + " partitions");
            }
            return existing.get();
        }
        long[] map = Jdbc.one(c, "SELECT current_map_epoch, CASE WHEN state = 'STABLE' AND target_map_epoch IS NULL"
                        + " THEN 1 ELSE 0 END FROM EXEC_MAP WHERE cluster_id = ?",
                rs -> new long[]{rs.getLong(1), rs.getInt(2)}, clusterId).orElseThrow(() ->
                new ResizeException(Code.NOT_INITIALISED, "no executor map for cluster " + clusterId
                        + " (sangam.engine.executor-partitioning is off on every node)"));
        long m = map[0];
        if (expectedMapEpoch != null && expectedMapEpoch != m) {
            throw new ResizeException(Code.REVISION_CONFLICT, "expected map epoch " + expectedMapEpoch + ", current " + m);
        }
        if (map[1] == 0) {
            throw new ResizeException(Code.INVALID_TRANSITION, "another resize is in progress");
        }
        int[] current = assignment(c, m);
        int fromCount = PartitionAllocation.partitionCount(current);
        if (fromCount == partitionCount) {
            throw new ResizeException(Code.INVALID_TRANSITION, "the partition count is already " + partitionCount);
        }
        if (Jdbc.update(c, "UPDATE EXEC_MAP SET state = 'TRANSITION', target_map_epoch = ? WHERE cluster_id = ?"
                + " AND current_map_epoch = ? AND state = 'STABLE' AND target_map_epoch IS NULL", m + 1, clusterId, m) != 1) {
            throw new ResizeException(Code.INVALID_TRANSITION, "another resize started concurrently");
        }
        Instant now = Jdbc.dbNow(c, d);
        Jdbc.update(c, "INSERT INTO EXEC_RESIZE (resize_id, cluster_id, state, from_count, to_count, from_map_epoch,"
                        + " to_map_epoch, algorithm_version, actor_id, reason, requested_at) VALUES (?, ?, 'REQUESTED', ?, ?,"
                        + " ?, ?, ?, ?, ?, ?)", resizeId, clusterId, fromCount, partitionCount, m, m + 1,
                PartitionAllocation.ALGORITHM_VERSION, actor, reason, now);
        Map<String, Object> p = payload(resizeId, actor, reason, fromCount, partitionCount, m, m + 1);
        audit.append(c, auditScope, root(), RESIZE_REQUESTED, p);
        return find(c, resizeId).orElseThrow();
    }

    // ------------------------------------------------------------------ advance (coordinator)

    /**
     * One coordinator step of the open resize, if any. Every write is fenced by {@code coordinatorName} and
     * {@code coordinatorEpoch}; a deposed coordinator's step changes nothing.
     *
     * @return the resize's state after the step, or empty when none is open
     */
    public Optional<State> advance(String coordinatorName, long coordinatorEpoch, Duration drainTimeout,
                                   Duration minSettle, java.util.function.Supplier<Instant> engineClock) {
        Optional<Resize> open = tx(c -> openResize(c));
        if (open.isEmpty()) {
            return Optional.empty();
        }
        Resize r = open.get();
        if (r.state() == State.REQUESTED) {
            if (!tx(c -> start(c, r, coordinatorName, coordinatorEpoch))) {
                return Optional.of(State.REQUESTED);
            }
        }
        Resize started = tx(c -> find(c, r.id())).orElseThrow();
        if (started.state() == State.FAILED) {
            return Optional.of(State.FAILED);
        }
        markMoving(started, coordinatorName, coordinatorEpoch);
        settleMoving(started, coordinatorName, coordinatorEpoch, drainTimeout, minSettle, engineClock);
        return Optional.of(tx(c -> commitIfDone(c, started, coordinatorName, coordinatorEpoch)));
    }

    /** REQUESTED -> IN_PROGRESS: plan, store it, create the target partitions. */
    private boolean start(Connection c, Resize r, String coordinator, long epoch) {
        if (!fenced(c, coordinator, epoch)) {
            return false;
        }
        int[] current = assignment(c, r.fromMapEpoch());
        PartitionAllocation.Plan plan;
        try {
            plan = PartitionAllocation.plan(current, r.toCount());
        } catch (RuntimeException e) {
            fail(c, r, "plan: " + e.getMessage());
            return false;
        }
        List<PartitionAllocation.Move> moves = plan.ranges(current);
        Instant now = Jdbc.dbNow(c, d);
        for (int p = 1; p <= r.toCount(); p++) { // target partitions exist and can be claimed
            if (Jdbc.update(c, "UPDATE EXEC_PARTITION SET state = 'UNOWNED', owner_node = NULL, owner_incarnation = NULL,"
                    + " lease_expires_at = NULL, drain_deadline = NULL, map_epoch = ? WHERE partition_id = ?"
                    + " AND state IN ('RETIRED', 'SHARED')", r.toMapEpoch(), p) == 0) {
                Jdbc.insertIfAbsent(c, d, "INSERT INTO EXEC_PARTITION (partition_id, owner_epoch, state, map_epoch)"
                        + " VALUES (?, 0, 'UNOWNED', ?)", p, r.toMapEpoch());
            }
        }
        if (r.toCount() == 0 && Jdbc.update(c, "UPDATE EXEC_PARTITION SET state = 'SHARED', owner_node = NULL,"
                + " owner_incarnation = NULL, lease_expires_at = NULL, drain_deadline = NULL, map_epoch = ?"
                + " WHERE partition_id = 0", r.toMapEpoch()) == 0) {
            Jdbc.update(c, "INSERT INTO EXEC_PARTITION (partition_id, owner_epoch, state, map_epoch) VALUES (0, 0,"
                    + " 'SHARED', ?)", r.toMapEpoch());
        }
        return Jdbc.update(c, "UPDATE EXEC_RESIZE SET state = 'IN_PROGRESS', moved_slot_count = ?, moved_slots = ?,"
                        + " started_at = ? WHERE resize_id = ? AND state = 'REQUESTED'", plan.moved().length,
                PartitionAllocation.encode(moves), now, r.id()) == 1;
    }

    /** Moving slots of the stored plan go MOVING at M+1; slots that stay go STABLE at M+1. Batches, idempotent. */
    private void markMoving(Resize r, String coordinator, long epoch) {
        List<Object[]> rows = new ArrayList<>();
        for (PartitionAllocation.Move m : r.movedSlots()) {
            for (int s = m.first(); s <= m.last(); s++) {
                rows.add(new Object[]{m.toPartition(), r.toMapEpoch(), s, r.fromMapEpoch(), m.fromPartition()});
            }
        }
        for (int i = 0; i < rows.size(); i += batch) {
            List<Object[]> part = rows.subList(i, Math.min(rows.size(), i + batch));
            boolean ok = tx(c -> {
                if (!fenced(c, coordinator, epoch)) {
                    return false;
                }
                for (Object[] row : part) {
                    Jdbc.update(c, "UPDATE EXEC_SLOT SET state = 'MOVING', from_partition = partition_id, partition_id = ?,"
                            + " move_started_at = " + d.now() + ", map_epoch = ? WHERE slot = ? AND map_epoch = ?"
                            + " AND partition_id = ?", row);
                }
                return true;
            });
            if (!ok) {
                return;
            }
        }
        tx(c -> fenced(c, coordinator, epoch) ? Jdbc.update(c, "UPDATE EXEC_SLOT SET map_epoch = ? WHERE map_epoch = ?"
                + " AND state = 'STABLE'", r.toMapEpoch(), r.fromMapEpoch()) : 0);
    }

    /** MOVING slots become STABLE when drained, or after drainTimeout with recovery of the source's locked jobs. */
    private void settleMoving(Resize r, String coordinator, long epoch, Duration drainTimeout, Duration minSettle,
                              java.util.function.Supplier<Instant> engineClock) {
        int[] cursor = {-1}; // pages by slot id, so a busy slot never hides the ones after it
        while (true) {
            int seen = tx(c -> {
                if (!fenced(c, coordinator, epoch)) {
                    return 0;
                }
                java.sql.Timestamp jobNow = java.sql.Timestamp.from(engineClock.get());
                Instant dbNow = Jdbc.dbNow(c, d);
                List<Object[]> moving = Jdbc.query(c, d.skipLockedSelect("es.slot, es.from_partition, es.move_started_at",
                                "EXEC_SLOT", "es", "es.state = 'MOVING' AND es.map_epoch = " + r.toMapEpoch() + " AND es.slot > "
                                        + cursor[0], "es.slot", batch),
                        rs -> new Object[]{rs.getInt(1), rs.getInt(2), Jdbc.instant(rs, "move_started_at")});
                int n = 0;
                long pause = 0;
                boolean measured = false; // a pause of 0 ms is a measurement too, never left NULL
                for (Object[] m : moving) {
                    int slot = (Integer) m[0];
                    cursor[0] = Math.max(cursor[0], slot);
                    int from = (Integer) m[1];
                    Instant started = (Instant) m[2];
                    boolean overdue = started != null && !started.plus(drainTimeout).isAfter(dbNow);
                    long busy = Jdbc.one(c, "SELECT COUNT(*) FROM EXEC_JOB_SLOT s, ACT_RU_JOB j WHERE s.slot = ?"
                            + " AND s.owner_partition = ? AND j.ID_ = s.job_id AND j.LOCK_OWNER_ IS NOT NULL"
                            + " AND j.LOCK_EXP_TIME_ > ?", rs -> rs.getLong(1), slot, from, jobNow).orElse(0L);
                    if (busy > 0 && !overdue) {
                        continue;
                    }
                    if (started == null || started.plus(minSettle).isAfter(dbNow)) {
                        continue; // one full coordinator cycle first: an acquisition that read the slot as STABLE commits
                    }
                    if (busy > 0) { // X.2.1.7 step 5(b): recovery filtered by slot; retries are not touched
                        List<String> jobs = Jdbc.query(c, "SELECT s.job_id FROM EXEC_JOB_SLOT s, ACT_RU_JOB j WHERE s.slot = ?"
                                + " AND s.owner_partition = ? AND j.ID_ = s.job_id AND j.LOCK_OWNER_ IS NOT NULL",
                                rs -> rs.getString(1), slot, from);
                        for (String job : jobs) {
                            Jdbc.update(c, "UPDATE EXEC_JOB_SLOT SET owner_partition = NULL, owner_epoch = NULL,"
                                    + " recoveries = recoveries + 1 WHERE job_id = ?", job);
                            Jdbc.update(c, "UPDATE ACT_RU_JOB SET LOCK_OWNER_ = NULL, LOCK_EXP_TIME_ = NULL, REV_ = REV_ + 1"
                                    + " WHERE ID_ = ? AND LOCK_OWNER_ IS NOT NULL", job);
                        }
                        LOG.warn("decision 146: slot {} of resize {} recovered {} job(s) after the drain timeout", slot,
                                r.id(), jobs.size());
                    }
                    if (Jdbc.update(c, "UPDATE EXEC_SLOT SET state = 'STABLE', from_partition = NULL, move_started_at = NULL"
                            + " WHERE slot = ? AND state = 'MOVING' AND map_epoch = ?", slot, r.toMapEpoch()) == 1) {
                        n++;
                        if (started != null) {
                            pause = Math.max(pause, Duration.between(started, dbNow).toMillis());
                            measured = true;
                        }
                    }
                }
                if (measured) {
                    Jdbc.update(c, "UPDATE EXEC_RESIZE SET handover_pause_ms = CASE WHEN handover_pause_ms IS NULL"
                            + " OR handover_pause_ms < ? THEN ? ELSE handover_pause_ms END WHERE resize_id = ?",
                            pause, pause, r.id());
                }
                return moving.size();
            });
            if (seen < batch) {
                return;
            }
        }
    }

    /** Commits M+1 when every slot is STABLE at M+1. */
    private State commitIfDone(Connection c, Resize r, String coordinator, long epoch) {
        if (!fenced(c, coordinator, epoch)) {
            return r.state();
        }
        long notDone = Jdbc.one(c, "SELECT COUNT(*) FROM EXEC_SLOT WHERE state <> 'STABLE' OR map_epoch <> ?",
                rs -> rs.getLong(1), r.toMapEpoch()).orElse(0L);
        if (notDone > 0) {
            return State.IN_PROGRESS;
        }
        long total = Jdbc.one(c, "SELECT COUNT(*) FROM EXEC_SLOT", rs -> rs.getLong(1)).orElse(0L);
        if (total != slots) { // invariant I-1
            fail(c, r, "EXEC_SLOT has " + total + " rows, expected " + slots + " (invariant I-1)");
            return State.FAILED;
        }
        if (Jdbc.update(c, "UPDATE EXEC_MAP SET current_map_epoch = ?, target_map_epoch = NULL, state = 'STABLE'"
                + " WHERE cluster_id = ? AND state = 'TRANSITION' AND target_map_epoch = ?", r.toMapEpoch(), clusterId,
                r.toMapEpoch()) != 1) {
            return State.IN_PROGRESS;
        }
        Jdbc.update(c, "UPDATE EXEC_PARTITION SET state = 'RETIRED', owner_node = NULL, owner_incarnation = NULL,"
                + " lease_expires_at = NULL, drain_deadline = NULL WHERE state <> 'RETIRED' AND NOT EXISTS"
                + " (SELECT 1 FROM EXEC_SLOT es WHERE es.partition_id = EXEC_PARTITION.partition_id)");
        Jdbc.update(c, "UPDATE EXEC_PARTITION SET map_epoch = ? WHERE state <> 'RETIRED'", r.toMapEpoch());
        Instant now = Jdbc.dbNow(c, d);
        Jdbc.update(c, "UPDATE EXEC_RESIZE SET state = 'COMPLETED', completed_at = ? WHERE resize_id = ?"
                + " AND state = 'IN_PROGRESS'", now, r.id());
        Resize done = find(c, r.id()).orElseThrow();
        Map<String, Object> p = payload(r.id(), r.actor(), r.reason(), r.fromCount(), r.toCount(), r.fromMapEpoch(),
                r.toMapEpoch());
        p.put("algorithmVersion", r.algorithmVersion());
        p.put("movedSlotCount", done.movedSlotCount());
        p.put("movedSlots", PartitionAllocation.encode(done.movedSlots()));
        p.put("handoverPauseMs", done.handoverPauseMs());
        p.put("outcome", "COMPLETED");
        audit.append(c, auditScope, root(), COUNT_CHANGED, p);
        LOG.info("decision 146: executor map '{}' committed epoch {} with {} partitions ({} slots moved)", clusterId,
                r.toMapEpoch(), r.toCount(), done.movedSlotCount());
        return State.COMPLETED;
    }

    /** Only before any slot moved: the previous map stays committed. */
    private void fail(Connection c, Resize r, String why) {
        Jdbc.update(c, "UPDATE EXEC_RESIZE SET state = 'FAILED', failure = ?, completed_at = ? WHERE resize_id = ?",
                why, Jdbc.dbNow(c, d), r.id());
        if (r.state() == State.REQUESTED) {
            Jdbc.update(c, "UPDATE EXEC_MAP SET state = 'STABLE', target_map_epoch = NULL WHERE cluster_id = ?"
                    + " AND state = 'TRANSITION' AND target_map_epoch = ?", clusterId, r.toMapEpoch());
        }
        Map<String, Object> p = payload(r.id(), r.actor(), r.reason(), r.fromCount(), r.toCount(), r.fromMapEpoch(),
                r.toMapEpoch());
        p.put("failure", why);
        p.put("outcome", "FAILED");
        audit.append(c, auditScope, root(), RESIZE_FAILED, p);
        LOG.error("decision 146: resize {} failed: {}", r.id(), why);
    }

    // ------------------------------------------------------------------ views

    public Optional<Resize> get(String resizeId) {
        return tx(c -> find(c, resizeId));
    }

    /** Newest first; {@code movedSlots} is empty in the list (contract). */
    public List<Resize> list(State state) {
        return tx(c -> Jdbc.query(c, "SELECT " + COLUMNS + " FROM EXEC_RESIZE WHERE cluster_id = ?"
                        + (state == null ? "" : " AND state = '" + state.name() + "'") + " ORDER BY requested_at DESC, resize_id",
                rs -> read(rs, false), clusterId));
    }

    /** An inclusive range of logical slot ids (contract {@code ExecutorSlotRange}). */
    public record SlotRange(int first, int last) {
    }

    /**
     * One {@code EXEC_PARTITION} row and the slots it owns in the committed map (contract {@code ExecutorPartitionView}).
     * {@code ownerNode} and {@code leaseExpiresAt} are {@code null} without an owner.
     */
    public record PartitionView(int partitionId, String state, String ownerNode, long ownerEpoch, Instant leaseExpiresAt,
                                List<SlotRange> slots) {
    }

    /**
     * The committed executor map (contract {@code ExecutorPartitionMap}): {@code targetMapEpoch} and
     * {@code activeResize} are {@code null} when none. {@code algorithmVersion} is the allocation that produced the
     * committed map: the newest {@code COMPLETED} resize's, or {@link #BOOTSTRAP_ALGORITHM} for a map never resized.
     */
    public record PartitionMap(String clusterId, String state, long currentMapEpoch, Long targetMapEpoch,
                               int partitionCount, int slotCount, String algorithmVersion, List<PartitionView> partitions,
                               Resize activeResize) {
    }

    /** The bootstrap allocation (X.2.1.7 step 1, {@link PartitionLeaseManager#partitionOfSlot}): contiguous balanced ranges. */
    public static final String BOOTSTRAP_ALGORITHM = "CONTIGUOUS-1";

    /**
     * Decision 146 map view for {@code GET /admin/executor/partitions}, read in one transaction. Every slot is shown
     * under its owner in the committed map {@code M}: while a resize runs, a slot of the stored plan stays under its
     * {@code fromPartition} until {@code M+1} commits, whatever its progress (per-slot progress is not part of the
     * view); slots outside the plan never change partition. All {@code EXEC_PARTITION} rows are listed, sorted by id,
     * including {@code RETIRED} ones and target partitions that own no committed slot yet (empty {@code slots}).
     *
     * @throws ResizeException {@link Code#NOT_INITIALISED} when the cluster has no executor map
     */
    public PartitionMap map() {
        return tx(c -> {
            Object[] m = Jdbc.one(c, "SELECT state, current_map_epoch, target_map_epoch FROM EXEC_MAP WHERE cluster_id = ?",
                    rs -> {
                        long target = rs.getLong(3);
                        boolean noTarget = rs.wasNull(); // of column 3: asked before another column is read
                        return new Object[]{rs.getString(1), rs.getLong(2), noTarget ? null : target};
                    }, clusterId).orElseThrow(() -> new ResizeException(Code.NOT_INITIALISED, "no executor map for cluster "
                    + clusterId + " (sangam.engine.executor-partitioning is off on every node)"));
            Resize active = openResize(c).orElse(null);
            int[] committed = committedOf(c, active);
            // the newest COMPLETED resize only: every completed resize of the cluster keeps its row, and Jdbc.one
            // refuses more than one (the view failed from the second completed resize on)
            String algorithm = Jdbc.one(c, "SELECT algorithm_version FROM EXEC_RESIZE WHERE cluster_id = ? AND state ="
                    + " 'COMPLETED' AND to_map_epoch = (SELECT MAX(to_map_epoch) FROM EXEC_RESIZE WHERE cluster_id = ?"
                    + " AND state = 'COMPLETED')", rs -> rs.getString(1), clusterId, clusterId).orElse(BOOTSTRAP_ALGORITHM);
            Map<Integer, List<SlotRange>> ranges = new LinkedHashMap<>();
            for (int s = 0; s < committed.length; s++) {
                List<SlotRange> own = ranges.computeIfAbsent(committed[s], k -> new ArrayList<>());
                SlotRange last = own.isEmpty() ? null : own.get(own.size() - 1);
                if (last != null && last.last() == s - 1) {
                    own.set(own.size() - 1, new SlotRange(last.first(), s));
                } else {
                    own.add(new SlotRange(s, s));
                }
            }
            List<PartitionView> partitions = Jdbc.query(c, "SELECT partition_id, state, owner_node, owner_epoch,"
                            + " lease_expires_at FROM EXEC_PARTITION ORDER BY partition_id",
                    rs -> new PartitionView(rs.getInt("partition_id"), rs.getString("state"), rs.getString("owner_node"),
                            rs.getLong("owner_epoch"), Jdbc.instant(rs, "lease_expires_at"),
                            List.copyOf(ranges.getOrDefault(rs.getInt("partition_id"), List.of()))));
            return new PartitionMap(clusterId, (String) m[0], (Long) m[1], (Long) m[2],
                    PartitionAllocation.partitionCount(committed), slots, algorithm, List.copyOf(partitions), active);
        });
    }

    /**
     * {@code slot -> partition} of the committed map: each slot's current partition, except that a slot of the open
     * resize's stored plan is shown under its {@code fromPartition} (it belongs to the target only once M+1 commits).
     */
    private int[] committedOf(Connection c, Resize active) {
        int[] out = new int[slots];
        java.util.Arrays.fill(out, -1);
        for (int[] r : Jdbc.query(c, "SELECT slot, partition_id FROM EXEC_SLOT", rs -> new int[]{rs.getInt(1), rs.getInt(2)})) {
            if (r[0] < 0 || r[0] >= slots) {
                throw new IllegalStateException("decision 146: slot " + r[0] + " outside 0.." + (slots - 1));
            }
            out[r[0]] = r[1];
        }
        if (active != null) {
            for (PartitionAllocation.Move mv : active.movedSlots()) {
                for (int s = mv.first(); s <= mv.last(); s++) {
                    out[s] = mv.fromPartition();
                }
            }
        }
        for (int s = 0; s < slots; s++) {
            if (out[s] < 0) {
                throw new IllegalStateException("decision 146: slot " + s + " has no EXEC_SLOT row (invariant I-1)");
            }
        }
        return out;
    }

    /** The committed assignment {@code slot -> partition} (index = slot). */
    public int[] committedAssignment() {
        return tx(c -> {
            long m = Jdbc.one(c, "SELECT current_map_epoch FROM EXEC_MAP WHERE cluster_id = ?", rs -> rs.getLong(1),
                    clusterId).orElseThrow(() -> new ResizeException(Code.NOT_INITIALISED, "no executor map"));
            return assignment(c, m);
        });
    }

    // ------------------------------------------------------------------ plumbing

    private static final String COLUMNS = "resize_id, state, from_count, to_count, from_map_epoch, to_map_epoch,"
            + " algorithm_version, moved_slot_count, moved_slots, actor_id, reason, requested_at, started_at, completed_at,"
            + " failure, handover_pause_ms";

    private Optional<Resize> find(Connection c, String id) {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM EXEC_RESIZE WHERE resize_id = ? AND cluster_id = ?",
                rs -> read(rs, true), id, clusterId);
    }

    private Optional<Resize> openResize(Connection c) {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM EXEC_RESIZE WHERE cluster_id = ? AND state IN ('REQUESTED',"
                + " 'IN_PROGRESS') ORDER BY requested_at", rs -> read(rs, true), clusterId);
    }

    private static Resize read(java.sql.ResultSet rs, boolean withMoves) throws SQLException {
        int moved = rs.getInt("moved_slot_count");
        Integer movedCount = rs.wasNull() ? null : moved;
        long pause = rs.getLong("handover_pause_ms");
        Long pauseMs = rs.wasNull() ? null : pause;
        return new Resize(rs.getString("resize_id"), State.valueOf(rs.getString("state")), rs.getInt("from_count"),
                rs.getInt("to_count"), rs.getLong("from_map_epoch"), rs.getLong("to_map_epoch"),
                rs.getString("algorithm_version"), movedCount,
                withMoves ? PartitionAllocation.decode(rs.getString("moved_slots")) : List.of(), rs.getString("actor_id"),
                rs.getString("reason"), Jdbc.instant(rs, "requested_at"), Jdbc.instant(rs, "started_at"),
                Jdbc.instant(rs, "completed_at"), rs.getString("failure"), pauseMs);
    }

    /** {@code slot -> partition} of the slots at map epoch {@code m}; every slot must be there (I-1). */
    private int[] assignment(Connection c, long m) {
        int[] out = new int[slots];
        java.util.Arrays.fill(out, -1);
        List<int[]> rows = Jdbc.query(c, "SELECT slot, partition_id FROM EXEC_SLOT WHERE map_epoch = ? AND state = 'STABLE'",
                rs -> new int[]{rs.getInt(1), rs.getInt(2)}, m);
        for (int[] r : rows) {
            if (r[0] < 0 || r[0] >= slots) {
                throw new IllegalStateException("decision 146: slot " + r[0] + " outside 0.." + (slots - 1));
            }
            out[r[0]] = r[1];
        }
        for (int s = 0; s < slots; s++) {
            if (out[s] < 0) {
                throw new IllegalStateException("decision 146: slot " + s + " is not STABLE at map epoch " + m
                        + " (invariant I-1)");
            }
        }
        return out;
    }

    private boolean fenced(Connection c, String coordinator, long epoch) {
        return Jdbc.one(c, "SELECT 1 FROM EXEC_MAP WHERE cluster_id = ? AND coordinator_node = ? AND coordinator_epoch = ?"
                + " AND coordinator_expires_at > " + d.now(), rs -> rs.getInt(1), clusterId, coordinator, epoch).isPresent();
    }

    private Map<String, Object> payload(String id, String actor, String reason, int from, int to, long fromEpoch,
                                        long toEpoch) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("clusterId", clusterId);
        p.put("resizeId", id);
        p.put("actor", actor);
        p.put("reason", reason);
        p.put("fromPartitionCount", from);
        p.put("toPartitionCount", to);
        p.put("fromMapEpoch", fromEpoch);
        p.put("toMapEpoch", toEpoch);
        return p;
    }

    private String root() {
        return "executor:" + clusterId;
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
            throw new IllegalStateException("decision 146 partition resize: " + ex.getMessage(), ex);
        }
    }
}
