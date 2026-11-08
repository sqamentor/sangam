// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerServiceTest {

    static LedgerService service(Map<String, Object> def) {
        LedgerService s = new LedgerService(new LedgerStore.InMemory(), null);
        s.define("T", List.of(LedgerDefinition.from(def)));
        return s;
    }

    static final Map<String, Object> BUDGET = Map.of("id", "B", "kind", "LIMIT", "currency", "INR",
            "stages", List.of("ALLOCATION", "RELEASE"), "availability", Map.of("type", "ANNUAL"));

    @Test
    void stagesCommitmentConsumptionAndNoOversellUnderConcurrency() throws Exception {
        LedgerService s = service(BUDGET);
        AccountKey top = new AccountKey("T", "B", "2026-27", "2054-00-001-01-00-13", "DEPT", "ALLOCATION", null);
        AccountKey ddo = top.atStage("RELEASE", "DDO-1");
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", top, new BigDecimal("1000"), "a1")).applied());
        assertTrue(s.apply(LedgerRequest.of("DISTRIBUTE", top, new BigDecimal("600"), "d1").withTarget(ddo)).applied());
        assertFalse(s.apply(LedgerRequest.of("DISTRIBUTE", top, new BigDecimal("500"), "d2").withTarget(ddo)).applied());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < 100; i++) {
            int n = i;
            pool.submit(() -> {
                if (s.apply(LedgerRequest.of("COMMIT", ddo, BigDecimal.TEN, "c" + n)).applied()) {
                    ok.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(60, ok.get());
        assertEquals(0, s.available(ddo).signum());
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", top, new BigDecimal("1000"), "a1")).duplicate());
    }

    @Test
    void lapseAtPeriodCloseAndFefoIssue() {
        LedgerService s = service(BUDGET);
        AccountKey k = new AccountKey("T", "B", "2026-27", "L", "H", "RELEASE", null);
        s.apply(LedgerRequest.of("ALLOCATE", k, new BigDecimal("100"), "a"));
        s.apply(LedgerRequest.of("COMMIT", k, new BigDecimal("30"), "c"));
        s.closePeriod("T", "B", "2026-27", "2027-28", LocalDate.of(2027, 4, 1), "sys");
        assertEquals(0, s.available(k).signum());
        assertEquals(Balance.EXPIRED, s.balance(k).orElseThrow().state());

        LedgerService q = service(Map.of("id", "STOCK", "kind", "QUANTITY", "issueOrder", "FEFO"));
        AccountKey lot1 = new AccountKey("T", "STOCK", "-", "PARACETAMOL", "STORE-1", "LIMIT", "LOT-1");
        AccountKey lot2 = lot1.withSub("LOT-2");
        q.apply(LedgerRequest.of("ALLOCATE", lot1, new BigDecimal("50"), "r1"));
        q.apply(LedgerRequest.of("ALLOCATE", lot2, new BigDecimal("50"), "r2"));
        q.setAttributes(lot1, Map.of("expiry", "2027-06-30"));
        q.setAttributes(lot2, Map.of("expiry", "2027-01-31"));
        AccountKey item = lot1.withSub("-");
        assertTrue(q.issue(item, new BigDecimal("60"), "i1", LocalDate.of(2026, 11, 5)).applied());
        assertEquals(0, q.balance(lot2).orElseThrow().available().signum());
        assertEquals(new BigDecimal("40"), q.balance(lot1).orElseThrow().available());
        assertFalse(q.issue(item, new BigDecimal("41"), "i2", LocalDate.of(2026, 11, 5)).applied());
    }

    @Test
    void transferRulesAndFreezeOrders() {
        LedgerService s = service(Map.of("id", "B", "currency", "INR",
                "transfer", Map.of("sameAttributes", List.of("chargedVoted"))));
        AccountKey a = new AccountKey("T", "B", "P", "A", "H", "LIMIT", null);
        AccountKey b = a.withLine("B");
        s.apply(LedgerRequest.of("ALLOCATE", a, new BigDecimal("100"), "x"));
        LedgerRequest t = new LedgerRequest("TRANSFER", a, b, BigDecimal.TEN, null, null, "t1", null, null, null, false, false,
                false, Map.of("fromAttrs", Map.of("chargedVoted", "VOTED"), "toAttrs", Map.of("chargedVoted", "CHARGED")));
        assertEquals("TRANSFER_ATTRIBUTE_DIFFERS", s.apply(t).reason());
        s.freeze(new FreezeOrder("T", "F1", "B", null, "A", null, null, "in-year cut", "fd", null));
        assertEquals("FROZEN", s.apply(LedgerRequest.of("COMMIT", a, BigDecimal.ONE, "c")).reason());
        s.releaseFreeze("T", "F1");
        assertTrue(s.apply(LedgerRequest.of("COMMIT", a, BigDecimal.ONE, "c")).applied());
    }
}
