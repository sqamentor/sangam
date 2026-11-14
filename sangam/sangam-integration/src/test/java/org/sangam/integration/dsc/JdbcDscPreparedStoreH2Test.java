// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

/** Owner decision 165: the prepared-state table, from its reference DDL, on H2 in PostgreSQL mode. */
class JdbcDscPreparedStoreH2Test {

    private static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");

    private String url;
    private Connection keepAlive;
    private JdbcDscPreparedStore store;

    @BeforeEach
    void schema() throws Exception {
        url = "jdbc:h2:mem:dscprepared" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        String ddl;
        try (InputStream in = JdbcDscPreparedStore.class.getResourceAsStream("/db/sangam-dsc-reference.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement s = keepAlive.createStatement()) {
            for (String stmt : ddl.replaceAll("(?m)^--.*$", "").replaceAll("--[^\\n]*", "").split(";")) {
                if (!stmt.isBlank()) {
                    s.execute(stmt);
                }
            }
        }
        store = new JdbcDscPreparedStore(transactions());
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
        };
    }

    private static DscPreparedStore.Entry entry(String id, Instant expires) {
        return new DscPreparedStore.Entry("T1", id, "task-1", "doc-1", 3, "a".repeat(64), "user:asha", "c".repeat(64),
                "e".repeat(32), 7L, "Asha Verma", "SHA256withRSA", "b".repeat(64), new byte[]{0x31, 0x00}, T0,
                "prepared-pdf".getBytes(StandardCharsets.US_ASCII), T0, expires);
    }

    @Test
    void anEntryRoundTripsAndIsConsumedOnceOnly() {
        store.save(entry("p1", T0.plusSeconds(600)));
        Optional<DscPreparedStore.Entry> got = store.consume("T1", "p1", "task-1", "user:asha", T0.plusSeconds(1));
        assertTrue(got.isPresent());
        DscPreparedStore.Entry e = got.get();
        assertEquals(3, e.documentVersion());
        assertEquals("Asha Verma", e.intendedSignerName());
        assertEquals("e".repeat(32), e.enrolmentId(), "decision 241: the approval bound at prepare");
        assertEquals(7L, e.enrolmentApprovalSeq());
        assertArrayEquals(new byte[]{0x31, 0x00}, e.signedAttributes());
        assertArrayEquals("prepared-pdf".getBytes(StandardCharsets.US_ASCII), e.preparedPdf());
        assertEquals(T0, e.signingTime());
        assertTrue(store.consume("T1", "p1", "task-1", "user:asha", T0.plusSeconds(2)).isEmpty(), "once only");
    }

    @Test
    void consumeIsBoundToTenantTaskPrincipalAndTime() {
        store.save(entry("p2", T0.plusSeconds(600)));
        assertTrue(store.consume("T2", "p2", "task-1", "user:asha", T0).isEmpty());
        assertTrue(store.consume("T1", "p2", "task-2", "user:asha", T0).isEmpty());
        assertTrue(store.consume("T1", "p2", "task-1", "user:ravi", T0).isEmpty());
        assertTrue(store.consume("T1", "p2", "task-1", "user:asha", T0.plusSeconds(600)).isEmpty(), "expired at expiry");
        assertTrue(store.consume("T1", "p2", "task-1", null, T0).isEmpty());
        assertTrue(store.consume("T1", "p2", "task-1", "user:asha", T0.plusSeconds(599)).isPresent());
    }

    @Test
    void ofConcurrentConsumesExactlyOneSucceeds() throws Exception {
        store.save(entry("p3", T0.plusSeconds(600)));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> store.consume("T1", "p3", "task-1", "user:asha", T0).isPresent());
            }
            int wins = 0;
            for (Future<Boolean> f : pool.invokeAll(calls)) {
                wins += f.get() ? 1 : 0;
            }
            assertEquals(1, wins);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theOutcomeIsRecordedOnlyAfterConsumeAndDropsThePreparedPdf() throws Exception {
        store.save(entry("p4", T0.plusSeconds(600)));
        assertThrows(IllegalStateException.class, () -> store.recordOutcome("T1", "p4", "VERIFIED_VALID", null, 4, T0));
        store.consume("T1", "p4", "task-1", "user:asha", T0);
        assertTrue(store.verifiedSignatures("T1", "task-1", "doc-1", "user:asha").isEmpty());
        store.recordOutcome("T1", "p4", "VERIFIED_VALID", null, 4, T0.plusSeconds(5));
        assertEquals(List.of(new DscPreparedStore.VerifiedSignature("p4", "c".repeat(64), "e".repeat(32), 7L)),
                store.verifiedSignatures("T1", "task-1", "doc-1", "user:asha"),
                "decision 241 evidence: the signer certificate and the approval bound at prepare");
        assertTrue(store.verifiedSignatures("T1", "task-1", "doc-2", "user:asha").isEmpty(), "decision 236");
        assertTrue(store.verifiedSignatures("T1", "task-2", "doc-1", "user:asha").isEmpty());
        assertTrue(store.verifiedSignatures("T2", "task-1", "doc-1", "user:asha").isEmpty());
        assertTrue(store.verifiedSignatures("T1", "task-1", "doc-1", "user:ravi").isEmpty(), "decision 237");
        assertTrue(store.verifiedSignatures("T1", "task-1", "doc-1", null).isEmpty());
        try (Statement s = keepAlive.createStatement();
             ResultSet rs = s.executeQuery("SELECT prepared_pdf, outcome, signed_document_version FROM "
                     + JdbcDscPreparedStore.TABLE + " WHERE prepare_id = 'p4'")) {
            assertTrue(rs.next());
            assertNull(rs.getBytes(1));
            assertEquals("VERIFIED_VALID", rs.getString(2));
            assertEquals(4, rs.getInt(3));
        }
    }

    @Test
    void anInvalidOutcomeIsNotAVerifiedSignature() {
        store.save(entry("p5", T0.plusSeconds(600)));
        store.consume("T1", "p5", "task-1", "user:asha", T0);
        store.recordOutcome("T1", "p5", "VERIFIED_INVALID", "UNTRUSTED_CHAIN", null, T0);
        assertTrue(store.verifiedSignatures("T1", "task-1", "doc-1", "user:asha").isEmpty());
    }

    @Test
    void onlyUnusedExpiredEntriesArePurged() {
        store.save(entry("old", T0.minusSeconds(1)));
        store.save(entry("used", T0.plusSeconds(1)));
        store.save(entry("live", T0.plusSeconds(600)));
        store.consume("T1", "used", "task-1", "user:asha", T0);
        assertEquals(1, store.purgeExpired(T0.plusSeconds(10)));
        assertTrue(store.consume("T1", "live", "task-1", "user:asha", T0).isPresent());
    }

    @Test
    void aPrepareIdIsNeverReused() {
        store.save(entry("dup", T0.plusSeconds(600)));
        assertThrows(Jdbc.LayerSqlException.class, () -> store.save(entry("dup", T0.plusSeconds(600))));
    }
}
