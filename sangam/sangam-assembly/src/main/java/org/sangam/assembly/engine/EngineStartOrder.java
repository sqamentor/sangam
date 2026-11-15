// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.engine;

import java.time.Duration;
import java.util.Objects;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.jobexecutor.JobExecutor;
import org.sangam.assembly.host.EngineBuildClaims;
import org.sangam.assembly.host.Lifecycle;
import org.sangam.assembly.host.SangamRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The engine side of owner decision 376 (G7 extraction phase 3 blocker 5), for a host whose container builds the engine
 * before the host's start event:
 *
 * <ol>
 *   <li>the engine is built with the job executor off: {@link #preInit} keeps whether it was wanted and switches the
 *       activation off, and {@link #postInit} refuses the build when the job executor would still start with it;</li>
 *   <li>once the engine is built, the services that need it are assembled ({@link EngineBuildClaims#engineBuilt});</li>
 *   <li>the job executor starts last ({@link #lifecycle()}, phase {@value #PHASE}): {@link SangamRuntime#start()} runs it
 *       only after every capability check passed and every other component started, and stops it first.</li>
 * </ol>
 *
 * So no job runs before the capability check; a refused start leaves the job executor stopped. Put it after every
 * plugin that sets the job executor activation (the settings re-apply plugin does not).
 */
public final class EngineStartOrder extends AbstractProcessEnginePlugin {

    private static final Logger LOG = LoggerFactory.getLogger(EngineStartOrder.class);

    /** The runtime component name of the job executor. */
    public static final String NAME = "sangam-engine-job-executor";

    /** Last to start, first to stop. */
    public static final int PHASE = Integer.MAX_VALUE;

    static final String STARTS_WITH_BUILD = "the engine's job executor would start with the engine build, before the"
            + " capability check (owner decision 376); start-up refused";

    private final EngineBuildClaims<ProcessEngine> claims;
    private volatile Boolean wanted;
    private volatile JobExecutor jobExecutor;
    private boolean started;

    public EngineStartOrder(EngineBuildClaims<ProcessEngine> claims) {
        this.claims = Objects.requireNonNull(claims, "claims");
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        wanted = configuration.isJobExecutorActivate();
        configuration.setJobExecutorActivate(false);
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl configuration) {
        JobExecutor je = configuration.getJobExecutor();
        if (configuration.isJobExecutorActivate() || je != null && (je.isAutoActivate() || je.isActive())) {
            throw new IllegalStateException(STARTS_WITH_BUILD);
        }
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine processEngine) {
        JobExecutor je = ((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration())
                .getJobExecutor();
        if (je != null && je.isActive()) {
            throw new IllegalStateException(STARTS_WITH_BUILD);
        }
        jobExecutor = je;
        claims.engineBuilt(processEngine);
    }

    /** Whether the configuration asked for the job executor ({@code null}: the engine was not configured yet). */
    public Boolean wanted() {
        return wanted;
    }

    /** The job executor as a runtime component: started last, stopped first. */
    public Lifecycle lifecycle() {
        return Lifecycle.of(NAME, PHASE, this::startJobExecutor, this::stopJobExecutor);
    }

    private synchronized void startJobExecutor() {
        Boolean want = wanted;
        if (want == null) {
            throw new IllegalStateException(NAME + ": the engine was not configured; start-up refused");
        }
        if (!want) {
            LOG.info("{}: the job executor is off by configuration; not started", NAME);
            return;
        }
        JobExecutor je = jobExecutor;
        if (je == null) {
            throw new IllegalStateException(NAME + ": the engine was not built; start-up refused");
        }
        je.start();
        started = true;
    }

    private synchronized void stopJobExecutor(Duration drain) {
        JobExecutor je = jobExecutor;
        if (started && je != null && je.isActive()) {
            je.shutdown();
        }
        started = false;
    }
}
