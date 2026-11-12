// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.bulk;

import org.sangam.core.security.SecurityIdentity;

import java.sql.Connection;
import java.util.Map;

/**
 * A bulk sink whose target checks permissions per object (M-130, owner decision 59). The job keeps a snapshot of its
 * creator's identity, taken when the job is created ({@link CreatorSnapshot}). Every row is applied as that creator, so
 * the target module re-checks the per-object permission for every row in the row's transaction. A sink never acts as
 * the system principal, and a job created by the system principal is refused.
 */
public interface IdentitySink extends BulkIngest.Sink {

    /**
     * Applies one row as the job's creator.
     *
     * @param creator the creator snapshot; its tenant is the job's tenant
     */
    String apply(Connection c, SecurityIdentity creator, String externalId, Map<String, Object> data, String existingRef);

    /** Never used for an identity sink: a job without a creator snapshot cannot be applied. */
    @Override
    default String apply(Connection c, String tenant, String actor, String externalId, Map<String, Object> data, String existingRef) {
        throw new BulkIngest.RowRejectedException("CREATOR_IDENTITY_MISSING", "the job has no creator identity snapshot");
    }
}
