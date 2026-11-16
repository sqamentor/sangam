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

    /**
     * Further audit chains that hold events of one chain and are erased with it (SL-132): the denial-only chain of a
     * root ({@code sec:d:...}). The chain naming belongs to the module that writes those chains (sangam-security
     * {@code SecurityAuditService.denialChain}); the server supplies it, so the formula exists once and sangam-integration
     * gains no dependency on sangam-security.
     */
    @FunctionalInterface
    public interface RelatedAuditChains {
        /** The chains whose events belong to {@code chainId} of {@code tenantId}; never null. */
        List<String> of(String tenantId, String chainId);

        /** No related chains. */
        RelatedAuditChains NONE = (tenant, chain) -> List.of();
    }

    /**
     * RM-52: a copy of a tree's data that the erasure does not own but must follow: the history archive of the tree.
     * {@link #before} and {@link FollowUp#erased} run in the erasure's transaction (a failure rolls the whole erasure
     * back); {@link FollowUp#committed} runs after the commit, with the document store.
     */
    @FunctionalInterface
    public interface ErasureFollower {
        /**
         * Before any payload of the tree is erased: what the follower needs of the tree as it was (the audit payloads
         * are still readable); {@code null} when it holds no copy of the tree.
         */
        FollowUp before(Connection c, String tenantId, String rootId);

        /** The follow-up of one erasure. */
        interface FollowUp {
            /**
             * Once the tree is erased and {@code DATA_ERASED} is on the root's chain: the follower's copy is replaced by
             * one without the erased data.
             *
             * @return the number of copies replaced
             */
            int erased(Connection c, String dataErasedEventId, String reason);

            /** After the commit: the superseded copies are removed; returns how many. */
            int committed();
        }
    }

    /** Permission check per operation: {@code CANCEL_INSTANCE}, {@code DELETE_INSTANCE}, {@code ERASE_DATA} (R23). */
    @FunctionalInterface
    public interface Authorizer {
        /** Throws {@link SecurityException} when not permitted; re-checked inside the command transaction (VI.4 rule 5). */
        void require(Connection c, String tenantId, String principalId, String permission, String instanceId);
    }
}
