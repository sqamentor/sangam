// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.junit.jupiter.api.Test;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.core.money.Money;
import org.sangam.core.time.BusinessClock;
import org.sangam.ledger.AccountKey;
import org.sangam.ledger.LedgerService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-130 / SL-131 (M-002): a retried billing step (a class (a) repeat dispatch of the same operation, or a re-invoice)
 * keeps the document's number and draws none, and a retried collection replays the allocation it planned the first time.
 */
class BillingRetryTest {

    static final Clock AT = Clock.fixed(Instant.parse("2026-11-12T06:00:00Z"), ZoneOffset.UTC);

    static final Map<String, Object> FLAT = Map.of("id", "FLAT", "currency", "INR",
            "components", List.of(Map.of("id", "FIXED", "kind", "FIXED", "amount", 100, "head", "FIXED")),
            "rounding", Map.of("mode", "HALF_UP", "scale", 0));

    /** A config series that records every draw. */
    static final class CountingSeries implements NumberSeries {
        final NumberSeries.InMemory inner = new NumberSeries.InMemory();
        final List<String> draws = new ArrayList<>();

        @Override
        public long next(String scope, String series, String unit, String period) {
            draws.add(series + ":" + unit + ":" + period);
            return inner.next(scope, series, unit, period);
        }
    }

    static Money inr(String s) {
        return Money.parse(s, "INR");
    }

    static BigDecimal outstanding(LedgerService l, String consumer) {
        return l.arrears("T", "DEMAND", LocalDate.MAX).stream().filter(b -> b.key().holder().equals(consumer))
                .map(b -> b.available()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static AccountKey demand(String consumer, String cycle) {
        return new AccountKey("T", "DEMAND", "2026", "FIXED", consumer, "DEMAND", cycle);
    }

    @Test
    void aReInvoiceKeepsTheNumberAndDrawsNone() {
        CountingSeries series = new CountingSeries();
        LedgerService l = BillingServiceTest.ledger();
        BillingService b = new BillingService(l, new TariffEngine(null), series, null, BusinessClock.utc(AT));
        LocalDate due = LocalDate.parse("2026-11-01");
        BillingService.Invoice first = b.invoice("T", "DEMAND", "2026", "C-1", "2026-10", FLAT, Map.of(), Map.of(), due, "sys");
        BillingService.Invoice again = b.invoice("T", "DEMAND", "2026", "C-1", "2026-10", FLAT, Map.of(), Map.of(), due, "sys");
        assertEquals("INV/2026-10/1", first.number());
        assertEquals(first.number(), again.number(), "SL-130: one document, one number");
        assertEquals(List.of("INVOICE:-:2026-10"), series.draws, "the retry burns no number");
        assertTrue(again.postings().stream().allMatch(LedgerService.Result::duplicate), "the postings are idempotent too");
        assertEquals(first.number(), l.balance(demand("C-1", "2026-10")).orElseThrow().attrs().get("invoice"),
                "the account keeps the first number (no second number written by setAttributes)");

        BillingService.Invoice other = b.invoice("T", "DEMAND", "2026", "C-2", "2026-10", FLAT, Map.of(), Map.of(), due, "sys");
        assertEquals("INV/2026-10/2", other.number(), "another consumer is another document");
    }

    @Test
    void aRetriedCollectionKeepsTheReceiptNumber() {
        CountingSeries series = new CountingSeries();
        LedgerService l = BillingServiceTest.ledger();
        BillingService b = new BillingService(l, new TariffEngine(null), series, null, BusinessClock.utc(AT));
        b.invoice("T", "DEMAND", "2026", "C-1", "2026-10", FLAT, Map.of(), Map.of(), LocalDate.parse("2026-11-01"), "sys");
        BillingService.Receipt first = b.collect("T", "DEMAND", "C-1", "COUNTER", "PAY-1", inr("40"), "cashier");
        BillingService.Receipt retry = b.collect("T", "DEMAND", "C-1", "COUNTER", "PAY-1", inr("40"), "cashier");
        assertEquals("RCP/1", first.number());
        assertEquals(first.number(), retry.number());
        assertEquals(List.of("INVOICE:-:2026-10", "RECEIPT:COUNTER:2026-11-12"), series.draws, "one receipt draw");
        assertEquals(first.allocation(), retry.allocation());
        assertEquals(0, outstanding(l, "C-1").compareTo(new BigDecimal("60")), "the payment is applied once");
        BillingService.Receipt next = b.collect("T", "DEMAND", "C-1", "COUNTER", "PAY-2", inr("10"), "cashier");
        assertEquals("RCP/2", next.number(), "another payment is another receipt");
    }

    /**
     * SL-131: demands A = 100 (older) and B = 100, payment P = 100. Attempt 1 consumes A and its result is lost (the
     * step is dispatched again). Re-planning over the arrears would now find only B and consume it under a new
     * reference, so one payment would clear 200. The stored plan is replayed instead: A is a ledger duplicate, B stays.
     */
    @Test
    void aRetriedCollectionReplaysItsPlanAndNeverConsumesTheNextDemand() {
        LedgerService l = BillingServiceTest.ledger();
        BillingService b = new BillingService(l, new TariffEngine(null), new NumberSeries.InMemory(), null,
                BusinessClock.utc(AT));
        b.invoice("T", "DEMAND", "2026", "C-1", "2026-09", FLAT, Map.of(), Map.of(), LocalDate.parse("2026-10-01"), "sys");
        b.invoice("T", "DEMAND", "2026", "C-1", "2026-10", FLAT, Map.of(), Map.of(), LocalDate.parse("2026-11-01"), "sys");
        assertEquals(0, outstanding(l, "C-1").compareTo(new BigDecimal("200")));

        BillingService.Receipt first = b.collect("T", "DEMAND", "C-1", "COUNTER", "PAY-1", inr("100"), "cashier");
        assertEquals(Map.of("FIXED/2026-09", inr("100")), first.allocation(), "oldest due first: A");
        BillingService.Receipt retry = b.collect("T", "DEMAND", "C-1", "COUNTER", "PAY-1", inr("100"), "cashier");

        assertEquals(first.allocation(), retry.allocation(), "the retry replays the stored plan");
        assertTrue(retry.postings().stream().allMatch(LedgerService.Result::duplicate), "A is a ledger duplicate");
        assertEquals(0, l.available(demand("C-1", "2026-10")).compareTo(new BigDecimal("100")), "B is untouched");
        assertEquals(0, outstanding(l, "C-1").compareTo(new BigDecimal("100")), "one payment of 100 clears 100, not 200");
    }

    @Test
    void theSamePaymentForAnotherAmountOrConsumerIsRefused() {
        LedgerService l = BillingServiceTest.ledger();
        BillingService b = new BillingService(l, new TariffEngine(null), new NumberSeries.InMemory(), null,
                BusinessClock.utc(AT));
        b.invoice("T", "DEMAND", "2026", "C-1", "2026-10", FLAT, Map.of(), Map.of(), LocalDate.parse("2026-11-01"), "sys");
        b.collect("T", "DEMAND", "C-1", "COUNTER", "PAY-1", inr("40"), "cashier");
        assertThrows(IllegalStateException.class, () -> b.collect("T", "DEMAND", "C-1", "COUNTER", "PAY-1", inr("50"), "cashier"));
        assertThrows(IllegalStateException.class, () -> b.collect("T", "DEMAND", "C-2", "COUNTER", "PAY-1", inr("40"), "cashier"));
        assertEquals(0, outstanding(l, "C-1").compareTo(new BigDecimal("60")), "nothing more was consumed");
    }

    @Test
    void theAdvanceIsPartOfTheStoredPlan() {
        LedgerService l = BillingServiceTest.ledger();
        BillingService b = new BillingService(l, new TariffEngine(null), new NumberSeries.InMemory(), null,
                BusinessClock.utc(AT));
        b.invoice("T", "DEMAND", "2026", "C-1", "2026-10", FLAT, Map.of(), Map.of(), LocalDate.parse("2026-11-01"), "sys");
        BillingService.Receipt first = b.collect("T", "DEMAND", "C-1", "COUNTER", "PAY-1", inr("130"), "cashier");
        assertEquals(inr("30"), first.allocation().get("ADVANCE"));
        BillingService.Receipt retry = b.collect("T", "DEMAND", "C-1", "COUNTER", "PAY-1", inr("130"), "cashier");
        assertEquals(first.allocation(), retry.allocation());
    }

    @Test
    void thePlanIsComputedOnlyByTheFirstIssue() {
        DocumentNumbers numbers = DocumentNumbers.overSeries(new NumberSeries.InMemory());
        AtomicInteger planned = new AtomicInteger();
        DocumentNumbers.Request r = new DocumentNumbers.Request("T", DocumentNumbers.Kind.RECEIPT,
                DocumentNumbers.receiptKey("PAY-1"), "COUNTER", LocalDate.parse("2026-11-12"), "cashier");
        DocumentNumbers.Issued first = numbers.issue(r, () -> Map.of("n", Integer.toString(planned.incrementAndGet())));
        DocumentNumbers.Issued again = numbers.issue(r, () -> Map.of("n", Integer.toString(planned.incrementAndGet())));
        assertTrue(first.first());
        assertFalse(again.first());
        assertEquals(first.number(), again.number());
        assertEquals(Map.of("n", "1"), again.plan());
        assertEquals(1, planned.get());
    }

    @Test
    void thePlanSurvivesItsStoredForm() {
        AccountKey a = demand("C-1", "2026-09");
        BillingService.Plan p = new BillingService.Plan("DEMAND", "C-1", inr("130"),
                List.of(new BillingService.Leg(a, inr("100"))), inr("30"));
        Map<String, Object> stored = p.toMap();
        assertEquals(p, BillingService.Plan.from(stored));
        // as parsed back from the canonical JSON of the stored row: strings, a list of maps
        Map<String, Object> reparsed = new java.util.LinkedHashMap<>(stored);
        reparsed.put("legs", List.of(new java.util.LinkedHashMap<>((Map<?, ?>) ((List<?>) stored.get("legs")).get(0))));
        assertEquals(p, BillingService.Plan.from(reparsed));
        assertEquals(a.id(), BillingService.Plan.from(stored).legs().get(0).account().id(), "the same posting reference");
    }

    @Test
    void documentIdentitiesCannotRunIntoEachOther() {
        assertNotEquals(DocumentNumbers.invoiceKey("DEMAND", "C|1", "2026"), DocumentNumbers.invoiceKey("DEMAND|C", "1", "2026"));
        assertEquals("[\"DEMAND\",\"C-1\",\"2026-10\"]", DocumentNumbers.invoiceKey("DEMAND", "C-1", "2026-10"));
        assertThrows(IllegalArgumentException.class, () -> DocumentNumbers.invoiceKey("DEMAND", "C-1", null));
        assertThrows(IllegalArgumentException.class, () -> DocumentNumbers.receiptKey(" "));
    }
}
