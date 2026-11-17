// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.task.Task;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.jdbc.JdbcTaskFilterAdapter;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.adapter.operaton.OperatonTaskAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskFilterPort;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.web.TaskController;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Owner decision 406: {@code GET /api/v1/tasks?filterId=} runs a saved list of the caller's tenant with the task list's
 * own tenant and visibility rules still applied, so a list never widens what the caller sees: tenant A's run never
 * returns tenant B's (or tenant-less) tasks, a list naming a group or assignee outside the caller's visibility returns
 * nothing of it, and tenant B's list id is 404 for tenant A.
 */
class SavedTaskListRunH2Test {

    static final RequestContext ANIL = new RequestContext("tenant-a", "anil",
            Set.of(Permissions.GROUP_USER, "clerks"), null, null, null, null, null, "OIDC");

    static RestEngineFixture fx;
    static TaskController controller;
    static TaskFilterPort lists;

    @BeforeAll
    static void setUp() {
        fx = new RestEngineFixture("savedlistrun");
        RestProperties props = new RestProperties();
        JdbcAuditAdapter audit = new JdbcAuditAdapter(fx.tx, fx.engine.getHistoryService(),
                fx.engine.getRuntimeService(), props);
        OperatonTaskAdapter tasks = new OperatonTaskAdapter(fx.engine.getTaskService(), fx.engine.getRuntimeService(),
                fx.engine.getHistoryService(), fx.engine.getFormService(), fx.tx, audit, props);
        lists = new JdbcTaskFilterAdapter(fx.tx);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("lists", lists);
        controller = new TaskController(tasks, null, new GroupPermissionAdapter(),
                beans.getBeanProvider(TaskFilterPort.class));
        TaskService ts = fx.engine.getTaskService();
        task(ts, "a-clerks-1", "tenant-a", "clerks", null);
        task(ts, "a-clerks-2", "tenant-a", "clerks", null);
        task(ts, "a-desk", "tenant-a", "desk", null);          // a group anil is not in
        task(ts, "a-bob", "tenant-a", null, "bob");             // someone else's task
        task(ts, "b-clerks", "tenant-b", "clerks", null);       // same group, other tenant
        task(ts, "n-clerks", null, "clerks", null);             // no tenant
    }

    @AfterAll
    static void tearDown() {
        fx.close();
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

    private static List<String> run(RequestContext ctx, String filterId, String candidateGroup) {
        return controller.list(ctx, null, null, null, candidateGroup, null, null, null, null, null, null, null,
                null, null, null, null, null, null, filterId, null, null, null, null, null, null).getBody().stream().map(TaskPort.Task::id).sorted()
                .toList();
    }

    private static String save(String tenant, Map<String, Object> query) {
        return lists.create(tenant, "sup", "list", null, query, null).id();
    }

    @Test
    void tenantAsListReturnsOnlyTenantAsTasksOfTheList() {
        String clerks = save("tenant-a", Map.of("candidateGroup", "clerks"));
        assertEquals(List.of("a-clerks-1", "a-clerks-2"), run(ANIL, clerks, null),
                "tenant A's clerks list: never tenant B's or a tenant-less clerks task");
        assertEquals(List.of("a-clerks-1", "a-clerks-2"), run(ANIL, clerks, "clerks"), "same criterion again is fine");
        ApiProblem conflict = assertThrows(ApiProblem.class, () -> run(ANIL, clerks, "desk"));
        assertEquals("TASK_FILTER_CONFLICT", conflict.code());
    }

    @Test
    void aListNeverWidensTheCallersVisibility() {
        assertEquals(List.of(), run(ANIL, save("tenant-a", Map.of("candidateGroup", "desk")), null),
                "a group the caller is not in");
        assertEquals(List.of(), run(ANIL, save("tenant-a", Map.of("assignee", "bob")), null),
                "another user's tasks");
    }

    @Test
    void anotherTenantsListIsNotFound() {
        String bList = save("tenant-b", Map.of("candidateGroup", "clerks"));
        ApiProblem p = assertThrows(ApiProblem.class, () -> run(ANIL, bList, null));
        assertEquals(404, p.status());
        ApiProblem noTenant = assertThrows(ApiProblem.class, () -> run(new RequestContext(null, "anil",
                Set.of(Permissions.GROUP_USER, "clerks"), null, null, null, null, null, "OIDC"), bList, null));
        assertEquals("TENANT_REQUIRED", noTenant.code());
    }
}
