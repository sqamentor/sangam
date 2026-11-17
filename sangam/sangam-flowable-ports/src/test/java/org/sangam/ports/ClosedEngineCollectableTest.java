// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.ref.WeakReference;

import org.junit.jupiter.api.Test;
import org.sangam.core.engine.TaskSuspensionEvents;

/**
 * R8-heap: a closed engine with the ports plugin must become garbage. Before the fix the plugin registered its task
 * event dispatcher strongly in the static {@link TaskSuspensionEvents} registry under the (unique) engine name, so every
 * closed engine (~40 MB: MyBatis configuration, caches, the BPMN parser) stayed reachable, and the module's test JVM ran
 * out of heap after ~1500 tests whatever its size.
 */
class ClosedEngineCollectableTest {

    @Test
    void aClosedPortsEngineIsCollectedAndLeavesNoSuspensionListener() throws Exception {
        PortsH2Engine h2 = PortsH2Engine.start(null);
        String name = h2.engine.getName();
        WeakReference<Object> cfg = new WeakReference<>(h2.engine.getProcessEngineConfiguration());
        h2.close();
        h2 = null;
        // PortsContext holds the most recently built engine's context (one, by design); a next engine replaces it
        try (PortsH2Engine next = PortsH2Engine.start(null)) {
            for (int i = 0; i < 50 && cfg.get() != null; i++) {
                System.gc();
                Thread.sleep(20);
            }
            assertNull(cfg.get(), "the closed engine's configuration is still reachable");
            assertFalse(TaskSuspensionEvents.hasListeners(name), "the closed engine keeps no live suspension listener");
        }
    }
}
