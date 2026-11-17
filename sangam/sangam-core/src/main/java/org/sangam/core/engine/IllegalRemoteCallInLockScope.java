// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

/** VI.1 rule 5 violation: a remote call while the calling thread's engine command holds an L2 root lock. */
public final class IllegalRemoteCallInLockScope extends IllegalStateException {

    public IllegalRemoteCallInLockScope(String remoteCall) {
        super("IllegalRemoteCallInLockScope: " + remoteCall
                + " called while an L2 root lock is held; use the outbox or an external task");
    }
}
