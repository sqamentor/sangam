// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** VI.1 rule 5 seam in sangam-core: without sangam-engine there is no probe, so no lock can be held. */
class RemoteCallsTest {

    @Test
    void withoutAnEngineNoLockIsHeldAndRemoteCallsPass() {
        assertEquals(0, RemoteCalls.probeCount(), "sangam-core registers no LockProbe of its own");
        assertFalse(RemoteCalls.isLockHeld());
        RemoteCalls.assertNoLockHeld("HTTP GET http://example.invalid");
    }

    @Test
    void theViolationNamesTheCallAndTheWayOut() {
        IllegalRemoteCallInLockScope e = new IllegalRemoteCallInLockScope("JDBC erp");
        assertTrue(e.getMessage().startsWith("IllegalRemoteCallInLockScope: JDBC erp "), e.getMessage());
        assertTrue(e.getMessage().contains("outbox or an external task"), e.getMessage());
    }
}
