// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.bpm.engine.impl.jobexecutor.AcquireJobsCommandFactory;
import org.operaton.bpm.engine.impl.jobexecutor.DefaultJobExecutor;
import org.operaton.bpm.engine.impl.jobexecutor.JobExecutor;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.db.EngineDialect;
import org.sangam.engine.trace.EngineTracing;

/**
 * The job executor part of {@code SangamProcessEnginePlugin} (IR-7, M-054, M-056, M-062): sizing, the opt-in
 * {@link SangamJobExecutor}, the partitioned acquisition and the job side rows. Every option is off by default; with all
 * of them off the result is exactly the IR-7 behaviour (a plain {@code DefaultJobExecutor} when the configuration has
 * none, nothing installed otherwise).
 */
public final class JobExecutorSetup {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(JobExecutorSetup.class);

    private final SangamEngineSettings s;
    private final EngineDialect dialect;
    private final Supplier<java.time.Instant> clock;
    private AcquireJobsCommandFactory partitionedFactory;
    private String incarnation;

    private final org.sangam.engine.tenant.WorkTenants tenants;

    /** @param tenants decision 144: the tenant resolution of the side rows */
    public JobExecutorSetup(SangamEngineSettings settings, EngineDialect dialect, Supplier<java.time.Instant> engineClock,
                            org.sangam.engine.tenant.WorkTenants tenants) {
        this.tenants = tenants;
        this.s = settings;
        this.dialect = dialect;
        this.clock = engineClock;
    }

    private boolean traceLinks() {
        return s.isTraceLinks() && s.getTracing() != EngineTracing.NONE;
    }

    /**
     * preInit: the executor, the partitioned acquisition factory, and the side-row interceptors to register, as
     * factories: each command chain needs its own interceptor object, since the chain sets the interceptor's next.
     */
    public List<java.util.function.Supplier<CommandInterceptor>> preInit(ProcessEngineConfigurationImpl cfg) {
        if (s.isTraceLinks() && s.getTracing() == EngineTracing.NONE) {
            LOG.warn("M-062: sangam.engine.trace-links is on but no EngineTracing is bound; no trace context is stored");
        }
        boolean executorOptions = s.isJobVirtualThreads()
                || (s.getJobBulkheads() != null && !s.getJobBulkheads().isEmpty()) || traceLinks();
        if (cfg.getJobExecutor() == null) {
            DefaultJobExecutor je = executorOptions
                    ? new SangamJobExecutor(s.isJobVirtualThreads(), s.getJobBulkheads(), s.getJobBulkheadWait(),
                            traceLinks() ? s.getTracing() : null)
                    : new DefaultJobExecutor();
            je.setCorePoolSize(s.getJobCorePoolSize());
            je.setMaxPoolSize(s.getJobMaxPoolSize());
            je.setQueueSize(s.getJobQueueCapacity());
            je.setMaxJobsPerAcquisition(s.getJobMaxJobsPerAcquisition());
            je.setWaitTimeInMillis(s.getJobWaitTimeInMillis());
            je.setWaitIncreaseFactor(s.getJobWaitIncreaseFactor());
            je.setMaxWait(s.getJobMaxWait());
            je.setLockTimeInMillis(s.getJobLockTimeInMillis());
            je.setBackoffTimeInMillis(s.getJobBackoffTimeInMillis());
            je.setMaxBackoff(s.getJobMaxBackoff());
            if (s.getJobLockOwner() != null) {
                je.setLockOwner(s.getJobLockOwner());
            }
            cfg.setJobExecutor(je);
        } else if (executorOptions && !(cfg.getJobExecutor() instanceof SangamJobExecutor)) {
            // fail closed: an opt-in the host's own executor would silently ignore (the host must provide a
            // SangamJobExecutor, for example as the Spring Boot starter's JobExecutor bean)
            throw new ProcessEngineException("M-054: sangam.engine.job-virtual-threads / sangam.engine.job-bulkheads / "
                    + "sangam.engine.trace-links need the job executor to be a " + SangamJobExecutor.class.getName()
                    + ", but the configuration already has a " + cfg.getJobExecutor().getClass().getName());
        }
        if (s.isExecutorPartitioning()) {
            if (s.getExecutorSlots() < 1 || s.getExecutorSlots() > ExecutorSlots.MAX_SLOTS) {
                throw new IllegalArgumentException("sangam.engine.executor-slots must be 1.." + ExecutorSlots.MAX_SLOTS
                        + ": " + s.getExecutorSlots());
            }
            incarnation = UUID.randomUUID().toString(); // X.2.1.3: a fresh owner identity per process start
            JobExecutor je = cfg.getJobExecutor();
            String inc = incarnation;
            partitionedFactory = n -> new PartitionedAcquireJobsCmd(je, n, inc, dialect.coreDialect(), clock);
            je.setAcquireJobsCmdFactory(partitionedFactory);
        }
        if (!s.isExecutorPartitioning() && !traceLinks()) {
            return List.of();
        }
        JobSideRows rows = new JobSideRows(s.isExecutorPartitioning(), s.getExecutorSlots(),
                traceLinks() ? s.getTracing() : null, tenants, () -> clock.get().toEpochMilli());
        return List.of(rows::interceptor);
    }

    /**
     * postInit: with partitioning on, the acquisition options the partitioned query does not reproduce are refused, and
     * a later plugin that replaced the acquisition factory (for example WS-PORTS' split executors) is refused too
     * (fail closed: the partition filter would silently not apply).
     */
    public void postInit(ProcessEngineConfigurationImpl cfg) {
        if (partitionedFactory == null) {
            return;
        }
        PartitionedAcquireJobsCmd.requireSupported(cfg);
        if (cfg.getJobExecutor().getAcquireJobsCmdFactory() != partitionedFactory) {
            throw new ProcessEngineException("M-056: sangam.engine.executor-partitioning is on, but another plugin "
                    + "replaced the job acquisition (" + cfg.getJobExecutor().getAcquireJobsCmdFactory() + ")");
        }
    }

    private PartitionLeaseManager leases;
    private java.util.concurrent.ScheduledExecutorService leaseTimer;

    /**
     * postProcessEngineBuild (M-056 finish, C3-04): with partitioning on, starts this node's partition lease cycle
     * ({@link PartitionLeaseManager}) every {@code leaseTtl/3}. Partitions are held only while this node's job executor
     * is active (a node that owns partitions but does not acquire would strand their jobs); when the executor stops or
     * the engine is closed, the partitions are released and the cycle ends. Off: nothing is started.
     *
     * @return the manager, or {@code null} when partitioning is off
     */
    public PartitionLeaseManager startLeases(org.operaton.bpm.engine.ProcessEngine engine) {
        return startLeases(engine, org.sangam.core.engine.EngineMetrics.NONE, org.sangam.core.engine.AuditSink.NONE, null);
    }

    /** {@code OWNERSHIP_CHANGED} audit event type (C3-04, X.2.1.8 rule 5). */
    public static final String OWNERSHIP_CHANGED = "EXECUTOR_OWNERSHIP_CHANGED";

    /**
     * As {@link #startLeases(org.operaton.bpm.engine.ProcessEngine)}, with the C3-04 observability: every ownership
     * write appends {@value #OWNERSHIP_CHANGED} to the class-B audit chain in the same transaction (root
     * {@code executor:<clusterId>}, tenant {@code auditTenant}), and the {@code executor_*} metrics are fed.
     */
    public PartitionLeaseManager startLeases(org.operaton.bpm.engine.ProcessEngine engine,
                                             org.sangam.core.engine.EngineMetrics metrics,
                                             org.sangam.core.engine.AuditSink auditSink, String auditTenant) {
        return startLeases(engine, metrics, auditSink, auditTenant, null);
    }

    /**
     * As above, with the engine's one {@link PartitionResize} (decision 146, built by the plugin for every node, so the
     * server reaches it through {@code SangamEngineComponents.partitionResize()}); {@code null} builds one here.
     */
    public PartitionLeaseManager startLeases(org.operaton.bpm.engine.ProcessEngine engine,
                                             org.sangam.core.engine.EngineMetrics metrics,
                                             org.sangam.core.engine.AuditSink auditSink, String auditTenant,
                                             PartitionResize engineResize) {
        if (incarnation == null) {
            return null;
        }
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
        JobExecutor je = cfg.getJobExecutor();
        String node = s.getJobLockOwner() != null ? s.getJobLockOwner() : je.getLockOwner();
        PartitionLeaseManager.Config config = new PartitionLeaseManager.Config(s.getExecutorClusterId(), node, incarnation,
                s.getExecutorPartitions(), s.getExecutorSlots(), s.getExecutorLeaseTtl(), s.getExecutorLeaseSafetyMargin(),
                s.getExecutorDrainTimeout(), s.getExecutorRecoveryBatch(), s.getExecutorMaxRecoveries());
        org.sangam.core.engine.EngineMetrics m = metrics == null ? org.sangam.core.engine.EngineMetrics.NONE : metrics;
        PartitionLeaseManager.Listener incidents = new PartitionLeaseManager.Listener() {
            @Override
            public void ownerChanged(int partition, long epoch, String reason) {
                observe(() -> m.executorOwnerChanged(reason));
            }

            @Override
            public void recovered(int partition, int jobs) {
                observe(() -> m.executorJobsRecovered(jobs));
            }

            @Override
            public void orphanedPartitions(long count) {
                observe(() -> m.executorOrphanedPartitions(count));
            }

            @Override
            public void recoveryLoop(String jobId, String executionId, int recoveries) {
                if (executionId != null) { // X.2.1.6: the poison job is parked (retries 0) and visible as an incident
                    engine.getRuntimeService().createIncident(PartitionLeaseManager.RECOVERY_LOOP, executionId, jobId,
                            PartitionLeaseManager.RECOVERY_LOOP + ": job " + jobId + " was recovered " + recoveries
                                    + " times after its executor was lost; retries set to 0 (X.2.1.6)");
                }
            }
        };
        PartitionResize resize = engineResize != null ? engineResize : new PartitionResize(cfg.getDataSource(),
                dialect.coreDialect(), config.clusterId(), config.slots(), auditSink, auditTenant,
                config.recoveryBatch()); // decision 146
        leases = new PartitionLeaseManager(cfg.getDataSource(), dialect.coreDialect(), config, clock, System::nanoTime,
                incidents, ownershipAudit(auditSink, auditTenant, config, incarnation), resize);
        leaseTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sangam-exec-lease-" + node);
            t.setDaemon(true);
            return t;
        });
        long every = Math.max(1, config.renewInterval().toMillis());
        leaseTimer.scheduleWithFixedDelay(() -> leaseCycle(engine, je), 0, every,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        return leases;
    }

    private void leaseCycle(org.operaton.bpm.engine.ProcessEngine engine, JobExecutor je) {
        try {
            if (!je.getProcessEngines().contains(engine)) { // engine closed
                leases.releaseAll();
                leaseTimer.shutdown();
                return;
            }
            if (!je.isActive()) {
                if (!leases.ownedLive().isEmpty()) {
                    leases.releaseAll();
                }
                return;
            }
            leases.tick();
        } catch (RuntimeException e) {
            LOG.warn("M-056 partition lease cycle failed: {}", e.toString());
        }
    }

    /** The {@value #OWNERSHIP_CHANGED} audit on the ownership write's own connection (fails the write when it fails). */
    static PartitionLeaseManager.OwnershipAudit ownershipAudit(org.sangam.core.engine.AuditSink sink, String tenant,
                                                               PartitionLeaseManager.Config config, String incarnation) {
        if (sink == null || sink == org.sangam.core.engine.AuditSink.NONE) {
            return PartitionLeaseManager.OwnershipAudit.NONE;
        }
        String root = "executor:" + config.clusterId();
        return (conn, partition, epoch, reason, previousOwner) -> {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("clusterId", config.clusterId());
            payload.put("partition", partition);
            payload.put("epoch", epoch);
            payload.put("reason", reason);
            payload.put("node", config.nodeId());
            payload.put("incarnation", incarnation);
            if (previousOwner != null) {
                payload.put("previousOwner", previousOwner);
            }
            sink.append(conn, tenant, root, OWNERSHIP_CHANGED, payload);
        };
    }

    private static void observe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            LOG.warn("M-056 executor metrics failed: {}", e.toString());
        }
    }

    /** This node's incarnation for partition ownership, or {@code null} when partitioning is off. */
    public String incarnation() {
        return incarnation;
    }
}
