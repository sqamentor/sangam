// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.security.SangamSecurity;
import org.sangam.security.breakglass.BreakGlassService;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * M-084 controller: the contract's grant view (approvals as objects, no token id), the coarse permission before any
 * work, body validation, and 501-style "not installed" without sangam-security. The service rules are covered by
 * {@code BreakGlassServiceH2Test} (sangam-security). Written for the test phase (not run here).
 */
class BreakGlassControllerTest {

    static RequestContext ctx() {
        return new RequestContext("t1", "admin1", Set.of(Permissions.GROUP_TENANT_ADMIN), null, null, null, null, null,
                "TEST");
    }

    static PermissionPort allowing(Set<String> granted) {
        return new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return granted.contains(permission);
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return granted;
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return true;
            }
        };
    }

    static BreakGlassController controller(PermissionPort permissions) {
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        return new BreakGlassController(none.getBeanProvider(SangamSecurity.class), none.getBeanProvider(DataSource.class),
                permissions);
    }

    @Test
    void theGrantViewFollowsTheContract() {
        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        BreakGlassService.Grant g = new BreakGlassService.Grant("g1", "emergency-admin", "holder", "req", "incident",
                "ISSUED", 2, List.of("ap1", "ap2"), 60, "secret-token-id", now, now, now.plusSeconds(3600), null, null, 0,
                "NONE", null, null, null, null);
        Map<String, Object> v = BreakGlassController.view(g);
        assertEquals(List.of(Map.of("personId", "ap1"), Map.of("personId", "ap2")), v.get("approvals"));
        assertEquals("ISSUED", v.get("status"));
        assertEquals(now.plusSeconds(3600), v.get("expiresAt"));
        assertFalse(v.containsKey("tokenId"), "the token id is not part of the grant view");
        assertFalse(v.containsKey("reviewNotes"), "review notes stay in the audit and the store");
    }

    @Test
    void theCoarsePermissionIsCheckedFirst() {
        BreakGlassController c = controller(allowing(Set.of()));
        int forbidden = ApiProblem.notPermitted(Permissions.ADMIN_WRITE).status();
        assertEquals(forbidden, assertThrows(ApiProblem.class,
                () -> c.request(ctx(), new BreakGlassController.BreakGlassRequest("k", "h", "r"))).status());
        assertEquals(forbidden, assertThrows(ApiProblem.class, () -> c.list(ctx(), null, null, null, null)).status());
        assertEquals(forbidden, assertThrows(ApiProblem.class, () -> c.approve(ctx(), "g1")).status());
        BreakGlassController readOnly = controller(allowing(Set.of(Permissions.ADMIN_READ)));
        assertEquals(forbidden, assertThrows(ApiProblem.class, () -> readOnly.approve(ctx(), "g1")).status(),
                "approving needs admin.write");
    }

    @Test
    void missingFieldsAre400BeforeAnyWork() {
        BreakGlassController c = controller(allowing(Set.of(Permissions.ADMIN_WRITE, Permissions.ADMIN_READ)));
        assertEquals(400, assertThrows(ApiProblem.class, () -> c.request(ctx(), null)).status());
        assertEquals(400, assertThrows(ApiProblem.class,
                () -> c.request(ctx(), new BreakGlassController.BreakGlassRequest("k", "h", " "))).status());
        assertEquals(400, assertThrows(ApiProblem.class,
                () -> c.reject(ctx(), "g1", new BreakGlassController.ReasonRequest(null))).status());
        assertEquals(400, assertThrows(ApiProblem.class,
                () -> c.review(ctx(), "g1", new BreakGlassController.BreakGlassReview(null, "n"))).status());
    }

    @Test
    void withoutTheSecurityModuleTheRouteIsNotInstalled() {
        BreakGlassController c = controller(allowing(Set.of(Permissions.ADMIN_WRITE, Permissions.ADMIN_READ)));
        ApiProblem p = assertThrows(ApiProblem.class, () -> c.approve(ctx(), "g1"));
        assertEquals(ApiProblem.notInstalled("break-glass access", "sangam-security").status(), p.status());
    }
}
