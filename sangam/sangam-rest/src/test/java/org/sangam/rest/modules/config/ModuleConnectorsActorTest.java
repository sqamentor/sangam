// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;

/**
 * The actor of a module connector call made from a REST endpoint is the authenticated caller, never a value of the
 * request body. Before, the body's {@code actor} won ({@code putIfAbsent}): a caller could have a collection or a
 * waiver recorded under somebody else's name. Now a body that names another actor is refused (422 ACTOR_MISMATCH, the
 * connector is not called) and the caller is always set.
 */
class ModuleConnectorsActorTest {

    static final RequestContext CALLER = new RequestContext("T1", "user:cashier", Set.of(), null, null, null, null, null, "OIDC");

    static final class Recording implements SangamConnector {
        final List<ConnectorRequest> requests = new ArrayList<>();

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("billing", "billing", CapabilityClass.C_NEITHER, List.of("collect"), ConnectorProfile.LIVE);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            requests.add(request);
            return ConnectorResult.Success.of(Map.of("ok", true), null);
        }
    }

    @Test
    void aBodyActorOtherThanTheCallerIsRefusedAndTheConnectorIsNotCalled() {
        Recording connector = new Recording();
        Map<String, Object> body = new HashMap<>(Map.of("consumer", "C-1", "actor", "user:somebody-else"));
        ApiProblem refused = assertThrows(ApiProblem.class,
                () -> ModuleConnectors.call(connector, CALLER, "T1", "collect", body));
        assertEquals(422, refused.status());
        assertEquals("ACTOR_MISMATCH", refused.code());
        assertEquals(List.of(), connector.requests, "nothing is done");
    }

    @Test
    void theCallerIsAlwaysTheActorOfTheCall() {
        Recording connector = new Recording();
        ModuleConnectors.call(connector, CALLER, "T1", "collect", Map.of("consumer", "C-1"));
        ModuleConnectors.call(connector, CALLER, "T1", "collect", Map.of("consumer", "C-1", "actor", "user:cashier"));
        ModuleConnectors.call(connector, CALLER, "T1", "collect", new HashMap<>(Map.of("consumer", "C-1", "actor", " ")));
        ModuleConnectors.call(connector, CALLER, "T1", "collect", null);
        assertEquals(4, connector.requests.size());
        for (ConnectorRequest r : connector.requests) {
            assertEquals("user:cashier", r.input().get("actor"));
        }
    }
}
