// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

/**
 * VI.1 rule 5: no remote work while the L2 root lock is held. The engine marks the current thread while a command
 * holds L2; connectors, HTTP clients, LLM and broker clients call {@link #assertNoLockHeld(String)} before any remote
 * call and get {@link IllegalRemoteCallInLockScope} inside a locked transaction. Side effects belong in the outbox or
 * in external tasks, carried out after commit.
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

    /** Remote-call side: throws inside a locked transaction. */
    public static void assertNoLockHeld(String remoteCall) {
        if (isLockHeld()) {
            throw new IllegalRemoteCallInLockScope(remoteCall);
        }
    }

    /** VI.1 rule 5 violation. */
    public static final class IllegalRemoteCallInLockScope extends IllegalStateException {
        public IllegalRemoteCallInLockScope(String remoteCall) {
            super("IllegalRemoteCallInLockScope: " + remoteCall
                    + " called while an L2 root lock is held; use the outbox or an external task");
        }
    }
}
