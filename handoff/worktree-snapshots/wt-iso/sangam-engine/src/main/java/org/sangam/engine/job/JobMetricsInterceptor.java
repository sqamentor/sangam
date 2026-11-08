// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import org.operaton.bpm.engine.impl.cmd.AcquireJobsCmd;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.sangam.core.engine.EngineMetrics;

/**
 * {@code sangam_jobs_acquired_total} (FLOWABLE-INTO-OPERATON D-39, R13): counts the jobs each job-executor acquisition command
 * ({@link AcquireJobsCmd}) acquired. Registered as a post interceptor on the REQUIRED chain, which the job executor's
 * acquisition thread uses.
 */
public class JobMetricsInterceptor extends CommandInterceptor {

    private final EngineMetrics metrics;

    public JobMetricsInterceptor(EngineMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public <T> T execute(Command<T> command) {
        T result = next.execute(command);
        if (command instanceof AcquireJobsCmd && result instanceof AcquiredJobs acquired && acquired.size() > 0) {
            metrics.jobsAcquired(acquired.size());
        }
        return result;
    }
}
