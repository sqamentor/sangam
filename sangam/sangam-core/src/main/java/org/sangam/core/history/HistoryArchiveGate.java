// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.history;

import java.sql.Connection;

/**
 * RM-52 (decision 386): the check every purge of engine history makes first. A process tree (the root instance and
 * every called or sub-process instance, one tenant) may be purged only when it has a verified archive: stored, its
 * manifest digest recorded on the root's audit chain, signed by a trusted archive key and still complete. The three
 * purge paths ask it: the hierarchy-retention purge, the engine's own history deletion (removal-time cleanup, end-time
 * cleanup, {@code HistoryService} deletes, cascade deletes) and a partition drop.
 *
 * <p>Fail closed: without an archive store and key the gate is {@link #notConfigured()}, which refuses every tree, so
 * history without an archive is kept, never lost.
 */
@FunctionalInterface
public interface HistoryArchiveGate {

    /** Refusal code of {@link #notConfigured()}. */
    String NOT_CONFIGURED = "HISTORY_ARCHIVE_NOT_CONFIGURED";

    /**
     * @param c      the caller's connection (the engine command's, or a transaction of the caller)
     * @param tenant the tree's tenant (null for a tree without a tenant)
     * @param rootId the root process instance id
     * @return null when the tree has a verified archive and may be purged; otherwise the refusal code and reason
     */
    String refusal(Connection c, String tenant, String rootId);

    /** The gate of a node without an archive store or key: every purge is refused. */
    static HistoryArchiveGate notConfigured() {
        return (c, tenant, root) -> NOT_CONFIGURED + ": no history archive store and signing key are configured, so"
                + " tree " + root + " has no verified archive (RM-52)";
    }

    /** Thrown when a purge is refused; nothing of the refused tree was deleted. */
    final class PurgeRefusedException extends RuntimeException {
        private final String rootId;

        public PurgeRefusedException(String rootId, String refusal) {
            super("HISTORY_PURGE_REFUSED: tree " + rootId + ": " + refusal);
            this.rootId = rootId;
        }

        public String rootId() {
            return rootId;
        }
    }
}
