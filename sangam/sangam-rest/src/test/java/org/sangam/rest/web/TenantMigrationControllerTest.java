// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TenantMigrationPort;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Owner decision 381(3): every tenant migration route needs the platform-only permission and nothing reaches the engine
 * without it; a repair needs targetTenant (not the shared scope), evidenceRef and reason; a host without the engine
 * port answers 501.
 */
class TenantMigrationControllerTest {

    static final RequestContext CTX = new RequestContext("platform", "ops-admin", Set.of(), null, null, null, null,
            "127.0.0.1", "OIDC");

    final List<String> calls = new ArrayList<>();
    final TenantMigrationPort.TenantRepairResult result = new TenantMigrationPort.TenantRepairResult("p-1", "p-2", null,
            "t1", "TENANT_CONTEXT_MISSING", "REG-1");

    final TenantMigrationPort port = new TenantMigrationPort() {
        @Override
        public List<TenantFallbackInstance> inventory(RequestContext ctx) {
            calls.add("inventory");
            return List.of();
        }

        @Override
        public TenantRepairResult repair(RequestContext ctx, String id, TenantRepairRequest r) {
            calls.add("repair:" + id + ":" + r.targetTenant() + ":" + r.evidenceRef() + ":" + r.reason());
            return result;
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

    TenantMigrationController controller(TenantMigrationPort p, PermissionPort permissions) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        if (p != null) {
            bf.addBean("migration", p);
        }
        return new TenantMigrationController(bf.getBeanProvider(TenantMigrationPort.class), permissions);
    }

    static TenantMigrationPort.TenantRepairRequest req(String tenant, String evidence, String reason) {
        return new TenantMigrationPort.TenantRepairRequest(tenant, evidence, reason);
    }

    @Test
    void everyRouteNeedsThePlatformPermission() {
        assertEquals("platform.tenant-migration.admin", Permissions.TENANT_MIGRATION_ADMIN);
        TenantMigrationController c = controller(port, holding(Permissions.TENANT_ADMIN, Permissions.ADMIN_WRITE,
                Permissions.ADMIN_EXECUTOR));
        for (Runnable route : List.<Runnable>of(() -> c.inventory(CTX),
                () -> c.repair(CTX, "p-1", req("t1", "REG-1", "owner confirmed")))) {
            ApiProblem p = assertThrows(ApiProblem.class, route::run);
            assertEquals(403, p.status());
        }
        assertTrue(calls.isEmpty());
    }

    @Test
    void aRepairNeedsTheOwnerTheEvidenceAndAReason() {
        TenantMigrationController c = controller(port, holding(Permissions.TENANT_MIGRATION_ADMIN));
        for (TenantMigrationPort.TenantRepairRequest bad : new TenantMigrationPort.TenantRepairRequest[] {null,
                req(null, "REG-1", "r"), req(" ", "REG-1", "r"), req("x".repeat(65), "REG-1", "r"),
                req(" t1", "REG-1", "r"), req("t1", null, "r"), req("t1", " ", "r"), req("t1", "e".repeat(501), "r"),
                req("t1", "REG-1", null), req("t1", "REG-1", "x".repeat(1001))}) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> c.repair(CTX, "p-1", bad));
            assertEquals(400, p.status());
            assertEquals("VALIDATION_FAILED", p.code());
        }
        assertTrue(calls.isEmpty(), "nothing reaches the engine");
        assertSame(result, c.repair(CTX, "p-1", req("t1", " REG-1 ", "owner confirmed")));
        assertEquals(List.of("repair:p-1:t1:REG-1:owner confirmed"), calls);
    }

    @Test
    void theSharedScopeIsNotATargetTenant() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> controller(port, holding(Permissions.TENANT_MIGRATION_ADMIN))
                .repair(CTX, "p-1", req(org.sangam.core.layer.TenantKey.SHARED, "REG-1", "r")));
        assertEquals(400, p.status());
    }

    @Test
    void theInventoryReachesThePortAndAHostWithoutItAnswers501() {
        assertEquals(List.of(), controller(port, holding(Permissions.TENANT_MIGRATION_ADMIN)).inventory(CTX));
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> controller(null, holding(Permissions.TENANT_MIGRATION_ADMIN)).inventory(CTX));
        assertEquals(501, p.status());
        assertEquals("CAPABILITY_NOT_INSTALLED", p.code());
    }
}
