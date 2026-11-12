// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

import java.util.function.Supplier;

/**
 * SL-116: the critical section of one pack in one tenant scope. {@link PackDeployer#deploy(SangamPack, String)} and
 * {@link PackDeployer#rollback} read the installed versions, compute what to deploy and deploy it inside
 * {@link #exclusive}, so two operations on the same pack in the same scope never compute from the same state (two
 * rollbacks would otherwise take the same next version). Operations on another pack or another tenant scope are not
 * serialised against each other.
 *
 * <p>An implementation must make the lock and the engine deployment commit together (the server takes a row lock in
 * the Spring transaction the engine joins), wait a bounded time for a held lock, and report a lock-wait timeout as
 * {@link PackDeployer.PackLifecycleException} with code {@link #PACK_OPERATION_IN_PROGRESS}. A nested call for the same
 * pack and scope in the same thread must not block itself. {@link #NONE} serialises nothing: bare engines in tests and
 * tools, where nothing runs concurrently. Deploy, rollback and uninstall run inside it.
 *
 * <p><b>One transaction, several packs.</b> Deploy several packs in one caller transaction only if no concurrent
 * rollback, deploy or uninstall of those packs is possible. Operaton's engine-wide deployment lock (the
 * {@code deployment.lock} property row, taken by every deploy) is then held to the end of the outer transaction while
 * the next pack's section waits for that pack's lock; a concurrent operation holding that pack's lock and waiting for
 * the deployment lock closes a cycle, and the database ends it as a deadlock (not mapped to
 * {@link #PACK_OPERATION_IN_PROGRESS}).
 */
public interface PackOperationLock {

    /** Code of an operation refused because another one on the same pack and tenant held the lock too long. */
    String PACK_OPERATION_IN_PROGRESS = "PACK_OPERATION_IN_PROGRESS";

    /** No serialisation: runs the work as it is. */
    PackOperationLock NONE = new PackOperationLock() {
        @Override
        public <T> T exclusive(String tenantId, String packId, Supplier<T> work) {
            return work.get();
        }

        @Override
        public String toString() {
            return "PackOperationLock.NONE";
        }
    };

    /**
     * Runs {@code work} holding the lock of {@code packId} in the tenant scope ({@code null} = shared definitions).
     *
     * @throws PackDeployer.PackLifecycleException {@link #PACK_OPERATION_IN_PROGRESS} when the lock was not granted in
     *                                             the bounded wait
     */
    <T> T exclusive(String tenantId, String packId, Supplier<T> work);
}
