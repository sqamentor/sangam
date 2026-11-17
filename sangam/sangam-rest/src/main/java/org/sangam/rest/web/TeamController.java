// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TeamPort;
import org.sangam.rest.support.Permissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner decision 401: the team workload of the caller's tenant, read-only, gated by {@code team.read}. Tenant
 * identities cannot use Operaton's task report on {@code /engine-rest} (SL-73: it is engine-wide).
 */
@RestController
@RequestMapping("/api/v1")
public class TeamController {

    private final TeamPort team;
    private final PermissionPort permissions;

    public TeamController(TeamPort team, PermissionPort permissions) {
        this.team = team;
        this.permissions = permissions;
    }

    @GetMapping("/team/task-groups")
    public TeamPort.TaskGroupReport taskGroups(RequestContext ctx) {
        permissions.require(ctx, Permissions.TEAM_READ);
        return team.taskGroupReport(ctx);
    }
}
