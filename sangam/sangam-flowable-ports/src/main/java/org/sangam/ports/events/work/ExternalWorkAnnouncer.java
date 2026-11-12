// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.work;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.TransactionState;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.db.entitymanager.cache.CachedDbEntity;
import org.operaton.bpm.engine.impl.db.entitymanager.cache.DbEntityState;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.bpm.engine.impl.persistence.entity.ExternalTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Broker task mode as decided for 0.1.0 (M-066, owner decision 101): <b>announce</b> new external work, never hand it
 * out. Workers keep taking work only through the REST lease service ({@code fetchAndLock} and the worker long-poll), so
 * the VI.3 lease rules (lock version, attempt id, 409 STALE_LEASE, the operation ledger) have exactly one path.
 *
 * <p>Before, a waiting long-poll woke at once only when a relay cycle had delivered an outbox event (the M-005 hint
 * fires after a cycle with at least one delivery), so new external work was announced only by accident (a history
 * subscriber that happened to exist); otherwise waiters found it on their next poll (up to 1 s). This plugin adds a post
 * command interceptor (the pattern of {@code AsyncJobHandOffInterceptor}) that sees external tasks the command inserted
 * (transient, unlocked {@link ExternalTaskEntity} in the command's entity cache) and, after the transaction
 * <b>committed</b> (never before the row is visible), asks for one announcement.
 *
 * <ul>
 *   <li><b>Payload-free.</b> The announcement is the relay wake-up hint ({@code WakeupNotifier}: {@code pg_notify} on
 *       PostgreSQL, in-JVM elsewhere, plus the M-058 NATS/Redis bus when configured, which external workers may also
 *       subscribe to). No topic, tenant or task id leaves the transaction; the poll stays authoritative (R1).</li>
 *   <li><b>Coalesced.</b> Requests within {@code coalesce} produce one announcement at the end of the window (trailing
 *       edge, so work committed inside the window is covered), sent from one daemon thread: the engine thread never
 *       waits for the notification and the hint rate per node is bounded.</li>
 *   <li><b>Never fails a command.</b> Detection runs after the command succeeded and the announcement after commit; any
 *       failure is logged at debug and costs latency only.</li>
 * </ul>
 * Reads Operaton's entity cache (impl classes, PATCHES.md allow-list B).
 */
public final class ExternalWorkAnnouncer implements ProcessEnginePlugin, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalWorkAnnouncer.class);

    private final Runnable announce;
    private final long coalesceMillis;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicLong announcements = new AtomicLong();
    private final ScheduledExecutorService sender;

    /**
     * @param announce sends one wake-up hint; called from the announcer thread, must not block for long
     * @param coalesce window that merges requests into one announcement (zero = one per committed command)
     */
    public ExternalWorkAnnouncer(Runnable announce, Duration coalesce) {
        this.announce = Objects.requireNonNull(announce, "announce");
        if (coalesce == null || coalesce.isNegative()) {
            throw new IllegalArgumentException("coalesce must be zero or positive");
        }
        this.coalesceMillis = coalesce.toMillis();
        this.sender = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sangam-work-announcer");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        java.util.List<CommandInterceptor> post = cfg.getCustomPostCommandInterceptorsTxRequired() == null
                ? new java.util.ArrayList<>() : new java.util.ArrayList<>(cfg.getCustomPostCommandInterceptorsTxRequired());
        post.add(interceptor());
        cfg.setCustomPostCommandInterceptorsTxRequired(post);
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl cfg) {
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine engine) {
    }

    /** The post command interceptor (runs inside the command context, after the command, before its flush). */
    CommandInterceptor interceptor() {
        return new CommandInterceptor() {
            @Override
            public <T> T execute(Command<T> command) {
                T result = next.execute(command);
                try {
                    CommandContext cc = Context.getCommandContext();
                    if (cc != null && createdExternalWork(cc.getDbEntityManager().getDbEntityCache().getCachedEntities())) {
                        registerAfterCommit(cc);
                    }
                } catch (RuntimeException e) {
                    LOG.debug("external work not announced (the poll stays authoritative): {}", e.toString());
                }
                return result;
            }
        };
    }

    /** True when the cache holds an external task inserted by this command and not locked by it. */
    static boolean createdExternalWork(Iterable<CachedDbEntity> cached) {
        for (CachedDbEntity c : cached) {
            if (c.getEntity() instanceof ExternalTaskEntity t && c.getEntityState() == DbEntityState.TRANSIENT
                    && t.getWorkerId() == null && !t.isSuspended()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A nested command sharing the context may register a second listener; both land in the same coalescing window (the
     * {@code pending} flag), so no per-context bookkeeping is kept (nothing to leak when a transaction ends unseen).
     */
    private void registerAfterCommit(CommandContext cc) {
        cc.getTransactionContext().addTransactionListener(TransactionState.COMMITTED, ctx -> request());
    }

    /** Asks for one announcement; requests inside the coalescing window share it. Never throws. */
    public void request() {
        if (!pending.compareAndSet(false, true)) {
            return;
        }
        try {
            sender.schedule(this::send, coalesceMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            pending.set(false);   // closed: nothing to announce to
        }
    }

    private void send() {
        pending.set(false);   // a request from now on schedules the next announcement
        try {
            announce.run();
            announcements.incrementAndGet();
        } catch (RuntimeException e) {
            LOG.debug("external work announcement not sent (the poll stays authoritative): {}", e.toString());
        }
    }

    /** Announcements sent so far (monitoring and tests). */
    public long announcements() {
        return announcements.get();
    }

    @Override
    public void close() {
        sender.shutdownNow();
    }
}
