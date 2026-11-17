// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner decision 9: a ledger reference identifies one posting. An exact replay is an idempotent success; the same
 * reference with a different amount or different legs is refused ({@code REFERENCE_CONFLICT}, HTTP 409) and changes
 * nothing.
 */
class LedgerReferenceConflictH2Test {

    private static final AccountKey A = new AccountKey("T", "BUDGET", "2026-27", "2054-00-001-01-00-13", "DDO-1", "RELEASE", null);
    private static final AccountKey B = new AccountKey("T", "BUDGET", "2026-27", "2054-00-001-01-00-13", "DDO-2", "RELEASE", null);

    private static Movement total(AccountKey k, String amount) {
        return new Movement(k, "INR", Map.of(Balance.Bucket.TOTAL, new BigDecimal(amount)), null, false);
    }

    private static LedgerStore.Entry entry(String ref, Movement... legs) {
        return new LedgerStore.Entry("T", ref, "ALLOCATE", null, "u", null, List.of(legs), Map.of());
    }

    private static List<LedgerStore> stores(String name) throws Exception {
        JdbcDataSource ds = LedgerJdbcH2Test.db(name);
        return List.of(new JdbcLedgerStore(ds, Dialect.POSTGRESQL), new LedgerStore.InMemory());
    }

    @Test
    void exactReplayIsIdempotentEvenWithEquivalentScaleAndLegOrder() throws Exception {
        for (LedgerStore s : stores("refReplay")) {
            LedgerStore.PostResult first = s.post(entry("r1", total(A, "100.00"), total(B, "5")));
            assertTrue(first.applied() && !first.duplicate(), s.getClass().getSimpleName());
            LedgerStore.PostResult again = s.post(entry("r1", total(B, "5.00"), total(A, "100")));
            assertTrue(again.applied(), s.getClass().getSimpleName());
            assertTrue(again.duplicate(), s.getClass().getSimpleName());
            assertEquals(0, new BigDecimal("100").compareTo(s.get(A).orElseThrow().total()));
            assertEquals(0, new BigDecimal("5").compareTo(s.get(B).orElseThrow().total()));
        }
    }

    @Test
    void reusedReferenceWithDifferentAmountIsRefused() throws Exception {
        for (LedgerStore s : stores("refAmount")) {
            assertTrue(s.post(entry("r1", total(A, "100"))).applied());
            LedgerStore.PostResult r = s.post(entry("r1", total(A, "250")));
            assertFalse(r.applied(), s.getClass().getSimpleName());
            assertFalse(r.duplicate(), s.getClass().getSimpleName());
            assertEquals("REFERENCE_CONFLICT", r.reason());
            assertEquals(0, new BigDecimal("100").compareTo(s.get(A).orElseThrow().total()), "nothing changed");
        }
    }

    @Test
    void reusedReferenceWithDifferentLegsIsRefused() throws Exception {
        for (LedgerStore s : stores("refLegs")) {
            assertTrue(s.post(entry("r1", total(A, "100"))).applied());
            LedgerStore.PostResult other = s.post(entry("r1", total(B, "100")));
            assertFalse(other.applied(), s.getClass().getSimpleName());
            assertEquals("REFERENCE_CONFLICT", other.reason());
            LedgerStore.PostResult extra = s.post(entry("r1", total(A, "100"), total(B, "1")));
            assertFalse(extra.applied(), s.getClass().getSimpleName());
            assertEquals("REFERENCE_CONFLICT", extra.reason());
            assertTrue(s.get(B).isEmpty() || s.get(B).orElseThrow().total().signum() == 0, "B untouched");
        }
    }

    @Test
    void serviceSurfacesConflictAsRefusal() throws Exception {
        LedgerService s = LedgerJdbcH2Test.service(LedgerJdbcH2Test.db("refService"));
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", A, new BigDecimal("500"), "a1")).applied());
        LedgerService.Result replay = s.apply(LedgerRequest.of("ALLOCATE", A, new BigDecimal("500"), "a1"));
        assertTrue(replay.applied() && replay.duplicate());
        LedgerService.Result clash = s.apply(LedgerRequest.of("ALLOCATE", A, new BigDecimal("900"), "a1"));
        assertFalse(clash.applied());
        assertEquals("REFERENCE_CONFLICT", clash.reason());
        assertEquals(0, new BigDecimal("500").compareTo(s.available(A)));
    }

    @Test
    void concurrentPostsOfOneReferenceApplyOnceAndRefuseOtherAmounts() throws Exception {
        for (LedgerStore s : stores("refConcurrent")) {
            int n = 16;
            ExecutorService pool = Executors.newFixedThreadPool(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<LedgerStore.PostResult>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String amount = i % 2 == 0 ? "10" : "20";
                Callable<LedgerStore.PostResult> c = () -> {
                    go.await();
                    return s.post(entry("same", total(A, amount)));
                };
                fs.add(pool.submit(c));
            }
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
            int fresh = 0;
            int dup = 0;
            int conflict = 0;
            for (Future<LedgerStore.PostResult> f : fs) {
                LedgerStore.PostResult r = f.get();
                if (r.applied() && !r.duplicate()) {
                    fresh++;
                } else if (r.applied()) {
                    dup++;
                } else {
                    assertEquals("REFERENCE_CONFLICT", r.reason());
                    conflict++;
                }
            }
            String where = s.getClass().getSimpleName();
            assertEquals(1, fresh, where);
            assertEquals(n / 2 - 1, dup, where + ": same-amount replays are duplicates");
            assertEquals(n / 2, conflict, where + ": the other amount is refused");
            BigDecimal t = s.get(A).orElseThrow().total();
            assertTrue(t.compareTo(BigDecimal.TEN) == 0 || t.compareTo(new BigDecimal("20")) == 0, where + " " + t);
        }
    }
}
