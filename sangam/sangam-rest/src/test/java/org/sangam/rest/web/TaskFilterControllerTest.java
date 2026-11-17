// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskFilterPort;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TxSupport;

/** Owner decision 404: /team/task-filters gates (team.read, team.write), tenant requirement and validation. */
class TaskFilterControllerTest {

    /** Records every call with the tenant it was made for. */
    static final class Recording implements TaskFilterPort {
        final List<String> calls = new ArrayList<>();

        @Override
        public List<TaskFilter> list(String tenantId) {
            calls.add("list " + tenantId);
            return List.of();
        }

        @Override
        public Optional<TaskFilter> get(String tenantId, String id) {
            calls.add("get " + tenantId + " " + id);
            return Optional.empty();
        }

        @Override
        public TaskFilter create(String tenantId, String actor, String name, String owner, Map<String, Object> query,
                                 Map<String, Object> properties) {
            calls.add("create " + tenantId + " " + actor + " " + name);
            return new TaskFilter("f1", name, owner, query, properties == null ? Map.of() : properties, actor,
                    Instant.EPOCH, 1);
        }

        @Override
        public Optional<TaskFilter> update(String tenantId, String id, int expectedVersion, String name, String owner,
                                           Map<String, Object> query, Map<String, Object> properties) {
            calls.add("update " + tenantId + " " + id + " " + expectedVersion + " " + name);
            return "f1".equals(id) ? Optional.of(new TaskFilter(id, name, owner, query, Map.of(), "p0", Instant.EPOCH,
                    expectedVersion + 1)) : Optional.empty();
        }

        @Override
        public Optional<TaskFilter> delete(String tenantId, String id, int expectedVersion) {
            calls.add("delete " + tenantId + " " + id + " " + expectedVersion);
            return "f1".equals(id) ? Optional.of(new TaskFilter(id, "n", null, Map.of(), Map.of(), "p0",
                    Instant.EPOCH, expectedVersion)) : Optional.empty();
        }
    }

    private final Recording port = new Recording();
    private final List<String> audits = new ArrayList<>();
    private final TaskFilterController c = new TaskFilterController(port, new GroupPermissionAdapter(), audit(),
            directTx());

    private AuditPort audit() {
        return (AuditPort) Proxy.newProxyInstance(AuditPort.class.getClassLoader(), new Class<?>[] {AuditPort.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("record")) {
                        audits.add(args[1] + " " + args[2]);
                        return "e1";
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    /** Runs the work directly (no database in this test). */
    private static TxSupport directTx() {
        return new TxSupport(null, null) {
            @Override
            public <T> T inTx(Supplier<T> work) {
                return work.get();
            }

            @Override
            public <T> T inTxWithStatus(java.util.function.Function<TxStatus, T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T withReadConnection(org.sangam.core.api.ReadWork<T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T inNewTx(Supplier<T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T inNewReadTx(org.sangam.core.api.ReadWork<T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.sql.Connection connection() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void afterCommit(Runnable work) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static RequestContext ctx(String tenant, String... groups) {
        return new RequestContext(tenant, "p1", Set.of(groups), null, null, null, null, null, "OIDC");
    }

    @Test
    void readersListAndReadOnlyTheirTenantAndOthersAreRefusedBeforeAnyRead() {
        c.list(ctx("t1", Permissions.GROUP_SUPERVISOR));
        assertEquals(404, assertThrows(ApiProblem.class, () -> c.get(ctx("t1", Permissions.GROUP_TENANT_ADMIN), "x"))
                .status());
        assertEquals(List.of("list t1", "get t1 x"), port.calls);
        assertEquals(403, assertThrows(ApiProblem.class, () -> c.list(ctx("t1", Permissions.GROUP_USER))).status());
        ApiProblem noTenant = assertThrows(ApiProblem.class, () -> c.list(ctx(null, Permissions.GROUP_SUPERVISOR)));
        assertEquals("TENANT_REQUIRED", noTenant.code());
        assertEquals(2, port.calls.size(), "nothing read for a refused caller");
    }

    @Test
    void createNeedsTeamWriteAndIsAuditedOnTheTenantsChain() {
        var r = c.create(ctx("t1", Permissions.GROUP_SUPERVISOR),
                new TaskFilterController.CreateRequest("  Clerks ", "", Map.of("candidateGroup", "clerks"), null));
        assertEquals(201, r.getStatusCode().value());
        assertEquals("/api/v1/team/task-filters/f1", r.getHeaders().getLocation().toString());
        assertEquals("Clerks", r.getBody().name());
        assertEquals(List.of("create t1 p1 Clerks"), port.calls);
        assertEquals(List.of("sec:t1 TASK_FILTER_CREATED"), audits);
        assertEquals(403, assertThrows(ApiProblem.class, () -> c.create(ctx("t1", Permissions.GROUP_AUDITOR),
                new TaskFilterController.CreateRequest("x", null, Map.of(), null))).status());
    }

    @Test
    void invalidBodiesAreRefusedAndNothingIsStored() {
        RequestContext sup = ctx("t1", Permissions.GROUP_SUPERVISOR);
        assertEquals(400, assertThrows(ApiProblem.class, () -> c.create(sup, null)).status());
        assertEquals(400, assertThrows(ApiProblem.class, () -> c.create(sup,
                new TaskFilterController.CreateRequest(" ", null, Map.of(), null))).status());
        assertEquals(400, assertThrows(ApiProblem.class, () -> c.create(sup,
                new TaskFilterController.CreateRequest("n".repeat(256), null, Map.of(), null))).status());
        assertEquals(400, assertThrows(ApiProblem.class, () -> c.create(sup,
                new TaskFilterController.CreateRequest("n", null, null, null))).status());
        assertEquals(400, assertThrows(ApiProblem.class, () -> c.create(sup,
                new TaskFilterController.CreateRequest("n", null, Map.of("nameLike", "x".repeat(17_000)), null)))
                .status());
        for (String k : new String[] {"tenantIdIn", "withoutTenantId", "TenantId"}) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> c.create(sup,
                    new TaskFilterController.CreateRequest("n", null, Map.of(k, List.of("t2")), null)));
            assertEquals(422, p.status(), k);
            assertEquals("TASK_FILTER_INVALID", p.code());
        }
        assertTrue(port.calls.isEmpty());
        assertTrue(audits.isEmpty());
    }

    @Test
    void updateAndDeleteNeedTeamWriteIfMatchAndAreAuditedOnTheTenantsChain() {
        RequestContext sup = ctx("t1", Permissions.GROUP_SUPERVISOR);
        var body = new TaskFilterController.CreateRequest(" Desk ", null, Map.of("candidateGroup", "desk"), null);
        var r = c.update(sup, "f1", "\"3\"", body);
        assertEquals("\"4\"", r.getHeaders().getETag());
        assertEquals("Desk", r.getBody().name());
        assertEquals(204, c.delete(sup, "f1", "W/\"4\"", " no longer used ").getStatusCode().value());
        assertEquals(List.of("update t1 f1 3 Desk", "delete t1 f1 4"), port.calls);
        assertEquals(List.of("sec:t1 TASK_FILTER_UPDATED", "sec:t1 TASK_FILTER_DELETED"), audits);

        assertEquals(404, assertThrows(ApiProblem.class, () -> c.update(sup, "other", "\"1\"", body)).status());
        assertEquals(404, assertThrows(ApiProblem.class, () -> c.delete(sup, "other", "\"1\"", "r")).status());
        assertEquals(428, assertThrows(ApiProblem.class, () -> c.update(sup, "f1", null, body)).status());
        assertEquals(428, assertThrows(ApiProblem.class, () -> c.delete(sup, "f1", null, "r")).status());
        assertEquals("REASON_REQUIRED", assertThrows(ApiProblem.class, () -> c.delete(sup, "f1", "\"1\"", " "))
                .code());
        assertEquals(403, assertThrows(ApiProblem.class, () -> c.update(ctx("t1", Permissions.GROUP_AUDITOR), "f1",
                "\"1\"", body)).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> c.delete(ctx("t1", Permissions.GROUP_USER), "f1",
                "\"1\"", "r")).status());
        assertEquals("TENANT_REQUIRED", assertThrows(ApiProblem.class, () -> c.delete(ctx(null,
                Permissions.GROUP_SUPERVISOR), "f1", "\"1\"", "r")).code());
        assertEquals(422, assertThrows(ApiProblem.class, () -> c.update(sup, "f1", "\"1\"",
                new TaskFilterController.CreateRequest("n", null, Map.of("nameLike", "x"), null))).status());
        assertEquals(4, port.calls.size(), "refused requests touch nothing");
        assertEquals(2, audits.size());
    }

    @Test
    void aSavedQueryHoldsOnlyCriteriaTheTaskListApplies() {
        RequestContext sup = ctx("t1", Permissions.GROUP_SUPERVISOR);
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("priority", "3"), Map.of("dueBefore", "x"),
                Map.of("minPriority", 2.5), Map.of("maxPriority", true), Map.of("overdue", "yes"),
                Map.of("dueAfter", "2026-11-17"), Map.of("nameLike", "x"),
                Map.of("candidateGroups", List.of("a")), Map.of("assignee", 7), Map.of("mine", "yes"),
                Map.of("state", "DONE"), Map.of("currency", "inr"), Map.of("variableNames", List.of()),
                Map.of("candidateGroup", " "))) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> c.create(sup,
                    new TaskFilterController.CreateRequest("n", null, bad, null)), bad.toString());
            assertEquals(422, p.status(), bad.toString());
            assertEquals("TASK_FILTER_INVALID", p.code(), bad.toString());
        }
        assertTrue(port.calls.isEmpty());
        c.create(sup, new TaskFilterController.CreateRequest("all", null, Map.of("assignee", "a", "mine", true,
                "unassigned", false, "candidateGroup", "g", "processDefinitionKey", "p", "taskDefinitionKey", "t",
                "businessKey", "b", "state", "ACTIVE", "sortBy", "due", "sortOrder", "asc"), null));
        c.create(sup, new TaskFilterController.CreateRequest("money", null, Map.of("currency", "INR",
                "variableNames", List.of("amount")), null));
        c.create(sup, new TaskFilterController.CreateRequest("urgent", null, Map.of("priority", 3, "minPriority", 1,
                "maxPriority", 5, "dueBefore", "2026-12-01T00:00:00Z", "dueAfter", "2026-11-01T00:00:00Z",
                "overdue", true), null));
        assertEquals(3, port.calls.size());
    }

    @Test
    void runningAListCombinesItsCriteriaWithTheRequest() {
        var req = new org.sangam.rest.port.TaskPort.TaskFilter(null, null, null, null, "seat-1", null, null, null,
                null, null, null, "name", null, List.of("x"), null);
        var f = SavedTaskQuery.combine(Map.of("candidateGroup", "clerks", "sortBy", "due", "state", "ACTIVE",
                "variableNames", List.of("amount")), req);
        assertEquals("clerks", f.candidateGroup());
        assertEquals("ACTIVE", f.state());
        assertEquals("name", f.sortBy(), "sorting is the request's display choice");
        assertEquals("seat-1", f.seatId());
        assertEquals(List.of("amount", "x"), f.variableNames());
        var other = new org.sangam.rest.port.TaskPort.TaskFilter("bob", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        assertEquals("TASK_FILTER_CONFLICT", assertThrows(ApiProblem.class,
                () -> SavedTaskQuery.combine(Map.of("assignee", "alice"), other)).code());
        assertEquals("TASK_FILTER_INVALID", assertThrows(ApiProblem.class,
                () -> SavedTaskQuery.combine(Map.of("assigned", false), other)).code(), "an old unknown key never runs");
    }

    @Test
    void teamWriteHolders() {
        GroupPermissionAdapter g = new GroupPermissionAdapter();
        assertEquals("team.write", Permissions.TEAM_WRITE);
        for (String yes : new String[] {Permissions.GROUP_SUPERVISOR, Permissions.GROUP_OPERATOR,
                Permissions.GROUP_TENANT_ADMIN, Permissions.GROUP_ADMIN}) {
            assertTrue(g.has(ctx("t1", yes), Permissions.TEAM_WRITE), yes);
            assertTrue(g.has(ctx("t1", yes), Permissions.TEAM_READ), yes);
        }
        for (String no : new String[] {Permissions.GROUP_USER, Permissions.GROUP_AUDITOR, Permissions.GROUP_WORKER,
                Permissions.GROUP_ENGINE_ADMIN}) {
            assertTrue(!g.has(ctx("t1", no), Permissions.TEAM_WRITE), no);
        }
    }
}
