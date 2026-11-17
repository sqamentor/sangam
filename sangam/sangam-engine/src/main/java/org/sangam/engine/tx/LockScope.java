// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.tx;

import org.sangam.core.engine.IllegalRemoteCallInLockScope;
import org.sangam.core.engine.LockProbe;
import org.sangam.core.engine.RemoteCalls;

/**
 * VI.1 rule 5: no remote work while the L2 root lock is held. The engine marks the current thread while a command
 * holds L2. Connectors, HTTP clients, LLM and broker clients do not link this class: they call
 * {@link RemoteCalls#assertNoLockHeld(String)} in sangam-core, which reads this marker through {@link Probe} (registered
 * in {@code META-INF/services/org.sangam.core.engine.LockProbe}) and throws {@link IllegalRemoteCallInLockScope} inside
 * a locked transaction. Side effects belong in the outbox or in external tasks, carried out after commit.
 */
public final class LockScope {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private LockScope() {
    }

    /** Engine side: a command on this thread now holds at least one root lock. */
    public static void enter() {
        DEPTH.get()[0]++;
    }

    /** Engine side: the command that called {@link #enter()} ended (commit or rollback). */
    public static void exit() {
        int[] d = DEPTH.get();
        if (d[0] > 0) {
            d[0]--;
        }
        if (d[0] == 0) {
            DEPTH.remove();
        }
    }

    public static boolean isLockHeld() {
        return DEPTH.get()[0] > 0;
    }

    /** Remote-call side: throws inside a locked transaction (same check as {@link RemoteCalls#assertNoLockHeld}). */
    public static void assertNoLockHeld(String remoteCall) {
        if (isLockHeld()) {
            throw new IllegalRemoteCallInLockScope(remoteCall);
        }
    }

    /** The engine's {@link LockProbe}: exposes this thread's marker to {@link RemoteCalls}. */
    public static final class Probe implements LockProbe {
        @Override
        public boolean lockHeld() {
            return isLockHeld();
        }
    }
}
