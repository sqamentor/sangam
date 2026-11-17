// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.task.Task;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TeamPort;

/**
 * Owner decision 401: the team task-group report counts only the caller's tenant. Tenant B's tasks and groups, and
 * tasks without a tenant, never appear in tenant A's report (Operaton's own report has no such limit, SL-73).
 */
class OperatonTeamAdapterTenantIsolationTest {

    private static ProcessEngine engine;

    @BeforeAll
    static void start() {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:team-report-isolation;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP);
        cfg.setJobExecutorActivate(false);
        cfg.setHistory(ProcessEngineConfiguration.HISTORY_NONE);
        engine = cfg.buildProcessEngine();
        TaskService ts = engine.getTaskService();
        task(ts, "a1", "tenant-a", "clerks", null);
        task(ts, "a2", "tenant-a", "clerks", "alice");
        task(ts, "a3", "tenant-a", null, null);
        task(ts, "b1", "tenant-b", "b-secret-desk", null);
        task(ts, "b2", "tenant-b", "clerks", null);
        task(ts, "b3", "tenant-b", null, "bob");
        task(ts, "n1", null, "clerks", null);
        task(ts, "n2", null, "no-tenant-desk", null);
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static void task(TaskService ts, String id, String tenant, String group, String assignee) {
        Task t = ts.newTask(id);
        t.setName(id);
        t.setTenantId(tenant);
        t.setAssignee(assignee);
        ts.saveTask(t);
        if (group != null) {
            ts.addCandidateGroup(id, group);
        }
    }

    private static RequestContext ctx(String tenant) {
        return new RequestContext(tenant, "sup", Set.of("sangam-supervisor"), null, null, null, null, null, "OIDC");
    }

    @Test
    void tenantASeesOnlyItsOwnTasksAndGroups() {
        TeamPort.TaskGroupReport r = new OperatonTeamAdapter(engine.getTaskService()).taskGroupReport(ctx("tenant-a"));
        assertEquals(new TeamPort.TaskGroupReport(3, 1, 2, 1, List.of(new TeamPort.GroupCount("clerks", 1))), r);
    }

    @Test
    void tenantBSeesOnlyItsOwnTasksAndGroups() {
        TeamPort.TaskGroupReport r = new OperatonTeamAdapter(engine.getTaskService()).taskGroupReport(ctx("tenant-b"));
        assertEquals(new TeamPort.TaskGroupReport(3, 1, 2, 0, List.of(new TeamPort.GroupCount("b-secret-desk", 1),
                new TeamPort.GroupCount("clerks", 1))), r);
    }

    @Test
    void aCallerWithoutATenantSeesNothing() {
        TeamPort.TaskGroupReport r = new OperatonTeamAdapter(engine.getTaskService()).taskGroupReport(ctx(null));
        assertEquals(new TeamPort.TaskGroupReport(0, 0, 0, 0, List.of()), r);
    }
}
