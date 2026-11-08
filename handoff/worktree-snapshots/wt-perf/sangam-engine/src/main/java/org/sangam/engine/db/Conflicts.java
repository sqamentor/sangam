// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import java.sql.SQLException;

import org.operaton.bpm.engine.OptimisticLockingException;

/**
 * VI.1.6 error classification: which failures make a command safe to retry (it only changed database state; every
 * external effect goes through the outbox or an external task, VI.1 rule 7).
 *
 * <ul>
 *   <li>engine optimistic-locking conflicts;</li>
 *   <li>PostgreSQL 40001 / 40P01 / 55P03; Oracle ORA-00060 / ORA-08177 / ORA-30006 / ORA-00054;
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
            if (t instanceof SQLException s) {
                for (SQLException x = s; x != null; x = x.getNextException()) {
                    if (retryable(x, includeFirstUseDuplicates)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean retryable(SQLException s, boolean dup) {
        String state = s.getSQLState() == null ? "" : s.getSQLState();
        int code = s.getErrorCode();
        String msg = String.valueOf(s.getMessage());
        if (state.equals("40001") || state.equals("40P01") || state.equals("55P03") || state.equals("HYT00")) {
            return true;
        }
        if (msg.startsWith("ORA-") || state.startsWith("61") || state.equals("72000")) {
            if (code == 60 || code == 8177 || code == 30006 || code == 54) {
                return true;
            }
            if (dup && code == 1) {
                return true;
            }
        }
        if (code == 1205 || code == 1222 || code == 3960 || code == 1213 || code == 3572) {
            return true;
        }
        if (dup && (state.equals("23505") || code == 2627 || code == 2601 || code == 1062)) {
            return true;
        }
        return false;
    }
}
