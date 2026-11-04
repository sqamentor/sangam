package org.sangam.spike.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** Fast checks of the retry classification (VI.1.6) and the SQL Server lease shim; no Docker. */
class RetryClassifierUnitTest {

    static final class FakeOptimisticLockingException extends RuntimeException {
        FakeOptimisticLockingException(Throwable cause) {
            super("ole", cause);
        }
    }

    @Test
    void classifiesDialectCodes() {
        assertEquals(Retry.Kind.DEADLOCK, Retry.classify(wrap(new SQLException("x", "40P01")), "postgresql"));
        assertEquals(Retry.Kind.SERIALIZATION, Retry.classify(wrap(new SQLException("x", "40001")), "postgresql"));
        assertEquals(Retry.Kind.LOCK_TIMEOUT, Retry.classify(wrap(new SQLException("x", "55P03")), "postgresql"));
        assertEquals(Retry.Kind.DEADLOCK, Retry.classify(wrap(new SQLException("ORA-00060", "61000", 60)), "oracle"));
        assertEquals(Retry.Kind.DEADLOCK, Retry.classify(wrap(new SQLException("victim", "40001", 1205)), "sqlserver"));
        assertEquals(Retry.Kind.OTHER, Retry.classify(new IllegalStateException("boom"), "postgresql"));
        assertEquals(Retry.Kind.OLE, Retry.classify(new FakeOptimisticLockingException(null), "postgresql"));
        // a deadlock re-thrown as an OLE is still a deadlock
        assertEquals(Retry.Kind.DEADLOCK,
                Retry.classify(new FakeOptimisticLockingException(new SQLException("x", "40P01")), "postgresql"));
    }

    @Test
    void retriesRetryableAndStopsOnOther() {
        Retry r = new Retry("postgresql");
        AtomicInteger n = new AtomicInteger();
        assertEquals("ok", r.call(() -> {
            if (n.incrementAndGet() < 3) {
                throw new FakeOptimisticLockingException(null);
            }
            return "ok";
        }));
        assertEquals(2, r.kind(Retry.Kind.OLE));
        assertThrows(Retry.Unrecovered.class, () -> r.call(() -> {
            throw new IllegalArgumentException("no");
        }));
        assertEquals(1, r.unrecovered());
        assertThrows(Retry.Unrecovered.class, () -> r.call(() -> {
            throw new FakeOptimisticLockingException(null);
        }));
        assertEquals(2 + 6, r.kind(Retry.Kind.OLE));
    }

    @Test
    void sqlServerShimRewritesOnlyLeaseStatements() {
        assertEquals("select 1", DbSetup.rewrite("select 1"));
        String ddl = DbSetup.rewrite("create table if not exists spike_lease (work_id varchar(64))");
        assertEquals("if object_id('spike_lease','U') is null create table spike_lease (work_id varchar(64))", ddl);
        assertEquals("select lock_version from spike_lease with (updlock, rowlock) where work_id = ?",
                DbSetup.rewrite("select lock_version from spike_lease where work_id = ? for update"));
        assertEquals("create table if not exists spike_lease (lock_version number(19) not null, expires_at number(19))",
                DbSetup.rewrite("create table if not exists spike_lease (lock_version bigint not null, expires_at bigint)",
                        "oracle"));
    }

    private static RuntimeException wrap(SQLException e) {
        return new RuntimeException("engine", new RuntimeException("mybatis", e));
    }
}
