// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.retention;

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

    public HierarchyRetention(Transactions tx, AuditChain audit) {
        this.tx = tx;
        this.audit = audit;
    }

    /** {@code retentionByKey}: definition key to retention; a root key with no entry is kept. */
    public List<Tree> plan(String tenantId, Map<String, Duration> retentionByKey, Instant now, Predicate<String> onHold, int limit) {
        return tx.inTransaction(c -> {
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

    /** Scheduled runs have no human reason; they are recorded as such. */
    public int execute(List<Tree> plan, Purger purger, String actor) {
        return execute(plan, purger, actor, "scheduled retention run");
    }

    /**
     * Purges the plan and records one {@code OPS_RETENTION_RUN} row per tenant: plan id, SHA-256 of the canonical plan, reason,
     * purged count and executor. A row is written even when the purger fails part-way, with the count purged so far, and the
     * failure is rethrown.
     */
    public int execute(List<Tree> plan, Purger purger, String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required");
        }
        String planId = java.util.UUID.randomUUID().toString();
        java.util.Map<String, List<Tree>> byTenant = new java.util.LinkedHashMap<>();
        plan.forEach(t -> byTenant.computeIfAbsent(t.tenantId(), k -> new java.util.ArrayList<>()).add(t));
        int total = 0;
        for (Map.Entry<String, List<Tree>> e : byTenant.entrySet()) {
            int n = 0;
            RuntimeException failure = null;
            try {
                for (Tree t : e.getValue()) {
                    purger.purge(t.tenantId(), t.instanceIds());
                    tx.inTransaction(c -> audit.append(c, t.tenantId(), "retention:" + t.tenantId(), "HISTORY_TREE_PURGED", Map.of("root", t.rootId(), "definition", t.definitionKey(),
                            "instances", t.instanceIds().size(), "lastEnd", t.lastEnd().toString(), "actor", actor, "planId", planId)));
                    n += t.instanceIds().size();
                }
            } catch (RuntimeException ex) {
                failure = ex;
            }
            int purged = n;
            String recorded = failure == null ? reason : reason + " (failed: " + failure.getMessage() + ")";
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

    /** One recorded retention run. */
    public record Run(String runId, String tenantId, String planId, String planHash, String reason, int purged, String executedBy, Instant executedAt) {
    }

    public List<Run> runs(String tenantId) {
        return tx.inTransaction(c -> org.sangam.core.layer.Jdbc.query(c, "SELECT run_id,tenant_id,plan_id,plan_hash,reason,purged,executed_by,executed_at FROM OPS_RETENTION_RUN WHERE tenant_id=? ORDER BY executed_at DESC",
                rs -> new Run(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7),
                        org.sangam.core.layer.Jdbc.instant(rs, "executed_at")), tenantId));
    }
}
