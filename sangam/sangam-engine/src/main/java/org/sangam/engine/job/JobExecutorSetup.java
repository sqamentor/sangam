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

    public JobExecutorSetup(SangamEngineSettings settings, EngineDialect dialect, Supplier<java.time.Instant> engineClock) {
        this.s = settings;
        this.dialect = dialect;
        this.clock = engineClock;
    }

    private boolean traceLinks() {
        return s.isTraceLinks() && s.getTracing() != EngineTracing.NONE;
    }

    /** preInit: the executor, the partitioned acquisition factory, and the side-row interceptors to register. */
    public List<CommandInterceptor> preInit(ProcessEngineConfigurationImpl cfg) {
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
                traceLinks() ? s.getTracing() : null, s.getDefaultTenantId(), () -> clock.get().toEpochMilli());
        return List.of(rows.interceptor());
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

    /** This node's incarnation for partition ownership, or {@code null} when partitioning is off. */
    public String incarnation() {
        return incarnation;
    }
}
