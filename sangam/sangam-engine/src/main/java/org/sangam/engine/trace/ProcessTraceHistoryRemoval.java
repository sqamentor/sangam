// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.db.DbEntity;
import org.operaton.bpm.engine.impl.db.entitymanager.operation.DbOperation;
import org.operaton.bpm.engine.impl.db.sql.DbSqlSessionFactory;
import org.operaton.bpm.engine.impl.interceptor.Session;
import org.operaton.bpm.engine.impl.interceptor.SessionFactory;
import org.operaton.bpm.engine.impl.persistence.entity.HistoricProcessInstanceManager;
import org.sangam.engine.tx.CommandState;

/**
 * SL-177 (M-044 retention): a process instance's {@code PROCESS_TRACE} row is removed in the same transaction as the
 * instance's history, so a trace id never outlives the instance it describes. Operaton removes process-instance history
 * on exactly two paths of its {@link HistoricProcessInstanceManager} (javap, operaton-engine 2.2.0):
 * <ul>
 *   <li>{@code deleteHistoricProcessInstanceByIds(List)}: {@code HistoryService.deleteHistoricProcessInstance(s)},
 *       {@code ...IfExists}, the historic-instance deletion batch ({@code DeleteHistoricProcessInstancesCmd}), the
 *       end-time cleanup strategy ({@code HistoryCleanupBatch}) and a deployment's cascade delete
 *       ({@code deleteHistoricProcessInstanceByProcessDefinitionId} calls it);</li>
 *   <li>{@code deleteHistoricProcessInstancesByRemovalTime(Date, int, int, int)}: the removal-time cleanup strategy
 *       ({@code HistoryCleanupRemovalTime.performProcessCleanup}).</li>
 * </ul>
 * This subclass replaces the manager through the {@code customSessionFactories} extension point (PATCHES.md B-36, I-18:
 * the custom factory of a session type wins over the built-in one). The engine's own deletes are bulk operations run at
 * flush; the trace rows are deleted at once on the command's connection, so both commit or roll back together.
 *
 * <p>Removal-time strategy: Operaton deletes every history table by its own {@code REMOVAL_TIME_} with its own batch
 * limit, so the rows of one instance may go in different cleanup runs. The trace rows follow the same rule: a trace row
 * is deleted once its instance's {@code ACT_HI_PROCINST} row is due ({@code REMOVAL_TIME_ <= removalTime}, and in the
 * job's minute range when the engine filters by one, with the engine's own per-database minute expression). The
 * statement runs before the flush, in the same transaction, so every {@code ACT_HI_PROCINST} row that this run deletes
 * is still visible to it: no trace row is ever left behind. A trace row may go a run earlier than its history row (as
 * Operaton's own child tables may); both are past their removal time.
 *
 * <p>The DPDP erasure path ({@code ErasureService}) erases variables and comments but keeps the instance's history
 * (ids, timestamps, activity trail), so its trace row correctly stays.
 */
public final class ProcessTraceHistoryRemoval extends HistoricProcessInstanceManager {

    /** Oracle allows at most 1,000 expressions in an IN list. */
    static final int IN_CHUNK = 1000;

    /** The session factory that installs this manager for every command of the engine. */
    public static SessionFactory sessionFactory() {
        return new SessionFactory() {
            @Override
            public Class<?> getSessionType() {
                return HistoricProcessInstanceManager.class;
            }

            @Override
            public Session openSession() {
                return new ProcessTraceHistoryRemoval();
            }
        };
    }

    @Override
    public void deleteHistoricProcessInstanceByIds(List<String> processInstanceIds) {
        if (isHistoryEnabled() && processInstanceIds != null && !processInstanceIds.isEmpty()) {
            ProcessTraces.deleteInstances(CommandState.connection(getCommandContext()), processInstanceIds, IN_CHUNK);
        }
        super.deleteHistoricProcessInstanceByIds(processInstanceIds);
    }

    @Override
    public Map<Class<? extends DbEntity>, DbOperation> deleteHistoricProcessInstancesByRemovalTime(Date removalTime,
                                                                                                  int minuteFrom,
                                                                                                  int minuteTo,
                                                                                                  int batchSize) {
        ProcessEngineConfigurationImpl cfg = getCommandContext().getProcessEngineConfiguration();
        Connection c = CommandState.connection(getCommandContext());
        String history = prefix(cfg) + "ACT_HI_PROCINST";
        String minute = minuteFilter(cfg.getDatabaseType(), minuteFrom, minuteTo);
        Jdbc.deleteDue(c, history, minute, new Timestamp(removalTime.getTime()), minuteFrom, minuteTo);
        return super.deleteHistoricProcessInstancesByRemovalTime(removalTime, minuteFrom, minuteTo, batchSize);
    }

    private static String prefix(ProcessEngineConfigurationImpl cfg) {
        return cfg.getDatabaseTablePrefix() == null ? "" : cfg.getDatabaseTablePrefix();
    }

    /**
     * The engine's minute condition on {@code h.REMOVAL_TIME_} ({@code Commons.xml andWhereMinuteInDateBetweenSql} and
     * its {@code _oracle} form), or {@code null} when the engine applies none: the manager passes the minute bounds only
     * when {@code minuteTo - minuteFrom + 1 < 60} (javap, {@code deleteHistoricProcessInstancesByRemovalTime}).
     */
    static String minuteFilter(String databaseType, int minuteFrom, int minuteTo) {
        if (minuteTo - minuteFrom + 1 >= 60) {
            return null;
        }
        String d1 = DbSqlSessionFactory.getDatabaseSpecificDatepart1().get(databaseType);
        String d2 = DbSqlSessionFactory.getDatabaseSpecificDatepart2().get(databaseType);
        String d3 = DbSqlSessionFactory.getDatabaseSpecificDatepart3().get(databaseType);
        Map<String, String> constants = DbSqlSessionFactory.getDatabaseSpecificConstants().get(databaseType);
        String unit = constants == null ? null : constants.get("constant.datepart.minute");
        if (d1 == null || d2 == null || d3 == null || unit == null) {
            throw new IllegalStateException("SL-177: no minute expression for database type '" + databaseType + "'");
        }
        String col = "h.REMOVAL_TIME_";
        return DbSqlSessionFactory.ORACLE.equals(databaseType) ? d1 + col + d2 + unit + d3 : d1 + unit + d2 + col + d3;
    }

    /** The statements, kept apart so the SQL can be checked without an engine. */
    static final class Jdbc {
        private Jdbc() {
        }

        static String dueSql(String historyTable, String minuteCondition) {
            return "DELETE FROM PROCESS_TRACE WHERE process_instance_id IN (SELECT h.ID_ FROM " + historyTable
                    + " h WHERE h.REMOVAL_TIME_ <= ?" + (minuteCondition == null ? "" : " AND " + minuteCondition
                    + " BETWEEN ? AND ?") + ")";
        }

        static int deleteDue(Connection c, String historyTable, String minuteCondition, Timestamp removalTime,
                             int minuteFrom, int minuteTo) {
            String sql = dueSql(historyTable, minuteCondition);
            return minuteCondition == null ? org.sangam.core.layer.Jdbc.update(c, sql, removalTime)
                    : org.sangam.core.layer.Jdbc.update(c, sql, removalTime, minuteFrom, minuteTo);
        }
    }
}
