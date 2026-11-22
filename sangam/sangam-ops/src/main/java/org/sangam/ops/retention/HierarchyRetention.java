// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.retention;

import org.sangam.core.history.HistoryArchiveGate;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Hierarchy-aware history retention: a process tree is one unit. A tree is purgeable when every instance in it has ended, its last end
 * time is older than the root definition's retention, and neither the root nor any legal hold prevents it. So a child never disappears
 * before its root, and a child with a shorter retention follows the root. Read from {@code ACT_HI_PROCINST}. Purging runs through the
 * {@link Purger} port (the engine's history service), children first. Records retention (A-9) goes through {@code onHold}.
 * RM-52 (decision 386(2)): a tree without a verified archive is never purged ({@link HistoryArchiveGate}).
 */
public final class HierarchyRetention {

    public record Tree(String rootId, String tenantId, String definitionKey, Instant lastEnd, List<String> instanceIds) {
    }

    @FunctionalInterface
    public interface Purger {
        void purge(String tenantId, List<String> childrenFirst);
    }

    private final Transactions tx;
    private final AuditChain audit;
    private final HistoryArchiveGate gate;

    /**
     * @param gate RM-52 (decision 386(2)): a tree is purged only when this gate finds its archive verified; a node with
     *             no archive configured passes {@link HistoryArchiveGate#notConfigured()} and purges nothing
     */
    public HierarchyRetention(Transactions tx, AuditChain audit, HistoryArchiveGate gate) {
        this.tx = tx;
        this.audit = audit;
        this.gate = java.util.Objects.requireNonNull(gate, "gate");
    }

    /** {@code retentionByKey}: definition key to retention; a root key with no entry is kept. */
    public List<Tree> plan(String tenantId, Map<String, Duration> retentionByKey, Instant now, Predicate<String> onHold, int limit) {
        return tx.read(c -> {
            List<Tree> out = new ArrayList<>();
            List<String[]> roots = Jdbc.query(c, "SELECT r.ID_, r.PROC_DEF_KEY_ FROM ACT_HI_PROCINST r WHERE r.TENANT_ID_=? AND r.ID_=r.ROOT_PROC_INST_ID_ AND r.END_TIME_ IS NOT NULL ORDER BY r.END_TIME_",
                    rs -> new String[]{rs.getString(1), rs.getString(2)}, tenantId);
            for (String[] root : roots) {
                if (out.size() >= limit) {
                    break;
                }
                Duration keep = retentionByKey.get(root[1]);
                if (keep == null || onHold.test(root[0])) {
                    continue;
                }
                record Member(String id, Instant end) {
                }
                List<Member> tree = Jdbc.query(c, "SELECT ID_, END_TIME_ FROM ACT_HI_PROCINST WHERE ROOT_PROC_INST_ID_=? ORDER BY START_TIME_ DESC", rs -> new Member(rs.getString(1), Jdbc.instant(rs, "END_TIME_")), root[0]);
                if (tree.stream().anyMatch(m -> m.end() == null || onHold.test(m.id()))) {
                    continue;
                }
                Instant last = tree.stream().map(Member::end).max(Instant::compareTo).orElseThrow();
                if (last.plus(keep).isBefore(now)) {
                    // Latest start first: a child starts after its parent, so children precede parents.
                    out.add(new Tree(root[0], tenantId, root[1], last, tree.stream().map(Member::id).toList()));
                }
            }
            return out;
        });
    }

    /** Exports the archive of a tree before it is purged (RM-52), in the given transaction. */
    @FunctionalInterface
    public interface Archiver {
        void archive(java.sql.Connection c, Tree tree);
    }

    /** Scheduled runs have no human reason; they are recorded as such. */
    public int execute(List<Tree> plan, Purger purger, String actor) {
        return execute(plan, null, purger, actor, "scheduled retention run");
    }

    public int execute(List<Tree> plan, Purger purger, String actor, String reason) {
        return execute(plan, null, purger, actor, reason);
    }

    /**
     * Purges the plan and records one {@code OPS_RETENTION_RUN} row per tenant: plan id, SHA-256 of the canonical plan, reason,
     * purged count and executor. A row is written even when the purger fails part-way, with the count purged so far, and the
     * failure is rethrown.
     *
     * <p>RM-52 (decision 386): before a tree is purged its archive is exported ({@code archiver}, when given) and the
     * {@link HistoryArchiveGate} must find it verified. A tree it refuses (or whose export fails) is not purged: the refusal
     * is audited ({@code HISTORY_PURGE_REFUSED} on {@code retention:<tenant>}), counted in the run's reason, and the run
     * goes on with the next tree. History without a verified archive is kept, never lost.
     */
    public int execute(List<Tree> plan, Archiver archiver, Purger purger, String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required");
        }
        String planId = java.util.UUID.randomUUID().toString();
        java.util.Map<String, List<Tree>> byTenant = new java.util.LinkedHashMap<>();
        plan.forEach(t -> byTenant.computeIfAbsent(t.tenantId(), k -> new java.util.ArrayList<>()).add(t));
        int total = 0;
        for (Map.Entry<String, List<Tree>> e : byTenant.entrySet()) {
            int n = 0;
            int refused = 0;
            RuntimeException failure = null;
            try {
                for (Tree t : e.getValue()) {
                    String refusal = archived(t, archiver);
                    if (refusal != null) {
                        refused++;
                        tx.inTransaction(c -> audit.append(c, t.tenantId(), "retention:" + t.tenantId(), "HISTORY_PURGE_REFUSED",
                                Map.of("root", t.rootId(), "definition", t.definitionKey(), "refusal", refusal, "actor", actor,
                                        "planId", planId)));
                        continue;
                    }
                    purger.purge(t.tenantId(), t.instanceIds());
                    tx.inTransaction(c -> audit.append(c, t.tenantId(), "retention:" + t.tenantId(), "HISTORY_TREE_PURGED", Map.of("root", t.rootId(), "definition", t.definitionKey(),
                            "instances", t.instanceIds().size(), "lastEnd", t.lastEnd().toString(), "actor", actor, "planId", planId)));
                    n += t.instanceIds().size();
                }
            } catch (RuntimeException ex) {
                failure = ex;
            }
            int purged = n;
            String recorded = (failure == null ? reason : reason + " (failed: " + failure.getMessage() + ")")
                    + (refused == 0 ? "" : " (refused without a verified archive: " + refused + " trees)");
            String hash = org.sangam.core.layer.Hashes.hex(org.sangam.core.layer.Hashes.sha256(org.sangam.core.layer.CanonicalJson.write(
                    e.getValue().stream().map(t -> Map.of("root", t.rootId(), "instances", t.instanceIds(), "lastEnd", t.lastEnd().toString())).toList())));
            tx.inTransaction(c -> org.sangam.core.layer.Jdbc.update(c, "INSERT INTO OPS_RETENTION_RUN(run_id,tenant_id,plan_id,plan_hash,reason,purged,executed_by,executed_at) VALUES (?,?,?,?,?,?,?,?)",
                    java.util.UUID.randomUUID().toString(), e.getKey(), planId, hash, recorded, purged, actor,
                    Instant.now()));
            total += n;
            if (failure != null) {
                throw failure;
            }
        }
        return total;
    }

    /** The gate's refusal of the tree after its archive was exported (when an archiver is given); null: purge. */
    private String archived(Tree t, Archiver archiver) {
        if (archiver != null) {
            try {
                tx.inTransaction(c -> {
                    archiver.archive(c, t);
                    return null;
                });
            } catch (RuntimeException ex) {
                return "HISTORY_ARCHIVE_EXPORT_FAILED: " + ex.getMessage();
            }
        }
        try {
            return tx.inTransaction(c -> gate.refusal(c, t.tenantId(), t.rootId()));
        } catch (RuntimeException ex) {
            return "HISTORY_ARCHIVE_UNVERIFIABLE: " + ex.getMessage();
        }
    }

    /** One recorded retention run. */
    public record Run(String runId, String tenantId, String planId, String planHash, String reason, int purged, String executedBy, Instant executedAt) {
    }

    public List<Run> runs(String tenantId) {
        return tx.read(c -> org.sangam.core.layer.Jdbc.query(c, "SELECT run_id,tenant_id,plan_id,plan_hash,reason,purged,executed_by,executed_at FROM OPS_RETENTION_RUN WHERE tenant_id=? ORDER BY executed_at DESC",
                rs -> new Run(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7),
                        org.sangam.core.layer.Jdbc.instant(rs, "executed_at")), tenantId));
    }
}
