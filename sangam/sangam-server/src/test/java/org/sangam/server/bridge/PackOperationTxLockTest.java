// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.engine.db.EngineDialect;

/** SL-116: the lock-wait timeout classifier of {@link PackOperationTxLock}, per database, through wrapped causes. */
class PackOperationTxLockTest {

    private static SQLException sql(String state, int code) {
        return new SQLException("x", state, code);
    }

    @Test
    void eachDatabasesLockWaitTimeoutIsRecognised() {
        assertTrue(PackOperationTxLock.isLockTimeout(EngineDialect.POSTGRESQL, sql("55P03", 0)));
        assertTrue(PackOperationTxLock.isLockTimeout(EngineDialect.ORACLE, sql("61000", 30006)));
        assertTrue(PackOperationTxLock.isLockTimeout(EngineDialect.ORACLE, sql("61000", 54)));
        assertTrue(PackOperationTxLock.isLockTimeout(EngineDialect.SQLSERVER, sql("S00051", 1222)));
        assertTrue(PackOperationTxLock.isLockTimeout(EngineDialect.MYSQL, sql("40001", 1205)));
        assertTrue(PackOperationTxLock.isLockTimeout(EngineDialect.H2, sql("HYT00", 50200)));
    }

    @Test
    void otherErrorsAndOtherDatabasesCodesAreNot() {
        assertFalse(PackOperationTxLock.isLockTimeout(EngineDialect.POSTGRESQL, sql("40P01", 0)), "deadlock");
        assertFalse(PackOperationTxLock.isLockTimeout(EngineDialect.POSTGRESQL, sql("23505", 0)), "duplicate key");
        assertFalse(PackOperationTxLock.isLockTimeout(EngineDialect.SQLSERVER, sql("40001", 1205)),
                "SQL Server 1205 is a deadlock, not a lock-wait timeout");
        assertFalse(PackOperationTxLock.isLockTimeout(EngineDialect.MYSQL, sql("42000", 1222)),
                "MySQL 1222 is not a lock error");
        assertFalse(PackOperationTxLock.isLockTimeout(EngineDialect.ORACLE, sql("23000", 1)), "ORA-00001");
        assertFalse(PackOperationTxLock.isLockTimeout(EngineDialect.H2, new IllegalStateException("no SQL")));
        assertFalse(PackOperationTxLock.isLockTimeout(EngineDialect.H2, null));
    }

    @Test
    void wrappedCausesAndChainedExceptionsAreSearchedWithoutLooping() {
        SQLException top = sql("XX000", 0);
        top.setNextException(sql("55P03", 0));
        assertTrue(PackOperationTxLock.isLockTimeout(EngineDialect.POSTGRESQL,
                new ProcessEngineException("deploy failed", new IllegalStateException("layer", top))));

        // a cause cycle: a -> b -> a
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertFalse(PackOperationTxLock.isLockTimeout(EngineDialect.MYSQL, a));
        SQLException looping = sql("HY000", 0);
        looping.setNextException(looping);
        assertFalse(PackOperationTxLock.isLockTimeout(EngineDialect.MYSQL, looping));
    }
}
