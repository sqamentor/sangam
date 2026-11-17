// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.util.List;
import java.util.ServiceLoader;

/**
 * VI.1 rule 5: no remote work while the L2 root lock is held. Connectors, HTTP clients, LLM and broker clients call
 * {@link #assertNoLockHeld(String)} before any remote call. The lock-held state comes from the {@link LockProbe}s on
 * the classpath (sangam-engine registers one). With no probe the process has no engine, so no engine command can hold
 * a lock: sidecars and tools that carry sangam-integration without sangam-engine run their remote calls unguarded and
 * never touch an engine class. The probes are loaded once and kept in an immutable list; this class holds no
 * per-thread or mutable state.
 */
public final class RemoteCalls {

    private static final List<LockProbe> PROBES = ServiceLoader.load(LockProbe.class, LockProbe.class.getClassLoader())
            .stream().map(ServiceLoader.Provider::get).toList();

    private RemoteCalls() {
    }

    /** True while a command on the calling thread holds an L2 root lock; false when no engine is present. */
    public static boolean isLockHeld() {
        for (LockProbe p : PROBES) {
            if (p.lockHeld()) {
                return true;
            }
        }
        return false;
    }

    /** Remote-call side: throws {@link IllegalRemoteCallInLockScope} inside a locked transaction. */
    public static void assertNoLockHeld(String remoteCall) {
        if (isLockHeld()) {
            throw new IllegalRemoteCallInLockScope(remoteCall);
        }
    }

    /** Number of registered probes (0 without sangam-engine); for host self-checks and tests. */
    public static int probeCount() {
        return PROBES.size();
    }
}
