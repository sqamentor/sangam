// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import org.operaton.bpm.engine.impl.db.entitymanager.DbEntityManager;
import org.operaton.bpm.engine.impl.db.entitymanager.cache.CachedDbEntity;
import org.operaton.bpm.engine.impl.db.entitymanager.cache.DbEntityState;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandContextListener;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.bpm.engine.impl.interceptor.Session;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.persistence.entity.ExternalTaskEntity;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.trace.EngineTracing;
import org.sangam.engine.trace.JobTraceContexts;
import org.sangam.engine.tx.CommandState;

/**
 * M-056 / M-062: writes the job side rows of contract {@code W2-JOB-SIDE-TABLES-CONTRACT.md} in the command that
 * creates or deletes the job, on the command's connection and in its transaction. A command-context close listener
 * (the mechanism of the L2 flush backstop: close listeners run before Operaton flushes, PATCHES.md I-8) looks at the
 * engine entity cache:
 * <ul>
 *   <li>a new {@link JobEntity} (state {@code TRANSIENT}) with a root process instance gets its
 *       {@code EXEC_JOB_SLOT} row when partitioning is on ({@link ExecutorSlots#slotOf}); a new job or
 *       {@link ExternalTaskEntity} gets its {@code JOB_TRACE_CONTEXT} row when trace links are on and the tracer has a
 *       current context;</li>
 *   <li>a deleted one ({@code DELETED_PERSISTENT} / {@code DELETED_MERGED}) loses its rows; one created and deleted in
 *       the same command ({@code DELETED_TRANSIENT}) never had any.</li>
 * </ul>
 * Installed only when one of the two options is on; with both off the engine never touches these tables.
 */
public final class JobSideRows implements CommandContextListener {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(JobSideRows.class);

    private final boolean partitioning;
    private final int slots;
    private final EngineTracing tracing;
    private final String defaultTenant;
    private final LongSupplier clockMillis;

    /**
     * @param partitioning write {@code EXEC_JOB_SLOT} rows
     * @param slots        the slot count S
     * @param tracingOrNull write {@code JOB_TRACE_CONTEXT} rows from this tracer's current context ({@code null}: no)
     * @param defaultTenant the tenant of untenanted jobs (hash input)
     * @param clockMillis  the engine clock in epoch milliseconds
     */
    public JobSideRows(boolean partitioning, int slots, EngineTracing tracingOrNull, String defaultTenant,
                       LongSupplier clockMillis) {
        this.partitioning = partitioning;
        this.slots = slots;
        this.tracing = tracingOrNull;
        this.defaultTenant = defaultTenant;
        this.clockMillis = clockMillis;
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
        if (em == null || CommandState.of(cc).isAcquisition()) {
            return; // no engine entity touched, or an acquisition command (it creates and deletes no job)
        }
        List<Object> created = new ArrayList<>();
        List<Object> deleted = new ArrayList<>();
        for (CachedDbEntity cached : em.getDbEntityCache().getCachedEntities()) {
            Object e = cached.getEntity();
            if (!(e instanceof JobEntity) && !(e instanceof ExternalTaskEntity)) {
                continue;
            }
            DbEntityState st = cached.getEntityState();
            if (st == DbEntityState.TRANSIENT) {
                created.add(e);
            } else if (st == DbEntityState.DELETED_PERSISTENT || st == DbEntityState.DELETED_MERGED) {
                deleted.add(e);
            }
        }
        if (created.isEmpty() && deleted.isEmpty()) {
            return;
        }
        Connection c = CommandState.connection(cc);
        long now = clockMillis.getAsLong();
        EngineTracing.TraceContext ctx = created.isEmpty() ? null : currentContext();
        for (Object e : created) {
            if (e instanceof JobEntity job) {
                if (partitioning && job.getRootProcessInstanceId() != null) {
                    String tenant = job.getTenantId() == null ? defaultTenant : job.getTenantId();
                    Jdbc.update(c, "INSERT INTO EXEC_JOB_SLOT (job_id, tenant_id, root_id, slot, owner_partition, "
                                    + "owner_epoch, recoveries, created_at_ms) VALUES (?,?,?,?,NULL,NULL,0,?)",
                            job.getId(), tenant, job.getRootProcessInstanceId(),
                            ExecutorSlots.slotOf(tenant, job.getRootProcessInstanceId(), slots), now);
                }
                if (ctx != null) {
                    JobTraceContexts.insert(c, job.getId(), JobTraceContexts.Kind.JOB, ctx, now);
                }
            } else if (ctx != null) {
                JobTraceContexts.insert(c, ((ExternalTaskEntity) e).getId(), JobTraceContexts.Kind.EXTERNAL_TASK, ctx, now);
            }
        }
        for (Object e : deleted) {
            if (e instanceof JobEntity job) {
                if (partitioning) {
                    Jdbc.update(c, "DELETE FROM EXEC_JOB_SLOT WHERE job_id = ?", job.getId());
                }
                if (tracing != null) {
                    JobTraceContexts.delete(c, job.getId());
                }
            } else if (tracing != null) {
                JobTraceContexts.delete(c, ((ExternalTaskEntity) e).getId());
            }
        }
    }

    /** The tracer's current context; a tracer failure only means no link is stored (R13: never affects the engine). */
    private EngineTracing.TraceContext currentContext() {
        if (tracing == null) {
            return null;
        }
        try {
            return tracing.current();
        } catch (RuntimeException e) {
            LOG.debug("M-062: tracing current() failed; no trace link stored", e);
            return null;
        }
    }

    @Override
    public void onCommandFailed(CommandContext cc, Throwable t) {
        // nothing to undo: the rows go with the rolled-back transaction
    }
}
