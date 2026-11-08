// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.lifecycle;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;

/**
 * Engine and security ports used by {@link InstanceLifecycleService} and {@link ErasureService} (VI.5, R23). The
 * Operaton implementations live in {@code org.sangam.integration.bridge.operaton}; WS-SECURITY implements
 * {@link Authorizer}.
 */
public final class LifecyclePorts {

    private LifecyclePorts() {
    }

    /** Open external work of an instance tree (OFFERED or LOCKED), with its lease. */
    public record OpenWork(String workId, String activityInstanceId, long lockVersion, Instant lockExpiresAt, boolean locked) {
    }

    @FunctionalInterface
    public interface OpenWorkPort {
        /** External work of the instance and its sub-instances; called inside the lifecycle transaction. */
        List<OpenWork> openWork(Connection c, String instanceId);
    }

    /** Engine side of cancel and delete; both run inside the caller's engine transaction. */
    public interface EngineInstancePort {
        /** Business cancel: runs the model's cancel/compensation paths (VI.5.1). */
        void businessCancel(Connection c, String instanceId, String reason);

        /** Technical delete: removes runtime rows; internal jobs (timers, async continuations) are dropped (VI.5 rule 2). */
        void technicalDelete(Connection c, String instanceId, String reason);

        /** Root and all sub-instance ids (for erasure scope). */
        List<String> instanceTree(Connection c, String instanceId);
    }

    /** Scrubs payload data in engine runtime and history (variables, task comments, attachments' content refs). */
    @FunctionalInterface
    public interface EngineDataEraser {
        int erase(Connection c, String tenantId, List<String> instanceIds, String reason);
    }

    /** Scrubs objects in the document store (R74) referenced by the instances. */
    @FunctionalInterface
    public interface DocumentEraser {
        int erase(String tenantId, List<String> instanceIds, String reason);

        DocumentEraser NONE = (t, i, r) -> 0;
    }

    /** Permission check per operation: {@code CANCEL_INSTANCE}, {@code DELETE_INSTANCE}, {@code ERASE_DATA} (R23). */
    @FunctionalInterface
    public interface Authorizer {
        /** Throws {@link SecurityException} when not permitted; re-checked inside the command transaction (VI.4 rule 5). */
        void require(Connection c, String tenantId, String principalId, String permission, String instanceId);
    }
}
