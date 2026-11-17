// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.List;

/**
 * Owner decision 401 (Q4 option 3): the team workload of the caller's tenant, read-only, gated by {@code team.read}.
 * Replaces, for tenant identities, the engine-wide Operaton reads {@code /engine-rest/task/count} and
 * {@code /engine-rest/task/report/candidate-group-count}, which SL-73 keeps platform-only.
 */
public interface TeamPort {

    /**
     * One candidate group and the number of its open, unassigned tasks in the caller's tenant.
     *
     * @param group           the candidate group id
     * @param unassignedTasks open tasks of the tenant without an assignee that offer this group
     */
    record GroupCount(String group, long unassignedTasks) {
    }

    /**
     * The task-group report of the caller's tenant.
     *
     * @param open                   open tasks of the tenant
     * @param assigned               open tasks with an assignee
     * @param unassigned             open tasks without an assignee
     * @param withoutCandidateGroup  unassigned tasks that offer no candidate group
     * @param groups                 per candidate group, sorted by group id; groups without an unassigned task are left out
     */
    record TaskGroupReport(long open, long assigned, long unassigned, long withoutCandidateGroup,
                           List<GroupCount> groups) {
    }

    /** The report for {@code ctx}'s tenant only; tasks of another tenant and tasks without a tenant are never counted. */
    TaskGroupReport taskGroupReport(RequestContext ctx);
}
