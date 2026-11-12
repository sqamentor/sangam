// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.db.entitymanager.DbEntityManager;
import org.operaton.bpm.engine.impl.db.entitymanager.cache.CachedDbEntity;
import org.operaton.bpm.engine.impl.db.entitymanager.cache.DbEntityState;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandContextListener;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.bpm.engine.impl.interceptor.Session;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.sangam.engine.tx.CommandState;

/**
 * M-044 (decision 57, {@code ProcessInstance.traceId}): records the trace id of every process instance a command
 * created in {@code PROCESS_TRACE} (changeset 0043-12), on the command's connection when its context closes, so the row
 * commits or rolls back with the instance (the pattern of {@code JobSideRows}). It reads the command's new
 * process-instance executions from the entity cache, so every start path is covered: API start, message, signal,
 * timer, call activity, instantiation at activities.
 *
 * <ul>
 *   <li>A <b>root</b> instance takes the trace id of the current context ({@code traceIdSource}).</li>
 *   <li>A <b>child</b> (call activity) inherits its root's recorded trace id, whether the root started in this command
 *       or an earlier one; only when the root has none does it take the current context's.</li>
 *   <li>No valid W3C trace id means no row: a trace id is never made up.</li>
 *   <li>A failing trace source only means no trace id (R13: tracing never changes engine behaviour); a failing write
 *       fails the command like any other product row.</li>
 * </ul>
 */
public final class ProcessTraceRecorder implements CommandContextListener {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProcessTraceRecorder.class);

    private final Supplier<String> traceIdSource;
    private final LongSupplier clockMillis;

    /**
     * @param traceIdSource the current context's W3C trace id, or {@code null} when there is none
     * @param clockMillis   the engine clock in epoch milliseconds
     */
    public ProcessTraceRecorder(Supplier<String> traceIdSource, LongSupplier clockMillis) {
        this.traceIdSource = traceIdSource;
        this.clockMillis = clockMillis;
    }

    /** The trace id source of a tracing port: the trace id of its current context. */
    public static Supplier<String> fromTracing(EngineTracing tracing) {
        return () -> tracing == null ? null : ProcessTraces.traceIdOf(tracing.current());
    }

    /** The post command interceptor that registers the listener once per command context. */
    public CommandInterceptor interceptor() {
        CommandContextListener listener = this;
        return new CommandInterceptor() {
            @Override
            public <T> T execute(Command<T> command) {
                if (Context.hasActiveCommandContext()) {
                    // registerCommandContextListener ignores a listener that is already registered (javap 2.2.0)
                    Context.getCommandContext().registerCommandContextListener(listener);
                }
                return next.execute(command);
            }
        };
    }

    @Override
    public void onCommandContextClose(CommandContext cc) {
        DbEntityManager em = null;
        for (Session s : cc.getSessions().values()) {
            if (s instanceof DbEntityManager m) {
                em = m;
            }
        }
        if (em == null) {
            return;
        }
        List<ExecutionEntity> roots = new ArrayList<>();
        List<ExecutionEntity> children = new ArrayList<>();
        for (CachedDbEntity cached : em.getDbEntityCache().getCachedEntities()) {
            if (cached.getEntity() instanceof ExecutionEntity x && cached.getEntityState() == DbEntityState.TRANSIENT
                    && x.isProcessInstanceExecution()) {
                String root = x.getRootProcessInstanceId();
                (root == null || root.equals(x.getId()) ? roots : children).add(x);
            }
        }
        if (roots.isEmpty() && children.isEmpty()) {
            return;
        }
        String current = current();
        Connection c = CommandState.connection(cc);
        long now = clockMillis.getAsLong();
        Map<String, String> byRoot = new HashMap<>();
        for (ExecutionEntity r : roots) {
            byRoot.put(r.getId(), current);
            ProcessTraces.record(c, r.getId(), r.getTenantId(), r.getId(), current, now);
        }
        for (ExecutionEntity child : children) {
            String rootId = child.getRootProcessInstanceId();
            String inherited = byRoot.containsKey(rootId) ? byRoot.get(rootId)
                    : ProcessTraces.read(c, rootId).orElse(null);
            byRoot.put(rootId, inherited);
            ProcessTraces.record(c, child.getId(), child.getTenantId(), rootId, inherited != null ? inherited : current,
                    now);
        }
    }

    @Override
    public void onCommandFailed(CommandContext cc, Throwable t) {
        // the instance rolls back, and so would its row: nothing to record
    }

    private String current() {
        if (traceIdSource == null) {
            return null;
        }
        try {
            return ProcessTraces.valid(traceIdSource.get());
        } catch (RuntimeException e) {
            LOG.debug("M-044: trace id source failed; no trace id recorded", e);
            return null;
        }
    }
}
