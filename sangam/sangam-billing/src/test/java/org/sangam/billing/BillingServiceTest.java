// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.core.money.Money;
import org.sangam.ledger.AccountKey;
import org.sangam.ledger.LedgerDefinition;
import org.sangam.ledger.LedgerService;
import org.sangam.ledger.LedgerStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    static Money inr(String s) {
        return Money.parse(s, "INR");
    }

    static Money sum(List<TariffEngine.Line> lines) {
        return Money.sum(lines.stream().map(TariffEngine.Line::money).toList(), "INR");
    }

    @Test
    void slabTariffMinimumAndRounding() {
        TariffEngine t = new TariffEngine(null);
        List<TariffEngine.Line> lines = t.compute("T", WATER, Map.of("units", new BigDecimal("23")), Map.of());
        // usage: 10 x 0 + 10 x 5.5 + 3 x 9 = 82; sewer 30 % = 24.6 (exact); fixed 50; total 156.6 -> 157 once
        assertEquals(inr("50"), lines.get(0).money());
        assertEquals(inr("82"), lines.get(1).money());
        assertEquals(0, new BigDecimal("24.6").compareTo(lines.get(2).exact()));
        assertEquals(inr("25"), lines.get(2).money()); // the largest remainder takes the rounding unit
        assertEquals(inr("157"), sum(lines));
        List<TariffEngine.Line> low = t.compute("T", WATER, Map.of("units", new BigDecimal("2")), Map.of());
        assertEquals(inr("75"), sum(low));
    }

    @Test
    void invoiceCollectArrearsPenaltyWaiver() {
        LedgerService l = ledger();
        BillingService b = new BillingService(l, new TariffEngine(null), new NumberSeries.InMemory(), null);
        LocalDate due = LocalDate.now().minusDays(5);
        BillingService.Invoice inv = b.invoice("T", "DEMAND", "2026", "C-1", "2026-10", WATER, Map.of("units", new BigDecimal("23")),
                Map.of(), due, "sys");
        assertEquals(inr("157"), inv.total());
        assertEquals("157.00", inv.total().toPlainString());
        // the same invoice again posts nothing new (idempotent per consumer x cycle x component)
        b.invoice("T", "DEMAND", "2026", "C-1", "2026-10", WATER, Map.of("units", new BigDecimal("23")), Map.of(), due, "sys");
        BillingService.Receipt rc = b.collect("T", "DEMAND", "C-1", "COUNTER", "PAY-1", inr("100"), "cashier");
        assertEquals(inr("100"), rc.amount());
        BigDecimal outstanding = l.arrears("T", "DEMAND", LocalDate.now()).stream()
                .filter(x -> x.key().holder().equals("C-1")).map(x -> x.available()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, outstanding.compareTo(new BigDecimal("57")));
        List<LedgerService.Result> pen = b.levyPenalties("T", "DEMAND", "2026", "C-1", WATER, LocalDate.now(), "sys");
        assertFalse(pen.isEmpty());
        AccountKey sewer = new AccountKey("T", "DEMAND", "2026", "SEWER", "C-1", "DEMAND", "2026-10");
        assertFalse(b.waive(sewer, inr("1000"), "W-1", "officer").applied(), "cannot waive more than outstanding");
        AccountKey open = l.arrears("T", "DEMAND", LocalDate.MAX).stream().filter(x -> x.key().holder().equals("C-1"))
                .filter(x -> x.available().signum() > 0).findFirst().orElseThrow().key();
        assertTrue(b.waive(open, l.balance(open).orElseThrow().availableMoney(), "W-2", "officer").applied());
        // SG-23: a zero / negative waiver is not a waiver
        assertThrows(IllegalArgumentException.class, () -> b.waive(open, Money.zero("INR"), "W-3", "officer"));
    }

    // ------------------------------------------------------------------ SG-20 regression: round once per document

    /**
     * Three lines of 10.50 rounded to whole rupees. Per-line rounding (the old engine: setScale on each line) gives
     * 11 + 11 + 11 = 33, a drift of 1.50 over the exact 31.50. Rounding the document once gives 32, allocated
     * 11 / 11 / 10 (equal remainders: earlier lines first). This test fails on the old per-line rounding.
     */
    @Test
    void billTotalIsRoundedOnceNotPerLine() {
        Map<String, Object> tariff = Map.of("id", "HALVES", "currency", "INR", "rounding", Map.of("mode", "HALF_UP", "scale", 0),
                "components", List.of(Map.of("id", "A", "kind", "FIXED", "amount", "10.5"),
                        Map.of("id", "B", "kind", "FIXED", "amount", "10.5"),
                        Map.of("id", "C", "kind", "FIXED", "amount", "10.5")));
        List<TariffEngine.Line> lines = new TariffEngine(null).compute("T", tariff, Map.of(), Map.of());
        BigDecimal perLineOld = lines.stream().map(l -> l.exact().setScale(0, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, perLineOld.compareTo(new BigDecimal("33")), "what per-line rounding produced");
        assertEquals(inr("32"), sum(lines));
        assertNotEquals(0, perLineOld.compareTo(sum(lines).toMajor()), "the old drift is gone");
        assertEquals(List.of(inr("11"), inr("11"), inr("10")), lines.stream().map(TariffEngine.Line::money).toList());

        LedgerService l = ledger();
        BillingService.Invoice inv = new BillingService(l, new TariffEngine(null), new NumberSeries.InMemory(), null)
                .invoice("T", "DEMAND", "2026", "C-7", "2026-11", tariff, Map.of(), Map.of(), LocalDate.now(), "sys");
        assertEquals(inr("32"), inv.total());
        BigDecimal posted = l.arrears("T", "DEMAND", LocalDate.MAX).stream().filter(x -> x.key().holder().equals("C-7"))
                .map(x -> x.available()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, posted.compareTo(new BigDecimal("32")), "ledger demand == invoice total");
    }

    /**
     * SG-24 (IFMS session): minimum bill 75 at scale 0 was billed 76, because the minimum top-up was computed on the
     * exact lines and then every line was rounded on its own (51 + 11 + 14). Now: exact lines, exact total, minimum on
     * the document total, round once, allocate.
     */
    @Test
    void sg24MinimumBillIsAppliedToTheDocumentTotalBeforeTheSingleRounding() {
        Map<String, Object> t = Map.of("id", "MIN", "currency", "INR", "minimum", 75,
                "rounding", Map.of("mode", "HALF_UP", "scale", 0),
                "components", List.of(Map.of("id", "A", "kind", "FIXED", "amount", "50.5"),
                        Map.of("id", "B", "kind", "FIXED", "amount", "10.5")));
        List<TariffEngine.Line> lines = new TariffEngine(null).compute("T", t, Map.of(), Map.of());
        BigDecimal old = lines.stream().map(l -> l.exact().setScale(0, RoundingMode.HALF_UP)).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, old.compareTo(new BigDecimal("76")), "the old per-line result");
        assertEquals(inr("75"), sum(lines));
    }

    /**
     * SG-25 (IFMS session): base 0.01 with two 50 % components was billed 0.03 (0.01 + 0.005->0.01 + 0.005->0.01).
     * The exact total is 0.02; rounded once it stays 0.02, allocated 0.01 / 0.01 / 0.00 (equal remainders: earlier first).
     */
    @Test
    void sg25TwoHalfPercentLinesOnOnePaisaAreBilledTheExactTotal() {
        Map<String, Object> t = Map.of("id", "HALF", "currency", "INR", "rounding", Map.of("mode", "HALF_UP"),
                "components", List.of(Map.of("id", "BASE", "kind", "FIXED", "amount", "0.01"),
                        Map.of("id", "P1", "kind", "PERCENT", "of", "BASE", "percent", 50),
                        Map.of("id", "P2", "kind", "PERCENT", "of", "BASE", "percent", 50)));
        List<TariffEngine.Line> lines = new TariffEngine(null).compute("T", t, Map.of(), Map.of());
        BigDecimal old = lines.stream().map(l -> l.exact().setScale(2, RoundingMode.HALF_UP)).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, old.compareTo(new BigDecimal("0.03")), "the old per-line result");
        BigDecimal exact = lines.stream().map(TariffEngine.Line::exact).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(Money.round(exact, "INR", RoundingMode.HALF_UP), sum(lines));
        assertEquals(inr("0.02"), sum(lines));
        assertEquals(List.of(inr("0.01"), inr("0.01"), inr("0.00")), lines.stream().map(TariffEngine.Line::money).toList());
    }

    /** Many small percentage lines: per-line rounding to paise loses or gains paise; the document total never does. */
    @Test
    void manyPercentLinesSumToTheRoundedExactTotal() {
        Random r = new Random(398);
        for (int run = 0; run < 2_000; run++) {
            List<Object> comps = new ArrayList<>();
            comps.add(Map.of("id", "BASE", "kind", "PER_UNIT", "measure", "units", "rate", "7.37"));
            int n = 1 + r.nextInt(8);
            for (int i = 0; i < n; i++) {
                comps.add(Map.of("id", "P" + i, "kind", "PERCENT", "of", "BASE", "percent", BigDecimal.valueOf(r.nextInt(10_000), 2)));
            }
            String mode = r.nextBoolean() ? "HALF_UP" : "HALF_EVEN";
            Map<String, Object> tariff = Map.of("id", "PCT", "currency", "INR", "components", comps,
                    "rounding", Map.of("mode", mode));
            BigDecimal units = BigDecimal.valueOf(r.nextInt(1_000_000), 3);
            List<TariffEngine.Line> lines = new TariffEngine(null).compute("T", tariff, Map.of("units", units), Map.of());
            BigDecimal exact = lines.stream().map(TariffEngine.Line::exact).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(Money.round(exact, "INR", RoundingMode.valueOf(mode)), sum(lines), "run " + run);
        }
    }

    @Test
    void halfEvenFromTenantConfiguration() {
        Map<String, Object> t = new LinkedHashMap<>(Map.of("id", "E", "currency", "INR",
                "components", List.of(Map.of("id", "A", "kind", "FIXED", "amount", "0.125"))));
        t.put("rounding", Map.of("mode", "HALF_EVEN"));
        assertEquals(inr("0.12"), sum(new TariffEngine(null).compute("T", t, Map.of(), Map.of())));
        t.put("rounding", Map.of("mode", "HALF_UP"));
        assertEquals(inr("0.13"), sum(new TariffEngine(null).compute("T", t, Map.of(), Map.of())));
    }

    @Test
    void tariffWithoutCurrencyIsRejected() {
        Map<String, Object> t = Map.of("id", "X", "components", List.of(Map.of("id", "A", "kind", "FIXED", "amount", 1)));
        assertThrows(IllegalArgumentException.class, () -> new TariffEngine(null).compute("T", t, Map.of(), Map.of()));
    }

    // ------------------------------------------------------------------ slab band boundaries

    static final Map<String, Object> SLABBED = Map.of("id", "S", "currency", "INR", "components", List.of(
            Map.of("id", "U", "kind", "SLAB", "measure", "units",
                    "slabs", List.of(Map.of("upTo", 99_999, "rate", "1"), Map.of("upTo", 100_000, "rate", "2"),
                            Map.of("rate", "3")))));

    @ParameterizedTest
    @CsvSource({"99999,99999.00", "100000,100001.00", "100001,100004.00", "99999.99,100000.98", "100000.01,100001.03"})
    void telescopicSlabBoundaries(String units, String expected) {
        // up to 99,999 at 1; the 1,00,000th unit at 2; above 1,00,000 at 3
        assertEquals(inr(expected), sum(new TariffEngine(null).compute("T", SLABBED, Map.of("units", new BigDecimal(units)), Map.of())));
    }

    @ParameterizedTest
    @CsvSource({"99999,99999.00", "100000,200000.00", "100001,300003.00"})
    void flatSlabBoundariesAreInclusiveUpperBounds(String units, String expected) {
        Map<String, Object> flat = Map.of("id", "F", "currency", "INR", "components", List.of(
                Map.of("id", "U", "kind", "SLAB", "measure", "units", "telescopic", false,
                        "slabs", List.of(Map.of("upTo", 99_999, "rate", "1"), Map.of("upTo", 100_000, "rate", "2"),
                                Map.of("rate", "3")))));
        assertEquals(inr(expected), sum(new TariffEngine(null).compute("T", flat, Map.of("units", new BigDecimal(units)), Map.of())));
    }

    // ------------------------------------------------------------------ penalty rounded once

    @Test
    void penaltyIsComputedExactlyAndRoundedOnce() {
        Map<String, Object> t = Map.of("id", "P", "currency", "INR",
                "penalty", Map.of("graceDays", 0, "kind", "PERCENT_PER_ANNUM", "rate", "18"));
        LocalDate due = LocalDate.of(2026, 1, 1);
        // 1,00,000 x 18 % x 1 day / 365 = 49.3150684... -> 49.32
        assertEquals(inr("49.32"), new TariffEngine(null).penalty("T", t, new BigDecimal("100000"), due, due.plusDays(1)));
        assertEquals(Money.zero("INR"), new TariffEngine(null).penalty("T", t, new BigDecimal("100000"), due, due));
    }
}
