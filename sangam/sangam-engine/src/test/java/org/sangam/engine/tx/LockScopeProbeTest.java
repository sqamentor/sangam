// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.engine.IllegalRemoteCallInLockScope;
import org.sangam.core.engine.RemoteCalls;

/** The engine's lock-held marker reaches sangam-core's RemoteCalls through the registered LockProbe service. */
class LockScopeProbeTest {

    @AfterEach
    void clear() {
        while (LockScope.isLockHeld()) {
            LockScope.exit();
        }
    }

    @Test
    void theEngineRegistersExactlyOneProbe() {
        assertEquals(1, RemoteCalls.probeCount());
    }

    @Test
    void remoteCallsSeeTheEngineLockOnThisThreadOnly() throws Exception {
        assertFalse(RemoteCalls.isLockHeld());
        LockScope.enter();
        assertTrue(RemoteCalls.isLockHeld());
        assertThrows(IllegalRemoteCallInLockScope.class, () -> RemoteCalls.assertNoLockHeld("HTTP GET x"));
        assertThrows(IllegalRemoteCallInLockScope.class, () -> LockScope.assertNoLockHeld("HTTP GET x"));
        boolean[] other = new boolean[1];
        Thread t = new Thread(() -> other[0] = RemoteCalls.isLockHeld());
        t.start();
        t.join();
        assertFalse(other[0], "another thread holds no lock");
        LockScope.exit();
        assertFalse(RemoteCalls.isLockHeld());
        RemoteCalls.assertNoLockHeld("HTTP GET x");
    }
}
