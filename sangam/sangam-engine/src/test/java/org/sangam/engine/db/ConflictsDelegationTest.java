// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.OptimisticLockingException;
import org.sangam.core.layer.SqlConflicts;

/**
 * Decision 302: the engine's {@link Conflicts} delegates the SQL rule to {@link SqlConflicts} with no behaviour change.
 * {@link #legacy} is a frozen copy of the engine rule before the split; every case must give the same answer.
 */
class ConflictsDelegationTest {

    /** The engine rule as it was before decision 302 (frozen, do not edit). */
    private static boolean legacy(Throwable e, boolean dup) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof OptimisticLockingException) {
                return true;
            }
            if (t instanceof SQLException s) {
                for (SQLException x = s; x != null; x = x.getNextException()) {
                    if (legacySql(x, dup)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean legacySql(SQLException s, boolean dup) {
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
        return dup && (state.equals("23505") || code == 2627 || code == 2601 || code == 1062);
    }

    private static List<Throwable> cases() {
        List<Throwable> all = new ArrayList<>();
        String[] states = {null, "", "40001", "40P01", "55P03", "HYT00", "61000", "72000", "23505", "23000", "42000",
                "HY000", "99999"};
        int[] codes = {0, 1, 54, 60, 8177, 30006, 1205, 1222, 3960, 1213, 3572, 2627, 2601, 1062, 1451, 547};
        for (String state : states) {
            for (int code : codes) {
                all.add(new SQLException("plain", state, code));
                all.add(new SQLException(String.format("ORA-%05d: x", code), state, code));
                all.add(new RuntimeException("wrapped", new SQLException("plain", state, code)));
            }
        }
        SQLException batch = new SQLException("batch", "HY000", 0);
        batch.setNextException(new SQLException("dup", "23000", 1062));
        all.add(batch);
        all.add(new RuntimeException(batch));
        all.add(new OptimisticLockingException("ole"));
        all.add(new RuntimeException("outer", new OptimisticLockingException("ole")));
        all.add(new OptimisticLockingException("ole", new SQLException("x", "42000", 0)));
        all.add(new IllegalStateException("plain"));
        all.add(new SQLException((String) null));
        all.add(null);
        return all;
    }

    @Test
    void theEngineResultIsUnchanged() {
        int retryable = 0;
        for (Throwable t : cases()) {
            for (boolean dup : new boolean[] {false, true}) {
                boolean expected = legacy(t, dup);
                assertEquals(expected, Conflicts.isRetryable(t, dup), t + " dup=" + dup);
                if (expected) {
                    retryable++;
                }
            }
        }
        assertTrue(retryable > 0, "the matrix holds retryable cases");
    }

    @Test
    void withoutAnEngineConflictTheEngineAndTheSharedRuleAgree() {
        for (Throwable t : cases()) {
            if (hasOle(t)) {
                continue;
            }
            for (boolean dup : new boolean[] {false, true}) {
                assertEquals(SqlConflicts.isRetryable(t, dup), Conflicts.isRetryable(t, dup), t + " dup=" + dup);
            }
        }
    }

    @Test
    void anEngineConflictIsRetryableOnlyInTheEngine() {
        Throwable ole = new RuntimeException("outer", new OptimisticLockingException("ole"));
        assertTrue(Conflicts.isRetryable(ole, false));
        assertEquals(false, SqlConflicts.isRetryable(ole, true));
    }

    private static boolean hasOle(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof OptimisticLockingException) {
                return true;
            }
        }
        return false;
    }
}
