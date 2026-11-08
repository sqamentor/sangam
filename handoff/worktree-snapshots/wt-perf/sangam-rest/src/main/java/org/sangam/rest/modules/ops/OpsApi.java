// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.sangam.core.layer.Transactions;
import org.sangam.ops.metrics.UsageMetrics;
import org.sangam.ops.retention.HierarchyRetention;
import org.sangam.ops.search.GlobalSearch;
import org.sangam.ops.search.SearchIndexMaintenance;
import org.sangam.ops.secrets.SecretAdmin;
import org.sangam.ops.sequence.SequenceAdmin;
import org.sangam.ops.vars.ScopedVariables;

/** The sangam-ops services bound by sangam-server. Its presence activates {@link OpsRestConfiguration}. */
public record OpsApi(GlobalSearch search, UsageMetrics usage, ScopedVariables variables, SecretAdmin secrets, SequenceAdmin sequences,
                     SearchIndexMaintenance index, List<SearchIndexMaintenance.Reader> readers, Retention retention,
                     Supplier<List<MigrationStatus>> migration, Transactions tx, Set<String> outlierVariables) {

    /** Execute always re-plans on the server; a client-supplied list is never purged. */
    public interface Retention {
        List<HierarchyRetention.Tree> plan(String tenantId, int limit);

        int execute(String tenantId, int limit, String actor, String reason);

        /** Executed purges, newest first, each with its plan hash and reason. */
        List<HierarchyRetention.Run> runs(String tenantId);
    }

    /** One checkpoint row of a migration job. */
    public record MigrationStatus(String jobId, String sourceTable, long copied, long failed, long mismatched, String status) {
    }
}
