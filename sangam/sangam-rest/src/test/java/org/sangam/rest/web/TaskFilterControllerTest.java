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
            public <T> T inNewTx(Supplier<T> work) {
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
