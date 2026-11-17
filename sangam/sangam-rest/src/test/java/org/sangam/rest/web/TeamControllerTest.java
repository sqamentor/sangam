// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TeamPort;
import org.sangam.rest.support.Permissions;

/** Owner decision 401: GET /team/task-groups needs team.read; who holds it by default. */
class TeamControllerTest {

    private static final TeamPort.TaskGroupReport REPORT =
            new TeamPort.TaskGroupReport(2, 1, 1, 0, List.of(new TeamPort.GroupCount("clerks", 1)));

    private static RequestContext ctx(String... groups) {
        return new RequestContext("t1", "p1", Set.of(groups), null, null, null, null, null, "OIDC");
    }

    @Test
    void theSupervisorReadsTheReportAndAPlainUserIsRefusedBeforeAnyRead() {
        AtomicInteger reads = new AtomicInteger();
        TeamController c = new TeamController(x -> {
            reads.incrementAndGet();
            return REPORT;
        }, new GroupPermissionAdapter());
        assertSame(REPORT, c.taskGroups(ctx(Permissions.GROUP_SUPERVISOR)));
        ApiProblem p = assertThrows(ApiProblem.class, () -> c.taskGroups(ctx(Permissions.GROUP_USER)));
        assertEquals(403, p.status());
        assertEquals(1, reads.get());
    }

    @Test
    void teamReadHolders() {
        GroupPermissionAdapter g = new GroupPermissionAdapter();
        assertEquals("team.read", Permissions.TEAM_READ);
        assertEquals("sangam-supervisor", Permissions.GROUP_SUPERVISOR);
        assertTrue(g.has(ctx(Permissions.GROUP_SUPERVISOR), Permissions.TEAM_READ));
        assertTrue(g.permissions(ctx(Permissions.GROUP_SUPERVISOR)).contains(Permissions.TEAM_READ));
        assertTrue(g.has(ctx(Permissions.GROUP_OPERATOR), Permissions.TEAM_READ));
        assertTrue(g.has(ctx(Permissions.GROUP_ADMIN), Permissions.TEAM_READ));
        for (String no : new String[] {Permissions.GROUP_USER, Permissions.GROUP_WORKER, Permissions.GROUP_AUDITOR,
                Permissions.GROUP_CITIZEN, Permissions.GROUP_ENGINE_ADMIN, Permissions.GROUP_TENANT_ADMIN}) {
            assertFalse(g.has(ctx(no), Permissions.TEAM_READ), no);
        }
    }
}
