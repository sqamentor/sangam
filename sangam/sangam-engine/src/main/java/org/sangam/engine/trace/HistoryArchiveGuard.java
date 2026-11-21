// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.db.DbEntity;
import org.operaton.bpm.engine.impl.db.entitymanager.operation.DbOperation;
import org.operaton.bpm.engine.impl.history.event.HistoricDecisionInstanceManager;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.Session;
import org.operaton.bpm.engine.impl.interceptor.SessionFactory;
import org.sangam.core.history.HistoryArchiveGate;
import org.sangam.core.layer.SqlIdentifier;
import org.sangam.engine.tx.CommandState;

/**
 * RM-52 (decision 386): the engine's own history deletion asks the {@link HistoryArchiveGate} first, for every process
 * tree it would remove rows of, and refuses the whole command when one has no verified archive
 * ({@link HistoryArchiveGate.PurgeRefusedException}): nothing is deleted and the history is kept. The removal-time
 * cleanup deletes each table by its own {@code REMOVAL_TIME_} in bulk, so it cannot leave one tree out; the job fails
 * (and is retried, then raises an incident) until the tree is archived.
 *
 * <p>Paths (javap, operaton-engine 2.2.0):
 * <ul>
 *   <li>{@link ProcessTraceHistoryRemoval#deleteHistoricProcessInstanceByIds}: {@code HistoryService} deletes, the
 *       deletion batch, the end-time cleanup and a process deployment's cascade delete;</li>
 *   <li>{@link ProcessTraceHistoryRemoval#deleteHistoricProcessInstancesByRemovalTime}: the removal-time cleanup of
 *       process history (every table with a root that is due, the minute range included);</li>
 *   <li>{@link Decisions}: the decision history of trees (rows with a root process instance): by ids (end-time
 *       cleanup), by removal time and by decision definition (a decision deployment's cascade delete). A standalone
 *       decision evaluation belongs to no tree and is not gated.</li>
 * </ul>
 */
public final class HistoryArchiveGuard {

    /** Tables with {@code ROOT_PROC_INST_ID_} and {@code REMOVAL_TIME_} that the removal-time cleanup deletes from. */
    static final List<String> PROCESS_TABLES = List.of("ACT_HI_PROCINST", "ACT_HI_ACTINST", "ACT_HI_TASKINST",
            "ACT_HI_VARINST", "ACT_HI_DETAIL", "ACT_HI_IDENTITYLINK", "ACT_HI_COMMENT", "ACT_HI_ATTACHMENT",
            "ACT_HI_OP_LOG", "ACT_HI_INCIDENT", "ACT_HI_JOB_LOG", "ACT_HI_EXT_TASK_LOG", "ACT_GE_BYTEARRAY");
    static final List<String> DECISION_TABLES = List.of("ACT_HI_DECINST", "ACT_HI_DEC_IN", "ACT_HI_DEC_OUT");

    private static final Map<String, Boolean> GATEABLE = new ConcurrentHashMap<>();

    private HistoryArchiveGuard() {
    }

    /** Asks the gate for each tree ({@code root -> tenant}); the first refusal throws. */
    static void require(HistoryArchiveGate gate, Connection c, Map<String, String> tenantByRoot) {
        for (Map.Entry<String, String> t : tenantByRoot.entrySet()) {
            String refusal = gate.refusal(c, t.getValue(), t.getKey());
            if (refusal != null) {
                throw new HistoryArchiveGate.PurgeRefusedException(t.getKey(), refusal);
            }
        }
    }

    /** The only columns {@link #treesOfDecisions} looks a decision instance up by. */
    static final java.util.Set<String> DECISION_COLUMNS = java.util.Set.of("ID_", "DEC_DEF_ID_");

    /** {@code root -> tenant} of the given process instances (an instance without a root is its own root). */
    static Map<String, String> treesOfInstances(Connection c, String prefix, Collection<String> ids) {
        String vetted = SqlIdentifier.tablePrefix(prefix);
        Map<String, String> out = new TreeMap<>();
        List<String> list = new ArrayList<>(ids);
        for (int i = 0; i < list.size(); i += ProcessTraceHistoryRemoval.IN_CHUNK) {
            List<String> chunk = list.subList(i, Math.min(list.size(), i + ProcessTraceHistoryRemoval.IN_CHUNK));
            // Identifiers cannot be bound: vetted is SqlIdentifier.tablePrefix; the ids are bind marks.
            // nosemgrep: sangam-jdbc-sql-concat
            String sql = "SELECT ID_, ROOT_PROC_INST_ID_, TENANT_ID_ FROM " + vetted + "ACT_HI_PROCINST WHERE ID_ IN ("
                    + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int k = 0; k < chunk.size(); k++) {
                    ps.setString(k + 1, chunk.get(k));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String root = rs.getString(2) == null ? rs.getString(1) : rs.getString(2);
                        out.putIfAbsent(root, rs.getString(3));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("RM-52: the trees of the instances to delete cannot be read", e);
            }
        }
        return out;
    }

    /**
     * {@code root -> tenant} of every row of {@code tables} that is due ({@code REMOVAL_TIME_ <= removalTime}, in the
     * minute range when the engine filters by one): a superset of what the cleanup run deletes.
     */
    static Map<String, String> treesDue(Connection c, ProcessEngineConfigurationImpl cfg, List<String> tables,
                                        Date removalTime, int minuteFrom, int minuteTo) {
        String prefix = prefix(cfg);
        String minute = ProcessTraceHistoryRemoval.minuteFilter(cfg.getDatabaseType(), minuteFrom, minuteTo);
        Map<String, String> out = new TreeMap<>();
        for (String t : tables) {
            if (!gateable(c, prefix + t)) {
                continue;
            }
            String sql = "SELECT DISTINCT h.ROOT_PROC_INST_ID_, h.TENANT_ID_ FROM " + prefix + t + " h WHERE"
                    + " h.ROOT_PROC_INST_ID_ IS NOT NULL AND h.REMOVAL_TIME_ <= ?"
                    + (minute == null ? "" : " AND " + minute + " BETWEEN ? AND ?");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setTimestamp(1, new Timestamp(removalTime.getTime()));
                if (minute != null) {
                    ps.setInt(2, minuteFrom);
                    ps.setInt(3, minuteTo);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.putIfAbsent(rs.getString(1), rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("RM-52: the trees due in " + t + " cannot be read", e);
            }
        }
        return out;
    }

    /** {@code root -> tenant} of decision instances by id or by definition (rows without a root are left out). */
    static Map<String, String> treesOfDecisions(Connection c, String prefix, String column, Collection<String> values) {
        if (!DECISION_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("not a decision-instance lookup column: " + column);
        }
        String vetted = SqlIdentifier.tablePrefix(prefix);
        Map<String, String> out = new TreeMap<>();
        List<String> list = new ArrayList<>(values);
        for (int i = 0; i < list.size(); i += ProcessTraceHistoryRemoval.IN_CHUNK) {
            List<String> chunk = list.subList(i, Math.min(list.size(), i + ProcessTraceHistoryRemoval.IN_CHUNK));
            // Identifiers cannot be bound: vetted is SqlIdentifier.tablePrefix, column one of DECISION_COLUMNS; the values
            // are bind marks.
            // nosemgrep: sangam-jdbc-sql-concat
            String sql = "SELECT DISTINCT ROOT_PROC_INST_ID_, TENANT_ID_ FROM " + vetted + "ACT_HI_DECINST WHERE "
                    + column + " IN (" + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")"
                    + " AND ROOT_PROC_INST_ID_ IS NOT NULL";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int k = 0; k < chunk.size(); k++) {
                    ps.setString(k + 1, chunk.get(k));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.putIfAbsent(rs.getString(1), rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("RM-52: the trees of the decisions to delete cannot be read", e);
            }
        }
        return out;
    }

    /** True when the table exists with both {@code ROOT_PROC_INST_ID_} and {@code REMOVAL_TIME_}. */
    private static boolean gateable(Connection c, String table) {
        return GATEABLE.computeIfAbsent(table.toUpperCase(Locale.ROOT), t -> {
            try {
                DatabaseMetaData md = c.getMetaData();
                boolean root = false;
                boolean removal = false;
                for (String name : List.of(t, t.toLowerCase(Locale.ROOT))) {
                    try (ResultSet rs = md.getColumns(null, null, name, null)) {
                        while (rs.next()) {
                            String col = rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT);
                            root |= col.equals("ROOT_PROC_INST_ID_");
                            removal |= col.equals("REMOVAL_TIME_");
                        }
                    }
                }
                return root && removal;
            } catch (SQLException e) {
                throw new IllegalStateException("RM-52: the columns of " + t + " cannot be read", e);
            }
        });
    }

    static String prefix(ProcessEngineConfigurationImpl cfg) {
        return SqlIdentifier.tablePrefix(cfg.getDatabaseTablePrefix());
    }

    /** The decision-history manager that asks the gate before Operaton's own deletes. */
    public static final class Decisions extends HistoricDecisionInstanceManager {
        private final HistoryArchiveGate gate;

        Decisions(HistoryArchiveGate gate) {
            this.gate = Objects.requireNonNull(gate, "gate");
        }

        /** The session factory that installs this manager for every command of the engine. */
        public static SessionFactory sessionFactory(HistoryArchiveGate gate) {
            Objects.requireNonNull(gate, "gate");
            return new SessionFactory() {
                @Override
                public Class<?> getSessionType() {
                    return HistoricDecisionInstanceManager.class;
                }

                @Override
                public Session openSession() {
                    return new Decisions(gate);
                }
            };
        }

        @Override
        public void deleteHistoricDecisionInstanceByIds(List<String> ids) {
            if (isHistoryEnabled() && ids != null && !ids.isEmpty()) {
                CommandContext cc = getCommandContext();
                Connection c = CommandState.connection(cc);
                require(gate, c, treesOfDecisions(c, prefix(cc.getProcessEngineConfiguration()), "ID_", ids));
            }
            super.deleteHistoricDecisionInstanceByIds(ids);
        }

        @Override
        public void deleteHistoricDecisionInstancesByDecisionDefinitionId(String decisionDefinitionId) {
            if (isHistoryEnabled() && decisionDefinitionId != null) {
                CommandContext cc = getCommandContext();
                Connection c = CommandState.connection(cc);
                require(gate, c, treesOfDecisions(c, prefix(cc.getProcessEngineConfiguration()), "DEC_DEF_ID_",
                        List.of(decisionDefinitionId)));
            }
            super.deleteHistoricDecisionInstancesByDecisionDefinitionId(decisionDefinitionId);
        }

        @Override
        public Map<Class<? extends DbEntity>, DbOperation> deleteHistoricDecisionsByRemovalTime(Date removalTime,
                                                                                               int minuteFrom,
                                                                                               int minuteTo,
                                                                                               int batchSize) {
            CommandContext cc = getCommandContext();
            Connection c = CommandState.connection(cc);
            require(gate, c, treesDue(c, cc.getProcessEngineConfiguration(), DECISION_TABLES, removalTime, minuteFrom,
                    minuteTo));
            return super.deleteHistoricDecisionsByRemovalTime(removalTime, minuteFrom, minuteTo, batchSize);
        }
    }
}
