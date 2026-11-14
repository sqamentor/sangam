// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.engine.tenant.SharedDeployments;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;

/**
 * Decisions 144 and 197: the bridge hands the authenticated identity to the engine registry, maps rows to the contract
 * shape, and maps each SharedDeploymentException code to the status of routes/shared_deployments.py.
 */
class SharedDeploymentBridgeTest {

    static final RequestContext CTX = new RequestContext("platform", "deployer-1",
            Set.of(SharedDeployments.PLATFORM_MARKER, SharedDeployments.DEPLOYER_GROUP), null, null, "key-1", null,
            "127.0.0.1", "OIDC");
    static final Instant AT = Instant.parse("2026-11-13T00:00:00Z");
    static final Instant LATER = Instant.parse("2026-11-13T01:00:00Z");

    final List<String> calls = new ArrayList<>();
    final List<SecurityIdentity> actors = new ArrayList<>();

    SharedDeploymentBridge bridge(RuntimeException failure) {
        return new SharedDeploymentBridge(new SharedDeploymentBridge.Registry() {
            @Override
            public SharedDeployments.Registration register(SecurityIdentity actor, String deploymentId, String reason) {
                actors.add(actor);
                calls.add("register:" + deploymentId + ":" + reason);
                if (failure != null) {
                    throw failure;
                }
                return new SharedDeployments.Registration(deploymentId, actor.personId(), reason, AT, null, null, null);
            }

            @Override
            public SharedDeployments.Registration revoke(SecurityIdentity actor, String deploymentId, String reason) {
                actors.add(actor);
                calls.add("revoke:" + deploymentId + ":" + reason);
                if (failure != null) {
                    throw failure;
                }
                return new SharedDeployments.Registration(deploymentId, "deployer-0", "first", AT, actor.personId(),
                        LATER, reason);
            }

            @Override
            public List<SharedDeployments.Registration> list(boolean includeRevoked) {
                calls.add("list:" + includeRevoked);
                if (failure != null) {
                    throw failure;
                }
                return List.of(new SharedDeployments.Registration("d-1", "deployer-0", "first", AT, null, null, null));
            }
        });
    }

    @Test
    void theActorIsTheAuthenticatedIdentityAndRowsKeepEveryField() {
        SharedDeploymentBridge b = bridge(null);
        SharedDeploymentBridge.SharedDeployment d = b.register(CTX, "d-1", "history cleanup");
        assertEquals(new SharedDeploymentBridge.SharedDeployment("d-1", "deployer-1", "history cleanup", AT, null, null,
                null), d);
        SecurityIdentity actor = actors.get(0);
        assertEquals("deployer-1", actor.personId());
        assertFalse(actor.system());
        assertTrue(actor.groups().containsAll(Set.of(SharedDeployments.PLATFORM_MARKER,
                SharedDeployments.DEPLOYER_GROUP)), String.valueOf(actor.groups()));

        SharedDeploymentBridge.SharedDeployment r = b.revoke(CTX, "d-1", "retired");
        assertEquals(new SharedDeploymentBridge.SharedDeployment("d-1", "deployer-0", "first", AT, "deployer-1", LATER,
                "retired"), r);
        assertEquals(1, b.list(CTX, true).size());
        assertEquals(List.of("register:d-1:history cleanup", "revoke:d-1:retired", "list:true"), calls);
    }

    @Test
    void everyEngineCodeMapsToTheContractStatus() {
        Map<String, Integer> expected = Map.of(
                SharedDeployments.VALIDATION_FAILED, 400,
                SharedDeployments.NOT_PERMITTED, 403,
                SharedDeployments.NOT_FOUND, 404,
                SharedDeployments.REGISTERED, 409,
                SharedDeployments.REVOKED, 409,
                SharedDeployments.INVALID, 422);
        assertEquals("ACTION_NOT_PERMITTED", SharedDeployments.NOT_PERMITTED);
        expected.forEach((code, status) -> {
            SharedDeploymentBridge b = bridge(new SharedDeployments.SharedDeploymentException(code, "why"));
            ApiProblem p = assertThrows(ApiProblem.class, () -> b.register(CTX, "d-1", "r"), code);
            assertEquals(status, p.status(), code);
            assertEquals(code, p.code());
            assertEquals("why", p.detail(), "the code prefix is not repeated in the detail");
            ApiProblem q = assertThrows(ApiProblem.class, () -> b.revoke(CTX, "d-1", "r"), code);
            assertEquals(status, q.status(), code);
        });
    }

    @Test
    void aWrappedRefusalMapsTheSameAndOtherFailuresPassThrough() {
        RuntimeException wrapped = new RuntimeException("tx", new SharedDeployments.SharedDeploymentException(
                SharedDeployments.INVALID, "the deployment is tenant-owned"));
        ApiProblem p = assertThrows(ApiProblem.class, () -> bridge(wrapped).register(CTX, "d-1", "r"));
        assertEquals(422, p.status());
        assertEquals("SHARED_DEPLOYMENT_INVALID", p.code());

        IllegalStateException other = new IllegalStateException("database down");
        assertSame(other, assertThrows(IllegalStateException.class, () -> bridge(other).list(CTX, false)));
    }

    @Test
    void anUnknownEngineCodeIsNeverTurnedIntoAClientError() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> bridge(
                new SharedDeployments.SharedDeploymentException("SOMETHING_NEW", "x")).list(CTX, false));
        assertFalse(e instanceof ApiProblem);
    }
}
