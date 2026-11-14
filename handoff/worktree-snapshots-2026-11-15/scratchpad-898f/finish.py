import os
os.chdir(r"D:\GITHUB  Project\Custom WorkFlow\sangam\sangam-integration\src\main\java\org\sangam\integration")
p='pipeline/PipelineRunner.java'
s=open(p,encoding='utf-8').read()
s=s.replace('''    /** Not caught by the row-level handler in {@link #transform}: see the rethrow there. */
    private static final class TooManyRejects extends Error {''','''    /** Thrown from inside the row-level catch blocks, so it ends the step instead of rejecting another row. */
    private static final class TooManyRejects extends RuntimeException {''')
s=s.replace('ctx.firstIn, ctx.lastOut','Math.max(0, ctx.firstIn), ctx.lastOut')
s=s.replace('p.put("recordsIn", ctx.firstIn);','p.put("recordsIn", Math.max(0, ctx.firstIn));')
s=s.replace('m.put("recordsIn", ctx.firstIn);','m.put("recordsIn", Math.max(0, ctx.firstIn));')
s=s.replace('''        String current = null;
        try {
            Files.createDirectories''','''        String current = null;
        java.util.concurrent.ScheduledFuture<?> beat = HEARTBEATS.scheduleWithFixedDelay(() -> {
            try {
                store.heartbeat(runId, epoch, TTL);
            } catch (RuntimeException e) {
                LOG.debug("pipeline heartbeat for {}: {}", runId, e.getMessage());   // the next fenced write stops the run
            }
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
        try {
            Files.createDirectories''')
s=s.replace('''            end(ctx, current, "FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
''','''            end(ctx, current, "FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            beat.cancel(false);
        }
''')
s=s.replace('''    private static final Duration TTL = Duration.ofMinutes(2);
''','''    private static final Duration TTL = Duration.ofMinutes(2);
    private static final java.util.concurrent.ScheduledExecutorService HEARTBEATS =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sangam-pipeline-heartbeat");
                t.setDaemon(true);
                return t;
            });
''')
open(p,'w',encoding='utf-8').write(s)
open('pipeline/PipelineScheduler.java','w',encoding='utf-8').write('''// SPDX-License-Identifier: Apache-2.0
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
''')
open('pipeline/PipelineDelegate.java','w',encoding='utf-8').write('''// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.pipeline;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.impl.context.Context;
import org.sangam.core.engine.OperationIdentity;
import org.sangam.integration.connector.Json;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * A pipeline as a step of a process (N11). The delegate only queues the run, in the engine transaction, so no data work
 * holds engine locks (VI.1 rule 5). It sets {@code pipelineRunId}; the model then waits on message
 * {@value PipelineMessages#MESSAGE}, correlated on that variable, which carries {@code pipelineState},
 * {@code pipelineRecordsOut}, {@code pipelineRejected} and {@code pipelineError}.
 *
 * <p>The run id is the T35 identity of the step, so a job retry cannot start a second run. Fields: {@code pipeline},
 * {@code parameters} (Map or JSON). Call {@link #install} at start-up.
 */
public final class PipelineDelegate implements JavaDelegate {

    private static volatile PipelineStore store;
    private static volatile BiFunction<String, String, PipelineDefinition> definitions;

    private Expression pipeline;
    private Expression parameters;

    public static void install(PipelineStore s, BiFunction<String, String, PipelineDefinition> d) {
        store = s;
        definitions = d;
    }

    public void setPipeline(Expression pipeline) {
        this.pipeline = pipeline;
    }

    public void setParameters(Expression parameters) {
        this.parameters = parameters;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution ex) {
        if (store == null) {
            throw new IllegalStateException("PipelineDelegate is not installed (sangam-server wiring)");
        }
        String tenant = ex.getTenantId() == null ? "default" : ex.getTenantId();
        String id = String.valueOf(pipeline.getValue(ex));
        Object p = parameters == null ? null : parameters.getValue(ex);
        Map<String, Object> params = p instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m)
                : p instanceof String s ? Json.readMap(s) : new LinkedHashMap<>();
        long visit = ex.getProcessEngineServices().getHistoryService().createHistoricActivityInstanceQuery()
                .processInstanceId(ex.getProcessInstanceId()).activityId(ex.getCurrentActivityId()).finished().count();
        Object loop = ex.getVariable("loopCounter");
        String bk = ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId() : ex.getProcessBusinessKey();
        String runId = OperationIdentity.of(tenant, bk, ex.getCurrentActivityId(),
                OperationIdentity.scope(visit, loop instanceof Number n ? n.intValue() : null, null)).toString();
        Connection c = Context.getCommandContext().getDbSqlSession().getSqlSession().getConnection();
        store.enqueue(c, runId, tenant, definitions.apply(tenant, id), params, "BPMN");
        ex.setVariable("pipelineRunId", runId);
    }
}
''')
open('pipeline/PipelineMessages.java','w',encoding='utf-8').write('''// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.pipeline;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.integration.connector.Json;
import org.sangam.integration.inbox.ChannelBinding;
import org.sangam.integration.inbox.InboundMessage;
import org.sangam.integration.inbox.InboxHandler;
import org.sangam.integration.inbox.InboxService;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delivers a finished BPMN-started run to its waiting instance through the inbox (buffered if the instance is not
 * waiting yet, D-33), and offers the DMN port on the engine.
 */
public final class PipelineMessages implements InboxHandler {

    public static final String MESSAGE = "sangam.pipelineRunCompleted";
    private static final String VARIABLE = "pipelineRunId";

    private final ProcessEngine engine;

    public PipelineMessages(ProcessEngine engine) {
        this.engine = engine;
    }

    public static void wire(ProcessEngine engine, InboxService inbox, PipelineRunner runner) {
        inbox.registerHandler(MESSAGE, new PipelineMessages(engine));
        runner.onCompletion(run -> {
            if (!"BPMN".equals(run.trigger())) {
                return;
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("pipelineState", run.state());
            v.put("pipelineRecordsOut", run.recordsOut());
            v.put("pipelineRejected", run.rejected());
            v.put("pipelineError", run.error());
            // the epoch suffix lets a restarted run deliver its later result
            String id = MESSAGE + ":" + run.runId() + ":" + run.epoch();
            inbox.receive(ChannelBinding.of("sangam:pipeline", run.tenantId(), "system:integration"),
                    new InboundMessage(id, MESSAGE, run.runId(), run.tenantId(), Json.write(v)));
        });
    }

    /** DMN through the engine; tenant {@code default} means a decision deployed without a tenant. */
    public static PipelineResources.DecisionPort decisions(ProcessEngine engine) {
        return (tenant, key, vars) -> {
            var b = engine.getDecisionService().evaluateDecisionByKey(key).variables(vars);
            b = tenant == null || "default".equals(tenant) ? b.decisionDefinitionWithoutTenantId() : b.decisionDefinitionTenantId(tenant);
            return b.evaluate().getFirstResult();
        };
    }

    @Override
    public Disposition handle(Connection c, Envelope m) {
        var q = engine.getRuntimeService().createExecutionQuery().messageEventSubscriptionName(MESSAGE)
                .processVariableValueEquals(VARIABLE, m.correlationKey());
        boolean noTenant = "default".equals(m.tenantId());
        q = noTenant ? q.withoutTenantId() : q.tenantIdIn(m.tenantId());
        if (q.count() == 0) {
            return Disposition.NO_SUBSCRIPTION;
        }
        var b = engine.getRuntimeService().createMessageCorrelation(MESSAGE).processInstanceVariableEquals(VARIABLE, m.correlationKey())
                .setVariables(Json.readMap(m.dataJson()));
        b = noTenant ? b.withoutTenantId() : b.tenantId(m.tenantId());
        return b.correlateAllWithResult().isEmpty() ? Disposition.NO_SUBSCRIPTION : Disposition.PROCESSED;
    }
}
''')
open('telemetry/TelemetryWiring.java','w',encoding='utf-8').write('''// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import org.sangam.integration.inbox.InboxService;

/**
 * Connects telemetry to the inbox: rows of source {@code telemetry:*} go to the rule handler. Alarm events leave through
 * the ports {@code InboundEventProcessor}, whose event definitions decide the process start or message.
 */
public final class TelemetryWiring {

    private TelemetryWiring() {
    }

    public static void wire(InboxService inbox, TelemetryRuleHandler handler) {
        inbox.registerSourceHandler(TelemetryRuleHandler.SOURCE_PREFIX, handler);
    }
}
''')
rp=r"D:\GITHUB  Project\Custom WorkFlow\sangam\sangam-integration\README.md"
r=open(rp,encoding='utf-8').read()
r=r.replace('### Hand-off to WS-PORTS (event registry)\n','''### N11 pipelines (`pipeline/`) and N12 telemetry (`telemetry/`)

Reference DDL to port to sangam-persistence: `PipelineStore.DDL`, `TelemetrySchema.DDL`.

- Pipelines: build `PipelineRunner` and `PipelineScheduler`, then call `PipelineMessages.wire` and `PipelineDelegate.install`. `stageRoot` must be shared storage for a restart on another node.
- Telemetry: call `TelemetryWiring.wire`, and ingest through `TelemetryHttpReceiver` or `MqttTelemetryConsumer`.

### One operationId

`ExternalWork.operationId` (T35) is the ledger key and the downstream key. The delegate mints the same identity, and pack calls use the pack's UUID. The 12-argument `Invocation` constructor derives an id and logs a warning.

### Hand-off to WS-PORTS (event registry)
''',1)
r=r.replace("- Please add `POISON_INBOUND` to `ReconciliationQueue.Kind`. Until then, core `ReconciliationQueue.get/listOpen/resolve` throw `IllegalArgumentException` on such rows. The reconciliation engine only claims the two core kinds. WS-REST's list endpoint uses raw SQL and is unaffected, but its resolve endpoint (which calls the core queue) answers 422 for these items. Resolve them through `PoisonInbound` instead.","- None open. `POISON_INBOUND` is now in `ReconciliationQueue.Kind`. Resolve these items through `PoisonInbound`, because the core queue's actions do not close the dead letter.")
open(rp,'w',encoding='utf-8').write(r)
