// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Oracle picks claim candidates from the statement's read-consistent snapshot (the ROWID window). A row another
 * claimer changes and commits after that snapshot is no longer locked, so SKIP LOCKED would lock and return it. The
 * locking query must therefore carry the caller's predicate itself: Oracle re-checks it against the current row when
 * it locks, and a row that no longer qualifies is not returned.
 */
class DialectOracleSkipLockedTest {

    @Test
    void theLockingQueryReChecksTheCallersPredicate() {
        assertEquals("SELECT /*sangam:limit=5*/ d.id FROM T d WHERE (d.x=1) AND d.ROWID IN (SELECT sgm_rid FROM (SELECT"
                        + " d.ROWID AS sgm_rid FROM T d WHERE (d.x=1) ORDER BY d.id) WHERE ROWNUM <= 40) ORDER BY d.id"
                        + " FOR UPDATE SKIP LOCKED",
                Dialect.ORACLE.skipLockedSelect("d.id", "T", "d", "d.x=1", "d.id", 5));
    }
}
