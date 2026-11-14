// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Optional;

/**
 * Owner decision 273: the endpoint permission of a stored bulk job, for the replay re-check of the bulk commands
 * ({@code bulk.job.create}, {@code bulk.chunk.upload}, {@code bulk.job.close}, {@code bulk.job.abort}), whose route
 * permission is that of the job's sink (BulkController). Contract agreed with INT-H2 (board 05:13:32Z, ACK 05:17:56Z).
 */
@FunctionalInterface
public interface BulkJobPermissions {

    /**
     * The permission of the sink of job {@code jobId} in {@code tenantId}: {@code BulkIngest.job(tenantId, jobId)}'s
     * object type, then that type's sink permission. Empty for an unknown job, a job of another tenant or a type without
     * a sink; it does not throw for those (the bridge treats anything thrown as a refusal).
     */
    Optional<String> sinkPermission(String tenantId, String jobId);
}
