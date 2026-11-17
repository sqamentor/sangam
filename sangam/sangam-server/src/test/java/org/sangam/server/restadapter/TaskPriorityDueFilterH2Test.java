// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
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
 * Lane R9-filters (decision 404 follow-up): saved task lists with priority and due-date conditions run through
 * {@code GET /api/v1/tasks}, and the result stays inside the caller's tenant and task visibility: tasks of another
 * tenant, of no tenant and of a group the caller is not in never appear, whatever their priority or due date.
 */
class TaskPriorityDueFilterH2Test {

    static final RequestContext ANIL = new RequestContext("tenant-a", "anil",
            Set.of(Permissions.GROUP_USER, "clerks"), null, null, null, null, null, "OIDC");
    static final Instant NOW = Instant.now();
    static final Instant PAST = NOW.minus(Duration.ofDays(2));
    static final Instant FUTURE = NOW.plus(Duration.ofDays(2));

    static RestEngineFixture fx;
    static TaskController controller;
    static TaskFilterPort lists;

    @BeforeAll
    static void setUp() {
        fx = new RestEngineFixture("prioritydue");
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
        task(ts, "a-p10-past", "tenant-a", "clerks", 10, PAST);
        task(ts, "a-p50-future", "tenant-a", "clerks", 50, FUTURE);
        task(ts, "a-p90-nodue", "tenant-a", "clerks", 90, null);
        task(ts, "a-desk-p90-past", "tenant-a", "desk", 90, PAST);    // a group anil is not in
        task(ts, "b-p90-past", "tenant-b", "clerks", 90, PAST);       // other tenant
        task(ts, "n-p90-past", null, "clerks", 90, PAST);             // no tenant
    }

    @AfterAll
    static void tearDown() {
        fx.close();
        // TestClassStaticsReleasedTest (R9-statics): a test class stays loaded for the whole run
        fx = null;
        controller = null;
        lists = null;
    }

    private static void task(TaskService ts, String id, String tenant, String group, int priority, Instant due) {
        Task t = ts.newTask(id);
        t.setName(id);
        t.setTenantId(tenant);
        t.setPriority(priority);
        t.setDueDate(due == null ? null : Date.from(due));
        ts.saveTask(t);
        ts.addCandidateGroup(id, group);
    }

    private static List<String> run(String filterId) {
        return controller.list(ANIL, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, filterId, null, null, null, null, null, null).getBody().stream().map(TaskPort.Task::id).sorted()
                .toList();
    }

    private static List<String> saved(Map<String, Object> query) {
        return run(lists.create("tenant-a", "sup", "list", null, query, null).id());
    }

    @Test
    void savedListsFilterByPriority() {
        assertEquals(List.of("a-p10-past"), saved(Map.of("priority", 10)));
        assertEquals(List.of("a-p50-future", "a-p90-nodue"), saved(Map.of("minPriority", 50)));
        assertEquals(List.of("a-p10-past", "a-p50-future"), saved(Map.of("maxPriority", 50)));
        assertEquals(List.of("a-p50-future"), saved(Map.of("minPriority", 20, "maxPriority", 60)));
    }

    private static List<String> direct(Integer priority, Integer min, Integer max, String before, String after,
                                       Boolean overdue) {
        return controller.list(ANIL, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, priority, min, max, before, after, overdue).getBody()
                .stream().map(TaskPort.Task::id).sorted().toList();
    }

    @Test
    void getTasksFiltersByPriorityAndDueDateInsideTheCallersVisibility() {
        assertEquals(List.of("a-p90-nodue"), direct(90, null, null, null, null, null),
                "priority 90: never the desk, tenant B or tenant-less p90 tasks");
        assertEquals(List.of("a-p50-future", "a-p90-nodue"), direct(null, 50, null, null, null, null));
        assertEquals(List.of("a-p10-past"), direct(null, null, 49, null, null, null));
        assertEquals(List.of("a-p10-past"), direct(null, null, null, null, null, true));
        assertEquals(List.of("a-p10-past"), direct(null, null, null, FUTURE.toString(), null, true),
                "overdue with a later dueBefore: the earlier bound (now) applies");
        assertEquals(List.of("a-p10-past", "a-p50-future"), direct(null, null, null, null,
                PAST.minus(Duration.ofDays(1)).toString(), null));
        assertEquals(List.of("a-p10-past", "a-p50-future", "a-p90-nodue"), direct(null, null, null, null, null,
                false), "overdue=false is no condition");
        ApiProblem bad = assertThrows(ApiProblem.class, () -> direct(null, null, null, "2026-11-17", null, null));
        assertEquals(400, bad.status());
        assertEquals(400, assertThrows(ApiProblem.class, () -> direct(null, 9, 1, null, null, null)).status());
        assertEquals(400, assertThrows(ApiProblem.class,
                () -> direct(null, null, null, PAST.toString(), FUTURE.toString(), null)).status());
    }

    @Test
    void aRequestCriterionThatDiffersFromTheListIsAConflict() {
        String id = lists.create("tenant-a", "sup", "list", null, Map.of("minPriority", 50), null).id();
        ApiProblem p = assertThrows(ApiProblem.class, () -> controller.list(ANIL, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, id, null, 60, null, null, null,
                null));
        assertEquals("TASK_FILTER_CONFLICT", p.code());
        assertEquals(List.of("a-p90-nodue"), controller.list(ANIL, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, id, 90, 50, null, null, null, null).getBody()
                .stream().map(TaskPort.Task::id).toList(), "the list's minPriority and the request's priority");
    }

    @Test
    void savedListsFilterByDueDate() {
        assertEquals(List.of("a-p10-past"), saved(Map.of("overdue", true)),
                "overdue: due before now; never another tenant's, a tenant-less or an invisible group's task");
        assertEquals(List.of("a-p10-past"), saved(Map.of("dueBefore", NOW.toString())));
        assertEquals(List.of("a-p50-future"), saved(Map.of("dueAfter", NOW.toString())));
        assertEquals(List.of(), saved(Map.of("dueAfter", NOW.toString(), "overdue", true)));
        assertEquals(List.of("a-p10-past"), saved(Map.of("overdue", true, "minPriority", 5,
                "candidateGroup", "clerks")));
    }
}
