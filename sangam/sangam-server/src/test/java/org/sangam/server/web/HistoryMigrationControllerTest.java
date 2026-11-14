// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.engine.history.HistoricInstanceMigration;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.web.Lists;
import org.sangam.server.bridge.HistoryMigrationBridge;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;

/**
 * Decision 258 (FC-18, contract routes/history_migration.py): history.migrate first, a missing body is 400, without the
 * engine bridge 501, and the answer is the result with the process instance id as resource reference.
 */
class HistoryMigrationControllerTest {

    static final RequestContext CTX = new RequestContext("t1", "admin-1", Set.of(), null, null, "key-1", null,
            "127.0.0.1", "OIDC");

    final List<String> calls = new ArrayList<>();

    final HistoryMigrationBridge bridge = new HistoryMigrationBridge((actor, pi, target, reason) -> {
        calls.add(pi + ":" + target + ":" + reason);
        return new HistoricInstanceMigration.Result(pi, "p:1:a", target, 1, 2, Map.of("ACT_HI_PROCINST", 1));
    });

    static PermissionPort holding(String... granted) {
        Set<String> g = Set.of(granted);
        return new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return g.contains(permission);
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return g;
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return false;
            }
        };
    }

    HistoryMigrationController controller(HistoryMigrationBridge b, PermissionPort permissions) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        if (b != null) {
            bf.addBean("migrations", b);
        }
        return new HistoryMigrationController(bf.getBeanProvider(HistoryMigrationBridge.class), permissions);
    }

    static HistoryMigrationController.HistoryInstanceMigrationRequest body() {
        return new HistoryMigrationController.HistoryInstanceMigrationRequest("p:2:b", "definition fix");
    }

    @Test
    void theMigrationAnswersTheResultWithTheInstanceAsResourceRef() {
        ResponseEntity<HistoryMigrationBridge.HistoryInstanceMigrationResult> r =
                controller(bridge, holding(Permissions.HISTORY_MIGRATE)).migrate(CTX, "pi-7", body());
        assertEquals(200, r.getStatusCode().value());
        assertEquals("pi-7", r.getHeaders().getFirst(Lists.RESOURCE_REF));
        assertEquals(1, r.getBody().fromVersion());
        assertEquals(2, r.getBody().toVersion());
        assertEquals(List.of("pi-7:p:2:b:definition fix"), calls);
    }

    @Test
    void historyMigrateIsCheckedFirst() {
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> controller(bridge, holding(Permissions.HISTORY_READ)).migrate(CTX, "pi-7", body()));
        assertEquals(403, p.status());
        assertEquals(403, assertThrows(ApiProblem.class,
                () -> controller(null, holding()).migrate(CTX, "pi-7", body())).status(), "before the 501");
        assertTrue(calls.isEmpty());
        assertEquals("history.migrate", Permissions.HISTORY_MIGRATE);
    }

    @Test
    void aMissingBodyIs400AndNoEngineBridgeIs501() {
        HistoryMigrationController c = controller(bridge, holding(Permissions.HISTORY_MIGRATE));
        ApiProblem missing = assertThrows(ApiProblem.class, () -> c.migrate(CTX, "pi-7", null));
        assertEquals(400, missing.status());
        assertEquals("VALIDATION_FAILED", missing.code());
        ApiProblem absent = assertThrows(ApiProblem.class, () -> controller(null,
                holding(Permissions.HISTORY_MIGRATE)).migrate(CTX, "pi-7", body()));
        assertEquals(501, absent.status());
        assertTrue(calls.isEmpty());
    }
}
