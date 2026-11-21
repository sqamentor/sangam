// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.dsc.DscCertificateEnrolments.Enrolment;
import org.sangam.integration.dsc.DscCertificateEnrolments.Status;

/** Owner decision 227: the enrolled-certificate table, from its reference DDL, on H2 in PostgreSQL mode. */
class JdbcDscCertificateEnrolmentsH2Test {

    private static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");

    private String url;
    private Connection keepAlive;
    private JdbcDscCertificateEnrolments store;

    @BeforeEach
    void schema() throws Exception {
        url = "jdbc:h2:mem:dsccert" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        String ddl;
        try (InputStream in = JdbcDscCertificateEnrolments.class
                .getResourceAsStream("/db/sangam-dsc-enrolment-reference.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement s = keepAlive.createStatement()) {
            for (String stmt : ddl.replaceAll("--[^\\n]*", "").split(";")) {
                if (!stmt.isBlank()) {
                    s.execute(stmt);
                }
            }
        }
        store = new JdbcDscCertificateEnrolments(transactions());
    }

    @AfterEach
    void close() throws SQLException {
        keepAlive.close();
    }

    private Transactions transactions() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    throw new Jdbc.LayerSqlException("tx", e);
                }
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.read(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    throw new Jdbc.LayerSqlException("tx", e);
                }
            }
        };
    }

    private static Enrolment pending(String tenant, String id, String principal, String sha, Instant at) {
        return new Enrolment(tenant, id, principal, sha, "CN=Test CA,O=Test", "1a2b", "CN=Asha Verma,O=Test",
                T0.minusSeconds(86_400), T0.plusSeconds(86_400 * 365L), new byte[]{0x30, 0x03, 0x02, 0x01, 0x01},
                Status.PENDING, at, null, null, null);
    }

    @Test
    void anEnrolmentRoundTrips() {
        assertTrue(store.enrol(pending("T1", "e1", "user:asha", "a".repeat(64), T0)));
        Enrolment e = store.byId("T1", "e1").orElseThrow();
        assertEquals("user:asha", e.principalId());
        assertEquals("CN=Test CA,O=Test", e.issuer());
        assertEquals("1a2b", e.serialHex());
        assertEquals(Status.PENDING, e.status());
        assertEquals(T0, e.enrolledAt());
        assertArrayEquals(new byte[]{0x30, 0x03, 0x02, 0x01, 0x01}, e.certificate());
        assertEquals("e1", store.byCertificate("T1", "a".repeat(64)).orElseThrow().enrolmentId());
        assertTrue(store.byId("T2", "e1").isEmpty(), "tenant bound");
        assertTrue(store.byCertificate("T2", "a".repeat(64)).isEmpty());
    }

    @Test
    void aLiveCertificateIsEnrolledOnceAndARejectedOneAgain() {
        assertTrue(store.enrol(pending("T1", "e1", "user:asha", "a".repeat(64), T0)));
        assertFalse(store.enrol(pending("T1", "e2", "user:ravi", "a".repeat(64), T0)), "pending holds it");
        assertTrue(store.decide("T1", "e1", EnumSet.of(Status.PENDING), Status.APPROVED, "user:admin", T0, null));
        assertFalse(store.enrol(pending("T1", "e2", "user:ravi", "a".repeat(64), T0)), "approved holds it");
        assertTrue(store.decide("T1", "e1", DscCertificateEnrolments.LIVE, Status.REVOKED, "user:asha", T0, "lost"));
        assertTrue(store.enrol(pending("T1", "e3", "user:asha", "a".repeat(64), T0.plusSeconds(5))));
        Enrolment again = store.byCertificate("T1", "a".repeat(64)).orElseThrow();
        assertEquals("e3", again.enrolmentId());
        assertEquals(Status.PENDING, again.status());
        assertEquals(null, again.decidedBy(), "the old decision does not carry over");
        assertEquals(1L, again.approvalSeq(), "decision 241: the approval counter carries over");
        assertTrue(store.byId("T1", "e1").isEmpty(), "the row now belongs to the new enrolment");
        assertTrue(store.enrol(pending("T2", "e1", "user:asha", "a".repeat(64), T0)), "another tenant");
    }

    @Test
    void aDecisionIsConditionalOnTheExpectedStatus() {
        store.enrol(pending("T1", "e1", "user:asha", "a".repeat(64), T0));
        assertFalse(store.decide("T1", "e1", EnumSet.of(Status.APPROVED), Status.REVOKED, "user:admin", T0, "x"));
        assertFalse(store.decide("T2", "e1", EnumSet.of(Status.PENDING), Status.APPROVED, "user:admin", T0, null));
        assertTrue(store.decide("T1", "e1", EnumSet.of(Status.PENDING), Status.REJECTED, "user:admin",
                T0.plusSeconds(3), "wrong token"));
        Enrolment e = store.byId("T1", "e1").orElseThrow();
        assertEquals(Status.REJECTED, e.status());
        assertEquals("user:admin", e.decidedBy());
        assertEquals(T0.plusSeconds(3), e.decidedAt());
        assertEquals("wrong token", e.decisionReason());
    }

    @Test
    void onlyAMoveToApprovedRaisesTheApprovalSequence() {
        // integrator ruling on decision 241: every approval of the certificate row gets a new sequence number
        store.enrol(pending("T1", "e1", "user:asha", "a".repeat(64), T0));
        assertEquals(0L, store.byId("T1", "e1").orElseThrow().approvalSeq());
        store.decide("T1", "e1", EnumSet.of(Status.PENDING), Status.APPROVED, "user:admin", T0, null);
        assertEquals(1L, store.byId("T1", "e1").orElseThrow().approvalSeq());
        store.decide("T1", "e1", DscCertificateEnrolments.LIVE, Status.REVOKED, "user:asha", T0, "lost");
        assertEquals(1L, store.byId("T1", "e1").orElseThrow().approvalSeq(), "a revoke keeps it");
        store.enrol(pending("T1", "e2", "user:asha", "a".repeat(64), T0));
        store.decide("T1", "e2", EnumSet.of(Status.PENDING), Status.REJECTED, "user:admin", T0, "blurred");
        store.enrol(pending("T1", "e3", "user:asha", "a".repeat(64), T0));
        store.decide("T1", "e3", EnumSet.of(Status.PENDING), Status.APPROVED, "user:admin", T0, null);
        assertEquals(2L, store.byId("T1", "e3").orElseThrow().approvalSeq(), "a rejection does not count, an approval does");
    }

    @Test
    void ofConcurrentApprovalsExactlyOneWins() throws Exception {
        store.enrol(pending("T1", "e1", "user:asha", "a".repeat(64), T0));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String admin = "user:admin" + i;
                calls.add(() -> store.decide("T1", "e1", EnumSet.of(Status.PENDING), Status.APPROVED, admin, T0, null));
            }
            int wins = 0;
            for (Future<Boolean> f : pool.invokeAll(calls)) {
                wins += f.get() ? 1 : 0;
            }
            assertEquals(1, wins);
            assertEquals(1L, store.byId("T1", "e1").orElseThrow().approvalSeq(), "one approval, one sequence step");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void listsAreTenantBoundAndOrdered() {
        store.enrol(pending("T1", "e1", "user:asha", "a".repeat(64), T0));
        store.enrol(pending("T1", "e2", "user:ravi", "b".repeat(64), T0.plusSeconds(1)));
        store.enrol(pending("T1", "e3", "user:asha", "c".repeat(64), T0.plusSeconds(2)));
        store.enrol(pending("T2", "e4", "user:asha", "d".repeat(64), T0));
        assertEquals(List.of("e3", "e1"), store.ofPrincipal("T1", "user:asha").stream().map(Enrolment::enrolmentId).toList());
        assertEquals(List.of("e1", "e2"), store.withStatus("T1", Status.PENDING, 2).stream()
                .map(Enrolment::enrolmentId).toList());
        assertTrue(store.withStatus("T1", Status.APPROVED, 10).isEmpty());
    }

    @Test
    void anEnrolmentIdIsUniqueInTheTenant() {
        store.enrol(pending("T1", "e1", "user:asha", "a".repeat(64), T0));
        assertThrows(Jdbc.LayerSqlException.class, () -> store.enrol(pending("T1", "e1", "user:ravi", "b".repeat(64),
                T0)));
        assertThrows(IllegalArgumentException.class, () -> store.enrol(pending("T1", "e9", "user:asha", "e".repeat(64),
                T0).decided(Status.APPROVED, "x", T0, null)), "only a PENDING enrolment is stored");
    }
}
