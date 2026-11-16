// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.archive;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.sangam.core.history.HistoryArchiveGate;

/**
 * RM-52, decision 386(3): a history partition (IR-8: the {@code ACT_HI_*} tables range-partitioned by
 * {@code REMOVAL_TIME_}) removes rows by time, not by tree, so it may be dropped only when every tree with a row in its
 * range has a verified archive; otherwise the partition is kept and the trees without one are reported. The schema
 * has no automatic drop (the maintenance routines only add partitions), so every drop, scripted or by an operator, asks
 * {@link #check} first and drops only on {@link Verdict#allowed()}.
 */
public final class PartitionDropGuard {

    /** The partitioned history tables (sangam-persistence {@code history-partitioning/*.sql}). */
    public static final List<String> PARTITIONED = List.of("ACT_HI_ACTINST", "ACT_HI_TASKINST", "ACT_HI_VARINST",
            "ACT_HI_DETAIL", "ACT_HI_IDENTITYLINK", "ACT_HI_OP_LOG", "ACT_HI_INCIDENT", "ACT_HI_JOB_LOG",
            "ACT_HI_EXT_TASK_LOG");

    /** The guard's answer for one partition range. */
    public record Verdict(Instant from, Instant to, int trees, Map<String, String> refused) {
        public boolean allowed() {
            return refused.isEmpty();
        }
    }

    private final HistoryArchiveGate gate;
    private final String prefix;

    public PartitionDropGuard(HistoryArchiveGate gate, String tablePrefix) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.prefix = tablePrefix == null ? "" : tablePrefix;
    }

    /**
     * The trees with a row of a partitioned table whose removal time is in {@code [from, to)}, each checked by the
     * gate. Rows without a root (a job log of no process) belong to no tree and are not checked.
     */
    public Verdict check(Connection c, Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("a partition range needs from < to");
        }
        Map<String, String> tenantOfRoot = new TreeMap<>();
        try {
            for (String t : PARTITIONED) {
                if (HistoryArchiveExporter.columns(c, prefix + t).isEmpty()) {
                    continue;
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT h.ROOT_PROC_INST_ID_, h.TENANT_ID_ FROM "
                        + prefix + t + " h WHERE h.REMOVAL_TIME_ >= ? AND h.REMOVAL_TIME_ < ?"
                        + " AND h.ROOT_PROC_INST_ID_ IS NOT NULL")) {
                    ps.setTimestamp(1, Timestamp.from(from));
                    ps.setTimestamp(2, Timestamp.from(to));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            tenantOfRoot.putIfAbsent(rs.getString(1), rs.getString(2));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("partition range " + from + ".." + to + " could not be read", e);
        }
        Map<String, String> refused = new LinkedHashMap<>();
        for (Map.Entry<String, String> root : tenantOfRoot.entrySet()) {
            String r = gate.refusal(c, root.getValue(), root.getKey());
            if (r != null) {
                refused.put(root.getKey(), r);
            }
        }
        return new Verdict(from, to, tenantOfRoot.size(), Map.copyOf(refused));
    }

    /** Roots refused in a verdict, for a report. */
    public static List<String> refusedRoots(Verdict v) {
        return new ArrayList<>(new TreeMap<>(v.refused()).keySet());
    }
}
