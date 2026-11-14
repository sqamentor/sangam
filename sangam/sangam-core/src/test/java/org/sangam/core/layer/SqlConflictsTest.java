// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

/** VI.1.6 and decision 302: the shared SQL conflict rule, every code and the first-use duplicate flag. */
class SqlConflictsTest {

    private static SQLException sql(String state, int code) {
        return new SQLException("conflict", state, code);
    }

    private static SQLException ora(int code) {
        return new SQLException(String.format("ORA-%05d: oracle error", code), "99999", code);
    }

    @Test
    void sqlStatesAreRetryable() {
        for (String state : new String[] {"40001", "40P01", "55P03", "HYT00"}) {
            assertTrue(SqlConflicts.isRetryable(sql(state, 0), false), state);
            assertTrue(SqlConflicts.isRetryable(sql(state, 0), true), state);
        }
    }

    @Test
    void oracleCodesAreRetryable() {
        for (int code : new int[] {60, 8177, 30006, 54}) {
            assertTrue(SqlConflicts.isRetryable(ora(code), false), "ORA " + code);
            assertTrue(SqlConflicts.isRetryable(sql("61000", code), false), "61xxx " + code);
            assertTrue(SqlConflicts.isRetryable(sql("72000", code), false), "72000 " + code);
        }
    }

    @Test
    void oracleCodesNeedAnOracleMarker() {
        // 60, 8177, 30006 and 54 are Oracle codes only; without ORA-, 61xxx or 72000 they are not a conflict
        for (int code : new int[] {60, 8177, 30006, 54}) {
            assertFalse(SqlConflicts.isRetryable(sql("HY000", code), true), "no marker " + code);
        }
    }

    @Test
    void sqlServerAndMySqlCodesAreRetryable() {
        for (int code : new int[] {1205, 1222, 3960, 1213, 3572}) {
            assertTrue(SqlConflicts.isRetryable(sql("HY000", code), false), "code " + code);
        }
    }

    @Test
    void firstUseDuplicatesOnlyWithTheFlag() {
        SQLException[] duplicates = {sql("23505", 0), ora(1), sql("23000", 2627), sql("23000", 2601),
                sql("23000", 1062)};
        for (SQLException d : duplicates) {
            assertFalse(SqlConflicts.isRetryable(d, false), d.getSQLState() + "/" + d.getErrorCode());
            assertTrue(SqlConflicts.isRetryable(d, true), d.getSQLState() + "/" + d.getErrorCode());
        }
    }

    @Test
    void otherFailuresAreNotRetryable() {
        assertFalse(SqlConflicts.isRetryable(null, true));
        assertFalse(SqlConflicts.isRetryable(new IllegalStateException("x"), true));
        assertFalse(SqlConflicts.isRetryable(sql("42000", 0), true));
        assertFalse(SqlConflicts.isRetryable(sql(null, 0), true));
        assertFalse(SqlConflicts.isRetryable(new SQLException((String) null), true));
        assertFalse(SqlConflicts.isRetryable(sql("23503", 0), true), "foreign key is not a first-use duplicate");
    }

    @Test
    void theCauseChainIsWalked() {
        Throwable wrapped = new RuntimeException("outer", new IllegalStateException("mid", sql("40P01", 0)));
        assertTrue(SqlConflicts.isRetryable(wrapped, false));
        assertFalse(SqlConflicts.isRetryable(new RuntimeException("outer",
                new IllegalStateException("mid", sql("42000", 0))), true));
    }

    @Test
    void theNextExceptionChainIsWalked() {
        SQLException batch = sql("HY000", 0);
        SQLException second = sql("42000", 0);
        batch.setNextException(second);
        second.setNextException(sql("23000", 1062));
        assertFalse(SqlConflicts.isRetryable(batch, false));
        assertTrue(SqlConflicts.isRetryable(batch, true));
        assertTrue(SqlConflicts.isRetryable(new RuntimeException(batch), true), "next chain of a nested cause");
    }

    @Test
    void aSelfCauseEndsTheWalk() {
        RuntimeException self = new RuntimeException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertFalse(SqlConflicts.isRetryable(self, true));
    }
}
