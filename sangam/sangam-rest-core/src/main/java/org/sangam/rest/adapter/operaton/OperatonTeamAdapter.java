// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.task.TaskCountByCandidateGroupResult;
import org.operaton.bpm.engine.task.TaskQuery;
import org.sangam.rest.port.TeamPort;
import org.sangam.rest.port.RequestContext;

/** {@link TeamPort} on Operaton's task queries. */
public class OperatonTeamAdapter implements TeamPort {

    private final TaskService tasks;

    public OperatonTeamAdapter(TaskService tasks) {
        this.tasks = tasks;
    }

    @Override
    public TaskGroupReport taskGroupReport(RequestContext ctx) {
        if (ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            return new TaskGroupReport(0, 0, 0, 0, List.of());
        }
        long open = q(ctx).count();
        long assigned = q(ctx).taskAssigned().count();
        long unassigned = q(ctx).taskUnassigned().count();
        long none = q(ctx).taskUnassigned().withoutCandidateGroups().count();
        TreeSet<String> names = new TreeSet<>();
        for (TaskCountByCandidateGroupResult r : tasks.createTaskReport().taskCountByCandidateGroup()) {
            if (r.getGroupName() != null) {
                names.add(r.getGroupName());
            }
        }
        List<GroupCount> groups = new ArrayList<>();
        for (String g : names) {
            long n = q(ctx).taskCandidateGroup(g).taskUnassigned().count();
            if (n > 0) {
                groups.add(new GroupCount(g, n));
            }
        }
        return new TaskGroupReport(open, assigned, unassigned, none, List.copyOf(groups));
    }

    /**
     * Every count is an explicit {@code tenantIdIn(caller's tenant)} query: Operaton's own tenant check also shows rows
     * without a tenant (SL-73), and its candidate-group report has no tenant limit without an engine authentication.
     * The report only proposes group names; a name counts only when the tenant has an unassigned task offering it.
     * A caller without a tenant matches nothing (fail closed).
     */
    private TaskQuery q(RequestContext ctx) {
        return tasks.createTaskQuery().tenantIdIn(ctx.tenantId().trim());
    }
}
