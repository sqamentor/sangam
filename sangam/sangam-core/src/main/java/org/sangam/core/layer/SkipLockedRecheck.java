// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import java.sql.Connection;

/**
 * Re-check of a row returned by {@link Dialect#skipLockedSelect}, for callers whose follow-up write cannot itself be
 * fenced on the claim predicate.
 *
 * <p>On Oracle the skip-locked select locks rows chosen from the statement's starting snapshot (a ROWID window), so it
 * can return a row that another worker claimed or finished, and committed, after that snapshot. PostgreSQL re-checks the
 * predicate when it locks the row; MySQL and SQL Server lock the current row version. The select's predicate cannot be
 * repeated inside the Oracle locking statement (its placeholders would be bound twice, ORA-17041). Instead, once the
 * row is locked, this runs the caller's predicate again as its own statement: under read committed it sees the
 * committed row, and no other transaction can change it while this one holds the lock.
 */
public final class SkipLockedRecheck {

    private SkipLockedRecheck() {
    }

    /**
     * @param table     the table of the skip-locked select
     * @param alias     its alias, as used in {@code where}
     * @param keyColumn the column (unqualified) that identifies the returned row
     * @param key       the returned row's key
     * @param where     the skip-locked select's predicate, unchanged
     * @param whereArgs the values of its placeholders, in order
     * @return true while the locked row still meets {@code where}
     */
    public static boolean stillMatches(Connection c, String table, String alias, String keyColumn, Object key,
                                       String where, Object... whereArgs) {
        Object[] args = new Object[whereArgs.length + 1];
        args[0] = key;
        System.arraycopy(whereArgs, 0, args, 1, whereArgs.length);
        return Jdbc.one(c, "SELECT 1 FROM " + table + " " + alias + " WHERE " + alias + "." + keyColumn + " = ? AND (" + where + ")",
                rs -> Boolean.TRUE, args).isPresent();
    }
}
