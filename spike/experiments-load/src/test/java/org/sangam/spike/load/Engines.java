package org.sangam.spike.load;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.sangam.spike.api.EngineAdapter;
import org.sangam.spike.flowable.FlowableEngineAdapter;
import org.sangam.spike.operaton.OperatonEngineAdapter;

/** Creates the adapters and reads/sets the engine history level for the reports. */
public final class Engines {

    public static final List<String> ALL = List.of("flowable", "operaton");

    private Engines() {
    }

    /** Pool auto-commit as in each adapter's own factory (Flowable true, Operaton false). */
    public static boolean autoCommit(String engine) {
        return "flowable".equals(engine);
    }

    public static EngineAdapter create(String engine, DataSource ds, String jdbcUrl) {
        return switch (engine) {
            case "flowable" -> new FlowableEngineAdapter(ds, jdbcUrl);
            case "operaton" -> new OperatonEngineAdapter(ds, jdbcUrl);
            default -> throw new IllegalArgumentException(engine);
        };
    }

    /** History level the adapter configured (Flowable: engine default; Operaton: set to FULL by the adapter). */
    public static String historyLevel(EngineAdapter a) {
        if (a instanceof OperatonEngineAdapter o) {
            return ((org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl) o.processEngine()
                    .getProcessEngineConfiguration()).getHistoryLevel().getName();
        }
        return flowableConfig(a).getHistoryLevel().getKey();
    }

    /**
     * E9 only: sets the history level at runtime on the already built engine (a test hook, not an adapter change),
     * so both engines run at the same level. Its effect is verified afterwards by counting ACT_HI_DETAIL rows
     * (written only at FULL on both engines).
     */
    public static void setHistoryLevel(EngineAdapter a, String level) {
        if (a instanceof OperatonEngineAdapter o) {
            var cfg = (org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl) o.processEngine()
                    .getProcessEngineConfiguration();
            cfg.setHistoryLevel("full".equals(level)
                    ? org.operaton.bpm.engine.impl.history.HistoryLevel.HISTORY_LEVEL_FULL
                    : org.operaton.bpm.engine.impl.history.HistoryLevel.HISTORY_LEVEL_AUDIT);
            return;
        }
        flowableConfig(a).setHistoryLevel("full".equals(level)
                ? org.flowable.common.engine.impl.history.HistoryLevel.FULL
                : org.flowable.common.engine.impl.history.HistoryLevel.AUDIT);
    }

    private static org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl flowableConfig(EngineAdapter a) {
        try {
            Field f = FlowableEngineAdapter.class.getDeclaredField("processEngineConfiguration");
            f.setAccessible(true);
            return (org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl) f.get(a);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Flowable only: triggers the message subscription {@code msg} of process instance {@code pid} (any execution of
     * it) in one engine command. Used because the adapter's correlateMessage filters by processInstanceBusinessKey,
     * which does not match child executions in parallel branches.
     */
    public static String flowableCorrelateInInstance(EngineAdapter a, String pid, String msg,
                                                     java.util.Map<String, Object> vars) {
        var cfg = flowableConfig(a);
        var rs = cfg.getRuntimeService();
        return cfg.getManagementService().executeCommand(ctx -> {
            var ex = rs.createExecutionQuery().processInstanceId(pid).messageEventSubscriptionName(msg).list();
            if (ex.size() != 1) {
                throw new IllegalStateException("message " + msg + " matched " + ex.size() + " executions of " + pid);
            }
            rs.messageEventReceived(msg, ex.get(0).getId(), vars);
            return ex.get(0).getId();
        });
    }

    /** E9 only: starts Flowable's async executor (built but not activated by the adapter). Operaton: nothing. */
    public static java.util.Map<String, Object> startAsyncExecutorIfFlowable(EngineAdapter a) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (!(a instanceof FlowableEngineAdapter)) {
            m.put("started", false);
            m.put("note", "not needed: Operaton continues the process synchronously in the external-task completion command");
            return m;
        }
        var ex = flowableConfig(a).getAsyncExecutor();
        if (ex == null) {
            m.put("started", false);
            m.put("note", "no async executor instance");
            return m;
        }
        ex.start();
        m.put("started", ex.isActive());
        m.put("class", ex.getClass().getName());
        if (ex instanceof org.flowable.job.service.impl.asyncexecutor.DefaultAsyncJobExecutor d) {
            m.put("corePoolSize", d.getTaskExecutor() == null ? null : d.getTaskExecutor().getClass().getSimpleName());
        }
        m.put("note", "Flowable runs the continuation after an external-worker completion as an async job; the adapter "
                + "builds the engine with the async executor off, so E9 starts it (test hook, engine defaults)");
        return m;
    }

    /** {@code select count(*)} through the engine's own connection; table names are the same on both engines. */
    public static long count(EngineAdapter a, String sql, Object... args) {
        return a.inEngineTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                throw new IllegalStateException(sql, e);
            }
        });
    }
}
