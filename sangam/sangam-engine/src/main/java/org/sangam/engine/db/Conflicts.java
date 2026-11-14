// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import org.operaton.bpm.engine.OptimisticLockingException;
import org.sangam.core.layer.SqlConflicts;

/**
 * VI.1.6 error classification: which failures make a command safe to retry (it only changed database state; every
 * external effect goes through the outbox or an external task, VI.1 rule 7).
 *
 * <ul>
 *   <li>engine optimistic-locking conflicts (checked here, sangam-core has no engine dependency);</li>
 *   <li>the plain SQL conflicts of {@link SqlConflicts} (owner decision 302: one classification, shared with the outbox
 *       relay): PostgreSQL 40001 / 40P01 / 55P03; Oracle ORA-00060 / ORA-08177 / ORA-30006 / ORA-00054;
 *       SQL Server 1205 / 1222 / 3960; MySQL 1213 / 1205 / 3572 (and SQLState 40001); H2 HYT00 / 40001;</li>
 *   <li>with {@code includeFirstUseDuplicates}: a duplicate key on the first use of a fetch-lock, lease or root row
 *       (23505, ORA-00001, SQL Server 2627/2601, MySQL 1062).</li>
 * </ul>
 */
public final class Conflicts {

    private Conflicts() {
    }

    public static boolean isRetryable(Throwable e, boolean includeFirstUseDuplicates) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof OptimisticLockingException) {
                return true;
            }
        }
        return SqlConflicts.isRetryable(e, includeFirstUseDuplicates);
    }
}
