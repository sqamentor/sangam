// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.engine.tenant.SharedDeployments;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.web.Lists;
import org.sangam.server.bridge.SecurityPermissionBridge;
import org.sangam.server.bridge.SharedDeploymentBridge;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;

/**
 * Decisions 144, 197 and 250: the list needs platform.shared-deployment.read, register and revoke need
 * platform.shared-deployment.manage, nothing reaches the registry without them; register answers 201 with the
 * deployment id as Location and resource reference, revoke 200; a missing body is 400; the list is paged; without the
 * engine registry every route is 501.
 */
class SharedDeploymentControllerTest {

    static final RequestContext CTX = new RequestContext("platform", "deployer-1", Set.of(), null, null, "key-1", null,
            "127.0.0.1", "OIDC");
    static final Instant AT = Instant.parse("2026-11-13T00:00:00Z");

    final List<String> calls = new ArrayList<>();

    final SharedDeploymentBridge bridge = new SharedDeploymentBridge(new SharedDeploymentBridge.Registry() {
        @Override
        public SharedDeployments.Registration register(SecurityIdentity actor, String deploymentId, String reason) {
            calls.add("register:" + deploymentId);
            return new SharedDeployments.Registration(deploymentId, actor.personId(), reason, AT, null, null, null);
        }

        @Override
        public SharedDeployments.Registration revoke(SecurityIdentity actor, String deploymentId, String reason) {
            calls.add("revoke:" + deploymentId);
            return new SharedDeployments.Registration(deploymentId, "deployer-0", "first", AT, actor.personId(), AT,
                    reason);
        }

        @Override
        public List<SharedDeployments.Registration> list(boolean includeRevoked) {
            calls.add("list:" + includeRevoked);
            List<SharedDeployments.Registration> out = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                out.add(new SharedDeployments.Registration("d-" + i, "deployer-0", "r", AT, null, null, null));
            }
            return out;
        }
    });

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

    SharedDeploymentController controller(SharedDeploymentBridge b, PermissionPort permissions) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        if (b != null) {
            bf.addBean("registry", b);
        }
        return new SharedDeploymentController(bf.getBeanProvider(SharedDeploymentBridge.class), permissions,
                new RestProperties());
    }

    static SharedDeploymentController.SharedDeploymentRegistration reg(String id) {
        return new SharedDeploymentController.SharedDeploymentRegistration(id, "history cleanup");
    }

    @Test
    void thePermissionNamesAreTheContracts() {
        assertEquals("platform.shared-deployment.read", SecurityPermissionBridge.SHARED_DEPLOYMENT_READ);
        assertEquals("platform.shared-deployment.manage", SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE);
    }

    @Test
    void readingNeedsTheReadPermissionAndChangesNeedManage() {
        SharedDeploymentController reader = controller(bridge, holding(SecurityPermissionBridge.SHARED_DEPLOYMENT_READ));
        reader.list(CTX, null, null, null);
        for (Runnable change : List.<Runnable>of(() -> reader.register(CTX, reg("d-9")),
                () -> reader.revoke(CTX, "d-9", new SharedDeploymentController.SharedDeploymentRevocation("x")))) {
            ApiProblem p = assertThrows(ApiProblem.class, change::run);
            assertEquals(403, p.status());
            assertEquals("ACTION_NOT_PERMITTED", p.code());
        }
        SharedDeploymentController none = controller(bridge, holding("admin.read", "admin.write", "executor.admin"));
        assertEquals(403, assertThrows(ApiProblem.class, () -> none.list(CTX, true, null, null)).status());
        assertEquals(List.of("list:false"), calls, "nothing else reached the registry");
    }

    @Test
    void registerIs201WithTheIdAndRevokeIs200() {
        SharedDeploymentController c = controller(bridge, holding(SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE));
        ResponseEntity<SharedDeploymentBridge.SharedDeployment> created = c.register(CTX, reg("d-9"));
        assertEquals(201, created.getStatusCode().value());
        assertEquals("/api/v1/admin/shared-deployments/d-9", String.valueOf(created.getHeaders().getLocation()));
        assertEquals("d-9", created.getHeaders().getFirst(Lists.RESOURCE_REF));
        assertEquals("deployer-1", created.getBody().registeredBy(), "the actor is the caller, never a body field");

        ResponseEntity<SharedDeploymentBridge.SharedDeployment> revoked = c.revoke(CTX, "d-9",
                new SharedDeploymentController.SharedDeploymentRevocation("retired"));
        assertEquals(200, revoked.getStatusCode().value());
        assertEquals("retired", revoked.getBody().revokeReason());
        assertEquals("deployer-1", revoked.getBody().revokedBy());
        assertEquals(List.of("register:d-9", "revoke:d-9"), calls);
    }

    @Test
    void aMissingBodyIs400AndNeverReachesTheRegistry() {
        SharedDeploymentController c = controller(bridge, holding(SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE));
        for (Runnable bad : List.<Runnable>of(() -> c.register(CTX, null), () -> c.revoke(CTX, "d-9", null))) {
            ApiProblem p = assertThrows(ApiProblem.class, bad::run);
            assertEquals(400, p.status());
            assertEquals("VALIDATION_FAILED", p.code());
        }
        assertTrue(calls.isEmpty());
    }

    @Test
    void theListIsPagedWithTheTotalAndRevokedRowsOnlyWhenAsked() {
        SharedDeploymentController c = controller(bridge, holding(SecurityPermissionBridge.SHARED_DEPLOYMENT_READ));
        ResponseEntity<List<SharedDeploymentBridge.SharedDeployment>> r = c.list(CTX, true, 0, 2);
        assertEquals(2, r.getBody().size());
        assertEquals("3", r.getHeaders().getFirst(Lists.TOTAL));
        assertEquals(List.of("list:true"), calls);
    }

    @Test
    void withoutTheEngineRegistryEveryRouteIs501() {
        SharedDeploymentController c = controller(null, holding(SecurityPermissionBridge.SHARED_DEPLOYMENT_READ,
                SecurityPermissionBridge.SHARED_DEPLOYMENT_MANAGE));
        for (Runnable route : List.<Runnable>of(() -> c.list(CTX, null, null, null), () -> c.register(CTX, reg("d-9")),
                () -> c.revoke(CTX, "d-9", new SharedDeploymentController.SharedDeploymentRevocation("x")))) {
            ApiProblem p = assertThrows(ApiProblem.class, route::run);
            assertEquals(501, p.status());
            assertEquals("CAPABILITY_NOT_INSTALLED", p.code());
        }
    }
}
