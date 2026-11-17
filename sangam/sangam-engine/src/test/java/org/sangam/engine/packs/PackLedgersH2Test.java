// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.engine.SangamClock;
import org.sangam.packs.port.BatchLedger;
import org.sangam.packs.port.LedgerStore;
import org.sangam.persistence.SangamSchema;

/** Fast check of the WS-PACKS port semantics on H2 (same rules as the in-memory simulators). */
class PackLedgersH2Test {

    private static List<LedgerStore> stores(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        return List.of(new JdbcLedgerStore(ds, SangamClock.SYSTEM, Duration.ofSeconds(5)),
                new org.sangam.packs.sim.InMemoryLedgerStore());
    }

    /** Owner decision 9: one reference is one posting; a different amount under it is refused, never absorbed. */
    @Test
    void reusedReferenceWithDifferentAmountIsRefused() {
        for (LedgerStore s : stores("packsRefAmount")) {
            String w = s.getClass().getSimpleName();
            s.post("t", "budget", "H1", "INR", new BigDecimal("100.00"), "seed", true);
            LedgerStore.Posting first = s.post("t", "budget", "H1", "INR", new BigDecimal("-60"), "bill-1", false);
            assertTrue(first.applied() && first.reason() == null, w);
            LedgerStore.Posting replay = s.post("t", "budget", "H1", "INR", new BigDecimal("-60.00"), "bill-1", false);
            assertTrue(replay.applied(), w);
            assertEquals("DUPLICATE_REFERENCE", replay.reason(), w + ": exact replay (equal amount, other scale) is idempotent");
            LedgerStore.Posting clash = s.post("t", "budget", "H1", "INR", new BigDecimal("-10"), "bill-1", false);
            assertFalse(clash.applied(), w);
            assertEquals("REFERENCE_CONFLICT", clash.reason(), w);
            assertEquals(0, new BigDecimal("40").compareTo(s.balance("t", "budget", "H1")), w + ": nothing changed");
        }
    }

    /** Owner decision 9: a reversed reference is closed; posting it again is refused. */
    @Test
    void reversedReferenceCannotBePostedAgain() {
        for (LedgerStore s : stores("packsRefReversed")) {
            String w = s.getClass().getSimpleName();
            s.post("t", "budget", "H1", "INR", new BigDecimal("100"), "seed", true);
            assertTrue(s.post("t", "budget", "H1", "INR", new BigDecimal("-60"), "bill-1", false).applied(), w);
            assertTrue(s.reverse("t", "budget", "H1", "bill-1").applied(), w);
            LedgerStore.Posting again = s.post("t", "budget", "H1", "INR", new BigDecimal("-60"), "bill-1", false);
            assertFalse(again.applied(), w);
            assertEquals("REFERENCE_REVERSED", again.reason(), w);
            assertEquals(0, new BigDecimal("100").compareTo(s.balance("t", "budget", "H1")), w + ": nothing changed");
            assertEquals("NOTHING_TO_REVERSE", s.reverse("t", "budget", "H1", "bill-1").reason(), w);
        }
    }

    @Test
    void concurrentPostsOfOneReferenceApplyOnce() throws Exception {
        for (LedgerStore s : stores("packsRefConcurrent")) {
            String w = s.getClass().getSimpleName();
            s.post("t", "budget", "H1", "INR", new BigDecimal("1000"), "seed", true);
            int n = 12;
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<LedgerStore.Posting>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                BigDecimal amt = new BigDecimal(i % 2 == 0 ? "-10" : "-20");
                fs.add(pool.submit(() -> {
                    go.await();
                    return s.post("t", "budget", "H1", "INR", amt, "same", false);
                }));
            }
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS));
            int fresh = 0;
            int dup = 0;
            int conflict = 0;
            for (java.util.concurrent.Future<LedgerStore.Posting> f : fs) {
                LedgerStore.Posting p = f.get();
                if (p.applied() && p.reason() == null) {
                    fresh++;
                } else if (p.applied() && "DUPLICATE_REFERENCE".equals(p.reason())) {
                    dup++;
                } else {
                    assertEquals("REFERENCE_CONFLICT", p.reason(), w);
                    conflict++;
                }
            }
            assertEquals(1, fresh, w);
            assertEquals(n / 2 - 1, dup, w);
            assertEquals(n / 2, conflict, w);
            BigDecimal b = s.balance("t", "budget", "H1");
            assertTrue(b.compareTo(new BigDecimal("990")) == 0 || b.compareTo(new BigDecimal("980")) == 0, w + " " + b);
        }
    }

    @Test
    void ledgerAndBatchSemantics() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:packs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);

        JdbcLedgerStore ledger = new JdbcLedgerStore(ds, SangamClock.SYSTEM, Duration.ofSeconds(2));
        ledger.open("t", "budget", "2026-27/H1", "INR", new BigDecimal("100.00"));
        LedgerStore.Posting p1 = ledger.post("t", "budget", "2026-27/H1", "INR", new BigDecimal("-60"), "bill-1", false);
        assertEquals(0, new BigDecimal("40").compareTo(p1.balanceAfter()));
        assertEquals("DUPLICATE_REFERENCE", ledger.post("t", "budget", "2026-27/H1", "INR", new BigDecimal("-60"), "bill-1", false).reason());
        assertFalse(ledger.post("t", "budget", "2026-27/H1", "INR", new BigDecimal("-50"), "bill-2", false).applied());
        assertEquals(0, new BigDecimal("100").compareTo(ledger.reverse("t", "budget", "2026-27/H1", "bill-1").balanceAfter()));
        assertEquals("NOTHING_TO_REVERSE", ledger.reverse("t", "budget", "2026-27/H1", "bill-1").reason());

        // SG-20: explicit units; money in minor units, quantities at 4 places, never mixed or rounded
        assertEquals("UNIT_MISMATCH", ledger.post("t", "budget", "2026-27/H1", "DAYS", BigDecimal.ONE, "bill-9", true).reason());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ledger.post("t", "budget", "2026-27/H1", "INR", new BigDecimal("0.001"), "bill-10", true));
        assertEquals(0, new BigDecimal("1.5").compareTo(ledger.post("t", "leave", "E1/EL", "DAYS", new BigDecimal("1.5"), "c1", true).balanceAfter()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ledger.post("t", "leave", "E1/EL", "DAYS", new BigDecimal("0.00001"), "c2", true));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ledger.post("t", "leave", "E1/EL", null, BigDecimal.ONE, "c3", true));
        try (java.sql.Connection c = ds.getConnection();
             java.sql.ResultSet rs = c.createStatement().executeQuery(
                     "SELECT unit, balance FROM PACK_LEDGER_ACCOUNT WHERE tenant_id='t' AND ledger='budget'")) {
            org.junit.jupiter.api.Assertions.assertTrue(rs.next());
            assertEquals("INR", rs.getString(1));
            assertEquals(10_000L, rs.getLong(2), "100.00 INR = 10,000 paise");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }

        JdbcBatchLedger batch = new JdbcBatchLedger(ds, SangamClock.SYSTEM);
        batch.open("t", "merit", 1, List.of("a", "b", "c"));
        batch.open("t", "merit", 1, List.of("a"));
        batch.record("t", "merit", 1, "a", "SUCCESS", null, Map.of("score", 91));
        batch.record("t", "merit", 1, "a", "FAILED", "LATE", Map.of()); // first outcome wins
        batch.record("t", "merit", 1, "b", "FAILED", "INELIGIBLE", null);
        BatchLedger.BatchSummary s = batch.summary("t", "merit", 1);
        assertEquals(3, s.total());
        assertEquals(2, s.done());
        assertEquals(1, s.succeeded());
        assertEquals(Map.of("b", "INELIGIBLE"), batch.failures("t", "merit", 1));
        assertEquals(91, ((Number) batch.itemData("t", "merit", 1).get("a").get("score")).intValue());
    }
}
