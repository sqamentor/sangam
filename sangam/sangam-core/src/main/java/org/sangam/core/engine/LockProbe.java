// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

/**
 * VI.1 rule 5 seam: tells a remote-call site whether the calling thread's engine command holds an L2 root lock. The
 * engine owns the lock-held marker (a thread-local, kept out of sangam-core by decision 396(3)) and registers its
 * implementation in {@code META-INF/services/org.sangam.core.engine.LockProbe}; callers use {@link RemoteCalls}.
 */
public interface LockProbe {

    /** True while a command on the calling thread holds at least one L2 root lock. */
    boolean lockHeld();
}
