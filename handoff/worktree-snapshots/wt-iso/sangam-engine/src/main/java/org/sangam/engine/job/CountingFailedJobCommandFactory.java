// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.jobexecutor.FailedJobCommandFactory;
import org.sangam.core.engine.EngineMetrics;

/**
 * {@code sangam_jobs_failed_total} (FLOWABLE-INTO-OPERATON D-39, R13): Operaton runs the failed-job command for every job execution that
 * failed (in the handler, at flush or at commit), from the job executor and from {@code ManagementService.executeJob}
 * alike. This factory counts the failure and delegates to the engine's own factory, so retries and incidents are
 * unchanged.
 */
public final class CountingFailedJobCommandFactory implements FailedJobCommandFactory {

    private final FailedJobCommandFactory delegate;
    private final EngineMetrics metrics;

    public CountingFailedJobCommandFactory(FailedJobCommandFactory delegate, EngineMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public Command<Object> getCommand(String jobId, Throwable exception) {
        metrics.jobFailed(exception == null ? "unknown" : exception.getClass().getSimpleName());
        return delegate.getCommand(jobId, exception);
    }
}
