// SPDX-License-Identifier: Apache-2.0
package org.sangam.backup;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where backups are scheduled and run (owner decision 155). {@link KubernetesBackupTargets} reads the chart's backup
 * CronJobs. The console never restores: restore stays the documented offline procedure.
 */
public interface BackupTargets {

    /** A scheduled backup, for example the chart's {@code pg-full} CronJob. */
    record Target(String id, String name, String schedule, String timeZone, boolean suspended, int active,
                  Instant lastScheduleTime, Instant lastSuccessfulTime) {
    }

    enum Trigger { SCHEDULED, MANUAL }

    enum Status { PENDING, RUNNING, SUCCEEDED, FAILED }

    /** One run of a target. {@code requestedBy} is null when the platform does not record it. */
    record Run(String id, String target, Trigger trigger, Status status, Instant startedAt, Instant finishedAt,
               String requestedBy, String message) {

        public boolean active() {
            return status == Status.PENDING || status == Status.RUNNING;
        }
    }

    /** Every target, ordered by id. */
    List<Target> targets() throws IOException;

    /** The runs the platform still keeps, of one target or (null) of all targets. */
    List<Run> runs(String targetId) throws IOException;

    /** The id a run started now would get; deterministic for the second, so a double submit cannot start two runs. */
    String runId(Target target, Instant now);

    /** Starts the run; empty when a run with this id exists already. */
    Optional<Run> start(Target target, String runId) throws IOException;
}
