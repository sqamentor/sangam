// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.ExecutorPartitionPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;

/**
 * Decision 146 controller: every route needs executor.admin and nothing reaches the engine without it (a tenant
 * administrator's permissions do not suffice); the resize answers 202 with its id as resource reference; count and
 * reason are checked; the list is paged with the total header; a host without the engine port answers 501.
 */
class ExecutorPartitionControllerTest {

    static final RequestContext CTX = new RequestContext("platform", "ops-admin", Set.of(), null, null, null, null,
            "127.0.0.1", "OIDC");

    final List<String> calls = new ArrayList<>();

    final ExecutorPartitionPort.ExecutorPartitionResize resize = new ExecutorPartitionPort.ExecutorPartitionResize(
            "r-1", "REQUESTED", 4, 6, 7, 8, "MM-1", null, List.of(), "ops-admin", "peak",
            Instant.parse("2026-11-13T00:00:00Z"), null, null, null, null);

    final ExecutorPartitionPort.ExecutorPartitionMap map = new ExecutorPartitionPort.ExecutorPartitionMap("c1",
            "STABLE", 7, null, 4, 16, "CONTIGUOUS-1", List.of(), null);

    final ExecutorPartitionPort port = new ExecutorPartitionPort() {
        @Override
        public ExecutorPartitionMap map(RequestContext ctx) {
            calls.add("map");
            return map;
        }

        @Override
        public ExecutorPartitionResize resize(RequestContext ctx, ExecutorPartitionResizeRequest request) {
            calls.add("resize:" + request.partitionCount());
            return resize;
        }

        @Override
        public Page<ExecutorPartitionResize> list(RequestContext ctx, String state, Page.PageRequest page) {
            calls.add("list:" + state + ":" + page.page() + ":" + page.size());
            return Page.of(List.of(resize), 3, false);
        }

        @Override
        public ExecutorPartitionResize get(RequestContext ctx, String id) {
            calls.add("get:" + id);
            return resize;
        }
    };

    static PermissionPort holding(String... granted) {
        Set<String> set = Set.of(granted);
        return new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return set.contains(permission);
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return set;
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return false;
            }
        };
    }

    ExecutorPartitionController controller(ExecutorPartitionPort p, PermissionPort permissions) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        if (p != null) {
            bf.addBean("executor", p);
        }
        return new ExecutorPartitionController(bf.getBeanProvider(ExecutorPartitionPort.class), permissions);
    }

    static ExecutorPartitionPort.ExecutorPartitionResizeRequest req(Integer count, String reason) {
        return new ExecutorPartitionPort.ExecutorPartitionResizeRequest(count, reason, null);
    }

    @Test
    void everyRouteNeedsExecutorAdmin() {
        assertEquals("executor.admin", Permissions.ADMIN_EXECUTOR);
        ExecutorPartitionController c = controller(port, holding(Permissions.PROCESS_MODIFY, Permissions.PROCESS_READ));
        List<Runnable> routes = List.of(() -> c.map(CTX), () -> c.resize(CTX, req(6, "peak")),
                () -> c.list(CTX, null, null, null), () -> c.get(CTX, "r-1"));
        for (Runnable route : routes) {
            ApiProblem p = assertThrows(ApiProblem.class, route::run);
            assertEquals(403, p.status());
            assertEquals("ACTION_NOT_PERMITTED", p.code());
        }
        assertTrue(calls.isEmpty(), "nothing reaches the engine without executor.admin");
    }

    @Test
    void aResizeIsAcceptedWith202AndItsIdAsResourceReference() {
        ResponseEntity<ExecutorPartitionPort.ExecutorPartitionResize> r = controller(port,
                holding(Permissions.ADMIN_EXECUTOR)).resize(CTX, req(6, "peak"));
        assertEquals(202, r.getStatusCode().value());
        assertSame(resize, r.getBody());
        assertEquals("r-1", r.getHeaders().getFirst(Lists.RESOURCE_REF));
        assertEquals(List.of("resize:6"), calls);
    }

    @Test
    void countAndReasonAreRequired() {
        ExecutorPartitionController c = controller(port, holding(Permissions.ADMIN_EXECUTOR));
        for (ExecutorPartitionPort.ExecutorPartitionResizeRequest bad : new ExecutorPartitionPort
                .ExecutorPartitionResizeRequest[] {null, req(null, "peak"), req(6, null), req(6, " "),
                req(6, "x".repeat(1001))}) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> c.resize(CTX, bad));
            assertEquals(400, p.status());
            assertEquals("VALIDATION_FAILED", p.code());
        }
        assertTrue(calls.isEmpty());
        c.resize(CTX, req(0, "x".repeat(1000)));
        assertEquals(List.of("resize:0"), calls, "0 turns partitioning off; 1000 characters is the maximum");
    }

    @Test
    void mapGetAndThePagedListReachThePort() {
        ExecutorPartitionController c = controller(port, holding(Permissions.ADMIN_EXECUTOR));
        assertSame(map, c.map(CTX));
        assertSame(resize, c.get(CTX, "r-1"));
        ResponseEntity<List<ExecutorPartitionPort.ExecutorPartitionResize>> list = c.list(CTX, "COMPLETED", 1, 20);
        assertEquals(List.of(resize), list.getBody());
        assertEquals("3", list.getHeaders().getFirst(Lists.TOTAL));
        assertEquals(List.of("map", "get:r-1", "list:COMPLETED:1:20"), calls);
        ApiProblem state = assertThrows(ApiProblem.class, () -> c.list(CTX, "DONE", null, null));
        assertEquals(400, state.status());
    }

    @Test
    void aHostWithoutTheEnginePortAnswers501() {
        ExecutorPartitionController c = controller(null, holding(Permissions.ADMIN_EXECUTOR));
        ApiProblem p = assertThrows(ApiProblem.class, () -> c.map(CTX));
        assertEquals(501, p.status());
        assertEquals("CAPABILITY_NOT_INSTALLED", p.code());
        assertEquals(501, assertThrows(ApiProblem.class, () -> c.resize(CTX, req(6, "peak"))).status());
    }
}
