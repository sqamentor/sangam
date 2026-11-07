// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.pipeline;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;
import org.sangam.integration.relay.LeaderElection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Leader-elected pipeline scheduling (R29 pattern, N11). Each tick on the leader queues due schedules and starts queued
 * or abandoned runs, which also picks up runs queued by {@code PipelineDelegate} and runs of crashed nodes.
 *
 * <p>A scheduled run id is UUIDv5(tenant, pipeline, due time), and the run row and the next due time commit together, so
 * a leader failover neither loses nor doubles a firing. Firings missed during an outage collapse into one catch-up run.
 */
public final class PipelineScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineScheduler.class);
    public static final String LEADER_NAME = "sangam-pipeline-scheduler";

    private final LeaderElection leader;
    private final Transactions tx;
    private final Dialect dialect;
    private final PipelineStore store;
    private final PipelineRunner runner;
    private final BiFunction<String, String, PipelineDefinition> definitions;
    private final int maxConcurrent;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sangam-pipeline-scheduler");
        t.setDaemon(true);
        return t;
    });

    /** @param definitions (tenant, pipelineId) to the current definition */
    public PipelineScheduler(LeaderElection leader, Transactions tx, Dialect dialect, PipelineStore store, PipelineRunner runner,
                             BiFunction<String, String, PipelineDefinition> definitions, int maxConcurrent) {
        this.leader = leader;
        this.tx = tx;
        this.dialect = dialect;
        this.store = store;
        this.runner = runner;
        this.definitions = definitions;
        this.maxConcurrent = Math.max(1, maxConcurrent);
    }

    public void start(Duration every) {
        ticker.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                LOG.warn("pipeline scheduler tick failed", e);
            }
        }, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** One pass, if this node leads. */
    public boolean tick() {
        if (!leader.isLeader(LEADER_NAME)) {
            return false;
        }
        tx.inTransaction(c -> {
            Instant now = Jdbc.dbNow(c, dialect);
            for (PipelineStore.Schedule s : store.dueSchedules(c, now)) {
                try {
                    PipelineDefinition def = definitions.apply(s.tenantId(), s.pipelineId());
                    Map<String, Object> params = Json.readMap(s.paramsJson());
                    params.put("scheduledFor", s.nextRunAt().toString());
                    store.enqueue(c, runId(s.tenantId(), s.pipelineId(), s.nextRunAt()), s.tenantId(), def, params, "SCHEDULE");
                    store.advanceSchedule(c, s, new CronSchedule(s.cron(), ZoneId.of(s.zone())).next(now));
                } catch (IllegalArgumentException e) {
                    LOG.error("schedule of pipeline {} cannot fire: {}", s.pipelineId(), e.getMessage());
                }
            }
            return null;
        });
        for (String runId : store.runnable(maxConcurrent)) {
            if (active.size() < maxConcurrent && active.add(runId)) {
                workers.execute(() -> {
                    try {
                        runner.execute(runId);
                    } finally {
                        active.remove(runId);
                    }
                });
            }
        }
        return true;
    }

    static String runId(String tenant, String pipeline, Instant due) {
        return Hashes.uuidV5(OperationLedger.NAMESPACE, "pipeline|" + tenant + "|" + pipeline + "|" + due).toString();
    }

    @Override
    public void close() {
        ticker.shutdownNow();
        workers.shutdown();
    }
}
