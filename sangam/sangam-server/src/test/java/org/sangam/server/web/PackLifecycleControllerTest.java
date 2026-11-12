// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.rest.port.ApiProblem;

/** SL-41: request validation and the mapping of PackDeployer refusals to problems (no server). */
class PackLifecycleControllerTest {

    static ApiProblem refused(RuntimeException e) {
        return assertThrows(ApiProblem.class, () -> PackLifecycleController.call(() -> {
            throw e;
        }));
    }

    @Test
    void notInstalledIs404() {
        ApiProblem p = refused(new PackDeployer.PackLifecycleException("PACK_NOT_INSTALLED", "x", List.of()));
        assertEquals(404, p.status());
        assertEquals("PACK_NOT_INSTALLED", p.code());
        assertTrue(!p.extensions().containsKey("blockers"));
    }

    @Test
    void dependantsLiveInstancesAndNoPreviousVersionAre409WithBlockers() {
        ApiProblem dep = refused(new PackDeployer.PackLifecycleException("PACK_HAS_DEPENDANTS", "x", List.of("p2")));
        assertEquals(409, dep.status());
        assertEquals("PACK_HAS_DEPENDANTS", dep.code());
        assertEquals(List.of("p2"), dep.extensions().get("blockers"));
        ApiProblem live = refused(new PackDeployer.PackLifecycleException("PACK_HAS_LIVE_INSTANCES", "x",
                List.of("p@1.0.0: 2 process instance(s)")));
        assertEquals(409, live.status());
        assertEquals(409, refused(new PackDeployer.PackLifecycleException("PACK_NO_PREVIOUS_VERSION", "x",
                List.of())).status());
    }

    @Test
    void aRejectedModelIs422WithTheViolations() {
        ApiProblem p = refused(new PackDeployer.PackRejectedException("p", List.of("v1", "v2")));
        assertEquals(422, p.status());
        assertEquals("PACK_REJECTED", p.code());
        assertEquals(List.of("v1", "v2"), p.extensions().get("violations"));
    }

    @Test
    void otherFailuresAreNotRewritten() {
        IllegalStateException boom = new IllegalStateException("engine");
        assertSame(boom, assertThrows(IllegalStateException.class, () -> PackLifecycleController.call(() -> {
            throw boom;
        })));
    }

    @Test
    void tenantAndReasonAreRequired() {
        ApiProblem noTenant = assertThrows(ApiProblem.class, () -> PackLifecycleController.requireTenant(" "));
        assertEquals("tenant", noTenant.extensions().get("field"));
        ApiProblem noBody = assertThrows(ApiProblem.class, () -> PackLifecycleController.requireBody(null));
        assertEquals(400, noBody.status());
        ApiProblem noReason = assertThrows(ApiProblem.class, () -> PackLifecycleController.requireBody(
                new PackLifecycleController.PackLifecycleRequest("t1", "")));
        assertEquals("REASON_REQUIRED", noReason.code());
        assertEquals("reason", noReason.extensions().get("field"));
        assertEquals("t1", PackLifecycleController.requireBody(
                new PackLifecycleController.PackLifecycleRequest("t1", "wrong pack")).tenant());
    }
}
