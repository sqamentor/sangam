// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.context.Context;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;

/**
 * The gateway outside an engine command (a relay or a REST thread): it works in its own transaction. It used to test
 * {@code Context.getCommandContext()} for null, which Operaton 2.2 never answers (it throws ENGINE-09025 outside a
 * command), so {@code submit} and {@code cancel} failed exactly on the path that was meant to open a transaction.
 */
class IntegrationConnectorGatewayOutsideCommandH2Test {

    @Test
    void cancelOutsideACommandRunsInItsOwnTransaction() throws Exception {
        try (PackH2Fixture f = new PackH2Fixture("nocommand")) {
            assertFalse(Context.hasActiveCommandContext(), "the test thread is in no engine command");
            ConnectorResult r = f.gateway.cancel(PackH2Fixture.TENANT, "any-connector", "never-submitted", "test");
            assertEquals(OperationStatus.CANCELLED, r.status());
            assertEquals("nothing was submitted", r.message());
        }
    }
}
