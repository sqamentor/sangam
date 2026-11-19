// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.persistence.SangamSchema;

/**
 * On Oracle the recovery's skip-locked select locks jobs chosen from the statement's snapshot, so it can return a job
 * that was recovered and acquired again under a newer owner epoch after that snapshot; recovering it would clear that
 * live lock. H2 cannot reproduce the snapshot, so the re-acquisition is injected right after the select, on the same
 * connection.
 */
class PartitionLeaseManagerSkipLockedFenceH2Test {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicBoolean raced = new AtomicBoolean();
    private final long[] liveEpoch = {0};

    @Test
    void aJobReacquiredUnderANewerEpochAfterTheSelectKeepsItsLock() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:fence" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            Jdbc.update(c, PartitionLeaseManagerH2Test.JOB_DDL);
        }
        PartitionLeaseManager b = new PartitionLeaseManager(racing(ds), Dialect.POSTGRESQL, new PartitionLeaseManager.Config(
                "c1", "b", UUID.randomUUID().toString(), 1, 64, Duration.ofSeconds(15), Duration.ofSeconds(3),
                Duration.ofSeconds(30), 2, 3), Instant::now, nanos::get, null);
        b.bootstrap();
        try (Connection c = ds.getConnection()) {
            long stale = Jdbc.one(c, "SELECT owner_epoch FROM EXEC_PARTITION WHERE partition_id = 1", rs -> rs.getLong(1)).orElseThrow();
            // a dead owner holds the job under the current epoch, then its lease expires
            Jdbc.update(c, "INSERT INTO ACT_RU_JOB (ID_, REV_, EXECUTION_ID_, LOCK_OWNER_, LOCK_EXP_TIME_, RETRIES_)"
                    + " VALUES ('j1', 1, 'e1', 'dead', DATEADD('HOUR', 1, CURRENT_TIMESTAMP), 3)");
            Jdbc.update(c, "INSERT INTO EXEC_JOB_SLOT (job_id, tenant_id, root_id, slot, recoveries, created_at_ms, owner_partition,"
                    + " owner_epoch) VALUES ('j1', 't1', 'r', 0, 0, 0, 1, ?)", stale);
            Jdbc.update(c, "UPDATE EXEC_PARTITION SET state = 'ACTIVE', lease_expires_at = DATEADD('SECOND', -60, CURRENT_TIMESTAMP)"
                    + " WHERE partition_id = 1");
        }
        long epoch = b.claim(1);
        assertTrue(epoch > 0, "claimed");
        liveEpoch[0] = epoch;

        armed.set(true);
        int recovered = b.recover(1, epoch);
        armed.set(false);

        assertTrue(raced.get(), "the re-acquisition was injected");
        assertEquals(0, recovered, "a job re-acquired under the new epoch is not recovered");
        try (Connection c = ds.getConnection()) {
            assertEquals("live-node", Jdbc.one(c, "SELECT LOCK_OWNER_ FROM ACT_RU_JOB WHERE ID_ = 'j1'", rs -> rs.getString(1))
                    .orElseThrow(), "the live lock is not cleared");
            assertEquals(0, (int) Jdbc.one(c, "SELECT recoveries FROM EXEC_JOB_SLOT WHERE job_id = 'j1'", rs -> rs.getInt(1))
                    .orElseThrow(), "the recoveries counter is not incremented");
            assertEquals(epoch, (long) Jdbc.one(c, "SELECT owner_epoch FROM EXEC_JOB_SLOT WHERE job_id = 'j1'",
                    rs -> rs.getLong(1)).orElseThrow(), "the new owner stamp stays");
        }
    }

    /**
     * After the recovery's skip-locked select, before the next statement, re-stamps the job's slot row with the new
     * epoch and locks the job for a live node (as the new owner would have committed after the select's snapshot).
     */
    private DataSource racing(DataSource real) {
        ClassLoader cl = getClass().getClassLoader();
        AtomicBoolean afterSelect = new AtomicBoolean();
        return (DataSource) Proxy.newProxyInstance(cl, new Class<?>[]{DataSource.class}, (p, m, args) -> {
            Object out = invoke(real, m, args);
            if (out instanceof Connection conn) {
                return Proxy.newProxyInstance(cl, new Class<?>[]{Connection.class}, (p2, m2, a2) -> {
                    if (m2.getName().equals("prepareStatement") && armed.get() && !raced.get()) {
                        String sql = String.valueOf(a2[0]);
                        if (afterSelect.get()) {
                            raced.set(true);
                            Jdbc.update(conn, "UPDATE EXEC_JOB_SLOT SET owner_partition = 1, owner_epoch = ? WHERE job_id = 'j1'",
                                    liveEpoch[0]);
                            Jdbc.update(conn, "UPDATE ACT_RU_JOB SET LOCK_OWNER_ = 'live-node', REV_ = REV_ + 1 WHERE ID_ = 'j1'");
                        } else if (sql.contains("SKIP LOCKED") && sql.contains("ACT_RU_JOB")) {
                            afterSelect.set(true);
                        }
                    }
                    return invoke(conn, m2, a2);
                });
            }
            return out;
        });
    }

    private static Object invoke(Object target, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
