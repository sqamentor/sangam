// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.engine.EngineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bounded synchronous job run of {@link OperatonEngineAdapter#executeDueJobs()} (A-12): at most
 * {@code maxJobExecutionsPerCall} executions, and at most {@code maxExecutionsPerJobDefinitionPerCall} per job
 * definition, so a refiring timer can never spin. Failing jobs are not rethrown (the engine decremented their retries).
 *
 * <p>A pass pages through every due job: a definition at its limit can fill a whole page (its jobs stay due), and
 * stopping there starved every due job sorted after it (E46 WF-2 deemed timer on MySQL, CI 44022815109). Jobs executed
 * in a pass may leave the result and shift later pages; the next pass picks up whatever was passed over.
 */
final class DueJobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(OperatonEngineAdapter.class);
    /** Page size of the due-job query. */
    static final int PAGE = 100;

    private DueJobRunner() {
    }

    static int run(ManagementService managementService, SangamEngineSettings settings, EngineMetrics metrics) {
        int max = settings.getMaxJobExecutionsPerCall();
        int perDefinitionMax = settings.getMaxExecutionsPerJobDefinitionPerCall();
        int executed = 0;
        Map<String, Integer> perDefinition = new HashMap<>();
        Set<String> skipped = new HashSet<>();
        boolean progressed = true;
        while (progressed && executed < max) {
            progressed = false;
            for (int first = 0; executed < max; first += PAGE) {
                List<Job> due = managementService.createJobQuery().executable().active().orderByJobId().asc()
                        .listPage(first, PAGE);
                for (Job job : due) {
                    if (executed >= max) {
                        break;
                    }
                    String def = job.getJobDefinitionId() == null ? "-" : job.getJobDefinitionId();
                    int n = perDefinition.getOrDefault(def, 0);
                    if (n >= perDefinitionMax) {
                        skipped.add(def);
                        continue;
                    }
                    perDefinition.put(def, n + 1);
                    metrics.jobsAcquired(1); // sangam_jobs_acquired_total (adapter acquisition path)
                    try {
                        managementService.executeJob(job.getId());
                    } catch (RuntimeException e) {
                        LOG.debug("job {} failed; the engine handles its retries", job.getId(), e);
                    }
                    executed++;
                    progressed = true;
                }
                if (due.size() < PAGE) {
                    break;
                }
            }
        }
        if (executed >= max || !skipped.isEmpty()) {
            metrics.jobExecutionBounded(skipped.isEmpty() ? "per-call" : "per-job-definition");
            LOG.warn("executeDueJobs stopped at a bound: {} executions; job definitions at their limit: {}", executed, skipped);
        }
        return executed;
    }
}
