// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.IdentityAdminPort;
import org.sangam.rest.port.IncidentActionPort;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;

/**
 * Round 6 item 4 at the web layer: each new route checks its permission before anything reaches the port, refuses a
 * missing reason or an out-of-contract field by name, and a host whose port does not implement the state change
 * answers 501 instead of pretending.
 */
class AdminLifecycleControllersTest {

    static final RequestContext CTX = new RequestContext("t1", "admin-1", Set.of(), null, null, null, null, "127.0.0.1",
            "OIDC");

    final List<String> calls = new ArrayList<>();

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

    /** A port that records the call and answers a canned value; with {@code defaults} it runs the interface defaults. */
    @SuppressWarnings("unchecked")
    <T> T port(Class<T> type, boolean defaults) {
        InvocationHandler h = (p, m, a) -> {
            if (defaults && m.isDefault()) {
                return InvocationHandler.invokeDefault(p, m, a);
            }
            calls.add(m.getName());
            Class<?> r = m.getReturnType();
            if (r == IdentityAdminPort.User.class) {
                return new IdentityAdminPort.User("u1", "u1", "U", null, true, List.of(), null, null);
            }
            if (r == IdentityAdminPort.Group.class) {
                return new IdentityAdminPort.Group("g1", "G", "LOCAL", 0, "LOCAL");
            }
            if (r == AdminPort.Tenant.class) {
                return new AdminPort.Tenant("t2", "T", "ACTIVE", "STANDARD", "SHARED", null, List.of(), null, null, 0);
            }
            if (r == AdminPort.TenantReadiness.class) {
                return new AdminPort.TenantReadiness("t2", "CREATED", false, List.of("X"), List.of("X"));
            }
            if (r == OpsPort.Incident.class) {
                return new OpsPort.Incident((String) a[1], "failedJob", null, "p1", null, "a", null, null, null, null,
                        null, null, "t1");
            }
            throw new UnsupportedOperationException(m.getName());
        };
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {type}, h);
    }

    static IdentityAdminPort.UserRequest user(String id, String name, Boolean active, String source) {
        return new IdentityAdminPort.UserRequest(id, null, name, null, active, source);
    }

    static OpsPort.IncidentAction because(String reason) {
        return new OpsPort.IncidentAction(reason, null, null);
    }

    static void status(int expected, Runnable route) {
        assertEquals(expected, assertThrows(ApiProblem.class, route::run).status());
    }

    @Test
    void everyRouteChecksItsPermissionFirst() {
        PermissionPort none = holding(Permissions.ADMIN_READ, Permissions.OPS_READ);
        IncidentActionsController inc = new IncidentActionsController(port(IncidentActionPort.class, false), none);
        IdentityAdminController id = new IdentityAdminController(port(IdentityAdminPort.class, false), none);
        AdminController adm = new AdminController(port(AdminPort.class, false), none);
        List<Runnable> routes = List.of(
                () -> inc.skipIncident(CTX, "i1", because("r")),
                () -> inc.cancelIncident(CTX, "i1", because("r")),
                () -> id.createUser(CTX, user("u1", "U", null, null)),
                () -> id.updateUser(CTX, "u1", user(null, "U", null, null)),
                () -> id.disableUser(CTX, "u1", new IdentityAdminPort.DecisionRequest("r")),
                () -> id.createGroup(CTX, new IdentityAdminPort.Group("g1", "G", null, null, null)),
                () -> id.updateGroup(CTX, "g1", new IdentityAdminPort.Group(null, "G", null, null, null)),
                () -> adm.validateTenant(CTX, "t2"),
                () -> adm.activateTenant(CTX, "t2", null),
                () -> adm.suspendTenant(CTX, "t2", new AdminController.ReasonRequest("r")));
        for (Runnable r : routes) {
            status(403, r);
        }
        assertTrue(calls.isEmpty(), "nothing reaches a port without the permission: " + calls);
    }

    @Test
    void aMissingReasonIsRefusedByName() {
        IncidentActionsController inc = new IncidentActionsController(port(IncidentActionPort.class, false),
                holding(Permissions.OPS_WRITE));
        IdentityAdminController id = new IdentityAdminController(port(IdentityAdminPort.class, false),
                holding(Permissions.ADMIN_WRITE));
        AdminController adm = new AdminController(port(AdminPort.class, false), holding(Permissions.TENANT_ADMIN));
        for (String blank : new String[] {null, " "}) {
            for (Runnable r : List.<Runnable>of(() -> inc.skipIncident(CTX, "i1", because(blank)),
                    () -> inc.cancelIncident(CTX, "i1", because(blank)),
                    () -> id.disableUser(CTX, "u1", new IdentityAdminPort.DecisionRequest(blank)),
                    () -> adm.suspendTenant(CTX, "t2", new AdminController.ReasonRequest(blank)))) {
                assertEquals("REASON_REQUIRED", assertThrows(ApiProblem.class, r::run).code());
            }
        }
        for (Runnable r : List.<Runnable>of(() -> inc.skipIncident(CTX, "i1", null), () -> inc.cancelIncident(CTX, "i1",
                null), () -> id.disableUser(CTX, "u1", null), () -> adm.suspendTenant(CTX, "t2", null))) {
            assertEquals("REASON_REQUIRED", assertThrows(ApiProblem.class, r::run).code());
        }
        assertTrue(calls.isEmpty());
        assertEquals("i1", inc.skipIncident(CTX, "i1", because("done by hand")).getBody().id());
        assertEquals("i1", inc.cancelIncident(CTX, "i1", because("duplicate")).getBody().id());
        assertEquals("ACTIVE", adm.activateTenant(CTX, "t2", null).getBody().status(), "activate takes no body");
        assertEquals(List.of("skipIncident", "cancelIncident", "activateTenant"), calls);
    }

    @Test
    void userAndGroupFieldsOutsideTheContractAreRefused() {
        IdentityAdminController id = new IdentityAdminController(port(IdentityAdminPort.class, false),
                holding(Permissions.ADMIN_WRITE, Permissions.POLICY_ADMIN));
        List<Supplier<Object>> bad = List.of(
                () -> id.createUser(CTX, null),
                () -> id.createUser(CTX, user(null, "U", null, null)),
                () -> id.createUser(CTX, user(" ", "U", null, null)),
                () -> id.createUser(CTX, user("x".repeat(65), "U", null, null)),
                () -> id.createUser(CTX, user(" u1", "U", null, null)),
                () -> id.createUser(CTX, user("u1", "n".repeat(256), null, null)),
                () -> id.createUser(CTX, user("u1", "U", false, null)),
                () -> id.createUser(CTX, user("u1", "U", null, "SCIM")),
                () -> id.updateUser(CTX, "u1", null),
                () -> id.updateUser(CTX, "u1", user(null, "U ", null, null)),
                () -> id.createGroup(CTX, null),
                () -> id.createGroup(CTX, new IdentityAdminPort.Group(null, "G", null, null, null)),
                () -> id.createGroup(CTX, new IdentityAdminPort.Group("g1", null, null, null, null)),
                () -> id.createGroup(CTX, new IdentityAdminPort.Group("g1", "G", null, null, "LDAP")),
                () -> id.updateGroup(CTX, "g1", new IdentityAdminPort.Group(null, " ", null, null, null)));
        for (Supplier<Object> s : bad) {
            ApiProblem p = assertThrows(ApiProblem.class, s::get);
            assertEquals(400, p.status());
            assertEquals("VALIDATION_FAILED", p.code());
            assertTrue(p.extensions().containsKey("field"), "the refusal names the field");
        }
        assertTrue(calls.isEmpty());
        assertEquals(201, id.createUser(CTX, user("u1", "U", true, "LOCAL")).getStatusCode().value());
        assertEquals(201, id.createGroup(CTX, new IdentityAdminPort.Group("g1", "G", null, null, null)).getStatusCode()
                .value());
        assertEquals(List.of("createUser", "createGroup"), calls);
    }

    @Test
    void aPortWithoutTheStateChangeAnswers501() {
        IdentityAdminController id = new IdentityAdminController(port(IdentityAdminPort.class, true),
                holding(Permissions.ADMIN_WRITE, Permissions.POLICY_ADMIN));
        AdminController adm = new AdminController(port(AdminPort.class, true), holding(Permissions.TENANT_ADMIN));
        for (Runnable r : List.<Runnable>of(() -> id.createUser(CTX, user("u1", "U", null, null)),
                () -> id.updateUser(CTX, "u1", user(null, "U", null, null)),
                () -> id.disableUser(CTX, "u1", new IdentityAdminPort.DecisionRequest("r")),
                () -> id.createGroup(CTX, new IdentityAdminPort.Group("g1", "G", null, null, null)),
                () -> id.updateGroup(CTX, "g1", new IdentityAdminPort.Group(null, "G", null, null, null)),
                () -> adm.validateTenant(CTX, "t2"), () -> adm.activateTenant(CTX, "t2", null),
                () -> adm.suspendTenant(CTX, "t2", new AdminController.ReasonRequest("r")))) {
            status(501, r);
        }
    }
}
