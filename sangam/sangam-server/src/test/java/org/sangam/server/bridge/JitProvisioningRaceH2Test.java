// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.persistence.SangamSchema;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.org.OrgModel;

/**
 * SL-100 (HANDOFF 6b-5, found by the SL-43 deadlock work): the first requests of a new identity arrive together, each
 * finds no person, one inserts it and the others failed with 409 {@code USER_EXISTS}. The deterministic test puts a
 * committed insert of the same subject exactly between the provisioning's lookup and its insert (the race window); the
 * concurrent test runs several first requests at once. A person of another subject with the same id stays a conflict.
 */
class JitProvisioningRaceH2Test {

    static final String TENANT = "t-sl100";

    private JdbcDataSource ds;
    private SangamSecurity security;
    private Transactions tx;
    private ExecutorService pool;

    @BeforeEach
    void schema() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:jit" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
                + ";LOCK_TIMEOUT=10000");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        tx = Transactions.of(ds);
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    /** Inserts the person the way a concurrent first request does, in its own committed transaction. */
    private void concurrentFirstRequest(String subject) {
        tx.inTransaction(c -> security.persons().provisionJit(c, TENANT, subject, "Concurrent", null));
    }

    /** A connection whose first INSERT INTO SEC_PERSON is preceded by {@code before} (the race window). */
    private static Connection raceBeforeInsert(Connection c, Runnable before) {
        AtomicBoolean done = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (p, m, args) -> {
                    if (m.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql
                            && sql.startsWith("INSERT INTO SEC_PERSON") && done.compareAndSet(false, true)) {
                        before.run();
                    }
                    try {
                        return m.invoke(c, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    void aLostInsertReturnsThePersonTheConcurrentFirstRequestCreated() {
        String subject = "sub-race-1";
        OrgModel.Person p = tx.inTransaction(c -> security.persons().provisionJit(
                raceBeforeInsert(c, () -> concurrentFirstRequest(subject)), TENANT, subject, "Me", null));
        assertEquals(subject, p.externalSubject(), "before SL-100: 409 USER_EXISTS");
        assertEquals(1L, count(subject));
    }

    @Test
    void manyConcurrentFirstRequestsAllSucceedWithOnePerson() throws Exception {
        String subject = "sub-race-many";
        int n = 8;
        CyclicBarrier start = new CyclicBarrier(n);
        List<Future<OrgModel.Person>> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            all.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return tx.inTransaction(c -> security.persons().provisionJit(c, TENANT, subject, "Me", null));
            }));
        }
        for (Future<OrgModel.Person> f : all) {
            assertEquals(subject, f.get(60, TimeUnit.SECONDS).personId());
        }
        assertEquals(1L, count(subject));
    }

    @Test
    void aPersonOfAnotherSubjectWithTheSameIdIsStillAConflict() {
        String id = "sub-taken";
        tx.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO SEC_PERSON(tenant_id, person_id, display_name,"
                    + " external_subject, email, status, created_at) VALUES (?,?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP)")) {
                ps.setString(1, TENANT);
                ps.setString(2, id);
                ps.setString(3, "Other");
                ps.setString(4, "another-subject");
                ps.setString(5, null);
                ps.executeUpdate();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> tx.inTransaction(c -> security.persons().provisionJit(c, TENANT, id, "Me", null)));
        Throwable x = e;
        while (x != null && !(x instanceof SangamSecurityException.AdminConflict)) {
            x = x.getCause();
        }
        assertTrue(x != null && x.getMessage().contains("already exists"), String.valueOf(e));
    }

    private long count(String subject) {
        return tx.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM SEC_PERSON WHERE tenant_id=? AND external_subject=?")) {
                ps.setString(1, TENANT);
                ps.setString(2, subject);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
