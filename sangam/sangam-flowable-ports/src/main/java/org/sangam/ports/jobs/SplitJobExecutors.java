// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.jobs;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.ProcessEngineImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.bpm.engine.impl.jobexecutor.DefaultJobExecutor;
import org.operaton.bpm.engine.impl.jobexecutor.JobExecutor;
import org.sangam.core.layer.Dialect;

import java.util.ArrayList;
import java.util.List;

/**
 * Separate async and timer job executors plus post-commit hand-off (FC-24), all through configuration and plugin
 * hooks (no engine patch). WS-ENGINE already applies the config route (SKIP LOCKED, timers preferred, priorities);
 * this adds Flowable's executor shape:
 * <ul>
 *   <li>the engine's job executor acquires only non-timer jobs ({@link FilteredAcquireJobsCmd});</li>
 *   <li>a second {@link DefaultJobExecutor} with its own thread pool acquires only timers;</li>
 *   <li>optional global acquire lock for both;</li>
 *   <li>optional {@link AsyncJobHandOffInterceptor}.</li>
 * </ul>
 * Lifecycle: {@link #preInit} before the engine is built, {@link #postBuild} after it; {@link #close} on shutdown (the
 * engine does not stop the extra executor). Expired-lock reset is native in Operaton (an expired lock is acquirable),
 * so Flowable's {@code ResetExpiredJobsRunnable} is not needed.
 */
public class SplitJobExecutors implements AutoCloseable {

    private final Dialect dialect;
    private final int timerThreads;
    private final boolean globalLock;
    private final boolean handOff;
    private DefaultJobExecutor asyncExecutor;
    private DefaultJobExecutor timerExecutor;
    private volatile ProcessEngineImpl engine;

    public SplitJobExecutors(Dialect dialect, int timerThreads, boolean globalLock, boolean handOff) {
        this.dialect = dialect;
        this.timerThreads = timerThreads;
        this.globalLock = globalLock;
        this.handOff = handOff;
    }

    public void preInit(ProcessEngineConfigurationImpl cfg) {
        JobExecutor existing = cfg.getJobExecutor();
        asyncExecutor = existing instanceof DefaultJobExecutor d ? d : new DefaultJobExecutor();
        asyncExecutor.setLockOwner("sangam-async-" + java.util.UUID.randomUUID());
        asyncExecutor.setAcquireJobsCmdFactory(n -> new FilteredAcquireJobsCmd(asyncExecutor, n,
                FilteredAcquireJobsCmd.JobClass.NON_TIMERS, dialect, globalLock ? "async" : null));
        cfg.setJobExecutor(asyncExecutor);

        timerExecutor = new DefaultJobExecutor();
        timerExecutor.setLockOwner("sangam-timers-" + java.util.UUID.randomUUID());
        timerExecutor.setCorePoolSize(Math.max(1, timerThreads));
        timerExecutor.setMaxPoolSize(Math.max(1, timerThreads));
        timerExecutor.setAcquireJobsCmdFactory(n -> new FilteredAcquireJobsCmd(timerExecutor, n,
                FilteredAcquireJobsCmd.JobClass.TIMERS, dialect, globalLock ? "timers" : null));

        if (handOff) {
            List<CommandInterceptor> post = cfg.getCustomPostCommandInterceptorsTxRequired() == null ? new ArrayList<>()
                    : new ArrayList<>(cfg.getCustomPostCommandInterceptorsTxRequired());
            post.add(new AsyncJobHandOffInterceptor(() -> asyncExecutor, () -> engine, false));
            cfg.setCustomPostCommandInterceptorsTxRequired(post);
        }
    }

    public void postBuild(ProcessEngine processEngine) {
        engine = (ProcessEngineImpl) processEngine;
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
        timerExecutor.registerProcessEngine(engine);
        if (cfg.isJobExecutorActivate() && !timerExecutor.isActive()) {
            timerExecutor.start();
        }
    }

    public JobExecutor timerExecutor() {
        return timerExecutor;
    }

    public JobExecutor asyncExecutor() {
        return asyncExecutor;
    }

    @Override
    public void close() {
        if (timerExecutor != null && timerExecutor.isActive()) {
            timerExecutor.shutdown();
        }
    }
}
