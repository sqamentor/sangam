// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.junit.jupiter.api.Test;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.ledger.AccountKey;
import org.sangam.ledger.LedgerDefinition;
import org.sangam.ledger.LedgerService;
import org.sangam.ledger.LedgerStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingServiceTest {

    static final Map<String, Object> WATER = Map.of("id", "WATER", "currency", "INR", "minimum", 75,
            "components", List.of(
                    Map.of("id", "FIXED", "kind", "FIXED", "amount", 50, "head", "FIXED"),
                    Map.of("id", "USAGE", "kind", "SLAB", "measure", "units", "head", "USAGE",
                            "slabs", List.of(Map.of("upTo", 10, "rate", 0), Map.of("upTo", 20, "rate", "5.5"), Map.of("rate", 9))),
                    Map.of("id", "SEWER", "kind", "PERCENT", "of", "USAGE", "percent", 30, "head", "SEWER")),
            "rounding", Map.of("mode", "HALF_UP", "scale", 0),
            "penalty", Map.of("graceDays", 0, "kind", "PERCENT_ONCE", "rate", 10, "head", "PENALTY"));

    static LedgerService ledger() {
        LedgerService l = new LedgerService(new LedgerStore.InMemory(), null);
        l.define("T", List.of(LedgerDefinition.from(Map.of("id", "DEMAND", "kind", "RECEIVABLE", "currency", "INR",
                "stages", List.of("DEMAND")))));
        return l;
    }

    @Test
    void slabTariffMinimumAndRounding() {
        TariffEngine t = new TariffEngine(null);
        List<TariffEngine.Line> lines = t.compute("T", WATER, Map.of("units", new BigDecimal("23")), Map.of());
        // usage: 10 x 0 + 10 x 5.5 + 3 x 9 = 82; sewer 30 % = 24.6 -> 25; fixed 50
        assertEquals(new BigDecimal("50"), lines.get(0).amount());
        assertEquals(new BigDecimal("82"), lines.get(1).amount());
        assertEquals(new BigDecimal("25"), lines.get(2).amount());
        List<TariffEngine.Line> low = t.compute("T", WATER, Map.of("units", new BigDecimal("2")), Map.of());
        assertEquals(new BigDecimal("75"), low.stream().map(TariffEngine.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void invoiceCollectArrearsPenaltyWaiver() {
        LedgerService l = ledger();
        BillingService b = new BillingService(l, new TariffEngine(null), new NumberSeries.InMemory(), null);
        LocalDate due = LocalDate.now().minusDays(5);
        BillingService.Invoice inv = b.invoice("T", "DEMAND", "2026", "C-1", "2026-10", WATER, Map.of("units", new BigDecimal("23")),
                Map.of(), due, "sys");
        assertEquals(new BigDecimal("157"), inv.total());
        // the same invoice again posts nothing new (idempotent per consumer x cycle x component)
        b.invoice("T", "DEMAND", "2026", "C-1", "2026-10", WATER, Map.of("units", new BigDecimal("23")), Map.of(), due, "sys");
        BillingService.Receipt rc = b.collect("T", "DEMAND", "C-1", "COUNTER", "PAY-1", new BigDecimal("100"), "cashier");
        assertEquals(new BigDecimal("100"), rc.amount());
        BigDecimal outstanding = l.arrears("T", "DEMAND", LocalDate.now()).stream()
                .filter(x -> x.key().holder().equals("C-1")).map(x -> x.available()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, outstanding.compareTo(new BigDecimal("57")));
        List<LedgerService.Result> pen = b.levyPenalties("T", "DEMAND", "2026", "C-1", WATER, LocalDate.now(), "sys");
        assertFalse(pen.isEmpty());
        AccountKey sewer = new AccountKey("T", "DEMAND", "2026", "SEWER", "C-1", "DEMAND", "2026-10");
        assertFalse(b.waive(sewer, new BigDecimal("1000"), "W-1", "officer").applied(), "cannot waive more than outstanding");
        assertTrue(b.waive(sewer, l.balance(sewer).orElseThrow().available(), "W-2", "officer").applied());
    }
}
